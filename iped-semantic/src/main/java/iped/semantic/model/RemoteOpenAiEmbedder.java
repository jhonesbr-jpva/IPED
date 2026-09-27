package iped.semantic.model;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code openai-embeddings} protocol (contracts/remote-model-protocol.md): text only,
 * {@code POST {endpoint}/v1/embeddings}. The identity is the {@code model} the response reports,
 * never the name the configuration asked for (FR-043); a response without it is refused. The name
 * alone cannot detect weights swapped under it - the fingerprint does.
 *
 * <p>
 * The service knows nothing of prefixes: the query and passage prefixes the model needs travel
 * inside the text, from {@code textRemoteQueryPrefix} and {@code textRemotePassagePrefix}.
 * </p>
 */
public final class RemoteOpenAiEmbedder implements Embedder {

    static final String PATH = "/v1/embeddings";

    private final RemoteHttp http;
    private final String model;
    private final String queryPrefix;
    private final String passagePrefix;
    private final ModelIdentity identity;

    private RemoteOpenAiEmbedder(RemoteHttp http, String model, String queryPrefix, String passagePrefix)
            throws EmbeddingException {
        this.http = http;
        this.model = model;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.passagePrefix = passagePrefix == null ? "" : passagePrefix;
        // one probe learns what the service reports and how many dimensions it answers with
        Answer first = call(List.of(this.passagePrefix + FingerprintProbes.texts().get(0)));
        this.identity = new ModelIdentity(first.model, null, first.vectors[0].length, ModelIdentity.DOT_PRODUCT,
                this.queryPrefix, this.passagePrefix);
    }

    static RemoteOpenAiEmbedder open(RemoteHttp http, String model, String queryPrefix, String passagePrefix)
            throws EmbeddingException {
        return new RemoteOpenAiEmbedder(http, model, queryPrefix, passagePrefix);
    }

    @Override
    public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
        if (texts.isEmpty()) {
            return new float[0][];
        }
        String prefix = role == Role.QUERY ? queryPrefix : passagePrefix;
        List<String> inputs = new java.util.ArrayList<>(texts.size());
        for (String t : texts) {
            inputs.add(prefix + t);
        }
        Answer a = call(inputs);
        if (!a.model.equals(identity.getId())) {
            throw new EmbeddingException("the remote model at " + http.destination() + " now reports " + a.model
                    + ", not " + identity.getId() + " as when the session started", null, true);
        }
        return a.vectors;
    }

    private static final class Answer {
        final String model;
        final float[][] vectors;

        Answer(String model, float[][] vectors) {
            this.model = model;
            this.vectors = vectors;
        }
    }

    private Answer call(List<String> inputs) throws EmbeddingException {
        ObjectNode body = RemoteHttp.JSON.createObjectNode();
        body.put("model", model);
        ArrayNode in = body.putArray("input");
        inputs.forEach(in::add);
        body.put("encoding_format", "float");
        JsonNode r = http.post(PATH, body);
        String reported = r == null ? null : r.path("model").asText(null);
        if (reported == null || reported.isBlank()) {
            throw new EmbeddingException("the remote model at " + http.destination()
                    + " answered without a model identity; the answer is refused (FR-043)", null, true);
        }
        JsonNode data = r.path("data");
        if (!data.isArray()) {
            throw new EmbeddingException("the answer of " + http.destination() + " has no data array", null, true);
        }
        float[][] vectors = new float[inputs.size()][];
        for (JsonNode d : data) {
            int index = d.path("index").asInt(-1);
            if (index < 0 || index >= vectors.length || vectors[index] != null) {
                throw new EmbeddingException("the answer of " + http.destination() + " has an index out of place ("
                        + index + " for " + vectors.length + " inputs)");
            }
            vectors[index] = RemoteIpedEmbedder.vector(d.path("embedding"), http);
        }
        for (int i = 0; i < vectors.length; i++) {
            if (vectors[i] == null) {
                throw new EmbeddingException("the answer of " + http.destination() + " does not cover input " + i + " of "
                        + vectors.length + "; the whole request fails");
            }
        }
        return new Answer(reported.trim(), vectors);
    }

    @Override
    public ModelIdentity identity() {
        return identity;
    }

    @Override
    public String destination() {
        return http.destination();
    }

    @Override
    public String toString() {
        return "RemoteOpenAiEmbedder[" + identity.describe() + " at " + http.destination() + "]";
    }
}

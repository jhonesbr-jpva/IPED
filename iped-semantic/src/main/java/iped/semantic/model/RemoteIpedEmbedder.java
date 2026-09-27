package iped.semantic.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code iped-embed/1} protocol (contracts/remote-model-protocol.md): text and images,
 * {@code POST {endpoint}/iped-embed/1/embed}, with the optional discovery
 * {@code GET {endpoint}/iped-embed/1/model}. The identity is the {@code model} object the service
 * reports - id and, when given, the artifact hash - so a case prepared by the reference server can
 * be searched with the identical local artifact and the other way round (amendment A4).
 *
 * <p>
 * Texts go with their role and the service applies the model's prefix. Images go as PNG - lossless,
 * so the service sees the pixels IPED has, the thumbnail or the frame cut from the mosaic, never the
 * original file.
 * </p>
 */
public final class RemoteIpedEmbedder implements Embedder {

    static final String EMBED_PATH = "/iped-embed/1/embed";
    static final String MODEL_PATH = "/iped-embed/1/model";

    private final RemoteHttp http;
    private final String model;
    private final ModelIdentity identity;
    private final boolean images;

    private RemoteIpedEmbedder(RemoteHttp http, String model) throws EmbeddingException {
        this.http = http;
        this.model = model == null ? "" : model;
        JsonNode discovery = http.getOptional(MODEL_PATH);
        if (discovery != null && !discovery.path("id").asText("").isBlank()) {
            boolean withImages = false;
            for (JsonNode m : discovery.path("modalities")) {
                withImages |= "image".equals(m.asText());
            }
            this.images = withImages;
            this.identity = new ModelIdentity(discovery.path("id").asText(), discovery.path("sha256").asText(null),
                    discovery.path("dimensions").asInt(0), ModelIdentity.DOT_PRODUCT, null, null);
            if (identity.getDimensions() <= 0) {
                throw new EmbeddingException("the model description of " + http.destination()
                        + " declares no dimensions", null, true);
            }
        } else {
            // no discovery: one text learns the identity and the dimensions
            Answer a = call(List.of(textInput(Role.PASSAGE, FingerprintProbes.texts().get(0))));
            this.images = true;
            this.identity = new ModelIdentity(a.id, a.sha256, a.vectors[0].length, ModelIdentity.DOT_PRODUCT, null,
                    null);
        }
    }

    static RemoteIpedEmbedder open(RemoteHttp http, String model) throws EmbeddingException {
        return new RemoteIpedEmbedder(http, model);
    }

    @Override
    public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
        List<ObjectNode> inputs = new ArrayList<>(texts.size());
        for (String t : texts) {
            inputs.add(textInput(role, t));
        }
        return checked(call(inputs));
    }

    @Override
    public boolean supportsImages() {
        return images;
    }

    @Override
    public float[][] embedImages(List<BufferedImage> pictures) throws EmbeddingException {
        List<ObjectNode> inputs = new ArrayList<>(pictures.size());
        for (BufferedImage img : pictures) {
            ObjectNode in = RemoteHttp.JSON.createObjectNode();
            in.put("type", "image");
            in.put("media_type", "image/png");
            in.put("data", Base64.getEncoder().encodeToString(png(img)));
            inputs.add(in);
        }
        return checked(call(inputs));
    }

    private static byte[] png(BufferedImage img) throws EmbeddingException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", out)) {
                throw new EmbeddingException("no PNG writer for the picture");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new EmbeddingException("cannot encode the picture: " + e.getMessage(), e);
        }
    }

    private static ObjectNode textInput(Role role, String text) {
        ObjectNode in = RemoteHttp.JSON.createObjectNode();
        in.put("type", "text");
        in.put("role", role == Role.QUERY ? "query" : "passage");
        in.put("text", text);
        return in;
    }

    private float[][] checked(Answer a) throws EmbeddingException {
        ModelIdentity now = new ModelIdentity(a.id, a.sha256, identity.getDimensions(), ModelIdentity.DOT_PRODUCT, null,
                null);
        if (!identity.sameReportedIdentity(now)) {
            throw new EmbeddingException("the remote model at " + http.destination() + " now reports " + now.describe()
                    + ", not " + identity.describe() + " as when the session started", null, true);
        }
        for (float[] v : a.vectors) {
            if (v.length != identity.getDimensions()) {
                throw new EmbeddingException("the remote model at " + http.destination() + " answered " + v.length
                        + " dimensions, not " + identity.getDimensions(), null, true);
            }
        }
        return a.vectors;
    }

    private static final class Answer {
        final String id;
        final String sha256;
        final float[][] vectors;

        Answer(String id, String sha256, float[][] vectors) {
            this.id = id;
            this.sha256 = sha256;
            this.vectors = vectors;
        }
    }

    private Answer call(List<ObjectNode> inputs) throws EmbeddingException {
        if (inputs.isEmpty()) {
            return new Answer(identity.getId(), identity.getSha256(), new float[0][]);
        }
        ObjectNode body = RemoteHttp.JSON.createObjectNode();
        body.put("model", model);
        ArrayNode in = body.putArray("inputs");
        inputs.forEach(in::add);
        JsonNode r = http.post(EMBED_PATH, body);
        String id = r == null ? null : r.path("model").path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new EmbeddingException("the remote model at " + http.destination()
                    + " answered without a model identity; the answer is refused (FR-043)", null, true);
        }
        JsonNode embeddings = r.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != inputs.size()) {
            throw new EmbeddingException("the answer of " + http.destination() + " has "
                    + (embeddings.isArray() ? embeddings.size() : 0) + " embeddings for " + inputs.size()
                    + " inputs; the whole request fails");
        }
        float[][] vectors = new float[inputs.size()][];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = vector(embeddings.get(i), http);
        }
        return new Answer(id.trim(), r.path("model").path("sha256").asText(null), vectors);
    }

    /** A JSON array of numbers as a unit-norm vector; normalised here, whatever the service did. */
    static float[] vector(JsonNode array, RemoteHttp http) throws EmbeddingException {
        if (!array.isArray() || array.size() == 0) {
            throw new EmbeddingException("the answer of " + http.destination() + " has an empty or missing vector");
        }
        float[] v = new float[array.size()];
        for (int i = 0; i < v.length; i++) {
            JsonNode x = array.get(i);
            if (!x.isNumber()) {
                throw new EmbeddingException("the answer of " + http.destination() + " has a vector with a non-number");
            }
            v[i] = (float) x.asDouble();
        }
        return VectorMath.normalizeInPlace(v);
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
        return "RemoteIpedEmbedder[" + identity.describe() + " at " + http.destination() + "]";
    }
}

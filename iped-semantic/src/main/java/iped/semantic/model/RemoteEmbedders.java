package iped.semantic.model;

import java.net.URI;
import java.net.URISyntaxException;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;

/**
 * Opens the remote embedder a modality is configured with ({@code openai-embeddings} or
 * {@code iped-embed/1}).
 */
public final class RemoteEmbedders {

    private RemoteEmbedders() {
    }

    public static Embedder open(SemanticSearchConfig config, Modality modality) throws EmbeddingException {
        throw new EmbeddingException("remote model sources are not implemented yet", null, true);
    }

    /**
     * The destination as it may be shown and recorded: scheme, host, port and path, never user
     * info, query or fragment (which could carry a credential).
     */
    public static String safeDestination(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(endpoint.trim());
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString();
        } catch (URISyntaxException e) {
            return "(invalid endpoint)";
        }
    }
}

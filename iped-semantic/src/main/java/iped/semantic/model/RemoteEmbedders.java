package iped.semantic.model;

import java.net.URI;
import java.net.URISyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;

/**
 * Opens the remote embedder a modality is configured with ({@code openai-embeddings} or
 * {@code iped-embed/1}), checking the destination and finding the credential first. Opening talks
 * to the service once, so an unreachable destination or a refused credential is known before any
 * item is read (FR-042), and the identity is the one the service reports (FR-043).
 */
public final class RemoteEmbedders {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteEmbedders.class);

    public static final String OPENAI = "openai-embeddings";
    public static final String IPED_EMBED = "iped-embed/1";

    /** First wait between retries; it doubles on each retry, up to 30 s. */
    public static final long DEFAULT_RETRY_BASE_MILLIS = 1000;

    private RemoteEmbedders() {
    }

    public static Embedder open(SemanticSearchConfig config, Modality modality) throws EmbeddingException {
        return open(config, modality, DEFAULT_RETRY_BASE_MILLIS);
    }

    /**
     * @param retryBaseMillis
     *            first wait between retries (tests shorten it)
     */
    public static Embedder open(SemanticSearchConfig config, Modality modality, long retryBaseMillis)
            throws EmbeddingException {
        String protocol = config.getRemoteProtocol(modality);
        URI base = RemoteHttp.checkEndpoint(config.getRemoteEndpoint(modality), config.isRemoteAllowPlainHttp());
        CredentialResolver.Credential credential = CredentialResolver.resolve(config, modality);
        RemoteHttp http = new RemoteHttp(base, credential, config.getRemoteTimeoutSeconds(),
                config.getRemoteMaxRetries(), retryBaseMillis);
        LOGGER.info("Semantic {} model: remote, protocol {}, destination {}, credential {}", modality.key(), protocol,
                http.destination(), credential.origin());
        if (OPENAI.equals(protocol)) {
            if (modality == Modality.VISUAL) {
                throw new EmbeddingException(OPENAI + " cannot embed images; the visual modality needs " + IPED_EMBED,
                        null, true);
            }
            return RemoteOpenAiEmbedder.open(http, config.getRemoteModel(modality), config.getRemoteQueryPrefix(),
                    config.getRemotePassagePrefix());
        }
        if (IPED_EMBED.equals(protocol)) {
            return RemoteIpedEmbedder.open(http, config.getRemoteModel(modality));
        }
        throw new EmbeddingException("unknown remote protocol '" + protocol + "'; use " + OPENAI + " or " + IPED_EMBED,
                null, true);
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

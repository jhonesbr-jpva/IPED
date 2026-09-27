package iped.semantic.model;

/**
 * An embedding could not be produced. {@link #isConfiguration()} separates what retrying cannot fix
 * (a credential refused by the destination, a response without model identity) from transient
 * failures (timeout, 5xx, rate limit) that the remote embedders retry.
 */
public class EmbeddingException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean configuration;
    private final boolean remoteUnavailable;

    public EmbeddingException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public EmbeddingException(String message) {
        this(message, null, false);
    }

    public EmbeddingException(String message, Throwable cause, boolean configuration) {
        this(message, cause, configuration, false);
    }

    private EmbeddingException(String message, Throwable cause, boolean configuration, boolean remoteUnavailable) {
        super(message, cause);
        this.configuration = configuration;
        this.remoteUnavailable = remoteUnavailable;
    }

    /** A remote destination that did not answer after the configured retries. */
    public static EmbeddingException remoteUnavailable(String message, Throwable cause) {
        return new EmbeddingException(message, cause, false, true);
    }

    /** {@code true} when the failure is a configuration problem, not a transient one. */
    public boolean isConfiguration() {
        return configuration;
    }

    /** {@code true} when a remote model stayed unreachable after its retries ({@code REMOTE_UNAVAILABLE}). */
    public boolean isRemoteUnavailable() {
        return remoteUnavailable;
    }
}

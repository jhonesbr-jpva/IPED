package iped.semantic.config;

/**
 * A semantic search configuration that cannot serve an enabled modality. Raised at task start-up so
 * processing refuses to begin (FR-042), and by the searcher so the modality declares itself
 * unavailable instead of guessing another source.
 */
public class SemanticConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public SemanticConfigException(String message) {
        super(message);
    }

    public SemanticConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

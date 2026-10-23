package iped.engine.graph;

/**
 * Thrown by {@link GraphServiceProvider#validate()} when the installed graph plugin is incomplete or
 * corrupted. The message describes what is missing.
 */
public class GraphPluginException extends Exception {

    private static final long serialVersionUID = 1L;

    public GraphPluginException(String message) {
        super(message);
    }

    public GraphPluginException(String message, Throwable cause) {
        super(message, cause);
    }

}

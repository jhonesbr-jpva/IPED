package iped.engine.graph;

/**
 * Thrown by a {@link GraphService} when a query cannot be executed, e.g. an invalid query text typed
 * in the advanced search. The message is meant to be shown to the user.
 */
public class GraphQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphQueryException(String message) {
        super(message);
    }

    public GraphQueryException(String message, Throwable cause) {
        super(message, cause);
    }

}

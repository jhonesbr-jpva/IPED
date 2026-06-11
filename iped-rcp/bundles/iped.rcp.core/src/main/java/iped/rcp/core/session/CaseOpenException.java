package iped.rcp.core.session;

/**
 * Failure to open a case session (invalid case folder, missing index,
 * missing evidence images, corrupted data...). The session rolls back to
 * {@link SessionState#CLOSED} before this is thrown, so callers only need to
 * report the error to the user (FR-001 acceptance: clean shutdown, no
 * half-open session).
 */
public class CaseOpenException extends Exception {

    private static final long serialVersionUID = 1L;

    public CaseOpenException(String message) {
        super(message);
    }

    public CaseOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}

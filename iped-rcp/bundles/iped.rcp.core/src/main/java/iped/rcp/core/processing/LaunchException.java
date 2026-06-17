package iped.rcp.core.processing;

/**
 * Thrown by {@link ProcessingLaunchService} when a processing job cannot be
 * started — invalid request, the install layout (java / {@code iped.jar})
 * cannot be resolved, an output-folder conflict (FR-024), or the OS failing to
 * spawn the subprocess.
 */
public class LaunchException extends Exception {

    private static final long serialVersionUID = 1L;

    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}

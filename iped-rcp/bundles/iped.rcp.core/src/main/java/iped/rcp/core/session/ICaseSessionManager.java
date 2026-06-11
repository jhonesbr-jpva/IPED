package iped.rcp.core.session;

import java.nio.file.Path;
import java.util.List;

import iped.rcp.api.ICaseSessionService;

/**
 * Internal management surface of the case session, used by the application
 * lifecycle (case opening on startup) and by other core services. NOT part
 * of the provisional extension API: third-party bundles only see the
 * read-oriented {@link ICaseSessionService}.
 */
public interface ICaseSessionManager extends ICaseSessionService {

    /**
     * Opens a session synchronously. Long-running (index loading on large
     * cases) — never call on the UI thread; run it from a job and report
     * progress around it.
     *
     * <p>
     * Accepted inputs (FR-001/FR-002, mirroring the current UI launch modes):
     * <ul>
     * <li>one path to a case folder — single case;</li>
     * <li>one path to a folder that is not a case — recursively scanned for
     * case folders (multicase, parity with {@code -multicases <dir>});</li>
     * <li>one path to a UTF-8 text file — one case folder per line, {@code #}
     * comments allowed (multicase, parity with {@code -multicases <txt>});</li>
     * <li>several paths — each must be a case folder (multicase).</li>
     * </ul>
     *
     * @return the session, already in {@link SessionState#READY}
     * @throws CaseOpenException if validation or loading fails; the service
     *             is back to {@link SessionState#CLOSED} when it is thrown
     * @throws IllegalStateException if a session is already open or opening
     */
    CaseSession open(List<Path> casePaths) throws CaseOpenException;

    /**
     * Closes the current session and disposes the engine source. No-op when
     * already closed. Never call on the UI thread (storage/index disposal
     * may block on I/O).
     */
    void close();

    /** @return the current session, or null unless {@link SessionState#READY} */
    CaseSession getSession();

    /** @return the current lifecycle state */
    SessionState getState();
}

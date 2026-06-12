package iped.rcp.core.session;

/**
 * Callback for near-live source reload cycles (task T063, FR-029): the
 * {@link CommitMonitor} detects a new index consolidation and
 * {@link CaseSessionService} swaps the engine source inside the open
 * {@link CaseSession}. Both methods run on the monitor thread, never on the
 * UI thread.
 */
public interface SessionReloadListener {

    /**
     * Invoked right before the new source is opened, while the old one is
     * still active (e.g. flush pending bookmark writes so the freshly loaded
     * state includes them).
     */
    default void beforeReload() {
    }

    /**
     * Invoked after the new source was atomically swapped into the session.
     * The old source remains usable until the NEXT reload (grace period for
     * in-flight readers), then it is closed.
     */
    default void afterReload() {
    }
}

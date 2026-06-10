package iped.rcp.api;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Access to the case session opened in the workbench. Registered as an OSGi
 * Declarative Service by the RCP core and injectable into e4 parts.
 *
 * <p>
 * The session is read-only over evidence data (FR-004): extensions can never
 * modify case contents through this API; user artifacts are written only via
 * {@link IBookmarkService}.
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public interface ICaseSessionService {

    /** @return true when a case session is open and READY. */
    boolean isOpen();

    /**
     * @return the case folders of the active session (one entry for a single
     *         case, several for a multicase session); empty when no session
     *         is open
     */
    List<Path> getCasePaths();

    /**
     * @return true when the open case is still being processed and the
     *         near-live refresh is active (FR-029)
     */
    boolean isInteractive();

    /**
     * Registers a listener invoked on session lifecycle changes (after
     * {@link UiEventTopics#CASE_OPENED} / {@link UiEventTopics#CASE_CLOSED}
     * semantics). Listeners are called outside the UI thread.
     *
     * @return a runnable that unregisters the listener
     */
    Runnable addSessionListener(Consumer<Boolean> openStateListener);
}

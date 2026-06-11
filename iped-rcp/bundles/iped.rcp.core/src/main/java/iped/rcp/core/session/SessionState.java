package iped.rcp.core.session;

/**
 * Lifecycle states of the case session (data-model "CaseSession"):
 * {@code OPENING -> READY -> CLOSING -> CLOSED}. A failure while OPENING
 * rolls straight back to CLOSED (no half-open session). In interactive
 * (near-live) mode, READY admits reload cycles driven by the commit monitor
 * (FR-029, task T063) without leaving the READY state.
 */
public enum SessionState {

    /** A session is being opened (case validation and index loading). */
    OPENING,

    /** The session is open and usable; evidence data is read-only. */
    READY,

    /** The session is being disposed (index readers/storages closing). */
    CLOSING,

    /** No session. Initial and final state. */
    CLOSED;
}

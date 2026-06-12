package iped.rcp.api;

/**
 * Public event topics ({@code IEventBroker}) and selection key
 * ({@code ESelectionService}) of the RCP UI. Topics outside the
 * {@code iped/rcp/} prefix listed here are internal and may change at any
 * time.
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public final class UiEventTopics {

    /** Selection key used with the e4 ESelectionService ({@link SelectionContext} payload). */
    public static final String SELECTION_KEY = "iped.rcp.selection";

    /** A case session reached READY state. Payload: list of case paths (String). */
    public static final String CASE_OPENED = "iped/rcp/case/OPENED";

    /** The case session was closed. Payload: list of case paths (String). */
    public static final String CASE_CLOSED = "iped/rcp/case/CLOSED";

    /**
     * The active result set changed (new search/filter, or a near-live
     * refresh cycle — FR-029). Payload: opaque generation token (Long).
     */
    public static final String RESULTS_CHANGED = "iped/rcp/results/CHANGED";

    /** Bookmarks were created/changed/removed. Payload: bookmark name (String) or null for bulk. */
    public static final String BOOKMARKS_CHANGED = "iped/rcp/bookmarks/CHANGED";

    /**
     * The engine source of the open session was reloaded in place after a new
     * index consolidation was detected (near-live mode, FR-029/FR-030).
     * Consumers holding source-derived state (trees, facet aggregations)
     * should recompute it; the active result set is refreshed separately and
     * announced via {@link #RESULTS_CHANGED}. Payload: list of case paths
     * (String).
     */
    public static final String CASE_RELOADED = "iped/rcp/case/RELOADED";

    private UiEventTopics() {
    }
}

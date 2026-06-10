package iped.rcp.api;

import java.util.List;

/**
 * Search over the open case session with the same query syntax accepted by
 * the IPED analysis UI (FR-006). Results reflect the data consolidated up to
 * the latest refresh generation (near-live mode, FR-029).
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public interface ISearchService {

    /**
     * @return the total number of items matching the query
     * @throws IllegalArgumentException on query syntax errors (message is
     *         user-presentable and localized)
     */
    long count(String query);

    /**
     * Runs the query and returns one page of matching item ids in index
     * order. Use {@code offset}/{@code limit} to page through large result
     * sets instead of materializing them.
     */
    List<ItemId> search(String query, int offset, int limit);
}

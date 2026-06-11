package iped.rcp.core.search;

import iped.engine.search.MultiSearchResult;

/**
 * Immutable snapshot of the active result set (data-model "ResultSetModel"
 * backing): the engine result plus the query/sort state that produced it.
 * Instances are swapped atomically by {@link SearchService} — the result is
 * never mutated in place on the UI thread (data-model rule).
 *
 * @param queryText     the query text that produced this result (empty =
 *                      match-all)
 * @param result        the engine result; treat as read-only
 * @param generation    monotonically increasing token, published with
 *                      {@code iped/rcp/results/CHANGED} so parts can ignore
 *                      stale refreshes
 * @param sortField     index field the result is ordered by, or {@code null}
 *                      for index order
 * @param sortAscending sort direction (meaningless when {@code sortField} is
 *                      null)
 */
public record ResultSet(String queryText, MultiSearchResult result, long generation, String sortField,
        boolean sortAscending) {

    /** Convenience accessor: number of items in this result. */
    public int size() {
        return result.getLength();
    }
}

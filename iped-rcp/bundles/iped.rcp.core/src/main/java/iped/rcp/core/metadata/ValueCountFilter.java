package iped.rcp.core.metadata;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import iped.engine.search.MultiSearchResult;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IResultSetFilter;

/**
 * Filter-by-facet-values (task T030, FR-010/MD-03, legacy
 * {@code MetadataPanel} selection filtering): keeps the items of the result
 * whose field value falls in the selected ords, resolved by the SAME
 * {@link MetadataAggregator} instance that produced the counts (range ords
 * depend on its bin state).
 */
public class ValueCountFilter implements IResultSetFilter {

    private final MetadataAggregator aggregator;
    private final String field;
    private final Set<Integer> ords;

    public ValueCountFilter(MetadataAggregator aggregator, String field, Set<Integer> ords) {
        this.aggregator = aggregator;
        this.field = field;
        this.ords = new LinkedHashSet<>(ords);
    }

    @Override
    public IMultiSearchResult filterResult(IMultiSearchResult src) throws IOException {
        return aggregator.getIdsWithOrd((MultiSearchResult) src, field, ords);
    }

    @Override
    public String toString() {
        return field + ":" + ords.size() + " value(s)";
    }
}

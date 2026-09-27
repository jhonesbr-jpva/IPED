package iped.app.ui.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iped.app.ui.Messages;
import iped.data.IItemId;
import iped.engine.search.MultiSearchResult;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IFilter;
import iped.viewers.api.IResultSetFilter;
import iped.viewers.api.IResultSetFilterer;

/**
 * "Apply to table": shows one semantic ordering in the results table, in its order, with the
 * relative proximity in the score column - the same mechanism as similar images and faces. One
 * modality at a time (FR-034). The {@code src} it filters is the scope: only items of the current
 * result are kept (FR-012).
 */
public class SemanticResultFilterer implements IResultSetFilterer {

    /** The applied ordering: item ids in ordering order, with their proximity. */
    private volatile SemanticResultFilter filter;

    /** Installs an ordering; {@code ids} in ordering order, each once, with its proximity. */
    public void apply(Map<IItemId, Float> idsInOrder, String description) {
        this.filter = new SemanticResultFilter(new LinkedHashMap<>(idsInOrder), description);
    }

    @Override
    public List<IFilter> getDefinedFilters() {
        List<IFilter> out = new ArrayList<>();
        if (filter != null) {
            out.add(filter);
        }
        return out;
    }

    @Override
    public IFilter getFilter() {
        return filter;
    }

    @Override
    public boolean hasFilters() {
        return filter != null;
    }

    @Override
    public boolean hasFiltersApplied() {
        return filter != null;
    }

    @Override
    public void clearFilter() {
        filter = null;
    }

    static final class SemanticResultFilter implements IResultSetFilter {
        private final LinkedHashMap<IItemId, Float> ordering;
        private final String description;

        SemanticResultFilter(LinkedHashMap<IItemId, Float> ordering, String description) {
            this.ordering = ordering;
            this.description = description;
        }

        @Override
        public IMultiSearchResult filterResult(IMultiSearchResult src) {
            java.util.Set<IItemId> inScope = new java.util.HashSet<>();
            for (int i = 0; i < src.getLength(); i++) {
                inScope.add(src.getItem(i));
            }
            List<IItemId> ids = new ArrayList<>();
            List<Float> scores = new ArrayList<>();
            for (Map.Entry<IItemId, Float> e : ordering.entrySet()) {
                if (inScope.contains(e.getKey())) {
                    ids.add(e.getKey());
                    scores.add(e.getValue());
                }
            }
            float[] s = new float[scores.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = scores.get(i);
            }
            return new MultiSearchResult(ids.toArray(new IItemId[0]), s);
        }

        @Override
        public String toString() {
            return Messages.getString("SemanticSearch.Title") + ": " + description;
        }
    }
}

package iped.rcp.core.filters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.rcp.core.session.ICaseSessionManager;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IQueryFilter;
import iped.viewers.api.IResultSetFilter;

/**
 * Composition service for the UI filters (task T031, FR-016, data-model
 * "FilterState"): keeps the active query filters (ANDed into the search
 * query, legacy {@code CaseSearcherFilter.getQueryWithUIFilter}), the active
 * result-set filters (applied sequentially after the search, legacy
 * {@code CaseSearcherFilter.doInBackground}) and the combined AND/OR/NOT
 * tree (legacy {@code CombinedFilterer}, evaluated with per-source bitsets).
 *
 * <p>
 * Applying filters is always a new engine query/pipeline run — never
 * filtering on the UI thread (data-model rule); {@code SearchService} calls
 * {@link #composeQuery} and {@link #postProcess} from the search job. Parts
 * mutate the filter slots (keyed by the {@code KEY_*}-style constants below)
 * and then ask {@code SearchService.refresh()}, mirroring the legacy
 * {@code updateFileListing()} discipline.
 */
@Component(service = FilterStateService.class)
public class FilterStateService {

    // --- query filter slots (legacy IQueryFilterer registrations) ---
    public static final String CATEGORIES = "categories";
    public static final String EVIDENCE_TREE = "evidenceTree";
    public static final String AI_FILTERS = "aiFilters";
    public static final String SAVED_FILTER = "savedFilter";
    public static final String SIMILAR_IMAGES = "similarImages";
    public static final String SIMILAR_DOCUMENT = "similarDocument";

    // --- result-set filter slots (legacy IResultSetFilterer registrations) ---
    public static final String DUPLICATES = "duplicates";
    public static final String BOOKMARKS = "bookmarks";
    public static final String METADATA = "metadata";
    public static final String SIMILAR_FACES = "similarFaces";

    /**
     * Deterministic application order of the result-set pipeline. The
     * similar-images re-scorer runs last among slots so it scores the already
     * narrowed result (legacy filterer order in {@code App}).
     */
    private static final String[] RESULT_SET_ORDER = { DUPLICATES, BOOKMARKS, METADATA, SIMILAR_FACES,
            SIMILAR_IMAGES };

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterStateService.class);

    private final Object lock = new Object();
    private final Map<String, Entry<Query>> queryFilters = new LinkedHashMap<>();
    private final Map<String, Entry<IResultSetFilter>> resultSetFilters = new LinkedHashMap<>();
    private FilterTreeNode combinedRoot;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private record Entry<T>(T value, String label) {
    }

    @Reference
    private ICaseSessionManager sessionManager;

    /** DS constructor. */
    public FilterStateService() {
    }

    /** Headless harness constructor (no OSGi injection). */
    public FilterStateService(ICaseSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        watchSession();
    }

    @Activate
    void activate() {
        watchSession();
    }

    /** Filters never survive the case session (legacy behavior). */
    private void watchSession() {
        if (sessionManager != null) {
            sessionManager.addSessionListener(open -> {
                if (!open) {
                    clearAll();
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // mutation (UI thread / harness)
    // ------------------------------------------------------------------

    /** Sets/replaces a query filter slot; {@code null} query clears it. */
    public void setQueryFilter(String key, Query query, String label) {
        synchronized (lock) {
            if (query == null) {
                queryFilters.remove(key);
            } else {
                queryFilters.put(key, new Entry<>(query, label));
            }
        }
        fireChanged();
    }

    /** Sets/replaces a result-set filter slot; {@code null} filter clears it. */
    public void setResultSetFilter(String key, IResultSetFilter filter, String label) {
        synchronized (lock) {
            if (filter == null) {
                resultSetFilters.remove(key);
            } else {
                resultSetFilters.put(key, new Entry<>(filter, label));
            }
        }
        fireChanged();
    }

    /** Sets the combined AND/OR/NOT tree; {@code null} or empty disables it. */
    public void setCombinedTree(FilterTreeNode root) {
        synchronized (lock) {
            combinedRoot = root != null && !root.isEmpty() ? root : null;
        }
        fireChanged();
    }

    public FilterTreeNode getCombinedTree() {
        synchronized (lock) {
            return combinedRoot;
        }
    }

    /** Clears both legs of a slot (some slots carry query + result-set). */
    public void clear(String key) {
        boolean changed;
        synchronized (lock) {
            changed = queryFilters.remove(key) != null;
            changed |= resultSetFilters.remove(key) != null;
        }
        if (changed) {
            fireChanged();
        }
    }

    public void clearAll() {
        synchronized (lock) {
            queryFilters.clear();
            resultSetFilters.clear();
            combinedRoot = null;
        }
        fireChanged();
    }

    public boolean hasQueryFilters() {
        synchronized (lock) {
            return !queryFilters.isEmpty();
        }
    }

    public boolean hasResultSetFilters() {
        synchronized (lock) {
            return !resultSetFilters.isEmpty() || combinedRoot != null;
        }
    }

    public boolean hasFilters() {
        return hasQueryFilters() || hasResultSetFilters();
    }

    /** Active slots with their user-presentable labels (filters panel). */
    public Map<String, String> getActiveFilters() {
        Map<String, String> result = new LinkedHashMap<>();
        synchronized (lock) {
            queryFilters.forEach((key, entry) -> result.put(key, entry.label()));
            resultSetFilters.forEach((key, entry) -> result.merge(key, entry.label(), (a, b) -> a));
            if (combinedRoot != null) {
                result.put("combined", combinedRoot.toString());
            }
        }
        return result;
    }

    /** Subscribes to filter mutations; returns the unsubscriber. */
    public Runnable addChangeListener(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.error("Filter change listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // evaluation (search job / harness)
    // ------------------------------------------------------------------

    /**
     * ANDs the active query filters into the base query — exact composition
     * of the legacy {@code CaseSearcherFilter.getQueryWithUIFilter}.
     */
    public Query composeQuery(Query base) {
        List<Query> snapshot;
        synchronized (lock) {
            snapshot = queryFilters.values().stream().map(Entry::value).toList();
        }
        Query result = base;
        for (Query filterQuery : snapshot) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(filterQuery, Occur.MUST);
            builder.add(result, Occur.MUST);
            result = builder.build();
        }
        return result;
    }

    /**
     * Applies the result-set pipeline and the combined tree to a search
     * result, engine-side (legacy {@code CaseSearcherFilter.doInBackground} +
     * {@code CombinedFilterer.getSearchResult}).
     */
    public MultiSearchResult postProcess(IPEDMultiSource source, MultiSearchResult result) {
        List<Map.Entry<String, IResultSetFilter>> pipeline = orderedResultSetFilters();
        FilterTreeNode tree;
        synchronized (lock) {
            tree = combinedRoot != null ? combinedRoot.snapshot() : null;
        }

        MultiSearchResult current = result;
        try {
            for (Map.Entry<String, IResultSetFilter> entry : pipeline) {
                if (current.getLength() == 0) {
                    break;
                }
                current = toMulti(entry.getValue().filterResult(current));
                current.setIPEDSource(source);
            }
            if (tree != null && current.getLength() > 0) {
                BitSet[] bits = evaluate(source, tree, null);
                current = applyBits(source, current, bits);
                current.setIPEDSource(source);
            }
            return current;
        } catch (Exception e) {
            throw new IllegalStateException("Error applying UI filters: " + e.getMessage(), e);
        }
    }

    private List<Map.Entry<String, IResultSetFilter>> orderedResultSetFilters() {
        Map<String, Entry<IResultSetFilter>> snapshot;
        synchronized (lock) {
            snapshot = new LinkedHashMap<>(resultSetFilters);
        }
        List<Map.Entry<String, IResultSetFilter>> ordered = new ArrayList<>();
        for (String key : RESULT_SET_ORDER) {
            Entry<IResultSetFilter> entry = snapshot.remove(key);
            if (entry != null) {
                ordered.add(Map.entry(key, entry.value()));
            }
        }
        // unknown slots (third-party) keep their insertion order at the end
        snapshot.forEach((key, entry) -> ordered.add(Map.entry(key, entry.value())));
        return ordered;
    }

    /**
     * Evaluates a combined-tree node to one bitset per atomic source
     * (semantics of {@code CombinedFilterer.getBitSet}, with
     * {@code java.util.BitSet} instead of RoaringBitmap).
     */
    private BitSet[] evaluate(IPEDMultiSource source, FilterTreeNode node, MultiSearchResult[] matchAllHolder)
            throws Exception {
        if (matchAllHolder == null) {
            matchAllHolder = new MultiSearchResult[1];
        }
        BitSet[] result;
        if (node.isGroup()) {
            result = node.getOperand() == FilterTreeNode.Operand.AND ? allSet(source) : emptySet(source);
            boolean first = true;
            for (FilterTreeNode child : node.getChildren()) {
                if (child.isEmpty()) {
                    continue;
                }
                BitSet[] childBits = evaluate(source, child, matchAllHolder);
                if (first && node.getOperand() == FilterTreeNode.Operand.AND) {
                    // start AND from the first child to keep all-set semantics
                    result = childBits;
                    first = false;
                    continue;
                }
                first = false;
                for (int i = 0; i < result.length; i++) {
                    if (node.getOperand() == FilterTreeNode.Operand.AND) {
                        result[i].and(childBits[i]);
                    } else {
                        result[i].or(childBits[i]);
                    }
                }
            }
        } else {
            IMultiSearchResult base;
            if (node.getFilter() instanceof IQueryFilter queryFilter && queryFilter.getQuery() != null) {
                MultiSearchResult queryResult = new IPEDSearcher(source, queryFilter.getQuery()).multiSearch();
                queryResult.setIPEDSource(source);
                base = queryResult;
            } else {
                if (matchAllHolder[0] == null) {
                    matchAllHolder[0] = new IPEDSearcher(source, "").multiSearch();
                    matchAllHolder[0].setIPEDSource(source);
                }
                base = matchAllHolder[0];
            }
            if (node.getFilter() instanceof IResultSetFilter resultSetFilter) {
                base = resultSetFilter.filterResult(base);
            }
            result = emptySet(source);
            for (IItemId item : base.getIterator()) {
                result[item.getSourceId()].set(item.getId());
            }
        }
        if (node.isNegated()) {
            for (IPEDSource atomic : source.getAtomicSources()) {
                if (atomic.getLastId() >= 0) {
                    result[atomic.getSourceId()].flip(0, atomic.getLastId() + 1);
                }
            }
        }
        return result;
    }

    private static BitSet[] emptySet(IPEDMultiSource source) {
        return newSets(source, false);
    }

    private static BitSet[] allSet(IPEDMultiSource source) {
        return newSets(source, true);
    }

    private static BitSet[] newSets(IPEDMultiSource source, boolean allSet) {
        int maxSourceId = 0;
        for (IPEDSource atomic : source.getAtomicSources()) {
            maxSourceId = Math.max(maxSourceId, atomic.getSourceId());
        }
        BitSet[] sets = new BitSet[maxSourceId + 1];
        for (int i = 0; i < sets.length; i++) {
            sets[i] = new BitSet();
        }
        if (allSet) {
            for (IPEDSource atomic : source.getAtomicSources()) {
                if (atomic.getLastId() >= 0) {
                    sets[atomic.getSourceId()].set(0, atomic.getLastId() + 1);
                }
            }
        }
        return sets;
    }

    /** Keeps the input order/scores, retaining only items present in the bits. */
    private static MultiSearchResult applyBits(IPEDMultiSource source, MultiSearchResult input, BitSet[] bits) {
        List<IItemId> ids = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (int i = 0; i < input.getLength(); i++) {
            IItemId item = input.getItem(i);
            if (item.getSourceId() < bits.length && bits[item.getSourceId()].get(item.getId())) {
                ids.add(item);
                scores.add(input.getScore(i));
            }
        }
        float[] primitive = new float[scores.size()];
        for (int i = 0; i < primitive.length; i++) {
            primitive[i] = scores.get(i);
        }
        return new MultiSearchResult(ids.toArray(new IItemId[0]), primitive);
    }

    private static MultiSearchResult toMulti(IMultiSearchResult result) {
        if (result instanceof MultiSearchResult multi) {
            return multi;
        }
        // defensive: engine filters return MultiSearchResult today
        List<IItemId> ids = new ArrayList<>(result.getLength());
        float[] scores = new float[result.getLength()];
        int i = 0;
        for (IItemId item : result.getIterator()) {
            ids.add(item);
            scores[i] = result.getScore(i);
            i++;
        }
        return new MultiSearchResult(ids.toArray(new IItemId[0]), scores);
    }
}

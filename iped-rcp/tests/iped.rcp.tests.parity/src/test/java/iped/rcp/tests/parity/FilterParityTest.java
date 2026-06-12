package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import iped.data.IItem;
import iped.data.IItemId;
import iped.engine.data.Category;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.ImageSimilarityLowScoreFilter;
import iped.engine.search.ImageSimilarityScorer;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.QueryBuilder;
import iped.engine.task.index.IndexItem;
import iped.engine.task.similarity.ImageSimilarityTask;
import iped.properties.BasicProps;
import iped.rcp.core.filters.BookmarkFilters;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.filters.FilterTreeNode;
import iped.rcp.core.filters.SavedFiltersStore;
import iped.rcp.core.filters.SimilarityFilters;
import iped.rcp.core.metadata.MetadataAggregator;
import iped.rcp.core.metadata.RangeCount;
import iped.rcp.core.metadata.ValueCount;
import iped.rcp.core.metadata.ValueCountFilter;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.ICaseSessionManager;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IQueryFilter;

/**
 * Filter/similarity parity harness (task T025, US2, SC-001/FR-010/FR-013/
 * FR-016): counts produced by the new UI filter composition
 * ({@code FilterStateService} + {@code SearchService}) must match the engine
 * code paths used by the current UI ({@code CaseSearcherFilter} +
 * {@code CategoryTreeListener}/{@code MetadataPanel}/{@code App} filterers).
 *
 * <p>
 * Requires {@code -Dcase.dir=<reference-case>} (skipped without it).
 * Similarity legs depend on optional processing features and are skipped via
 * assumptions when the reference case lacks the indexed fields.
 */
class FilterParityTest {

    private static ICaseSessionManager manager;
    private static CaseSession session;
    private static IPEDMultiSource source;
    private static FilterStateService filterState;
    private static SearchService searchService;
    private static long total;

    @BeforeAll
    static void openCase() throws Exception {
        String caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set, skipping filter parity tests");
        manager = new CaseSessionService();
        session = manager.open(List.of(Path.of(caseDir)));
        source = session.getSource();
        filterState = new FilterStateService(manager);
        searchService = new SearchService(manager, filterState);
        total = baselineCount(new IPEDSearcher(source, "").multiSearch());
    }

    @AfterEach
    void clearFilters() {
        if (filterState != null) {
            filterState.clearAll();
        }
    }

    @AfterAll
    static void closeCase() {
        if (manager != null) {
            manager.close();
        }
    }

    // ------------------------------------------------------------------
    // Categories (FR-009/AR-02)
    // ------------------------------------------------------------------

    @Test
    void categoryFilterMatchesLegacyQueryPathAndTreeCounts() throws Exception {
        List<Category> categories = leafCategoriesWithItems(3);
        assumeTrue(!categories.isEmpty(), "case has no categorized items");

        for (Category category : categories) {
            filterState.setQueryFilter(FilterStateService.CATEGORIES, categoryQuery(List.of(category)),
                    category.getName());
            ResultSet result = searchService.runSearch("");

            // legacy baseline 1: the count displayed on the category tree
            // (engine countNumItems, the number the user sees)
            assertEquals(category.getNumItems(), result.size(),
                    "category filter count diverged from the tree count for " + category.getName());

            // legacy baseline 2: CaseSearcherFilter composition (match-all
            // MUST category-term)
            assertEquals(baselineCount(composedBaseline("", categoryQuery(List.of(category)))), result.size(),
                    "category filter count diverged from the legacy composed query for " + category.getName());
        }
    }

    @Test
    void composedQueryFiltersMatchBooleanBaseline() throws Exception {
        List<Category> categories = leafCategoriesWithItems(1);
        assumeTrue(!categories.isEmpty(), "case has no categorized items");
        Category category = categories.get(0);
        String queryText = BasicProps.ISDIR + ":false";

        filterState.setQueryFilter(FilterStateService.CATEGORIES, categoryQuery(List.of(category)),
                category.getName());
        ResultSet result = searchService.runSearch(queryText);

        assertEquals(baselineCount(composedBaseline(queryText, categoryQuery(List.of(category)))), result.size(),
                "query text AND category filter diverged from the boolean baseline");
    }

    // ------------------------------------------------------------------
    // Combined AND/OR/NOT tree (FR-016/FI-01)
    // ------------------------------------------------------------------

    @Test
    void combinedTreeAlgebraMatchesSetOperations() throws Exception {
        List<Category> categories = leafCategoriesWithItems(2);
        assumeTrue(categories.size() >= 2, "case needs two non-empty categories for the combined-tree leg");
        Category catA = categories.get(0);
        Category catB = categories.get(1);

        Set<String> setA = itemKeys(new IPEDSearcher(source, categoryQuery(List.of(catA))).multiSearch());
        Set<String> setB = itemKeys(new IPEDSearcher(source, categoryQuery(List.of(catB))).multiSearch());

        // OR
        FilterTreeNode or = FilterTreeNode.group(FilterTreeNode.Operand.OR);
        or.add(FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catA))), catA.getName()));
        or.add(FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catB))), catB.getName()));
        filterState.setCombinedTree(or);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        assertEquals(union.size(), searchService.runSearch("").size(), "OR tree diverged from set union");

        // AND
        FilterTreeNode and = FilterTreeNode.group(FilterTreeNode.Operand.AND);
        and.add(FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catA))), catA.getName()));
        and.add(FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catB))), catB.getName()));
        filterState.setCombinedTree(and);
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        assertEquals(intersection.size(), searchService.runSearch("").size(), "AND tree diverged from intersection");

        // NOT (negated leaf inside an AND group)
        FilterTreeNode not = FilterTreeNode.group(FilterTreeNode.Operand.AND);
        FilterTreeNode negated = FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catA))), catA.getName());
        negated.setNegated(true);
        not.add(negated);
        filterState.setCombinedTree(not);
        assertEquals(total - setA.size(), searchService.runSearch("").size(),
                "negated leaf diverged from set complement");

        // mixed leaf types: OR(result-set duplicates, query catA)
        FilterTreeNode mixed = FilterTreeNode.group(FilterTreeNode.Operand.OR);
        mixed.add(FilterTreeNode.leaf(SimilarityFilters.duplicates(source), "duplicates"));
        mixed.add(FilterTreeNode.leaf(queryFilter(categoryQuery(List.of(catA))), catA.getName()));
        filterState.setCombinedTree(mixed);
        Set<String> dupKeys = itemKeys(
                SimilarityFilters.duplicates(source).filterResult(new IPEDSearcher(source, "").multiSearch()));
        Set<String> mixedUnion = new HashSet<>(dupKeys);
        mixedUnion.addAll(setA);
        assertEquals(mixedUnion.size(), searchService.runSearch("").size(),
                "OR tree with result-set leaf diverged from set union");
    }

    // ------------------------------------------------------------------
    // Duplicates (FI-04) and bookmarks selection filter (AR-03)
    // ------------------------------------------------------------------

    @Test
    void duplicatesFilterAppliedThroughPipeline() throws Exception {
        IMultiSearchResult baseline = SimilarityFilters.duplicates(source)
                .filterResult(new IPEDSearcher(source, "").multiSearch());

        filterState.setResultSetFilter(FilterStateService.DUPLICATES, SimilarityFilters.duplicates(source),
                "duplicates");
        ResultSet result = searchService.runSearch("");

        assertEquals(baseline.getLength(), result.size(), "duplicates filter diverged from DynamicDuplicateFilter");
        assertTrue(result.size() <= total);
    }

    @Test
    void bookmarkSelectionFilterMatchesEngineFilter() throws Exception {
        String name = "T025 filtro temporário";
        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        assumeTrue(all.getLength() >= 5, "case too small for the bookmark leg");
        Set<IItemId> members = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            members.add(all.getItem(i));
        }
        // in-memory only: no saveState() is invoked, the case dir is untouched
        source.getMultiBookmarks().newBookmark(name);
        try {
            source.getMultiBookmarks().addBookmark(members, name);

            filterState.setResultSetFilter(FilterStateService.BOOKMARKS,
                    BookmarkFilters.selection(source, Set.of(name), false), name);
            assertEquals(5, searchService.runSearch("").size(), "bookmark selection filter diverged");
        } finally {
            source.getMultiBookmarks().delBookmark(name);
        }
    }

    // ------------------------------------------------------------------
    // Metadata facets (FR-010/MD-01..03)
    // ------------------------------------------------------------------

    @Test
    void metadataValueFacetsMatchLegacyQueryPath() throws Exception {
        MetadataAggregator aggregator = new MetadataAggregator(source);
        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        all.setIPEDSource(source);
        List<ValueCount> values = aggregator.countValues(BasicProps.CONTENTTYPE, all);
        assertFalse(values.isEmpty(), "contentType facet must produce values");

        // contentType is single-valued and always present: counts partition
        // the result
        long sum = values.stream().mapToLong(ValueCount::getCount).sum();
        assertEquals(total, sum, "facet counts of a single-valued field must partition the result");

        // each value count == legacy filter-by-value query path
        // (ValueCountQueryFilter builds field:"value" through QueryBuilder)
        int checked = 0;
        for (ValueCount value : values) {
            if (checked++ >= 5) {
                break;
            }
            Query baseline = new QueryBuilder(source)
                    .getQuery(BasicProps.CONTENTTYPE + ":\"" + value.getVal() + "\"");
            assertEquals(baselineCount(new IPEDSearcher(source, baseline).multiSearch()), (long) value.getCount(),
                    "facet count diverged from the legacy query for value " + value.getVal());
        }

        // filtering by the two first values keeps exactly their items
        Set<Integer> ords = new LinkedHashSet<>();
        long expected = 0;
        for (int i = 0; i < Math.min(2, values.size()); i++) {
            ords.add(values.get(i).getOrd());
            expected += values.get(i).getCount();
        }
        filterState.setResultSetFilter(FilterStateService.METADATA,
                new ValueCountFilter(aggregator, BasicProps.CONTENTTYPE, ords), "contentType");
        assertEquals(expected, searchService.runSearch("").size(),
                "value filter diverged from the sum of the selected facet counts");
    }

    @Test
    void metadataRangeFacetsMatchRangeQueries() throws Exception {
        MetadataAggregator aggregator = new MetadataAggregator(source);
        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        all.setIPEDSource(source);
        List<ValueCount> ranges = aggregator.countValues(BasicProps.LENGTH, all);
        assumeTrue(!ranges.isEmpty(), "case has no items with size for the range leg");

        long sum = 0;
        for (ValueCount value : ranges) {
            assertTrue(value instanceof RangeCount, "numeric facet must produce ranges");
            RangeCount range = (RangeCount) value;
            sum += range.getCount();

            // integer field: actualMin/actualMax are exact inclusive bounds
            Query baseline = new QueryBuilder(source).getQuery(BasicProps.LENGTH + ":[" + (long) range.getStart()
                    + " TO " + (long) range.getEnd() + "]");
            assertEquals(baselineCount(new IPEDSearcher(source, baseline).multiSearch()), (long) range.getCount(),
                    "range count diverged from the legacy range query for " + range.getVal());
        }

        // filtering by the first range keeps exactly its items
        RangeCount first = (RangeCount) ranges.get(0);
        filterState.setResultSetFilter(FilterStateService.METADATA,
                new ValueCountFilter(aggregator, BasicProps.LENGTH, Set.of(first.getOrd())), "size");
        assertEquals(first.getCount(), searchService.runSearch("").size(),
                "range filter diverged from the facet count");
        assertTrue(sum <= total, "range counts cannot exceed the result size");
    }

    // ------------------------------------------------------------------
    // Saved filters in the current format (FR-005/FI-02/FI-03)
    // ------------------------------------------------------------------

    @Test
    void savedFiltersLoadCurrentFormatAndCompose() throws Exception {
        SavedFiltersStore store = new SavedFiltersStore(source);
        Map<String, String> filters = store.getAll();
        assumeTrue(!filters.isEmpty(), "case ships no DefaultFilters.txt and user has no saved filters");

        int checked = 0;
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            if (checked >= 5) {
                break;
            }
            Query query;
            try {
                query = new QueryBuilder(source).getQuery(entry.getValue());
            } catch (Exception e) {
                continue; // filters referencing unsupported syntax are out of scope here
            }
            checked++;
            filterState.setQueryFilter(FilterStateService.SAVED_FILTER, query, entry.getKey());
            assertEquals(baselineCount(composedBaseline("", query)), (long) searchService.runSearch("").size(),
                    "saved filter count diverged for " + entry.getKey());
        }
        assumeTrue(checked > 0, "no saved filter expression was parseable in this case");
    }

    // ------------------------------------------------------------------
    // Similarity (FR-013/SI-01..03) — guarded by case capabilities
    // ------------------------------------------------------------------

    @Test
    void similarImagesMatchesLegacyScorePipeline() throws Exception {
        IItem refItem = findItemWithExtraAttribute(ImageSimilarityTask.IMAGE_FEATURES, 200);
        assumeTrue(refItem != null, "case has no imageSimilarity features (task disabled?)");

        IQueryFilter queryLeg = SimilarityFilters.similarImagesQuery(refItem);
        assertNotNull(queryLeg.getQuery());

        // legacy pipeline: candidates by query, engine scorer, low-score cut
        MultiSearchResult candidates = new IPEDSearcher(source, queryLeg.getQuery()).multiSearch();
        candidates.setIPEDSource(source);
        new ImageSimilarityScorer(source, candidates, refItem).score();
        MultiSearchResult baseline = ImageSimilarityLowScoreFilter.filter(candidates);

        filterState.setQueryFilter(FilterStateService.SIMILAR_IMAGES, queryLeg.getQuery(), refItem.getName());
        filterState.setResultSetFilter(FilterStateService.SIMILAR_IMAGES,
                SimilarityFilters.similarImagesRescore(source, refItem), refItem.getName());
        ResultSet result = searchService.runSearch("");

        assertEquals(baseline.getLength(), result.size(), "similar images count diverged from the legacy pipeline");
        assertTrue(result.size() >= 1, "the reference image itself must be in the result");
    }

    @Test
    void similarFacesMatchesEngineFilter() throws Exception {
        IItem refItem = findItemWithExtraAttribute(iped.engine.search.SimilarFacesSearch.FACE_FEATURES, 200);
        assumeTrue(refItem != null, "case has no face features (face recognition disabled?)");

        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        all.setIPEDSource(source);
        MultiSearchResult baseline = new iped.engine.search.SimilarFacesSearch(source, refItem).filter(all);

        filterState.setResultSetFilter(FilterStateService.SIMILAR_FACES,
                SimilarityFilters.similarFaces(source, refItem), refItem.getName());
        assertEquals(baseline.getLength(), searchService.runSearch("").size(),
                "similar faces count diverged from SimilarFacesSearch");
    }

    @Test
    void similarDocumentMatchesLegacyQuery() throws Exception {
        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        assumeTrue(all.getLength() > 0, "empty case");
        IItemId refId = all.getItem(0);

        Query query;
        try {
            IQueryFilter filter = SimilarityFilters.similarDocument(source, refId, 70);
            query = filter.getQuery();
        } catch (RuntimeException e) {
            assumeTrue(false, "similar document search unavailable: " + e.getMessage());
            return;
        }
        assumeTrue(query != null, "no term vectors stored for similar document search");

        filterState.setQueryFilter(FilterStateService.SIMILAR_DOCUMENT, query, "similarDoc");
        assertEquals(baselineCount(composedBaseline("", query)), (long) searchService.runSearch("").size(),
                "similar document count diverged from the legacy query");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Exactly the query the legacy CategoryTreeListener builds. */
    private static Query categoryQuery(List<Category> categories) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Category category : categories) {
            String name = IndexItem.normalize(category.getName(), true);
            builder.add(new TermQuery(new Term(IndexItem.CATEGORY, name)), Occur.SHOULD);
        }
        return builder.build();
    }

    /** Legacy CaseSearcherFilter composition: parsed text MUST filter query. */
    private static MultiSearchResult composedBaseline(String queryText, Query filterQuery) throws Exception {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(filterQuery, Occur.MUST);
        builder.add(new QueryBuilder(source).getQuery(queryText), Occur.MUST);
        return new IPEDSearcher(source, builder.build()).multiSearch();
    }

    private static long baselineCount(MultiSearchResult result) {
        return result.getLength();
    }

    private static IQueryFilter queryFilter(Query query) {
        return new IQueryFilter() {
            @Override
            public Query getQuery() {
                return query;
            }
        };
    }

    private static Set<String> itemKeys(IMultiSearchResult result) {
        Set<String> keys = new HashSet<>();
        for (IItemId item : result.getIterator()) {
            keys.add(item.getSourceId() + ":" + item.getId());
        }
        return keys;
    }

    /** Leaf categories with items, ordered by the tree order (legacy). */
    private static List<Category> leafCategoriesWithItems(int max) {
        List<Category> result = new ArrayList<>();
        collectLeaves(source.getCategoryTree(), result, max);
        return result;
    }

    private static void collectLeaves(Category node, List<Category> out, int max) {
        if (node == null || out.size() >= max) {
            return;
        }
        if (node.getChildren().isEmpty() && node.getParent() != null && node.getNumItems() > 0) {
            out.add(node);
            return;
        }
        for (Category child : new TreeSet<>(node.getChildren())) {
            collectLeaves(child, out, max);
        }
    }

    /** Scans the first items of the case for one carrying an extra attribute. */
    private static IItem findItemWithExtraAttribute(String attribute, int maxScan) throws Exception {
        MultiSearchResult all = new IPEDSearcher(source, "").multiSearch();
        int limit = Math.min(maxScan, all.getLength());
        for (int i = 0; i < limit; i++) {
            IItemId itemId = all.getItem(i);
            IItem item = source.getItemByItemId(itemId);
            if (item != null && item.getExtraAttribute(attribute) != null) {
                return item;
            }
        }
        return null;
    }
}

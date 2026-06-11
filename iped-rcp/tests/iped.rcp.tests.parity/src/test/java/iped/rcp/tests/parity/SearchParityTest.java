package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.properties.BasicProps;
import iped.rcp.api.ItemId;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Search parity harness (task T015, US1, SC-001/FR-006): counts returned by
 * the new UI search path ({@code SearchService}) must equal the engine
 * baseline used by the current UI ({@code IPEDSearcher.multiSearch()} without
 * UI filters, the exact code path of {@code CaseSearcherFilter}).
 *
 * <p>
 * Requires {@code -Dcase.dir=<reference-case>}; the multicase scenario
 * (FR-002) additionally requires {@code -Dcase.dir2=<second-case>} and is
 * skipped without it. Skipping keeps CI green without case data.
 */
class SearchParityTest {

    /**
     * Query set frozen for the parity baseline (parity-inventory.md, area
     * "busca"): match-all, term, field, range, boolean and phrase syntax.
     */
    private static final String[] PARITY_QUERIES = {
            "",
            "*",
            "a",
            BasicProps.NAME + ":a*",
            BasicProps.LENGTH + ":[0 TO 1000]",
            BasicProps.ISDIR + ":true",
            "category:\"Other files\" OR " + BasicProps.NAME + ":*.txt",
            BasicProps.NAME + ":(a AND NOT b)",
    };

    private static ICaseSessionManager manager;
    private static CaseSession session;
    private static SearchService searchService;

    @BeforeAll
    static void openCase() throws Exception {
        String caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set, skipping search parity tests");
        manager = new CaseSessionService();
        session = manager.open(List.of(Path.of(caseDir)));
        searchService = new SearchService(manager);
    }

    @AfterAll
    static void closeCase() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void countsMatchEngineBaseline() throws Exception {
        for (String query : PARITY_QUERIES) {
            long baseline = baselineCount(session, query);
            long uiCount = searchService.count(query);
            assertEquals(baseline, uiCount, "count diverged from engine baseline for query: " + query);
        }
    }

    @Test
    void resultSetMatchesCountAndPaging() throws Exception {
        for (String query : PARITY_QUERIES) {
            long count = searchService.count(query);
            List<ItemId> all = searchService.search(query, 0, Integer.MAX_VALUE);
            assertEquals(count, all.size(), "search() size diverged from count() for query: " + query);

            // paging concatenation must reproduce the full result, in order
            List<ItemId> paged = new ArrayList<>();
            int pageSize = Math.max(1, all.size() / 3);
            for (int offset = 0; offset < all.size(); offset += pageSize) {
                paged.addAll(searchService.search(query, offset, pageSize));
            }
            assertEquals(all, paged, "paged result diverged from full result for query: " + query);
        }
    }

    @Test
    void activeResultLifecycleAndSorting() throws Exception {
        ResultSet active = searchService.runSearch("*");
        assertNotNull(active.result());
        assertEquals(baselineCount(session, "*"), active.result().getLength());

        // engine-side sort (FR-007): same item set, new order, new generation
        ResultSet sorted = searchService.sortBy(BasicProps.NAME, true);
        assertEquals(active.result().getLength(), sorted.result().getLength(), "sorting must not change the item set");
        assertTrue(sorted.generation() > active.generation(), "sort must publish a new result generation");
        assertEquals(searchService.getCurrent().generation(), sorted.generation());

        ResultSet reversed = searchService.sortBy(BasicProps.NAME, false);
        assertEquals(sorted.result().getLength(), reversed.result().getLength());
        if (sorted.result().getLength() > 1) {
            assertEquals(sorted.result().getItem(0).getId(),
                    reversed.result().getItem(reversed.result().getLength() - 1).getId(),
                    "descending order must be the exact reverse of ascending");
        }
    }

    @Test
    void querySyntaxErrorIsUserPresentable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> searchService.count("name:[unclosed TO"));
        assertNotNull(error.getMessage());
    }

    @Test
    void multicaseCountsMatchSumOfSingles() throws Exception {
        String caseDir2 = System.getProperty("case.dir2");
        assumeTrue(caseDir2 != null && !caseDir2.isBlank(), "-Dcase.dir2 not set, skipping multicase parity");

        // single-case baselines, opened separately (current UI behavior for
        // each case alone)
        long single1 = baselineCount(session, "*");
        ICaseSessionManager manager2 = new CaseSessionService();
        CaseSession session2 = manager2.open(List.of(Path.of(caseDir2)));
        long single2;
        try {
            single2 = baselineCount(session2, "*");
        } finally {
            manager2.close();
        }

        // multicase session over both cases (FR-002)
        ICaseSessionManager multiManager = new CaseSessionService();
        CaseSession multiSession = multiManager.open(
                List.of(Path.of(System.getProperty("case.dir")), Path.of(caseDir2)));
        try {
            assertEquals(2, multiSession.getCasePaths().size());
            SearchService multiSearch = new SearchService(multiManager);
            assertEquals(single1 + single2, multiSearch.count("*"),
                    "multicase count must equal the sum of the single-case counts");
            assertEquals(baselineCount(multiSession, "a"), multiSearch.count("a"));
        } finally {
            multiManager.close();
        }
    }

    /**
     * The engine search path of the current UI (CaseSearcherFilter without
     * UI filters). The query text is passed verbatim: blank maps to
     * match-all inside QueryBuilder, exactly like the service under test
     * (note: "*" parses to a slightly different query than blank - both
     * sides must take the same path for the comparison to be meaningful).
     */
    private static long baselineCount(CaseSession caseSession, String query) throws Exception {
        IPEDSearcher searcher = new IPEDSearcher(caseSession.getSource(), query);
        MultiSearchResult result = searcher.multiSearch();
        return result.getLength();
    }
}

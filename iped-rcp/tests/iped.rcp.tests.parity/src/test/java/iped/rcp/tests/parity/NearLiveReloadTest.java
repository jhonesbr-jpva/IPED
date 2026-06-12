package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import iped.engine.data.IPEDMultiSource;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.SessionReloadListener;

/**
 * T063 (US4) — near-live reload machinery, headless
 * (FR-029/FR-030, research R14, data-model {@code CaseSession.commitMonitor}).
 *
 * <p>
 * The commit-generation DETECTION leg was validated live against a real
 * concurrent processing by the T062 spike; here the harness exercises the
 * RELOAD cycle the {@code CommitMonitor} triggers, against the static
 * reference case: atomic source swap inside the open session, reload
 * listeners (bookmark flush before, result refresh after), result-set
 * equality across the swap, grace-period retirement of the old source and a
 * clean close. Requires {@code -Dcase.dir}.
 */
class NearLiveReloadTest {

    private static CaseSessionService sessions;
    private static SearchService search;

    @BeforeAll
    static void openCase() throws Exception {
        String caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set, near-live reload leg skipped");
        sessions = new CaseSessionService();
        // registers the afterReload refresh listener, like the DS runtime does
        search = new SearchService(sessions);
        new BookmarkService(sessions);
        sessions.open(List.of(Path.of(caseDir)));
    }

    @AfterAll
    static void closeCase() {
        if (sessions != null) {
            sessions.close();
        }
    }

    @Test
    void reloadSwapsSourceKeepsResultsAndNotifiesListeners() {
        CaseSession session = sessions.getSession();
        IPEDMultiSource before = session.getSource();

        ResultSet initial = search.runSearch("");
        long countBefore = initial.result().getLength();
        long generationBefore = initial.generation();
        assertTrue(countBefore > 0, "reference case must have items");

        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        Runnable unsubscribe = sessions.addReloadListener(new SessionReloadListener() {
            @Override
            public void beforeReload() {
                beforeCalls.incrementAndGet();
            }

            @Override
            public void afterReload() {
                afterCalls.incrementAndGet();
            }
        });
        try {
            assertTrue(sessions.reloadSources(), "reload must succeed on a readable case");
        } finally {
            unsubscribe.run();
        }

        assertEquals(1, beforeCalls.get());
        assertEquals(1, afterCalls.get());

        IPEDMultiSource after = sessions.getSession().getSource();
        assertNotSame(before, after, "the engine source must be swapped");

        // SearchService.afterReload re-ran the last query on the new source
        ResultSet refreshed = search.getCurrent();
        assertTrue(refreshed.generation() > generationBefore, "results/CHANGED generation must advance");
        assertEquals(countBefore, refreshed.result().getLength(),
                "same on-disk index must yield the same counts after the swap");

        // grace period: the old source is retired, not closed — reads against
        // the PREVIOUS result set still work until the next cycle
        assertEquals(countBefore, initial.result().getLength());

        // a second cycle closes the previously retired source without error
        assertTrue(sessions.reloadSources());
        assertEquals(countBefore, search.getCurrent().result().getLength());
    }
}

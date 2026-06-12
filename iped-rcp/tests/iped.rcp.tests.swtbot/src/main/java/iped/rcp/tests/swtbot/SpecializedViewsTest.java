package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.results.Result;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTable;
import org.junit.Test;

import iped.rcp.specialized.bridge.LegacyUiBridge;

/**
 * SWTBot flow for US3 (task T033, FR-012): the specialized views (map, graph,
 * timeline) render alive inside the real e4 product and their selection stays
 * in sync with the results table, in BOTH directions, through the shared
 * legacy-bridge plumbing.
 *
 * <p>
 * What is automated here: part liveness (the three bridged parts create their
 * SWT_AWT hosts without breaking the workbench) and the selection-sync
 * contract of the bridge (results table → mirror table used by the legacy
 * views; mirror table → results table, which is the exact path a map marker
 * click / timeline highlight worker takes — both end on
 * {@code JTable.addRowSelectionInterval} of the shared mirror table). The
 * in-canvas interactions (clicking a map marker, dragging a timeline
 * interval, expanding a graph node) happen inside JavaFX/Swing surfaces that
 * SWTBot cannot drive; those are covered by the manual checklist rows of the
 * parity inventory (SV-* items).
 *
 * <p>
 * Contract of ids published by the US3 parts (this test is written first —
 * TDD):
 * <ul>
 * <li>{@code iped.rcp.specialized.map.host} — SWT composite hosting the
 * bridged {@code iped-geo} map;</li>
 * <li>{@code iped.rcp.specialized.graph.host} — SWT composite hosting the
 * bridged {@code AppGraphAnalytics};</li>
 * <li>{@code iped.rcp.specialized.timeline.host} — SWT composite hosting the
 * bridged {@code IpedChartsPanel};</li>
 * <li>{@link LegacyUiBridge#probeSelectedRows()} /
 * {@link LegacyUiBridge#probeSelectRows(int[])} — EDT-safe test probes over
 * the shared mirror table.</li>
 * </ul>
 */
public class SpecializedViewsTest {

    public static final String MAP_HOST_ID = "iped.rcp.specialized.map.host";
    public static final String GRAPH_HOST_ID = "iped.rcp.specialized.graph.host";
    public static final String TIMELINE_HOST_ID = "iped.rcp.specialized.timeline.host";

    private static final String MAP_TAB = "Map";
    private static final String GRAPH_TAB = "Graph";
    private static final String TIMELINE_TAB = "Timeline";
    private static final String RESULTS_TAB = "Results";

    private static final long SEARCH_TIMEOUT_MS = 120_000;
    private static final long SYNC_TIMEOUT_MS = 30_000;

    private final SWTBot bot = new SWTBot();

    /** Leave the Results tab selected for the next test in this workbench. */
    @org.junit.After
    public void restoreResultsTab() {
        try {
            bot.cTabItem(RESULTS_TAB).activate();
        } catch (RuntimeException e) {
            // workbench in a bad state: the test already failed elsewhere
        }
    }

    @Test
    public void specializedViewsSelectionSync() throws Exception {
        // 1. baseline: match-all search so the views have a result set
        bot.comboBoxWithId(TriageFlowTest.SEARCH_COMBO_ID).setText("");
        bot.buttonWithId(TriageFlowTest.SEARCH_BUTTON_ID).click();
        SWTBotTable table = bot.tableWithId(TriageFlowTest.RESULTS_TABLE_ID);
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return table.rowCount() > 2;
            }

            @Override
            public String getFailureMessage() {
                return "search returned too few rows (" + table.rowCount() + ")";
            }
        }, SEARCH_TIMEOUT_MS);

        // 2. activate the three specialized tabs: each part must create its
        // bridge host without breaking the workbench (FR-012, research R4)
        bot.cTabItem(MAP_TAB).activate();
        assertNotNull("map part must publish its host composite id", waitForHost(MAP_HOST_ID));
        bot.cTabItem(GRAPH_TAB).activate();
        assertNotNull("graph part must publish its host composite id", waitForHost(GRAPH_HOST_ID));
        bot.cTabItem(TIMELINE_TAB).activate();
        assertNotNull("timeline part must publish its host composite id", waitForHost(TIMELINE_HOST_ID));
        assertTrue("legacy bridge must be installed once a specialized part exists", LegacyUiBridge.isInstalled());

        // 3. table → specialized views: selecting a row in the results table
        // must reach the shared mirror table the legacy views listen to
        bot.cTabItem(RESULTS_TAB).activate();
        table.click(1, 0);
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() throws Exception {
                return Arrays.equals(new int[] { 1 }, LegacyUiBridge.probeSelectedRows());
            }

            @Override
            public String getFailureMessage() {
                try {
                    return "mirror selection did not follow the table: "
                            + Arrays.toString(LegacyUiBridge.probeSelectedRows()) + " ["
                            + LegacyUiBridge.probeDiagnostics() + "]";
                } catch (Exception e) {
                    return "mirror selection probe failed: " + e;
                }
            }
        }, SYNC_TIMEOUT_MS);

        // 4. specialized views → table: a selection change on the mirror
        // table (what a map marker click or a timeline highlight worker does)
        // must land on the SWT results table selection
        LegacyUiBridge.probeSelectRows(new int[] { 2 });
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return table.selectionCount() == 1 && table.selection().get(0, 0).equals(rowLabel(table, 2));
            }

            @Override
            public String getFailureMessage() {
                return "results table did not follow the mirror selection (count=" + table.selectionCount() + ")";
            }
        }, SYNC_TIMEOUT_MS);
    }

    /** First cell of a row (localized row number), for selection asserts. */
    private static String rowLabel(SWTBotTable table, int row) {
        return table.cell(row, 0);
    }

    private Composite waitForHost(String widgetId) {
        final Composite[] found = new Composite[1];
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                found[0] = findHost(widgetId);
                return found[0] != null;
            }

            @Override
            public String getFailureMessage() {
                return "specialized host composite not found: " + widgetId;
            }
        }, SEARCH_TIMEOUT_MS);
        return found[0];
    }

    /**
     * Finds the host composite by its SWTBot id, walking the widget tree
     * (matcher-based lookup would need org.hamcrest in the test runtime).
     */
    private Composite findHost(String widgetId) {
        return UIThreadRunnable.syncExec((Result<Composite>) () -> findHost(bot.activeShell().widget, widgetId));
    }

    private static Composite findHost(Composite parent, String widgetId) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Composite composite) {
                if (widgetId.equals(child.getData("org.eclipse.swtbot.widget.key"))) {
                    return composite;
                }
                Composite nested = findHost(composite, widgetId);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}

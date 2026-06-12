package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.results.StringResult;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotCombo;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTable;
import org.junit.AfterClass;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import iped.rcp.api.IBookmarkService;

/**
 * SWTBot end-to-end triage flow (task T014, US1 scenarios 1-5): open case ->
 * search -> sort -> view -> bookmark -> export. Runs inside the real e4
 * product launched by the tycho-surefire UI harness (see pom: enable with
 * {@code -DskipUiTests=false -Dcase.dir=<reference-case>}).
 *
 * <p>
 * Widget lookup uses the stable SWTBot widget ids published by the US1 parts
 * (the {@code org.eclipse.swtbot.widget.key} data constants below). Native
 * dialogs cannot be driven by SWTBot, so the export step uses the test hook
 * {@code -Diped.rcp.export.dir} honored by the export handler.
 */
public class TriageFlowTest {

    /** SWTBot widget ids published by the US1 parts (contract of this test). */
    public static final String SEARCH_COMBO_ID = "iped.rcp.views.searchbar.query";
    public static final String SEARCH_BUTTON_ID = "iped.rcp.views.searchbar.run";
    public static final String RESULTS_TABLE_ID = "iped.rcp.views.results.table";

    /** Test hook honored by the export handler instead of the native dialog. */
    public static final String EXPORT_DIR_PROP = "iped.rcp.export.dir";

    private static final String BOOKMARK_NAME = "T014 triagem àçãé";

    private static final long SEARCH_TIMEOUT_MS = 120_000;

    private final SWTBot bot = new SWTBot();

    @AfterClass
    public static void cleanupBookmark() {
        // best effort: leave the reference case as it was found
        IBookmarkService bookmarks = lookupBookmarkService();
        if (bookmarks != null && bookmarks.getBookmarkNames().contains(BOOKMARK_NAME)) {
            bookmarks.deleteBookmark(BOOKMARK_NAME);
        }
    }

    @Test
    public void triageFlowEndToEnd() throws Exception {
        SWTBotShell mainWindow = bot.activeShell();
        assertNotNull("main window must be open", mainWindow);

        // 1. search with the current query syntax (US1 scenario 1)
        SWTBotCombo query = bot.comboBoxWithId(SEARCH_COMBO_ID);
        query.setText("*");
        bot.buttonWithId(SEARCH_BUTTON_ID).click();

        SWTBotTable table = bot.tableWithId(RESULTS_TABLE_ID);
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return table.rowCount() > 0;
            }

            @Override
            public String getFailureMessage() {
                return "search returned no rows in the results table";
            }
        }, SEARCH_TIMEOUT_MS);
        int allItems = table.rowCount();
        assertTrue("reference case must list items", allItems > 0);

        // query history keeps the executed query (QueryComboBox parity).
        // Waiting matters when another UI test ran before in the same
        // workbench: the table may already have rows from a previous search,
        // so the row-count wait above can pass before this search's job
        // finishes updating the history.
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return Arrays.asList(query.items()).contains("*");
            }

            @Override
            public String getFailureMessage() {
                return "query history must remember the executed query";
            }
        }, SEARCH_TIMEOUT_MS);

        // 2. sort by a column (US1 scenario 2): click the name column header
        // and check the first cell changes deterministically
        String nameHeader = UIThreadRunnable.syncExec((StringResult) () -> table.widget.getColumn(2).getText());
        String firstBefore = table.cell(0, 2);
        table.header(nameHeader).click();
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return !table.cell(0, 2).equals(firstBefore) || isSorted(table, 2);
            }

            @Override
            public String getFailureMessage() {
                return "table did not re-sort after clicking the column header";
            }
        }, SEARCH_TIMEOUT_MS);
        assertTrue("rows must be ordered by the clicked column", isSorted(table, 2));

        // 3. select an item -> selection drives viewers (US1 scenario 3);
        // smoke level here: selection must not blank the table/active shell
        table.click(0, 2);
        assertEquals(1, table.selectionCount());

        // 4. bookmark the selection through the bookmark service (the dialog
        // flow is exercised manually / by later tests; the service write is
        // the parity-relevant behavior - FR-005)
        IBookmarkService bookmarks = lookupBookmarkService();
        assertNotNull("IBookmarkService must be registered by iped.rcp.core", bookmarks);
        bookmarks.createBookmark(BOOKMARK_NAME);
        assertTrue(bookmarks.getBookmarkNames().contains(BOOKMARK_NAME));

        // 5. export selected items honoring the test hook directory
        Path exportDir = Files.createTempDirectory("iped-rcp-t014-export");
        System.setProperty(EXPORT_DIR_PROP, exportDir.toString());
        try {
            table.contextMenu().menu(menuLabel("MenuClass.ExportItens")).click();
            bot.waitUntil(new DefaultCondition() {
                @Override
                public boolean test() {
                    File[] exported = exportDir.toFile().listFiles();
                    return exported != null && exported.length > 0;
                }

                @Override
                public String getFailureMessage() {
                    return "no file exported to " + exportDir;
                }
            }, SEARCH_TIMEOUT_MS);
        } finally {
            System.clearProperty(EXPORT_DIR_PROP);
        }
        assertFalse("exported file list must not be empty", isEmptyDir(exportDir));
    }

    private static boolean isSorted(SWTBotTable table, int col) {
        int rows = Math.min(table.rowCount(), 50);
        for (int i = 1; i < rows; i++) {
            if (table.cell(i - 1, col).compareToIgnoreCase(table.cell(i, col)) > 0) {
                return false;
            }
        }
        return rows > 1;
    }

    private static boolean isEmptyDir(Path dir) {
        String[] children = dir.toFile().list();
        return children == null || children.length == 0;
    }

    private static String menuLabel(String key) {
        return iped.rcp.core.i18n.Messages.getString(key);
    }

    private static IBookmarkService lookupBookmarkService() {
        BundleContext context = FrameworkUtil.getBundle(TriageFlowTest.class).getBundleContext();
        ServiceReference<IBookmarkService> ref = context.getServiceReference(IBookmarkService.class);
        return ref != null ? context.getService(ref) : null;
    }
}

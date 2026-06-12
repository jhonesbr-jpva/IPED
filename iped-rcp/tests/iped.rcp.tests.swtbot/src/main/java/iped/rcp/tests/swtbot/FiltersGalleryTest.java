package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.nebula.widgets.gallery.Gallery;
import org.eclipse.nebula.widgets.gallery.GalleryItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.results.IntResult;
import org.eclipse.swtbot.swt.finder.results.Result;
import org.eclipse.swtbot.swt.finder.results.VoidResult;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotCheckBox;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTable;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTree;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.junit.Test;

/**
 * SWTBot flow for US2 (task T024, FR-008/FR-009/FR-016, SC-004): filter
 * combination plus virtual gallery scrolling without UI freeze, inside the
 * real e4 product launched by the tycho-surefire UI harness (same harness as
 * {@link TriageFlowTest}; enable with {@code -DskipUiTests=false
 * -Dcase.dir=<reference-case>}).
 *
 * <p>
 * Contract of widget ids published by the US2 parts (this test is written
 * first — TDD):
 * <ul>
 * <li>{@code iped.rcp.views.gallery} — Nebula Gallery (virtual mode);</li>
 * <li>{@code iped.rcp.views.categories.tree} — category tree (labels carry
 * the legacy {@code name (count)} format);</li>
 * <li>{@code iped.rcp.views.filters.duplicates} — duplicates filter toggle
 * of the filters panel.</li>
 * </ul>
 */
public class FiltersGalleryTest {

    public static final String GALLERY_ID = "iped.rcp.views.gallery";
    public static final String CATEGORY_TREE_ID = "iped.rcp.views.categories.tree";
    public static final String DUPLICATES_TOGGLE_ID = "iped.rcp.views.filters.duplicates";

    /**
     * Tab labels of the parts involved (Application.e4xmi). e4 renders stack
     * parts lazily: each tab must be activated before its widgets exist.
     */
    private static final String GALLERY_TAB = "Gallery";
    private static final String CATEGORIES_TAB = "Categories";
    private static final String FILTERS_TAB = "Filters";
    private static final String RESULTS_TAB = "Results";

    private static final long SEARCH_TIMEOUT_MS = 120_000;

    /** SC-004 spirit: no UI-thread freeze longer than 1s while scrolling. */
    private static final long MAX_UI_FREEZE_MS = 1_000;

    /** Legacy category label: {@code Localized Name (1.234)}. */
    private static final Pattern CATEGORY_LABEL = Pattern.compile("^(.*) \\(([\\d.,\\u00A0\\u202F]+)\\)$");

    private final SWTBot bot = new SWTBot();

    /**
     * SWTBot only finds VISIBLE widgets: leave the Results tab selected for
     * whatever test runs next in the same workbench (TriageFlowTest looks the
     * table up fresh).
     */
    @org.junit.After
    public void restoreResultsTab() {
        try {
            bot.cTabItem(RESULTS_TAB).activate();
        } catch (RuntimeException e) {
            // workbench in a bad state: the test already failed elsewhere
        }
    }

    @Test
    public void filterCombinationAndGalleryScrolling() throws Exception {
        // 1. baseline: search everything. Empty text = match-all, the SAME
        // query base of the category tree counts (T015 learning: "*" parses
        // to a slightly different query than the empty text)
        bot.comboBoxWithId(TriageFlowTest.SEARCH_COMBO_ID).setText("");
        bot.buttonWithId(TriageFlowTest.SEARCH_BUTTON_ID).click();
        SWTBotTable table = bot.tableWithId(TriageFlowTest.RESULTS_TABLE_ID);
        int allItems = waitForRowCount(table, count -> count > 0, "search returned no rows");
        assertTrue("reference case must list items", allItems > 0);

        // 2. gallery mirrors the active result set (FR-008)
        bot.cTabItem(GALLERY_TAB).activate();
        Gallery gallery = findGallery();
        assertNotNull("gallery part must publish its widget id", gallery);
        waitForGalleryCount(gallery, allItems);

        // 3. scroll storm across the whole virtual range: the UI thread must
        // stay responsive (SC-004 — no freeze > 1s); thumb decoding is async
        int[] stops = { 0, allItems / 4, allItems / 2, (allItems * 3) / 4, allItems - 1 };
        for (int pass = 0; pass < 2; pass++) {
            for (int index : stops) {
                final int target = Math.max(0, index);
                long start = System.currentTimeMillis();
                UIThreadRunnable.syncExec((VoidResult) () -> {
                    GalleryItem group = gallery.getItem(0);
                    GalleryItem item = group.getItem(target);
                    if (item != null) {
                        gallery.showItem(item);
                    }
                });
                long elapsed = System.currentTimeMillis() - start;
                assertTrue("gallery scroll to index " + target + " blocked the UI thread for " + elapsed + "ms",
                        elapsed < MAX_UI_FREEZE_MS);
            }
        }
        // round-trip probe after the storm: UI thread free again within 1s
        long start = System.currentTimeMillis();
        UIThreadRunnable.syncExec((VoidResult) () -> {
        });
        assertTrue("UI thread still blocked after gallery scrolling",
                System.currentTimeMillis() - start < MAX_UI_FREEZE_MS);

        // 4. filter 1: category tree selection (FR-009) — table and gallery
        // shrink to the category count shown on the tree label
        bot.cTabItem(CATEGORIES_TAB).activate();
        SWTBotTree categories = bot.treeWithId(CATEGORY_TREE_ID);
        SWTBotTreeItem root = categories.getAllItems()[0];
        root.expand();
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return root.getItems().length > 0;
            }

            @Override
            public String getFailureMessage() {
                return "category tree root has no children (root label: " + root.getText() + ")";
            }
        }, SEARCH_TIMEOUT_MS);
        SWTBotTreeItem categoryNode = findFilterableCategory(root, allItems, 2);
        assertNotNull("no category with 0 < count < " + allItems + "; root=[" + root.getText() + "] tree: "
                + treeLabels(root, 2), categoryNode);
        int categoryCount = parseCategoryCount(categoryNode.getText());
        categoryNode.select();
        assertEquals("category filter must narrow the table to the category count", categoryCount,
                waitForRowCount(table, count -> count == categoryCount,
                        "table row count did not converge to the category count"));
        waitForGalleryCount(gallery, categoryCount);

        // 5. filter 2 combined: duplicates toggle (FR-016/FI-04) on top of the
        // category — never grows the result; gallery stays in sync
        bot.cTabItem(FILTERS_TAB).activate();
        SWTBotCheckBox duplicates = bot.checkBoxWithId(DUPLICATES_TOGGLE_ID);
        duplicates.click();
        int combined = waitForStableRowCount(table);
        assertTrue("combined filters must not grow the result (" + combined + " > " + categoryCount + ")",
                combined <= categoryCount);
        waitForGalleryCount(gallery, combined);

        // 6. clear: duplicates off, category back to root — full result again
        duplicates.click();
        waitForRowCount(table, count -> count == categoryCount, "removing duplicates filter did not restore counts");
        root.select();
        waitForRowCount(table, count -> count == allItems, "clearing the category filter did not restore the result");
        waitForGalleryCount(gallery, allItems);
    }

    private interface CountCondition {
        boolean test(int count);
    }

    private int waitForRowCount(SWTBotTable table, CountCondition condition, String failureMessage) {
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return condition.test(table.rowCount());
            }

            @Override
            public String getFailureMessage() {
                return failureMessage + " (rows=" + table.rowCount() + ")";
            }
        }, SEARCH_TIMEOUT_MS);
        return table.rowCount();
    }

    /** Waits until the row count stops changing (search job finished). */
    private int waitForStableRowCount(SWTBotTable table) {
        long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            int current = table.rowCount();
            if (current == last) {
                return current;
            }
            last = current;
            bot.sleep(1000);
        }
        return last;
    }

    private void waitForGalleryCount(Gallery gallery, int expected) {
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return galleryItemCount(gallery) == expected;
            }

            @Override
            public String getFailureMessage() {
                return "gallery count " + galleryItemCount(gallery) + " did not converge to " + expected;
            }
        }, SEARCH_TIMEOUT_MS);
    }

    private static int galleryItemCount(Gallery gallery) {
        return UIThreadRunnable.syncExec((IntResult) () -> {
            if (gallery.isDisposed() || gallery.getItemCount() == 0) {
                return -1;
            }
            return gallery.getItem(0).getItemCount();
        });
    }

    /**
     * Finds the gallery widget by its SWTBot id, walking the widget tree
     * (matcher-based lookup would need org.hamcrest in the test runtime).
     */
    private Gallery findGallery() {
        return UIThreadRunnable.syncExec((Result<Gallery>) () -> findGallery(bot.activeShell().widget));
    }

    private static Gallery findGallery(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Gallery && GALLERY_ID.equals(child.getData("org.eclipse.swtbot.widget.key"))) {
                return (Gallery) child;
            }
            if (child instanceof Composite composite) {
                Gallery nested = findGallery(composite);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * First category whose count narrows the result (0 &lt; count &lt;
     * total), searched to a BOUNDED depth (JFace materializes deeper levels
     * lazily through dummy items; unbounded recursion is unsafe here).
     */
    private SWTBotTreeItem findFilterableCategory(SWTBotTreeItem node, int total, int depth) {
        for (SWTBotTreeItem item : node.getItems()) {
            int count = parseCategoryCount(item.getText());
            if (count > 0 && count < total) {
                return item;
            }
            if (depth > 1 && !item.getText().isBlank()) {
                item.expand();
                SWTBotTreeItem nested = findFilterableCategory(item, total, depth - 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String treeLabels(SWTBotTreeItem node, int depth) {
        StringBuilder labels = new StringBuilder();
        for (SWTBotTreeItem item : node.getItems()) {
            labels.append('[').append(item.getText()).append("] ");
            if (depth > 1) {
                labels.append("{ ").append(treeLabels(item, depth - 1)).append("} ");
            }
        }
        return labels.toString();
    }

    private static int parseCategoryCount(String label) {
        Matcher matcher = CATEGORY_LABEL.matcher(label);
        if (!matcher.matches()) {
            return -1;
        }
        String digits = matcher.group(2).replaceAll("\\D", "");
        return digits.isEmpty() ? -1 : Integer.parseInt(digits);
    }
}

package iped.rcp.views;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.properties.BasicProps;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;
import iped.utils.LocalizedFormat;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Auxiliary tables part (task T019, FR-007): subitems, parent item,
 * duplicates and reference tables synchronized with the current selection.
 * The Lucene queries are exact ports of the legacy table models
 * ({@code SubitemTableModel}, {@code ParentTableModel},
 * {@code DuplicatesTableModel}, {@code ReferencingTableModel},
 * {@code ReferencedByTableModel}); searches run in a Job off the UI thread.
 *
 * <p>
 * Display is capped at {@value #MAX_ROWS} rows per table in this increment
 * (tab titles always show the real totals) — recorded in the parity
 * inventory.
 */
public class AuxTablesPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuxTablesPart.class);

    private static final int MAX_ROWS = 1000;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    @Inject
    private MPart part;

    private CTabFolder folder;
    private final List<AuxTab> tabs = new ArrayList<>();
    private final AtomicLong refreshStamp = new AtomicLong();

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        folder = new CTabFolder(parent, SWT.BORDER | SWT.BOTTOM);

        tabs.add(new AuxTab("SubitemTableModel.Subitens", this::subitemsQuery));
        tabs.add(new AuxTab("ParentTableModel.ParentCount", this::parentQuery));
        tabs.add(new AuxTab("DuplicatesTableModel.Duplicates", this::duplicatesQuery));
        tabs.add(new AuxTab("ReferencesTab.Title", this::referencingQuery));
        tabs.add(new AuxTab("ReferencedByTab.Title", this::referencedByQuery));

        for (AuxTab tab : tabs) {
            tab.create(folder);
        }
        folder.setSelection(0);
    }

    /** Selection sync: refresh all aux tables for the new active item. */
    @Inject
    @Optional
    public void onSelectionChanged(
            @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        if (folder == null || folder.isDisposed()) {
            return;
        }
        if (selection != null && part.getElementId().equals(selection.originPartId())) {
            return; // no echo
        }
        ItemId active = selection == null ? null : selection.activeItem();
        long stamp = refreshStamp.incrementAndGet();
        if (active == null) {
            for (AuxTab tab : tabs) {
                tab.show(stamp, "", List.of(), 0);
            }
            return;
        }
        Job job = Job.create("aux-tables-refresh", (IProgressMonitor monitor) -> {
            try {
                refresh(stamp, active);
                return Status.OK_STATUS;
            } catch (Exception e) {
                LOGGER.warn("Aux tables refresh failed", e);
                return Status.OK_STATUS; // aux views degrade silently
            }
        });
        job.setSystem(true);
        job.schedule();
    }

    private void refresh(long stamp, ItemId active) throws Exception {
        IPEDMultiSource source = source();
        int luceneId = source.getLuceneId(new iped.engine.data.ItemId(active.sourceId(), active.id()));
        Document doc = source.getReader().document(luceneId);
        for (AuxTab tab : tabs) {
            if (stamp != refreshStamp.get()) {
                return; // a newer selection superseded this refresh
            }
            Query query = tab.queryFactory.create(doc);
            if (query == null) {
                tab.show(stamp, "", List.of(), 0);
                continue;
            }
            IPEDSearcher searcher = new IPEDSearcher(source, query);
            MultiSearchResult result = searcher.multiSearch();
            List<String[]> rows = new ArrayList<>(Math.min(result.getLength(), MAX_ROWS));
            Set<String> fieldsToLoad = new HashSet<>(Set.of(BasicProps.NAME, BasicProps.PATH));
            for (int i = 0; i < result.getLength() && i < MAX_ROWS; i++) {
                IItemId itemId = result.getItem(i);
                Document rowDoc = source.getReader().document(source.getLuceneId(itemId), fieldsToLoad);
                rows.add(new String[] { LocalizedFormat.format(i + 1), rowDoc.get(BasicProps.NAME),
                        rowDoc.get(BasicProps.PATH) });
            }
            tab.show(stamp, LocalizedFormat.format(result.getLength()), rows, result.getLength());
        }
    }

    // ------------------------------------------------------------------
    // Query ports of the legacy aux table models — extracted to
    // RelatedItemsQueries (T046) so the check-with-related shortcuts share
    // the exact same queries
    // ------------------------------------------------------------------

    private Query subitemsQuery(Document doc) {
        return RelatedItemsQueries.subitems(source(), doc);
    }

    private Query parentQuery(Document doc) {
        return RelatedItemsQueries.parent(source(), doc);
    }

    private Query duplicatesQuery(Document doc) {
        return RelatedItemsQueries.duplicates(source(), doc);
    }

    private Query referencingQuery(Document doc) {
        return RelatedItemsQueries.referencing(source(), doc);
    }

    private Query referencedByQuery(Document doc) {
        return RelatedItemsQueries.referencedBy(source(), doc);
    }

    private IPEDMultiSource source() {
        return sessionManager.getSession().getSource();
    }

    /** One tab: localized title + query factory + result table. */
    private final class AuxTab {

        private final String titleKey;
        private final QueryFactory queryFactory;
        private CTabItem tabItem;
        private Table table;

        AuxTab(String titleKey, QueryFactory queryFactory) {
            this.titleKey = titleKey;
            this.queryFactory = queryFactory;
        }

        void create(CTabFolder parent) {
            tabItem = new CTabItem(parent, SWT.NONE);
            tabItem.setText(title(""));
            table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION);
            table.setHeaderVisible(true);
            TableColumn seq = new TableColumn(table, SWT.RIGHT);
            seq.setWidth(60);
            TableColumn name = new TableColumn(table, SWT.LEFT);
            name.setText(ResultColumns.labelOf(BasicProps.NAME));
            name.setWidth(250);
            TableColumn path = new TableColumn(table, SWT.LEFT);
            path.setText(ResultColumns.labelOf(BasicProps.PATH));
            path.setWidth(500);
            tabItem.setControl(table);
        }

        void show(long stamp, String countLabel, List<String[]> rows, int total) {
            uiSync.asyncExec(() -> {
                if (table.isDisposed() || stamp != refreshStamp.get()) {
                    return;
                }
                table.setRedraw(false);
                table.removeAll();
                for (String[] row : rows) {
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(row[0] == null ? "" : row[0]);
                    item.setText(1, row[1] == null ? "" : row[1]);
                    item.setText(2, row[2] == null ? "" : row[2]);
                }
                table.setRedraw(true);
                tabItem.setText(title(total > 0 ? countLabel : ""));
            });
        }

        private String title(String count) {
            String base = Messages.getString(titleKey).trim();
            return count.isEmpty() ? base : count + " " + base;
        }
    }

    @FunctionalInterface
    private interface QueryFactory {
        Query create(Document doc) throws Exception;
    }
}

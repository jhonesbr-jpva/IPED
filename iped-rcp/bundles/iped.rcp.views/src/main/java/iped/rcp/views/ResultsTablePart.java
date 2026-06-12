package iped.rcp.views;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.localization.CategoryLocalization;
import iped.properties.BasicProps;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.ICaseSessionManager;
import org.eclipse.jface.wizard.WizardDialog;

import iped.rcp.core.filters.FilterStateService;
import iped.rcp.views.bookmarks.BookmarkManagerDialog;
import iped.rcp.views.export.ExportActions;
import iped.rcp.views.report.ReportWizard;
import iped.rcp.views.similarity.SimilarityActions;
import iped.utils.LocalizedFormat;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Results table part (task T017, FR-007, research R12): SWT virtual table
 * over the active {@link ResultSet} ({@code MultiSearchResult} backing) with
 * lazy row fetch from the Lucene stored fields, engine-side sorting off the
 * UI thread, legacy checkbox semantics (checked state lives in the bookmarks
 * model) and selection publication through {@code ESelectionService}
 * (data-model {@code SelectionContext}).
 */
public class ResultsTablePart {

    /** SWTBot widget id (consumed by TriageFlowTest - T014). */
    public static final String TABLE_WIDGET_ID = "iped.rcp.views.results.table";

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultsTablePart.class);

    @Inject
    private SearchService searchService;

    @Inject
    private BookmarkService bookmarkService;

    @Inject
    private FilterStateService filterState;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ESelectionService selectionService;

    @Inject
    private org.eclipse.e4.ui.model.application.MApplication application;

    @Inject
    private UISynchronize uiSync;

    @Inject
    private MPart part;

    private Table table;
    private ResultColumns columns;
    private List<String> visibleFields = new ArrayList<>();

    private String sortField;
    private boolean sortAscending = true;
    private volatile long renderedGeneration = -1;

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        columns = new ResultColumns();

        table = new Table(parent, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.CHECK | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setData(SearchBarPart.SWTBOT_KEY, TABLE_WIDGET_ID);

        rebuildColumns();

        table.addListener(SWT.SetData, this::fillRow);
        table.addListener(SWT.Selection, this::onSelection);
        createContextMenu();

        ResultSet current = searchService.getCurrent();
        if (current != null) {
            onResultsChanged(current.generation());
        }
    }

    /** Rebuilds the column set (initial creation and columns dialog apply). */
    private void rebuildColumns() {
        for (TableColumn column : table.getColumns()) {
            column.dispose();
        }
        visibleFields = columns.getVisibleFields();

        // fixed first column: row number (the platform checkbox renders here)
        TableColumn rowColumn = new TableColumn(table, SWT.RIGHT);
        rowColumn.setText("");
        rowColumn.setWidth(70);

        for (String field : visibleFields) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(ResultColumns.labelOf(field));
            column.setWidth(columns.widthOf(field));
            column.setData(field);
            column.addListener(SWT.Selection, event -> sortBy(column));
        }
        table.clearAll();
    }

    private void fillRow(Event event) {
        TableItem item = (TableItem) event.item;
        int row = event.index;
        ResultSet current = searchService.getCurrent();
        if (current == null || row >= current.size()) {
            return;
        }
        IItemId itemId = current.result().getItem(row);
        item.setData(itemId);
        item.setText(0, LocalizedFormat.format(row + 1));
        item.setChecked(bookmarkService.isChecked(toApiId(itemId)));

        Document doc = loadDoc(itemId);
        for (int col = 0; col < visibleFields.size(); col++) {
            item.setText(col + 1, valueOf(current, row, itemId, doc, visibleFields.get(col)));
        }
    }

    private Document loadDoc(IItemId itemId) {
        try {
            IPEDMultiSource source = source();
            Set<String> fieldsToLoad = new HashSet<>(visibleFields);
            return source.getReader().document(source.getLuceneId(itemId), fieldsToLoad);
        } catch (IOException e) {
            LOGGER.warn("Error loading row values", e);
            return null;
        }
    }

    private String valueOf(ResultSet current, int row, IItemId itemId, Document doc, String field) {
        try {
            if (ResultColumns.SCORE.equals(field)) {
                return String.valueOf(current.result().getScore(row));
            }
            if (ResultColumns.BOOKMARK.equals(field)) {
                return String.join(" | ", bookmarkService.getBookmarksOf(toApiId(itemId)));
            }
            if (doc == null) {
                return "";
            }
            String[] values = doc.getValues(field);
            if (values.length == 0) {
                return "";
            }
            if (BasicProps.CATEGORY.equals(field)) {
                for (int i = 0; i < values.length; i++) {
                    values[i] = CategoryLocalization.getInstance().getLocalizedCategory(values[i]);
                }
            }
            if (BasicProps.LENGTH.equals(field) && values.length == 1 && !values[0].isEmpty()) {
                try {
                    return LocalizedFormat.format(Long.parseLong(values[0]));
                } catch (NumberFormatException e) {
                    return values[0];
                }
            }
            return String.join(" | ", values);
        } catch (RuntimeException e) {
            LOGGER.warn("Error rendering field {}", field, e);
            return Messages.getString("ResultTableModel.Error");
        }
    }

    private void onSelection(Event event) {
        if (event.detail == SWT.CHECK) {
            TableItem item = (TableItem) event.item;
            IItemId itemId = (IItemId) item.getData();
            if (itemId != null) {
                bookmarkService.setChecked(toApiId(itemId), item.getChecked());
            }
            return;
        }
        publishSelection();
    }

    private void publishSelection() {
        TableItem[] selection = table.getSelection();
        List<ItemId> selected = new ArrayList<>(selection.length);
        for (TableItem item : selection) {
            IItemId itemId = (IItemId) item.getData();
            if (itemId != null) {
                selected.add(toApiId(itemId));
            }
        }
        ItemId active = selected.isEmpty() ? null : selected.get(0);
        SelectionContext context = new SelectionContext(active, selected, part.getElementId());
        selectionService.setSelection(context);
        // deterministic mirror into the application context: the
        // UiEventsAddon path goes through the active-part selection
        // aggregator, which depends on real window focus (absent under the
        // SWTBot harness) - publishing parts set the shared key themselves
        application.getContext().set(UiEventTopics.SELECTION_KEY, context);
    }

    /** Engine-side sort off the UI thread (FR-007). */
    private void sortBy(TableColumn column) {
        String field = (String) column.getData();
        if (field == null || searchService.getCurrent() == null) {
            return;
        }
        boolean ascending = !field.equals(sortField) || !sortAscending;
        sortField = field;
        sortAscending = ascending;
        table.setSortColumn(column);
        table.setSortDirection(ascending ? SWT.UP : SWT.DOWN);

        Job job = Job.create(Messages.getString("ResultsTable.Sorting"), (IProgressMonitor monitor) -> {
            try {
                searchService.sortBy(field, ascending);
                return Status.OK_STATUS;
            } catch (RuntimeException e) {
                LOGGER.error("Sort by {} failed", field, e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    /**
     * Follows selections made on the bridged specialized views (map marker
     * click, timeline highlight - US3/T033, FR-012). Only selections with the
     * specialized-bridge origin are consumed: the table stays the selection
     * SOURCE for every other part (no echo loops, gallery sync unchanged).
     */
    @Inject
    @Optional
    public void onExternalSelection(
            @jakarta.inject.Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        if (table == null || table.isDisposed() || selection == null || selection.originPartId() == null
                || !selection.originPartId().startsWith("iped.rcp.specialized.")) {
            return;
        }
        ResultSet current = searchService.getCurrent();
        if (current == null) {
            return;
        }
        long generation = current.generation();
        List<ItemId> items = selection.selectedItems();
        Job job = Job.create("sync-specialized-selection", monitor -> {
            Set<Long> wanted = new HashSet<>(items.size() * 2);
            for (ItemId item : items) {
                wanted.add((((long) item.sourceId()) << 32) | (item.id() & 0xFFFFFFFFL));
            }
            int[] rows = new int[items.size()];
            int found = 0;
            for (int i = 0; i < current.size() && found < rows.length; i++) {
                IItemId itemId = current.result().getItem(i);
                if (wanted.contains((((long) itemId.getSourceId()) << 32) | (itemId.getId() & 0xFFFFFFFFL))) {
                    rows[found++] = i;
                }
            }
            int[] resolved = found == rows.length ? rows : java.util.Arrays.copyOf(rows, found);
            uiSync.asyncExec(() -> {
                ResultSet active = searchService.getCurrent();
                if (table.isDisposed() || active == null || active.generation() != generation) {
                    return; // result swapped while resolving
                }
                // programmatic selection: no SWT.Selection event, no republish
                table.deselectAll();
                if (resolved.length > 0) {
                    table.select(resolved);
                    table.showSelection();
                }
            });
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /** New active result (search, sort or near-live refresh - FR-029). */
    @Inject
    @Optional
    public void onResultsChanged(@UIEventTopic(UiEventTopics.RESULTS_CHANGED) Long generation) {
        if (table == null || table.isDisposed() || generation == null) {
            return;
        }
        ResultSet current = searchService.getCurrent();
        if (current == null || current.generation() == renderedGeneration) {
            return;
        }
        renderedGeneration = current.generation();
        uiSync.asyncExec(() -> {
            if (table.isDisposed()) {
                return;
            }
            table.setItemCount(current.size());
            table.clearAll();
        });
    }

    private void createContextMenu() {
        Menu menu = new Menu(table);

        MenuItem export = new MenuItem(menu, SWT.PUSH);
        export.setText(Messages.getString("MenuClass.ExportItens"));
        export.addListener(SWT.Selection,
                event -> ExportActions.exportSelectedItems(table.getShell(), selectedApiIds(), sessionManager));

        MenuItem exportProps = new MenuItem(menu, SWT.PUSH);
        exportProps.setText(Messages.getString("MenuClass.ExportProps.Highlighed"));
        exportProps.addListener(SWT.Selection, event -> ExportActions.exportSelectedProperties(table.getShell(),
                selectedApiIds(), visibleFields, sessionManager));

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem bookmarks = new MenuItem(menu, SWT.PUSH);
        bookmarks.setText(Messages.getString("MenuClass.ManageBookmarks"));
        bookmarks.addListener(SWT.Selection, event -> {
            new BookmarkManagerDialog(table.getShell(), bookmarkService, this::selectedApiIds).open();
        });

        MenuItem manageColumns = new MenuItem(menu, SWT.PUSH);
        manageColumns.setText(Messages.getString("MenuClass.ManageColumns"));
        manageColumns.addListener(SWT.Selection, event -> {
            ColumnsConfigDialog dialog = new ColumnsConfigDialog(table.getShell(), columns);
            if (dialog.open() == ColumnsConfigDialog.OK) {
                rebuildColumns();
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        // similarity searches over the highlighted item (T032, FR-013)
        MenuItem similarImages = new MenuItem(menu, SWT.PUSH);
        similarImages.setText(Messages.getString("MenuClass.FindSimilarImages"));
        similarImages.addListener(SWT.Selection, event -> SimilarityActions.searchSimilarImages(table.getShell(),
                firstSelectedApiId(), sessionManager, filterState, searchService, uiSync));

        MenuItem similarFaces = new MenuItem(menu, SWT.PUSH);
        similarFaces.setText(Messages.getString("MenuClass.FindSimilarFaces"));
        similarFaces.addListener(SWT.Selection, event -> SimilarityActions.searchSimilarFaces(table.getShell(),
                firstSelectedApiId(), sessionManager, filterState, searchService, uiSync));

        MenuItem similarDocs = new MenuItem(menu, SWT.PUSH);
        similarDocs.setText(Messages.getString("MenuClass.FindSimilarDocs"));
        similarDocs.addListener(SWT.Selection, event -> SimilarityActions.searchSimilarDocuments(table.getShell(),
                firstSelectedApiId(), sessionManager, filterState, searchService, uiSync));

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem report = new MenuItem(menu, SWT.PUSH);
        report.setText(Messages.getString("MenuClass.GenerateReport"));
        report.addListener(SWT.Selection, event -> {
            ReportWizard wizard = new ReportWizard(sessionManager, bookmarkService);
            new WizardDialog(table.getShell(), wizard).open();
        });

        table.setMenu(menu);
    }

    private ItemId firstSelectedApiId() {
        List<ItemId> selected = selectedApiIds();
        return selected.isEmpty() ? null : selected.get(0);
    }

    private List<ItemId> selectedApiIds() {
        List<ItemId> selected = new ArrayList<>();
        for (TableItem item : table.getSelection()) {
            IItemId itemId = (IItemId) item.getData();
            if (itemId != null) {
                selected.add(toApiId(itemId));
            }
        }
        return selected;
    }

    private IPEDMultiSource source() {
        return sessionManager.getSession().getSource();
    }

    private static ItemId toApiId(IItemId itemId) {
        return new ItemId(itemId.getSourceId(), itemId.getId());
    }
}

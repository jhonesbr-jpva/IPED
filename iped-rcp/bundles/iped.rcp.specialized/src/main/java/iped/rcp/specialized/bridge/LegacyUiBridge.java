package iped.rcp.specialized.bridge;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.ICaseSessionManager;
import iped.search.IMultiSearchResult;

/**
 * Shared plumbing of the bridged specialized views (tasks T033-T036,
 * FR-012): ONE hidden mirror {@link JTable} reproduces the
 * shared-results-table discipline of the legacy Swing App - the legacy map,
 * graph and timeline all observe (and write) the same table model and
 * selection model, exactly like they did against {@code App.resultsTable}.
 *
 * <p>
 * Selection synchronization is bidirectional:
 * <ul>
 * <li><b>workbench → views</b>: parts forward {@link SelectionContext}
 * changes here; rows are resolved off the UI threads and applied to the
 * mirror selection model on the EDT (guarded against echo);</li>
 * <li><b>views → workbench</b>: legacy interactions (map marker click,
 * timeline highlight worker) land on the mirror selection model; the bridge
 * publishes them through {@code ESelectionService} with
 * {@link #ORIGIN_ID} as origin - the results table consumes that origin and
 * follows (FR-012).</li>
 * </ul>
 *
 * <p>
 * Threading: SWT thread for e4 services, EDT for everything Swing, plus a
 * single daemon resolver thread for row scans (coalesces bursts; selection
 * over a 500k-item result resolves in milliseconds).
 */
public final class LegacyUiBridge {

    /** Origin id of selections published by the bridged views. */
    public static final String ORIGIN_ID = "iped.rcp.specialized.bridge";

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyUiBridge.class);

    private static volatile LegacyUiBridge instance;

    /** Marker for "clear the selection" in the coalescing slot. */
    private static final SelectionContext CLEAR = new SelectionContext(null, List.of(), ORIGIN_ID);

    private final Display display;
    private final SearchService searchService;
    private final ICaseSessionManager sessionManager;
    private final ESelectionService selectionService;
    private final IEclipseContext applicationContext;

    /** Built on the EDT during install (Swing single-thread rule). */
    private RcpLegacyProviders providers;
    private MirrorResultsModel model;
    private JTable mirrorTable;

    private final ExecutorService selectionResolver = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "specialized-selection-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<SelectionContext> pendingSelection = new AtomicReference<>();

    private volatile long lastGeneration = Long.MIN_VALUE;
    /** EDT-confined echo guard while applying a workbench selection. */
    private boolean syncingFromWorkbench;

    // diagnostic counters surfaced by probeDiagnostics() (T033 test support)
    private volatile int receivedSelections;
    private volatile String lastReceivedOrigin;
    private volatile int lastResolvedRows = -1;
    private volatile int appliedSelections;
    private volatile int publishedSelections;

    private LegacyUiBridge(Display display, SearchService searchService, ICaseSessionManager sessionManager,
            ESelectionService selectionService, IEclipseContext applicationContext) {
        this.display = display;
        this.searchService = searchService;
        this.sessionManager = sessionManager;
        this.selectionService = selectionService;
        this.applicationContext = applicationContext;
    }

    /**
     * Installs the singleton bridge (idempotent). Called from part
     * {@code @PostConstruct} on the SWT thread; the Swing side is built
     * synchronously on the EDT (one-time bounded wait) so parts can hand the
     * mirror table to the legacy views right away.
     */
    public static synchronized LegacyUiBridge install(Display display, SearchService searchService,
            BookmarkService bookmarkService, ICaseSessionManager sessionManager, ESelectionService selectionService,
            FilterStateService filterState, IEclipseContext applicationContext) {
        if (instance == null) {
            LegacyUiBridge bridge = new LegacyUiBridge(display, searchService, sessionManager, selectionService,
                    applicationContext);
            try {
                SwingUtilities.invokeAndWait(() -> bridge.initOnEdt(bookmarkService, filterState));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing the legacy UI bridge", e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Error initializing the legacy UI bridge", e.getCause());
            }
            instance = bridge;
        }
        return instance;
    }

    private void initOnEdt(BookmarkService bookmarkService, FilterStateService filterState) {
        model = new MirrorResultsModel(searchService, bookmarkService);
        mirrorTable = new JTable(model);
        providers = new RcpLegacyProviders(searchService, sessionManager, filterState, mirrorTable);
        // MapViewer.valueChanged requires a non-null row sorter on the
        // results table (legacy guard); no sort keys = identity mapping
        mirrorTable.setAutoCreateRowSorter(true);
        mirrorTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        mirrorTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || syncingFromWorkbench) {
                return;
            }
            publishMirrorSelection();
        });
    }

    /** The hidden table the legacy views are initialized with. EDT use. */
    public JTable getMirrorTable() {
        return mirrorTable;
    }

    public RcpLegacyProviders getProviders() {
        return providers;
    }

    /**
     * New active result set: refresh the mirror model so the legacy views
     * recompute (map reload, timeline dataset rebuild). Deduped by
     * generation - all specialized parts forward the same event.
     */
    public void onResultsChanged(long generation) {
        if (generation == lastGeneration) {
            return;
        }
        lastGeneration = generation;
        SwingUtilities.invokeLater(this::fireModelChangedOnEdt);
    }

    /**
     * Replays the current result set into the mirror model. Parts call this
     * right after creating their legacy content: the part is rendered lazily,
     * so the active result may predate the legacy view's listeners.
     */
    public void refreshMirror() {
        SwingUtilities.invokeLater(this::fireModelChangedOnEdt);
    }

    /** Case closed: empty model, cleared selection (FR-001 lifecycle). */
    public void caseClosed() {
        lastGeneration = Long.MIN_VALUE;
        SwingUtilities.invokeLater(this::fireModelChangedOnEdt);
    }

    /**
     * EDT. JTable clears its selection synchronously inside a full data
     * change; the echo guard keeps that implicit clear from being published
     * as a (wrong) empty workbench selection.
     */
    private void fireModelChangedOnEdt() {
        syncingFromWorkbench = true;
        try {
            model.fireTableDataChanged();
        } finally {
            syncingFromWorkbench = false;
        }
    }

    /**
     * Workbench selection (results table, gallery, ...) applied to the
     * mirror table. Selections originated by the bridged views themselves
     * are ignored (echo); bursts are coalesced to the latest.
     */
    public void applyWorkbenchSelection(SelectionContext selection) {
        if (selection != null && ORIGIN_ID.equals(selection.originPartId())) {
            return;
        }
        LOGGER.debug("Workbench selection received: origin={}, items={}",
                selection == null ? null : selection.originPartId(),
                selection == null ? 0 : selection.selectedItems().size());
        receivedSelections++;
        lastReceivedOrigin = selection == null ? "<null>" : selection.originPartId();
        pendingSelection.set(selection == null ? CLEAR : selection);
        selectionResolver.execute(this::drainPendingSelection);
    }

    private void drainPendingSelection() {
        SelectionContext selection = pendingSelection.getAndSet(null);
        if (selection == null) {
            return; // already drained by a previous task
        }
        int[] rows = resolveRows(selection.selectedItems());
        LOGGER.debug("Applying workbench selection to the mirror: {} row(s)", rows.length);
        lastResolvedRows = rows.length;
        SwingUtilities.invokeLater(() -> {
            appliedSelections++;
            syncingFromWorkbench = true;
            try {
                ListSelectionModel selectionModel = mirrorTable.getSelectionModel();
                selectionModel.setValueIsAdjusting(true);
                mirrorTable.clearSelection();
                int rangeStart = -1, previous = -2;
                for (int row : rows) {
                    if (row != previous + 1) {
                        if (rangeStart >= 0) {
                            selectionModel.addSelectionInterval(rangeStart, previous);
                        }
                        rangeStart = row;
                    }
                    previous = row;
                }
                if (rangeStart >= 0) {
                    selectionModel.addSelectionInterval(rangeStart, previous);
                }
                selectionModel.setValueIsAdjusting(false);
            } finally {
                syncingFromWorkbench = false;
            }
        });
    }

    /** Sorted row indices of the given items in the active result set. */
    private int[] resolveRows(List<ItemId> items) {
        ResultSet current = searchService.getCurrent();
        if (current == null || items.isEmpty()) {
            return new int[0];
        }
        Set<Long> wanted = new HashSet<>(items.size() * 2);
        for (ItemId item : items) {
            wanted.add(key(item.sourceId(), item.id()));
        }
        IMultiSearchResult result = current.result();
        int[] rows = new int[items.size()];
        int found = 0;
        for (int i = 0; i < result.getLength() && found < rows.length; i++) {
            IItemId item = result.getItem(i);
            if (wanted.contains(key(item.getSourceId(), item.getId()))) {
                rows[found++] = i;
            }
        }
        if (found < rows.length) {
            int[] trimmed = new int[found];
            System.arraycopy(rows, 0, trimmed, 0, found);
            return trimmed;
        }
        return rows;
    }

    /** EDT: a legacy view changed the mirror selection - publish it. */
    private void publishMirrorSelection() {
        ResultSet current = searchService.getCurrent();
        if (current == null || sessionManager.getSession() == null) {
            return;
        }
        IMultiSearchResult result = current.result();
        int[] viewRows = mirrorTable.getSelectedRows();
        int leadView = mirrorTable.getSelectionModel().getLeadSelectionIndex();
        List<ItemId> items = new ArrayList<>(viewRows.length);
        ItemId active = null;
        for (int viewRow : viewRows) {
            int row = mirrorTable.convertRowIndexToModel(viewRow);
            if (row < 0 || row >= result.getLength()) {
                continue;
            }
            IItemId itemId = result.getItem(row);
            ItemId api = new ItemId(itemId.getSourceId(), itemId.getId());
            items.add(api);
            if (viewRow == leadView) {
                active = api;
            }
        }
        if (active == null && !items.isEmpty()) {
            active = items.get(0);
        }
        SelectionContext context = new SelectionContext(active, items, ORIGIN_ID);
        publishedSelections++;
        display.asyncExec(() -> {
            if (!display.isDisposed()) {
                selectionService.setSelection(context);
                // deterministic mirror (see ResultsTablePart.publishSelection)
                applicationContext.set(UiEventTopics.SELECTION_KEY, context);
            }
        });
    }

    private static long key(int sourceId, int id) {
        return (((long) sourceId) << 32) | (id & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------
    // test probes (SWTBot cannot drive the Swing/JS surfaces - T033)
    // ------------------------------------------------------------------

    /** @return whether a specialized part already installed the bridge */
    public static boolean isInstalled() {
        return instance != null;
    }

    /** One-line state snapshot for test failure messages (T033). */
    public static String probeDiagnostics() {
        LegacyUiBridge bridge = instance;
        if (bridge == null) {
            return "bridge not installed";
        }
        return "received=" + bridge.receivedSelections + " lastOrigin=" + bridge.lastReceivedOrigin
                + " lastResolvedRows=" + bridge.lastResolvedRows + " applied=" + bridge.appliedSelections
                + " published=" + bridge.publishedSelections + " mirrorRows=" + bridge.model.getRowCount();
    }

    /** EDT-safe snapshot of the mirror selection (model indices, sorted). */
    public static int[] probeSelectedRows() throws InterruptedException, InvocationTargetException {
        LegacyUiBridge bridge = instance;
        if (bridge == null) {
            return new int[0];
        }
        int[][] holder = new int[1][];
        SwingUtilities.invokeAndWait(() -> holder[0] = bridge.mirrorTable.getSelectedRows());
        return holder[0];
    }

    /**
     * EDT-safe selection of mirror rows, NOT guarded against publication -
     * simulates exactly what a map marker click or a timeline highlight
     * worker does ({@code JTable.addRowSelectionInterval}).
     */
    public static void probeSelectRows(int[] rows) throws InterruptedException, InvocationTargetException {
        LegacyUiBridge bridge = instance;
        if (bridge == null) {
            throw new IllegalStateException("Bridge not installed");
        }
        SwingUtilities.invokeAndWait(() -> {
            bridge.mirrorTable.clearSelection();
            for (int row : rows) {
                bridge.mirrorTable.addRowSelectionInterval(row, row);
            }
        });
    }
}

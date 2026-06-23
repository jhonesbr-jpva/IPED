package iped.rcp.viewers.part;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.items.ItemAccessService;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Hits list part (legacy "Ocorrências" / {@code App.Hits} dock): a native SWT
 * table of every occurrence of the current query terms in the <b>extracted
 * text</b> of the selected item (number + snippet), clickable to bring the Text
 * viewer forward and navigate to that occurrence (FR-011, parity VW-01).
 *
 * <p>
 * It extracts/matches independently of the Text viewer (so it works regardless
 * of which viewer tab is active), but shares {@link TextExtraction} and
 * {@link TextHitFinder} with {@link RcpTextViewer} so the match offsets are
 * identical. Navigation is a one-way command on the application context
 * ({@link TextHitGoto}) consumed by {@link TextViewerPart}; there is no hit list
 * without an active search (the table stays empty until a query is run).
 */
public class HitsViewerPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(HitsViewerPart.class);

    private static final int SNIPPET_MAX_LEN = 300;
    private static final String TEXT_PART_ID = "iped.rcp.viewers.part.text"; //$NON-NLS-1$

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ItemAccessService itemAccess;

    @Inject
    private SearchService searchService;

    @Inject
    private MApplication application;

    @Inject
    private EPartService partService;

    @Inject
    private UISynchronize uiSync;

    private Table table;
    private final AtomicLong stamp = new AtomicLong();
    /** Match offsets parallel to the table rows, for navigation. */
    private final List<Integer> hitOffsets = new ArrayList<>();
    private long gotoNonce;

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION);
        table.setHeaderVisible(false);
        TableColumn seq = new TableColumn(table, SWT.RIGHT);
        seq.setWidth(50);
        TableColumn content = new TableColumn(table, SWT.LEFT);
        content.setWidth(2000);
        table.addListener(SWT.Selection, e -> onHitActivated());
    }

    /** Selection sync: rebuild the hits list for the new active item. */
    @Inject
    @Optional
    public void onSelectionChanged(
            @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        if (table == null || table.isDisposed()) {
            return;
        }
        long current = stamp.incrementAndGet();
        ItemId active = selection == null ? null : selection.activeItem();
        Set<String> terms = active == null ? Set.of() : safeTerms();
        if (active == null || terms.isEmpty()) {
            show(current, List.of(), List.of());
            return;
        }
        Job job = Job.create("hits-list-refresh", (IProgressMonitor monitor) -> {
            try {
                refresh(current, active, terms);
            } catch (Exception e) {
                LOGGER.warn("Hits list refresh failed", e);
                show(current, List.of(), List.of());
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void refresh(long current, ItemId active, Set<String> terms) throws Exception {
        IItem item = itemAccess.resolve(active);
        if (item == null || current != stamp.get()) {
            return;
        }
        IPEDSource source = resolveSource(active.sourceId());
        String text = TextExtraction.extract(item, source, TextExtraction.MAX_CHARS);
        if (current != stamp.get()) {
            return;
        }
        List<int[]> matches = TextHitFinder.findMatches(text, terms, TextHitFinder.MAX_MATCHES,
                TextHitFinder.MIN_TERM_LEN);
        List<String> snippets = new ArrayList<>(matches.size());
        List<Integer> offsets = new ArrayList<>(matches.size());
        for (int[] m : matches) {
            snippets.add(TextHitFinder.snippet(text, m[0], m[1], SNIPPET_MAX_LEN));
            offsets.add(m[0]);
        }
        show(current, snippets, offsets);
    }

    private void show(long current, List<String> snippets, List<Integer> offsets) {
        uiSync.asyncExec(() -> {
            if (table.isDisposed() || current != stamp.get()) {
                return;
            }
            table.setRedraw(false);
            table.removeAll();
            hitOffsets.clear();
            for (int i = 0; i < snippets.size(); i++) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(0, Integer.toString(i + 1));
                item.setText(1, snippets.get(i));
                hitOffsets.add(offsets.get(i));
            }
            table.setRedraw(true);
        });
    }

    /** Brings the Text viewer forward and navigates it to the clicked hit. */
    private void onHitActivated() {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= hitOffsets.size()) {
            return;
        }
        int offset = hitOffsets.get(index);
        MPart textPart = partService.findPart(TEXT_PART_ID);
        if (textPart != null) {
            partService.activate(textPart);
        }
        application.getContext().set(TextHitGoto.KEY, new TextHitGoto(offset, ++gotoNonce));
    }

    private Set<String> safeTerms() {
        try {
            return searchService == null ? Set.of() : searchService.getHighlightTerms();
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private IPEDSource resolveSource(int sourceId) {
        CaseSession session = sessionManager == null ? null : sessionManager.getSession();
        if (session == null) {
            return null;
        }
        IPEDMultiSource source = session.getSource();
        return source == null ? null : source.getAtomicSourceBySourceId(sourceId);
    }
}

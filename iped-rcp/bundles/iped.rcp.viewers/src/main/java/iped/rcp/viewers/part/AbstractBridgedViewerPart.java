package iped.rcp.viewers.part;

import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.IPartListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.items.ItemAccessService;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Base of the content-viewer parts (FR-011): each viewer (preview, text,
 * metadata, hex) is its OWN e4 part — a sibling top-level tab — instead of a
 * Swing sub-tab inside a single combined part. This restores the legacy
 * {@code ViewerController} layout, where Hex, Text, Metadata and Preview were
 * four independent dockables, and isolates every viewer behind its own
 * {@link SwtAwtBridgeHost}, so a later, per-part migration from the bridged
 * Swing/JavaFX viewer to a native SWT one is a local change (the bridges are
 * provisional).
 *
 * <p>
 * This base owns the shared plumbing:
 * <ul>
 * <li>one {@link SwtAwtBridgeHost} per part (created once, never reparented);</li>
 * <li>selection subscription ({@link UiEventTopics#SELECTION_KEY}) with stale-load
 * guarding;</li>
 * <li><b>visibility-gated lazy rendering</b> — the heavy viewer load only runs
 * when the part is the visible tab (legacy {@code ViewerController} discipline:
 * only the showing dock loaded), deferred via an {@link IPartListener} otherwise;</li>
 * <li>EDT marshalling and disposal.</li>
 * </ul>
 * Subclasses provide the concrete viewer through the {@code *Viewer} hooks.
 */
public abstract class AbstractBridgedViewerPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBridgedViewerPart.class);

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ItemAccessService itemAccess;

    /** Source of the query highlight terms (legacy {@code App.getHighlightTerms()}). */
    @Inject
    private SearchService searchService;

    @Inject
    private MPart part;

    @Inject
    private EPartService partService;

    private SwtAwtBridgeHost bridge;
    private IPartListener partListener;

    /** Bumped on every selection; renders carrying a stale stamp are dropped. */
    private final AtomicLong loadStamp = new AtomicLong();
    /** Stamp already pushed into the viewer (skip re-render of the same item). */
    private volatile long renderedStamp = -1;
    private volatile boolean viewerReady;
    /** Visible-tab flag; only written on the UI thread (see {@link #isVisible()}). */
    private volatile boolean partVisible;

    private volatile IItem currentItem;
    private volatile IPEDSource currentSource;
    private volatile String currentContentType;
    private volatile Set<String> currentHighlightTerms;
    /**
     * Latest selection seen, kept even before the viewer exists: the e4 DI calls
     * {@link #onSelectionChanged} once during injection — before {@code @PostConstruct}
     * builds the bridge — so an item selected before this part is first opened
     * (lazy part creation) would otherwise be dropped. Replayed once the viewer
     * is ready.
     */
    private volatile SelectionContext pendingSelection;

    @PostConstruct
    public final void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        // Optional per-part SWT toolbar (e.g. preview hit navigation) above the
        // bridge; the default adds nothing.
        createToolbar(parent);

        Composite bridgeArea = new Composite(parent, SWT.NONE);
        bridgeArea.setLayout(new FillLayout());
        bridgeArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        bridge = new SwtAwtBridgeHost(bridgeArea);

        if (partService != null) {
            // Seed the visibility flag on the UI thread (this hook runs on it);
            // kept current afterwards by the listener (off-UI reads use the flag).
            try {
                partVisible = partService.isPartVisible(part);
            } catch (RuntimeException e) {
                partVisible = true; // be permissive rather than stuck blank
            }
            partListener = new VisibilityListener();
            partService.addPartListener(partListener);
        } else {
            partVisible = true;
        }

        // Build the Swing viewer on the EDT, attach it to the bridge, then run
        // its heavy init off both UI threads (legacy ViewerController discipline).
        SwingUtilities.invokeLater(() -> {
            JComponent content;
            try {
                content = buildViewer(bridge);
            } catch (RuntimeException e) {
                LOGGER.error("Error building viewer for part {}", elementId(), e);
                return;
            }
            bridge.setContent(content);
            Thread initThread = new Thread(() -> {
                try {
                    initViewer();
                } catch (RuntimeException e) {
                    LOGGER.error("Error initializing viewer for part {}", elementId(), e);
                }
                viewerReady = true;
                // Replay the latest selection (possibly received before the
                // viewer existed) now that it can render — gated on visibility.
                processSelection(pendingSelection);
            }, "viewer-init-" + elementId());
            initThread.setDaemon(true);
            initThread.start();
        });
    }

    /** Selection sync (FR-011): the active item drives this part's viewer. */
    @Inject
    @Optional
    public void onSelectionChanged(
            @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        pendingSelection = selection;
        if (bridge == null) {
            return; // replayed by createPartControl once the viewer is built
        }
        processSelection(selection);
    }

    private void processSelection(SelectionContext selection) {
        if (selection != null && part.getElementId().equals(selection.originPartId())) {
            return; // no echo
        }
        long stamp = loadStamp.incrementAndGet();
        if (selection == null || selection.activeItem() == null) {
            currentItem = null;
            currentSource = null;
            currentContentType = null;
            currentHighlightTerms = null;
            maybeRender(stamp);
            return;
        }
        // Resolving the item/source/type is cheap and is done for every part, but
        // the heavy viewer load (parse/decode/hex) is gated on visibility below.
        Job job = Job.create("resolve-viewer-selection", (IProgressMonitor monitor) -> {
            try {
                IItem item = itemAccess.resolve(selection.activeItem());
                if (item == null || stamp != loadStamp.get()) {
                    return Status.OK_STATUS;
                }
                String contentType = item.getMediaType() == null ? null : item.getMediaType().toString();
                IPEDSource source = resolveSource(selection.activeItem().sourceId());
                if (stamp != loadStamp.get()) {
                    return Status.OK_STATUS;
                }
                currentItem = item;
                currentSource = source;
                currentContentType = contentType;
                // Query highlight terms are the same for the whole result set;
                // derive them off the UI thread here (parses the query).
                currentHighlightTerms = searchService == null ? null : searchService.getHighlightTerms();
                maybeRender(stamp);
            } catch (RuntimeException e) {
                LOGGER.warn("Error resolving selection for viewer part {}", elementId(), e);
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /** Renders only when the viewer is ready AND this part is the visible tab. */
    private void maybeRender(long stamp) {
        if (isVisible()) {
            renderNow(stamp);
        }
    }

    /**
     * Pushes the current item into the viewer on the EDT, unless the viewer is
     * not ready yet, the stamp is stale, or the same stamp was already rendered.
     */
    private void renderNow(long stamp) {
        if (!viewerReady || stamp != loadStamp.get() || renderedStamp == stamp) {
            return;
        }
        renderedStamp = stamp;
        IItem item = currentItem;
        IPEDSource source = currentSource;
        String contentType = currentContentType;
        Set<String> highlightTerms = currentHighlightTerms;
        SwingUtilities.invokeLater(() -> {
            if (stamp != loadStamp.get()) {
                return;
            }
            try {
                if (item == null) {
                    clearViewer();
                } else {
                    loadIntoViewer(item, source, contentType, highlightTerms);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Error rendering item in viewer part {}", elementId(), e);
            }
        });
    }

    /**
     * Whether this part is the visible tab. Read from a volatile flag (not from
     * the e4 model) because {@link #maybeRender} runs off the UI thread (resolve
     * Job / init thread) and {@link EPartService#isPartVisible} reads the model,
     * which must only be touched on the UI thread. The flag is seeded on the UI
     * thread in {@code @PostConstruct} and kept current by the part listener.
     */
    private boolean isVisible() {
        return partVisible;
    }

    private IPEDSource resolveSource(int sourceId) {
        CaseSession session = sessionManager == null ? null : sessionManager.getSession();
        if (session == null) {
            return null;
        }
        IPEDMultiSource source = session.getSource();
        return source == null ? null : source.getAtomicSourceBySourceId(sourceId);
    }

    @PreDestroy
    public final void dispose() {
        if (partListener != null && partService != null) {
            try {
                partService.removePartListener(partListener);
            } catch (RuntimeException ignore) {
                // service already gone on shutdown
            }
        }
        loadStamp.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            try {
                disposeViewer();
            } catch (RuntimeException e) {
                LOGGER.warn("Error disposing viewer part {}", elementId(), e);
            }
        });
    }

    protected final String elementId() {
        return part == null ? getClass().getSimpleName() : part.getElementId();
    }

    // ------------------------------------------------------------------
    // Subclass hooks

    /**
     * Optional SWT toolbar above the bridge (created on the SWT UI thread).
     * Default: none. Implementations must set their own layout data.
     */
    protected void createToolbar(Composite parent) {
        // no toolbar by default
    }

    /**
     * Builds the Swing viewer and returns the component to host in the bridge.
     * Runs on the EDT. Implementations keep their own reference to the viewer
     * for {@link #initViewer()}/{@link #loadIntoViewer}/{@link #disposeViewer()}.
     */
    protected abstract JComponent buildViewer(SwtAwtBridgeHost bridge);

    /** Heavy viewer initialization. Runs off both UI threads. */
    protected abstract void initViewer();

    /**
     * Loads the item into the viewer. Runs on the EDT.
     *
     * @param highlightTerms query terms to highlight (may be {@code null}/empty)
     */
    protected abstract void loadIntoViewer(IItem item, IPEDSource source, String contentType,
            Set<String> highlightTerms);

    /** Clears the viewer (no selection). Runs on the EDT. */
    protected abstract void clearViewer();

    /** Releases the viewer's resources. Runs on the EDT. */
    protected abstract void disposeViewer();

    // ------------------------------------------------------------------

    /**
     * Renders the pending selection as soon as this part becomes the visible
     * tab — restoring the legacy "load only the showing viewer" laziness.
     */
    private final class VisibilityListener implements IPartListener {
        @Override
        public void partVisible(MPart p) {
            if (p == part) {
                partVisible = true;
                renderNow(loadStamp.get());
            }
        }

        @Override
        public void partBroughtToTop(MPart p) {
            if (p == part) {
                partVisible = true;
                renderNow(loadStamp.get());
            }
        }

        @Override
        public void partActivated(MPart p) {
            // no-op
        }

        @Override
        public void partDeactivated(MPart p) {
            // no-op
        }

        @Override
        public void partHidden(MPart p) {
            if (p == part) {
                partVisible = false;
            }
        }
    }
}

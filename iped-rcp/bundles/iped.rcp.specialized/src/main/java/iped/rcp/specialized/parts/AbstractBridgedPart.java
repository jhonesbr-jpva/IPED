package iped.rcp.specialized.parts;

import javax.swing.SwingUtilities;

import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.specialized.bridge.LegacyApp;
import iped.rcp.specialized.bridge.LegacyUiBridge;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Common skeleton of the bridged specialized parts (tasks T034-T036,
 * research R4): one {@link SwtAwtBridgeHost} per part, the shared
 * {@link LegacyUiBridge} plumbing, and forwarding of the workbench events
 * the bridge needs (results generation, selection, case lifecycle). The
 * legacy Swing content is created on the EDT and NEVER rewritten (FR-028);
 * construction failures degrade to an empty host with a logged error so the
 * workbench never breaks on exotic environments (e.g. JavaFX missing).
 */
public abstract class AbstractBridgedPart {

    /** SWTBot widget-id data key (same one the harness looks up). */
    protected static final String SWTBOT_KEY = "org.eclipse.swtbot.widget.key";

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBridgedPart.class);

    @Inject
    protected SearchService searchService;

    @Inject
    protected BookmarkService bookmarkService;

    @Inject
    protected ICaseSessionManager sessionManager;

    @Inject
    protected ESelectionService selectionService;

    @Inject
    protected org.eclipse.e4.ui.model.application.MApplication application;

    @Inject
    @Optional
    protected FilterStateService filterState;

    protected SwtAwtBridgeHost bridgeHost;
    protected LegacyUiBridge bridge;

    /** Last selection seen before the bridge existed (replayed on install). */
    private SelectionContext lastSeenSelection;

    /** Guards the one-time construction of the bridged legacy content. */
    private volatile boolean legacyContentBuilt;

    @PostConstruct
    public final void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        Composite host = new Composite(parent, SWT.NONE);
        host.setLayout(new FillLayout());
        host.setData(SWTBOT_KEY, hostWidgetId());

        bridge = LegacyUiBridge.install(parent.getDisplay(), searchService, bookmarkService, sessionManager,
                selectionService, filterState, application.getContext());
        // field/method injection ran before @PostConstruct: replay a
        // selection that predates the bridge
        if (lastSeenSelection != null) {
            bridge.applyWorkbenchSelection(lastSeenSelection);
        }
        bridgeHost = new SwtAwtBridgeHost(host);

        // Build the legacy content as soon as a READY case session exists. The
        // part can be created before a case is open (it was the active tab at
        // startup) or while the case is still opening - getSession() returns
        // null until READY and e4 never re-creates the part, so building only
        // here would leave the part blank forever. onCaseOpened() retries when
        // the session reaches READY. (The embedded frame is created above, but
        // SwtAwtBridgeHost.setContent forces a repaint so content attached
        // after the frame was first shown still paints - see that method.)
        buildLegacyContentIfReady();
    }

    /**
     * Builds the bridged Swing/JavaFX content once, as soon as a READY case
     * session is available. Safe to call repeatedly (from {@code @PostConstruct}
     * and from the case lifecycle handlers); only the first call that finds a
     * non-null session actually builds. Runs on the SWT UI thread; the Swing
     * construction is marshalled to the EDT.
     */
    private void buildLegacyContentIfReady() {
        if (legacyContentBuilt || bridgeHost == null) {
            return;
        }
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            LOGGER.debug("{} deferred: case session not READY yet", getClass().getSimpleName());
            return;
        }
        legacyContentBuilt = true;
        SwingUtilities.invokeLater(() -> {
            try {
                // must precede any bridged view class load (CachePersistance
                // reads App.appCase from a static initializer - T036)
                LegacyApp.bind(session);
                createLegacyContent();
            } catch (Throwable e) {
                legacyContentBuilt = false; // allow a retry on a later event
                LOGGER.error("Error creating bridged content of {}", getClass().getSimpleName(), e);
            }
        });
    }

    /** Stable id of the host composite (T033 widget-id contract). */
    protected abstract String hostWidgetId();

    /** Builds the legacy Swing content. Runs on the EDT. */
    protected abstract void createLegacyContent() throws Exception;

    /** Forwards the active-result generation to the bridge (deduped). */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onResultsChanged(@UIEventTopic(UiEventTopics.RESULTS_CHANGED) Long generation) {
        if (bridge != null && generation != null) {
            bridge.onResultsChanged(generation);
        }
    }

    /** Forwards workbench selection changes to the mirror table. */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onSelectionChanged(
            @jakarta.inject.Named(UiEventTopics.SELECTION_KEY) @org.eclipse.e4.core.di.annotations.Optional SelectionContext selection) {
        lastSeenSelection = selection;
        if (bridge != null) {
            bridge.applyWorkbenchSelection(selection);
        }
    }

    /**
     * Case became READY - possibly after this part was already created during
     * the case opening (slow load), when getSession() still returned null.
     * Build the deferred bridged content now.
     */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        buildLegacyContentIfReady();
    }

    /** Case lifecycle: empty the mirror state on close. */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        if (bridge != null) {
            bridge.caseClosed();
        }
    }
}

package iped.rcp.viewers.part;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.items.ItemAccessService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import iped.viewers.HtmlViewer;
import iped.viewers.IcePDFViewer;
import iped.viewers.ImageViewer;
import iped.viewers.MetadataViewer;
import iped.viewers.MultiViewer;
import iped.viewers.TiffViewer;
import iped.viewers.EmailViewer;
import iped.viewers.api.AbstractViewer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Content viewer part (task T020, FR-011, research R4): hosts the EXISTING
 * {@code iped-viewers} stack ({@link MultiViewer} selecting the best viewer
 * by MIME type) inside the single {@link SwtAwtBridgeHost} of this part. The
 * viewers are NOT rewritten (FR-028); Swing/JavaFX content keeps its own
 * threading (EDT / FX thread) behind the bridge.
 *
 * <p>
 * Top-level structure (mirrors the legacy {@code ViewerController} top tabs):
 * a "Visualização" tab hosting the format-specific {@link MultiViewer}
 * (metadata fallback, image, TIFF, HTML, e-mail with case attachment
 * resolution, PDF) and a "Texto" tab hosting {@link RcpTextViewer} (extracted
 * text of any item, e.g. plain text files such as {@code .bashrc}). The legacy
 * {@code TextViewer} could not be reused directly because its {@code TextParser}
 * is hard-coupled to the Swing {@code App} singleton — hence the App-free
 * {@link RcpTextViewer}. The remaining legacy viewers (hex, LibreOffice,
 * audio/video, CAD, in-text hit highlight/navigation) are still tracked as
 * pending rows of the parity inventory; hit prev/next navigation drives the
 * {@link MultiViewer} only for now.
 */
public class ContentViewerPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentViewerPart.class);

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ItemAccessService itemAccess;

    @Inject
    private MPart part;

    private SwtAwtBridgeHost bridge;
    private volatile MultiViewer multiViewer;
    private volatile RcpTextViewer textViewer;
    private volatile JTabbedPane tabbedPane;
    /** Format viewers (metadata excluded) used to decide the default tab. */
    private final List<AbstractViewer> formatViewers = new ArrayList<>();
    private final AtomicLong loadStamp = new AtomicLong();
    /** Per-tab guard so switching tabs back and forth does not reload. */
    private volatile long viewTabLoaded = -1;
    private volatile long textTabLoaded = -1;
    private volatile IItem currentItem;
    private volatile IPEDSource currentSource;
    private volatile String currentContentType;

    private static final int TAB_VIEW = 0;
    private static final int TAB_TEXT = 1;

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(2, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Button previousHit = new Button(toolbar, SWT.PUSH);
        previousHit.setText("<");
        previousHit.setToolTipText(Messages.getString("ContentViewer.PrevHit"));
        previousHit.addListener(SWT.Selection, event -> scrollHit(false));
        Button nextHit = new Button(toolbar, SWT.PUSH);
        nextHit.setText(">");
        nextHit.setToolTipText(Messages.getString("ContentViewer.NextHit"));
        nextHit.addListener(SWT.Selection, event -> scrollHit(true));

        Composite bridgeArea = new Composite(parent, SWT.NONE);
        bridgeArea.setLayout(new org.eclipse.swt.layout.FillLayout());
        bridgeArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        bridge = new SwtAwtBridgeHost(bridgeArea);

        // build the Swing viewer stack on the EDT, then init the heavy
        // viewers off both UI threads (legacy ViewerController discipline)
        RcpAttachmentSearcher attachmentSearcher = new RcpAttachmentSearcher(sessionManager);
        SwingUtilities.invokeLater(() -> {
            MultiViewer viewer = new MultiViewer();
            // same anonymous subclass the legacy ViewerController registers
            viewer.addViewer(new MetadataViewer() {
                @Override
                public boolean isNumeric(String field) {
                    return IndexItem.isIntegerNumber(field) || IndexItem.isRealNumber(field);
                }
            });
            ImageViewer imageViewer = new ImageViewer();
            TiffViewer tiffViewer = new TiffViewer();
            HtmlViewer htmlViewer = new HtmlViewer();
            EmailViewer emailViewer = new EmailViewer(attachmentSearcher);
            IcePDFViewer pdfViewer = new IcePDFViewer();
            viewer.addViewer(imageViewer);
            viewer.addViewer(tiffViewer);
            viewer.addViewer(htmlViewer);
            viewer.addViewer(emailViewer);
            viewer.addViewer(pdfViewer);
            formatViewers.add(imageViewer);
            formatViewers.add(tiffViewer);
            formatViewers.add(htmlViewer);
            formatViewers.add(emailViewer);
            formatViewers.add(pdfViewer);
            multiViewer = viewer;

            RcpTextViewer text = new RcpTextViewer();
            textViewer = text;

            JTabbedPane tabs = new JTabbedPane();
            tabs.insertTab(Messages.getString("ContentViewer.TabView"), null, viewer.getPanel(), null, TAB_VIEW);
            tabs.insertTab(Messages.getString("ContentViewer.TabText"), null, text.getPanel(), null, TAB_TEXT);
            tabs.addChangeListener(e -> refreshActiveTab());
            tabbedPane = tabs;
            bridge.setContent(tabs);

            new Thread(() -> {
                try {
                    viewer.init();
                    text.init();
                } catch (RuntimeException e) {
                    LOGGER.error("Error initializing content viewers", e);
                }
            }, "content-viewers-init").start();
        });
    }

    /** Selection sync: the active item drives the viewer (FR-011). */
    @Inject
    @Optional
    public void onSelectionChanged(
            @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        if (bridge == null || multiViewer == null) {
            return;
        }
        if (selection != null && part.getElementId().equals(selection.originPartId())) {
            return; // no echo
        }
        long stamp = loadStamp.incrementAndGet();
        if (selection == null || selection.activeItem() == null) {
            currentItem = null;
            currentSource = null;
            currentContentType = null;
            SwingUtilities.invokeLater(() -> {
                if (stamp == loadStamp.get()) {
                    if (multiViewer != null) {
                        multiViewer.loadFile(null, null);
                    }
                    if (textViewer != null) {
                        textViewer.clear();
                    }
                }
            });
            return;
        }
        Job job = Job.create("load-viewer-content", (IProgressMonitor monitor) -> {
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
                int target = preferText(contentType) ? TAB_TEXT : TAB_VIEW;
                SwingUtilities.invokeLater(() -> {
                    if (stamp != loadStamp.get() || tabbedPane == null) {
                        return;
                    }
                    if (tabbedPane.getSelectedIndex() != target) {
                        tabbedPane.setSelectedIndex(target); // triggers refreshActiveTab()
                    } else {
                        refreshActiveTab();
                    }
                });
                return Status.OK_STATUS;
            } catch (RuntimeException e) {
                LOGGER.warn("Error loading item into viewer", e);
                return Status.OK_STATUS; // viewer degrades, no error dialog
            }
        });
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Loads the item into the currently visible tab's viewer only (lazy,
     * legacy {@code ViewerController} discipline) — must run on the EDT.
     */
    private void refreshActiveTab() {
        JTabbedPane tabs = tabbedPane;
        if (tabs == null) {
            return;
        }
        long stamp = loadStamp.get();
        IItem item = currentItem;
        if (item == null) {
            return;
        }
        if (tabs.getSelectedIndex() == TAB_TEXT) {
            if (textTabLoaded != stamp && textViewer != null) {
                textTabLoaded = stamp;
                textViewer.loadItem(item, currentSource);
            }
        } else {
            if (viewTabLoaded != stamp && multiViewer != null) {
                viewTabLoaded = stamp;
                multiViewer.loadFile(item, currentContentType, null);
            }
        }
    }

    /**
     * True when the text tab is the best default for the type: text/* and any
     * type no concrete (non-metadata) format viewer can render, so the user
     * sees content instead of the metadata fallback (e.g. {@code .bashrc}).
     */
    private boolean preferText(String contentType) {
        if (contentType == null || contentType.startsWith("text/")) {
            return true;
        }
        for (AbstractViewer viewer : formatViewers) {
            if (viewer.isSupportedType(contentType, true)) {
                return false;
            }
        }
        return true;
    }

    private IPEDSource resolveSource(int sourceId) {
        CaseSession session = sessionManager == null ? null : sessionManager.getSession();
        if (session == null) {
            return null;
        }
        IPEDMultiSource source = session.getSource();
        return source == null ? null : source.getAtomicSourceBySourceId(sourceId);
    }

    private void scrollHit(boolean forward) {
        SwingUtilities.invokeLater(() -> {
            if (multiViewer != null) {
                multiViewer.scrollToNextHit(forward);
            }
        });
    }

    @PreDestroy
    public void dispose() {
        MultiViewer viewer = multiViewer;
        RcpTextViewer text = textViewer;
        multiViewer = null;
        textViewer = null;
        SwingUtilities.invokeLater(() -> {
            if (viewer != null) {
                viewer.dispose();
            }
            if (text != null) {
                text.dispose();
            }
        });
    }
}

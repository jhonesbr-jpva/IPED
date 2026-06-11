package iped.rcp.viewers.part;

import java.util.concurrent.atomic.AtomicLong;

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
import iped.engine.task.index.IndexItem;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.items.ItemAccessService;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import iped.viewers.HtmlViewer;
import iped.viewers.IcePDFViewer;
import iped.viewers.ImageViewer;
import iped.viewers.MetadataViewer;
import iped.viewers.MultiViewer;
import iped.viewers.TiffViewer;
import iped.viewers.EmailViewer;
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
 * Registered viewers in this increment: metadata (fallback), image, TIFF,
 * HTML, e-mail (with case attachment resolution), PDF. The remaining legacy
 * viewers (text with hit highlight, hex, LibreOffice, audio/video, CAD) are
 * tracked as pending rows of the parity inventory and join in follow-up US1
 * iterations; hit prev/next navigation is wired to the viewer API already
 * (terms arrive with the text-hits infrastructure).
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
    private final AtomicLong loadStamp = new AtomicLong();

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
            viewer.addViewer(new ImageViewer());
            viewer.addViewer(new TiffViewer());
            viewer.addViewer(new HtmlViewer());
            viewer.addViewer(new EmailViewer(attachmentSearcher));
            viewer.addViewer(new IcePDFViewer());
            multiViewer = viewer;
            bridge.setContent(viewer.getPanel());
            new Thread(() -> {
                try {
                    viewer.init();
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
            SwingUtilities.invokeLater(() -> {
                if (stamp == loadStamp.get() && multiViewer != null) {
                    multiViewer.loadFile(null, null);
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
                SwingUtilities.invokeLater(() -> {
                    if (stamp == loadStamp.get() && multiViewer != null) {
                        multiViewer.loadFile(item, contentType, null);
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
        multiViewer = null;
        if (viewer != null) {
            SwingUtilities.invokeLater(viewer::dispose);
        }
    }
}

package iped.rcp.viewers.part;

import java.util.Set;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import iped.viewers.EmailViewer;
import iped.viewers.HtmlViewer;
import iped.viewers.IcePDFViewer;
import iped.viewers.ImageViewer;
import iped.viewers.MultiViewer;
import iped.viewers.TiffViewer;
import jakarta.inject.Inject;

/**
 * Preview part (legacy "Pré-visualização"): hosts the {@link MultiViewer},
 * which picks the best format-specific viewer by MIME type (image, TIFF, HTML,
 * e-mail with case attachment resolution, PDF). Unlike the previous combined
 * content part, the metadata fallback viewer is NOT registered here — item
 * metadata now has its own {@link MetadataViewerPart} tab, so the preview is
 * blank for types no format viewer renders (matching the legacy dock, where
 * "Pré-visualização" never showed the metadata table).
 */
public class PreviewViewerPart extends AbstractBridgedViewerPart {

    @Inject
    private ICaseSessionManager sessionManager;

    private volatile MultiViewer multiViewer;

    @Override
    protected void createToolbar(Composite parent) {
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
    }

    @Override
    protected JComponent buildViewer(SwtAwtBridgeHost bridge) {
        MultiViewer viewer = new MultiViewer();
        RcpAttachmentSearcher attachmentSearcher = new RcpAttachmentSearcher(sessionManager);
        viewer.addViewer(new ImageViewer());
        viewer.addViewer(new TiffViewer());
        viewer.addViewer(new HtmlViewer());
        viewer.addViewer(new EmailViewer(attachmentSearcher));
        viewer.addViewer(new IcePDFViewer());
        multiViewer = viewer;
        return viewer.getPanel();
    }

    @Override
    protected void initViewer() {
        MultiViewer viewer = multiViewer;
        if (viewer != null) {
            viewer.init();
        }
    }

    @Override
    protected void loadIntoViewer(IItem item, IPEDSource source, String contentType, Set<String> highlightTerms) {
        MultiViewer viewer = multiViewer;
        if (viewer != null) {
            viewer.loadFile(item, contentType, highlightTerms);
        }
    }

    @Override
    protected void clearViewer() {
        MultiViewer viewer = multiViewer;
        if (viewer != null) {
            viewer.loadFile(null, null, null);
        }
    }

    @Override
    protected void disposeViewer() {
        MultiViewer viewer = multiViewer;
        multiViewer = null;
        if (viewer != null) {
            viewer.dispose();
        }
    }

    private void scrollHit(boolean forward) {
        SwingUtilities.invokeLater(() -> {
            MultiViewer viewer = multiViewer;
            if (viewer != null) {
                viewer.scrollToNextHit(forward);
            }
        });
    }
}

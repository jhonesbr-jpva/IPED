package iped.rcp.viewers.part;

import java.util.Set;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.rcp.core.i18n.Messages;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Text part (legacy "Texto"): hosts the App-free {@link RcpTextViewer}, showing
 * the parsed/extracted text of any item (e.g. plain text files such as
 * {@code .bashrc}), with query-term highlighting and previous/next-hit
 * navigation (VW-01). Its own top-level tab, isolated behind its own bridge.
 */
public class TextViewerPart extends AbstractBridgedViewerPart {

    private volatile RcpTextViewer textViewer;

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
        RcpTextViewer viewer = new RcpTextViewer();
        textViewer = viewer;
        return viewer.getPanel();
    }

    @Override
    protected void initViewer() {
        RcpTextViewer viewer = textViewer;
        if (viewer != null) {
            viewer.init();
        }
    }

    @Override
    protected void loadIntoViewer(IItem item, IPEDSource source, String contentType, Set<String> highlightTerms) {
        RcpTextViewer viewer = textViewer;
        if (viewer != null) {
            viewer.loadItem(item, source, highlightTerms);
        }
    }

    @Override
    protected void clearViewer() {
        RcpTextViewer viewer = textViewer;
        if (viewer != null) {
            viewer.clear();
        }
    }

    @Override
    protected void disposeViewer() {
        RcpTextViewer viewer = textViewer;
        textViewer = null;
        if (viewer != null) {
            viewer.dispose();
        }
    }

    private void scrollHit(boolean forward) {
        SwingUtilities.invokeLater(() -> {
            RcpTextViewer viewer = textViewer;
            if (viewer != null) {
                viewer.scrollToNextHit(forward);
            }
        });
    }

    /**
     * Jumps to the occurrence clicked in the {@link HitsViewerPart}. The hits
     * part already brought this tab forward (so the base part loads the item);
     * {@link RcpTextViewer#scrollToOffset(int)} defers the jump if the text is
     * still being extracted.
     */
    @Inject
    @Optional
    public void onHitGoto(@Named(TextHitGoto.KEY) @Optional TextHitGoto target) {
        if (target == null) {
            return;
        }
        RcpTextViewer viewer = textViewer;
        if (viewer != null) {
            viewer.scrollToOffset(target.offset());
        }
    }
}

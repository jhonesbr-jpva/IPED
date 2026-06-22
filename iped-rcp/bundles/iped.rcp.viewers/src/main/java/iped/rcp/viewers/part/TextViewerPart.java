package iped.rcp.viewers.part;

import javax.swing.JComponent;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;

/**
 * Text part (legacy "Texto"): hosts the App-free {@link RcpTextViewer}, showing
 * the parsed/extracted text of any item (e.g. plain text files such as
 * {@code .bashrc}). Its own top-level tab, isolated behind its own bridge.
 */
public class TextViewerPart extends AbstractBridgedViewerPart {

    private volatile RcpTextViewer textViewer;

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
    protected void loadIntoViewer(IItem item, IPEDSource source, String contentType) {
        RcpTextViewer viewer = textViewer;
        if (viewer != null) {
            viewer.loadItem(item, source);
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
}

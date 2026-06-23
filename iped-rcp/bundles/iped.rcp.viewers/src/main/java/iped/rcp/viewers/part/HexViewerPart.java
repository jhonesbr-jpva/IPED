package iped.rcp.viewers.part;

import java.awt.Frame;
import java.util.Set;

import javax.swing.JComponent;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import iped.viewers.HexViewerPlus;

/**
 * Hex part (legacy "Hex"): hosts the {@link HexViewerPlus} (DeltaHex code area)
 * with the App-free {@link RcpHexSearcher}. Its own top-level tab; the embedded
 * AWT frame of the bridge is the heavyweight owner the viewer needs for its
 * popup dialogs (settings, go-to, charset).
 */
public class HexViewerPart extends AbstractBridgedViewerPart {

    private volatile HexViewerPlus hexViewer;

    @Override
    protected JComponent buildViewer(SwtAwtBridgeHost bridge) {
        Frame owner = bridge.getFrame();
        HexViewerPlus viewer = new HexViewerPlus(owner, new RcpHexSearcher());
        hexViewer = viewer;
        return viewer.getPanel();
    }

    @Override
    protected void initViewer() {
        HexViewerPlus viewer = hexViewer;
        if (viewer != null) {
            viewer.init();
        }
    }

    @Override
    protected void loadIntoViewer(IItem item, IPEDSource source, String contentType, Set<String> highlightTerms) {
        HexViewerPlus viewer = hexViewer;
        if (viewer != null) {
            viewer.loadFile(item, contentType, highlightTerms);
        }
    }

    @Override
    protected void clearViewer() {
        HexViewerPlus viewer = hexViewer;
        if (viewer != null) {
            viewer.loadFile(null);
        }
    }

    @Override
    protected void disposeViewer() {
        HexViewerPlus viewer = hexViewer;
        hexViewer = null;
        if (viewer != null) {
            viewer.dispose();
        }
    }
}

package iped.rcp.viewers.part;

import java.util.Set;

import javax.swing.JComponent;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;
import iped.rcp.viewers.bridge.SwtAwtBridgeHost;
import iped.viewers.MetadataViewer;

/**
 * Item-metadata part (legacy "Metadados"): hosts the {@link MetadataViewer}
 * (JavaFX tab pane with Basic/Advanced/Custom properties of the selected item).
 * In the legacy {@code ViewerController} this was a standalone dockable, not a
 * sub-tab of the preview; here it is its own top-level tab again — distinct from
 * the right-side metadata FACET panel ({@code MetadataPanelPart}), which
 * aggregates field values for filtering.
 */
public class MetadataViewerPart extends AbstractBridgedViewerPart {

    private volatile MetadataViewer metadataViewer;

    @Override
    protected JComponent buildViewer(SwtAwtBridgeHost bridge) {
        MetadataViewer viewer = new MetadataViewer() {
            @Override
            public boolean isNumeric(String field) {
                return IndexItem.isIntegerNumber(field) || IndexItem.isRealNumber(field);
            }
        };
        metadataViewer = viewer;
        return viewer.getPanel();
    }

    @Override
    protected void initViewer() {
        MetadataViewer viewer = metadataViewer;
        if (viewer != null) {
            viewer.init();
        }
    }

    @Override
    protected void loadIntoViewer(IItem item, IPEDSource source, String contentType, Set<String> highlightTerms) {
        MetadataViewer viewer = metadataViewer;
        if (viewer != null) {
            viewer.loadFile(item, contentType, highlightTerms);
        }
    }

    @Override
    protected void clearViewer() {
        MetadataViewer viewer = metadataViewer;
        if (viewer != null) {
            viewer.loadFile(null, null, null);
        }
    }

    @Override
    protected void disposeViewer() {
        MetadataViewer viewer = metadataViewer;
        metadataViewer = null;
        if (viewer != null) {
            viewer.dispose();
        }
    }
}

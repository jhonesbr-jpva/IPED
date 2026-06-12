package iped.rcp.specialized.parts;

import java.awt.event.HierarchyEvent;

import javax.swing.JPanel;

import bibliothek.gui.dock.common.DefaultSingleCDockable;
import iped.geo.impl.MapViewer;

/**
 * Map part (task T034, FR-012): hosts the EXISTING {@code iped-geo}
 * {@link MapViewer} / {@code AppMapPanel} stack (JavaFX WebView with
 * Leaflet/OpenStreet tiles) through the part's SWT_AWT bridge. The viewer is
 * initialized exactly like the legacy App did - against the shared results
 * table (here the bridge's mirror table) and the results/GUI providers - so
 * its own listeners give the bidirectional selection sync for free: map
 * marker clicks select rows on the mirror table (published to the workbench
 * by the bridge) and workbench selections land on the mirror selection model
 * the viewer observes.
 *
 * <p>
 * The legacy {@code DefaultSingleCDockable} visibility contract is adapted
 * by a detached dockable whose {@code isShowing()} reflects the AWT
 * visibility of the bridged panel (SWT_AWT propagates the part visibility to
 * the embedded frame); the legacy dockable-shown redraw trigger becomes an
 * AWT hierarchy listener.
 */
public class MapPart extends AbstractBridgedPart {

    public static final String HOST_WIDGET_ID = "iped.rcp.specialized.map.host";

    @Override
    protected String hostWidgetId() {
        return HOST_WIDGET_ID;
    }

    @Override
    protected void createLegacyContent() {
        MapViewer viewer = new MapViewer();
        viewer.init(bridge.getMirrorTable(), bridge.getProviders(), bridge.getProviders());

        JPanel panel = viewer.getPanel();
        viewer.setDockableContainer(new DefaultSingleCDockable(viewer.getID()) {
            @Override
            public boolean isShowing() {
                return panel.isShowing();
            }
        });
        // legacy CDockableLocationListener equivalent: process pending result
        // changes when the tab becomes visible (MapViewer.redraw contract)
        panel.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) {
                viewer.redraw();
            }
        });
        bridgeHost.setContent(panel);
        // the part renders lazily: replay the active result so the viewer's
        // fresh listeners see it (covers results that predate this part)
        bridge.refreshMirror();
    }
}

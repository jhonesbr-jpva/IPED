package iped.rcp.specialized.parts;

import iped.app.graph.AppGraphAnalytics;

/**
 * Communications graph part (task T035, FR-012): hosts the EXISTING
 * {@link AppGraphAnalytics} (Kharon graph pane + toolbar + side panel +
 * status bar, all built by the legacy panel itself) through the part's
 * SWT_AWT bridge. The graph database is the one produced by the processing
 * {@code GraphTask} inside the case ({@code iped/graph}); loading follows
 * the exact legacy worker ({@code LoadGraphDatabaseWorker}), which reads the
 * case from the bound legacy singleton and disables the panel when the
 * graph feature is off or the database is absent.
 *
 * <p>
 * In-panel actions (expand, find paths/connections, layouts, export image
 * and links) work against the embedded Neo4j service as before. Divergence
 * tracked in the parity inventory: "show evidence" from a graph node uses
 * the legacy {@code FileProcessor} pipeline, which needs the full Swing App
 * and degrades to a logged error here (navigation to the item joins a later
 * iteration); legacy dialogs parent on the invisible App frame and open
 * centered on the screen.
 */
public class GraphPart extends AbstractBridgedPart {

    public static final String HOST_WIDGET_ID = "iped.rcp.specialized.graph.host";

    @Override
    protected String hostWidgetId() {
        return HOST_WIDGET_ID;
    }

    @Override
    protected void createLegacyContent() {
        AppGraphAnalytics graph = new AppGraphAnalytics();
        bridgeHost.setContent(graph);
        // legacy CaseDataLoader discipline: kick the database load once the
        // panel exists; the worker no-ops when the graph feature is disabled
        graph.initGraphService();
    }
}

package iped.app.ui;

import java.awt.Dialog.ModalityType;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.graph.GraphPluginRegistry;
import iped.engine.graph.GraphPluginRejection;
import iped.engine.graph.GraphPluginState;
import iped.engine.graph.GraphServiceProvider;
import iped.engine.graph.GraphTask;

/**
 * Non-blocking notice shown when the opened case has link analysis (graph) data but the graph plugin
 * is not available, so the "Links" tab is not shown (feature 010, FR-007). Shown at most once per UI
 * session, in a modeless dialog.
 */
public class GraphPluginNotice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphPluginNotice.class);

    private static final AtomicBoolean shown = new AtomicBoolean();

    private GraphPluginNotice() {
    }

    /**
     * @param moduleDirs
     *            module folders ({@code <case>/iped}) of the atomic sources of the opened case
     * @return {@code true} if the graph plugin is not available and at least one source has a graph
     *         database
     */
    static boolean shouldShow(List<File> moduleDirs, GraphPluginState state) {
        if (state == GraphPluginState.AVAILABLE || moduleDirs == null) {
            return false;
        }
        for (File moduleDir : moduleDirs) {
            if (moduleDir != null && new File(moduleDir, GraphTask.DB_DATA_PATH).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows the notice if needed, once per session. Can be called from any thread.
     */
    public static void showIfNeeded(IPEDMultiSource appCase) {
        if (appCase == null) {
            return;
        }
        GraphPluginRegistry graphPlugin = GraphPluginRegistry.get();
        List<File> moduleDirs = appCase.getAtomicSources().stream().map(IPEDSource::getModuleDir)
                .collect(Collectors.toList());
        if (!shouldShow(moduleDirs, graphPlugin.getState()) || !shown.compareAndSet(false, true)) {
            return;
        }
        String reason = getReason(graphPlugin);
        LOGGER.info("Case has link analysis data but the graph plugin is not available ({}): the Links tab is not shown. {}",
                graphPlugin.getState() + graphPlugin.getRejection().map(r -> ", " + r).orElse(""),
                graphPlugin.getDetail());
        SwingUtilities.invokeLater(() -> show(reason));
    }

    /** Localized reason, without technical details (those only go to the log). */
    private static String getReason(GraphPluginRegistry graphPlugin) {
        if (graphPlugin.getState() == GraphPluginState.ABSENT) {
            return Messages.getString("GraphPlugin.Reason.NotInstalled");
        }
        switch (graphPlugin.getRejection().orElse(GraphPluginRejection.LOAD_ERROR)) {
            case MULTIPLE_COPIES:
                return Messages.getString("GraphPlugin.Reason.MultipleCopies");
            case SPI_MISMATCH:
                return Messages.getString("GraphPlugin.Reason.SpiMismatch", graphPlugin.getPluginSpiVersion(),
                        GraphServiceProvider.CURRENT_SPI_VERSION);
            case INCOMPLETE:
                return Messages.getString("GraphPlugin.Reason.Incomplete");
            default:
                return Messages.getString("GraphPlugin.Reason.LoadError");
        }
    }

    private static void show(String reason) {
        JTextArea text = new JTextArea(Messages.getString("GraphPlugin.Notice.Text", reason));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(UIManager.getFont("Label.font"));
        text.setColumns(45);

        JOptionPane pane = new JOptionPane(text, JOptionPane.INFORMATION_MESSAGE);
        JDialog dialog = pane.createDialog(App.get(), Messages.getString("GraphPlugin.Notice.Title"));
        dialog.setModalityType(ModalityType.MODELESS);
        dialog.setVisible(true);
    }

}

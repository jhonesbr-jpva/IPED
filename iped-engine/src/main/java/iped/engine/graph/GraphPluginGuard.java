package iped.engine.graph;

import java.io.File;
import java.io.IOException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import iped.engine.CmdLineArgs;
import iped.engine.localization.Messages;
import iped.exception.IPEDException;
import iped.utils.IOUtil;

/**
 * Protects the link analysis data of existing cases when the graph plugin is not available
 * (feature 010, FR-008). Called by the {@code Manager} before anything is written to the case.
 *
 * <ul>
 * <li>{@code --append}, {@code --continue} and {@code -remove} on a case that has link analysis data
 * are refused, unless {@code --ignore-missing-graph-plugin} is given: then they proceed with a warning
 * and the graph is not updated.</li>
 * <li>{@code --restart} is not blocked: the link analysis data of the aborted processing is deleted, so
 * the resulting case does not keep orphan graph data.</li>
 * <li>{@code --yara-only} and new cases are not affected.</li>
 * </ul>
 */
public final class GraphPluginGuard {

    private static final Logger LOGGER = LogManager.getLogger(GraphPluginGuard.class);

    private GraphPluginGuard() {
    }

    /**
     * @param output
     *            case module folder ({@code <case>/iped})
     * @return {@code true} if the case has link analysis data: graph database or graph CSV files.
     */
    public static boolean hasLinkData(File output) {
        return new File(output, GraphTask.DB_DATA_PATH).exists() || new File(output, GraphTask.CSVS_PATH).exists();
    }

    /**
     * Refuses modifications of a case with link analysis data when the graph plugin is not available.
     *
     * @throws IPEDException
     *             with a localized message if the modification must be refused
     */
    public static void checkCaseModification(CmdLineArgs args, File output) {
        // --yara-only makes isContinue() return true but does not touch the graph: test it first
        if (args.isYaraOnly()) {
            return;
        }
        boolean modification = args.isAppendIndex() || args.isContinue() || args.getEvidenceToRemove() != null;
        if (!modification || !hasLinkData(output)) {
            return;
        }
        GraphPluginRegistry graphPlugin = GraphPluginRegistry.get();
        if (graphPlugin.isAvailable()) {
            return;
        }
        if (!args.isIgnoreMissingGraphPlugin()) {
            throw new IPEDException(Messages.getString("GraphPlugin.MissingForCaseModification")); //$NON-NLS-1$
        }
        LOGGER.warn("Graph plugin not available ({}): modifying a case with link analysis data because of "
                + "--ignore-missing-graph-plugin, the graph of the case will not be updated.", graphPlugin.getState());
        // custom console level, defined by the IPED log configuration
        Level console = Level.getLevel("MSG"); //$NON-NLS-1$
        if (console != null) {
            LOGGER.log(console, Messages.getString("GraphPlugin.ProceedingWithoutUpdate")); //$NON-NLS-1$
        }
    }

    /**
     * On {@code --restart} without the graph plugin, deletes the link analysis data of the aborted
     * processing ({@code <output>/neo4j}).
     */
    public static void handleRestart(CmdLineArgs args, File output) throws IOException {
        if (!args.isRestart() || GraphPluginRegistry.get().isAvailable()) {
            return;
        }
        File graphHome = new File(output, GraphTask.DB_HOME_DIR);
        if (graphHome.exists()) {
            LOGGER.info("Graph plugin not available: deleting link analysis data of the restarted processing at {}",
                    graphHome.getAbsolutePath());
            IOUtil.deleteDirectory(graphHome, false);
        }
    }

}

package iped.app.graph;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

import org.apache.commons.codec.digest.DigestUtils;

import iped.app.ui.App;
import iped.app.ui.Messages;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.graph.GraphFileWriter;
import iped.engine.graph.GraphGenerator;
import iped.engine.graph.GraphImportRunner.ImportListener;
import iped.engine.graph.GraphService;
import iped.engine.graph.GraphServiceFactoryImpl;
import iped.engine.graph.GraphTask;
import iped.engine.graph.GraphTaskConfig;
import iped.engine.graph.Neo4jChildLauncher;
import iped.utils.IOUtil;
import iped.viewers.api.CancelableWorker;
import iped.viewers.util.ProgressDialog;

class LoadGraphDatabaseWorker extends SwingWorker<Void, Void> {

    private final AppGraphAnalytics app;
    private volatile boolean loaded = false;

    LoadGraphDatabaseWorker(AppGraphAnalytics appGraphAnalytics) {
        app = appGraphAnalytics;
    }

    @Override
    protected Void doInBackground() throws Exception {
        long t = System.currentTimeMillis();
        app.setEnabled(false);
        GraphTaskConfig config = ConfigurationManager.get().findObject(GraphTaskConfig.class);
        if (!config.isEnabled()) {
            return null;
        }
        List<IPEDSource> cases = App.get().appCase.getAtomicSources();
        ensureNeo4jLibDir(cases);
        if (cases.size() == 1) {
            loaded = initGraphService(new File(cases.get(0).getModuleDir(), GraphTask.DB_HOME_DIR));
        } else {
            String caseNames = cases.stream().map(c -> c.getCaseDir().getName()).sorted()
                    .collect(Collectors.joining("-"));
            String hash = DigestUtils.md5Hex(caseNames);
            String suffix = "iped-multicases/multicase-" + hash + "/graph";
            File multiCaseGraphPath = new File(App.get().casesPathFile.getParentFile(), suffix);
            if (!multiCaseGraphPath.getParentFile().exists() && !multiCaseGraphPath.getParentFile().mkdirs()) {
                multiCaseGraphPath = new File(System.getProperty("java.io.basetmpdir"), suffix);
            }
            File graphDataDir = new File(multiCaseGraphPath, GraphTask.DB_DATA_DIR);
            if (graphDataDir.exists() && !new File(multiCaseGraphPath, GraphTask.CSVS_DIR).exists()) {
                IOUtil.deleteDirectory(graphDataDir, false);
            }
            createMultiCaseGraph(cases, multiCaseGraphPath);
            if (multiCaseGraphPath.exists()) {
                loaded = initGraphService(multiCaseGraphPath);
            }
        }
        AppGraphAnalytics.LOGGER.info("Init graph database took {}s", (System.currentTimeMillis() - t) / 1000);
        return null;
    }

    /**
     * Points the out-of-process Neo4j child JVMs (Bolt server and, for multicase, the bulk
     * import) at the case's {@code iped/lib/neo4j/} stack via {@link Neo4jChildLauncher#NEO4J_LIB_DIR_PROPERTY}.
     *
     * <p>
     * In the legacy Swing deployment the engine jar lives in {@code <case>/iped/lib/}, so
     * {@link Neo4jChildLauncher#getNeo4jLibDir()} already finds {@code lib/neo4j/} as a sibling
     * and this is a no-op (the property resolves to the same directory). In the RCP/OSGi
     * deployment the engine classes load from the Equinox bundle cache instead, where that
     * sibling does not exist — without this the child JVM would launch with a bogus classpath
     * and never report a Bolt port ("Could not start neo4j graph server").
     * </p>
     */
    private void ensureNeo4jLibDir(List<IPEDSource> cases) {
        if (System.getProperty(Neo4jChildLauncher.NEO4J_LIB_DIR_PROPERTY) != null || cases.isEmpty()) {
            return;
        }
        File libNeo4j = new File(cases.get(0).getModuleDir(), "lib/" + Neo4jChildLauncher.NEO4J_LIB_DIR);
        if (libNeo4j.isDirectory()) {
            System.setProperty(Neo4jChildLauncher.NEO4J_LIB_DIR_PROPERTY, libNeo4j.getAbsolutePath());
            AppGraphAnalytics.LOGGER.info("Using neo4j lib dir for out-of-process graph server: {}",
                    libNeo4j.getAbsolutePath());
        } else {
            AppGraphAnalytics.LOGGER.warn("Neo4j lib dir not found at {}; graph server may fail to start.",
                    libNeo4j.getAbsolutePath());
        }
    }

    private void createMultiCaseGraph(List<IPEDSource> cases, File multiCaseGraphPath) {
        File graphDataDir = new File(multiCaseGraphPath, GraphTask.DB_DATA_DIR);
        if (!graphDataDir.exists()) {
            int option = JOptionPane.showConfirmDialog(App.get(), Messages.getString("GraphAnalysis.CreateMultiGraph"));
            if (option != JOptionPane.YES_OPTION)
                return;
            try {
                if (multiCaseGraphPath.exists())
                    IOUtil.deleteDirectory(multiCaseGraphPath, false);
                File graphHomeTemp = new File(multiCaseGraphPath.getAbsolutePath() + "_temp");
                // neo4j needs canonical path, see #1288
                graphHomeTemp = graphHomeTemp.getCanonicalFile();
                if (graphHomeTemp.exists())
                    IOUtil.deleteDirectory(graphHomeTemp, false);
                ImportWorker importer = new ImportWorker(cases, graphHomeTemp);
                importer.execute();
                if (importer.get()) {
                    Files.move(graphHomeTemp.toPath(), multiCaseGraphPath.toPath());
                    JOptionPane.showMessageDialog(App.get(), Messages.getString("GraphAnalysis.MultiGraphOk"));
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            JOptionPane.showMessageDialog(App.get(), Messages.getString("GraphAnalysis.MultiGraphError"), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private class ImportWorker extends CancelableWorker<Boolean, Void> implements ImportListener {

        List<IPEDSource> cases;
        File graphHome;
        ProgressDialog progress;

        private ImportWorker(List<IPEDSource> cases, File graphHome) {
            this.cases = cases;
            this.graphHome = graphHome;
            this.progress = new ProgressDialog(App.get(), this);
            progress.setIndeterminate(true);
            progress.setExtraWidth(100);
        }

        @Override
        public void output(String line) {
            if (!line.trim().isEmpty())
                progress.setNote(line.trim());
            else
                progress.setNote("Importing...");
        }

        @Override
        protected Boolean doInBackground() throws Exception {
            try {
                List<File> csvParents = cases.stream().map(c -> new File(c.getModuleDir(), GraphTask.CSVS_PATH))
                        .collect(Collectors.toList());
                File preparedCSVs = new File(graphHome, GraphTask.CSVS_DIR);
                GraphFileWriter.prepareMultiCaseCSVs(preparedCSVs, csvParents);
                GraphGenerator graphGenerator = new GraphGenerator();
                return graphGenerator.generate(this, graphHome, preparedCSVs.listFiles());
            } finally {
                progress.close();
            }
        }
    }

    private boolean initGraphService(File neo4jHome) {
        app.setStatus(Messages.getString("GraphAnalysis.Preparing"));
        app.setProgress(50);

        final ClassLoader classLoader = this.getClass().getClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        GraphService graphService = GraphServiceFactoryImpl.getInstance().getGraphService();
        try {
            File dbDataDir = new File(neo4jHome, GraphTask.DB_DATA_DIR);
            if (!dbDataDir.exists()) {
                AppGraphAnalytics.LOGGER.error("Graph database not found: " + dbDataDir.getAbsolutePath());
                return false;
            }
            if (!IOUtil.canWrite(dbDataDir)) {
                neo4jHome = copyToTempFolder(dbDataDir);
            }
            // neo4j needs canonical path, see #1288
            neo4jHome = neo4jHome.getCanonicalFile();
            graphService.start(neo4jHome);
            return true;
        } catch (Throwable e) {
            AppGraphAnalytics.LOGGER.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private File copyToTempFolder(File dataDir) throws IOException {
        File tmpDb = new File(System.getProperty("java.io.tmpdir"), "iped-graph" + dataDir.lastModified());
        IOUtil.copyDirectory(dataDir, new File(tmpDb, GraphTask.DB_DATA_DIR), true);
        return tmpDb;
    }

    @Override
    protected void done() {
        app.setStatus(Messages.getString("GraphAnalysis.Ready"));
        app.setProgress(100);
        app.setEnabled(loaded);
        app.setDatabaseLoaded(loaded);
    }
}
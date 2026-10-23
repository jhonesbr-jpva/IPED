package iped.engine.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.CmdLineArgs;
import iped.exception.IPEDException;

/**
 * Unit tests for {@link GraphPluginGuard}: table B of the cli-missing-plugin contract (mode x link
 * analysis data x plugin availability x {@code --ignore-missing-graph-plugin}) and the {@code --restart}
 * cleanup.
 */
public class GraphPluginGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetRegistry() {
        GraphPluginRegistry.resetForTesting(null);
    }

    private enum Mode {
        NEW, APPEND, CONTINUE, REMOVE, RESTART, YARA_ONLY
    }

    private enum LinkData {
        NONE, DATABASE, CSV
    }

    /** Minimal command line: only the options read by the guard. */
    private static class Args implements CmdLineArgs {

        private final Mode mode;
        private final boolean ignoreMissingGraphPlugin;

        Args(Mode mode, boolean ignoreMissingGraphPlugin) {
            this.mode = mode;
            this.ignoreMissingGraphPlugin = ignoreMissingGraphPlugin;
        }

        @Override
        public boolean isAppendIndex() {
            return mode == Mode.APPEND;
        }

        @Override
        public boolean isContinue() {
            // as in the real implementation, --yara-only implies --continue
            return mode == Mode.CONTINUE || mode == Mode.YARA_ONLY;
        }

        @Override
        public boolean isRestart() {
            return mode == Mode.RESTART;
        }

        @Override
        public boolean isYaraOnly() {
            return mode == Mode.YARA_ONLY;
        }

        @Override
        public String getEvidenceToRemove() {
            return mode == Mode.REMOVE ? "evidence-1" : null;
        }

        @Override
        public boolean isIgnoreMissingGraphPlugin() {
            return ignoreMissingGraphPlugin;
        }

        @Override
        public List<File> getDatasources() {
            return null;
        }

        @Override
        public List<String> getDname() {
            return null;
        }

        @Override
        public File getOutputDir() {
            return null;
        }

        @Override
        public File getKeywords() {
            return null;
        }

        @Override
        public List<String> getOcr() {
            return null;
        }

        @Override
        public File getLogFile() {
            return null;
        }

        @Override
        public File getAsap() {
            return null;
        }

        @Override
        public List<String> getNocontent() {
            return null;
        }

        @Override
        public String getTimezone() {
            return null;
        }

        @Override
        public int getBlocksize() {
            return 0;
        }

        @Override
        public List<String> getPasswords() {
            return null;
        }

        @Override
        public String getProfile() {
            return null;
        }

        @Override
        public boolean isAddowner() {
            return false;
        }

        @Override
        public boolean isNogui() {
            return true;
        }

        @Override
        public boolean isNologfile() {
            return false;
        }

        @Override
        public boolean isNopstattachs() {
            return false;
        }

        @Override
        public boolean isNoLinkedItems() {
            return false;
        }

        @Override
        public boolean isPortable() {
            return false;
        }

        @Override
        public String getSplashMessage() {
            return null;
        }

        @Override
        public boolean isHelp() {
            return false;
        }

        @Override
        public Map<String, String> getExtraParams() {
            return null;
        }

        @Override
        public String getDataSourceName(File datasource) {
            return null;
        }

        @Override
        public String getDataSourcePassword(File datasource) {
            return null;
        }

        @Override
        public boolean isDownloadInternetData() {
            return false;
        }
    }

    private void setPluginAvailable(boolean available) {
        URL[] urls = available ? new URL[] { GraphPluginGuardTest.class.getResource("providers/available/") }
                : new URL[0];
        GraphPluginRegistry.resetForTesting(new URLClassLoader(urls, GraphPluginGuardTest.class.getClassLoader()));
        assertTrue(GraphPluginRegistry.get().isAvailable() == available);
    }

    /** Case module folder ({@code <case>/iped}) with the given link analysis data. */
    private File caseOutput(LinkData linkData) throws Exception {
        File output = tmp.newFolder();
        if (linkData == LinkData.DATABASE) {
            assertTrue(new File(output, GraphTask.DB_DATA_PATH).mkdirs());
        } else if (linkData == LinkData.CSV) {
            assertTrue(new File(output, GraphTask.CSVS_PATH).mkdirs());
        }
        return output;
    }

    private static boolean refused(Mode mode, File output, boolean flag) {
        try {
            GraphPluginGuard.checkCaseModification(new Args(mode, flag), output);
            return false;
        } catch (IPEDException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("--ignore-missing-graph-plugin"));
            return true;
        }
    }

    @Test
    public void link_data_is_detected_from_database_or_csv() throws Exception {
        assertFalse(GraphPluginGuard.hasLinkData(caseOutput(LinkData.NONE)));
        assertTrue(GraphPluginGuard.hasLinkData(caseOutput(LinkData.DATABASE)));
        assertTrue(GraphPluginGuard.hasLinkData(caseOutput(LinkData.CSV)));
    }

    @Test
    public void modification_of_case_with_link_data_is_refused_without_plugin_unless_flag() throws Exception {
        setPluginAvailable(false);
        for (Mode mode : new Mode[] { Mode.APPEND, Mode.CONTINUE, Mode.REMOVE }) {
            for (LinkData linkData : new LinkData[] { LinkData.DATABASE, LinkData.CSV }) {
                File output = caseOutput(linkData);
                assertTrue(mode + "/" + linkData + " without flag must be refused", refused(mode, output, false));
                assertFalse(mode + "/" + linkData + " with flag must proceed", refused(mode, output, true));
                // nothing was written or deleted by the guard
                assertTrue(GraphPluginGuard.hasLinkData(output));
            }
        }
    }

    @Test
    public void modification_is_allowed_with_plugin() throws Exception {
        setPluginAvailable(true);
        for (Mode mode : new Mode[] { Mode.APPEND, Mode.CONTINUE, Mode.REMOVE }) {
            for (LinkData linkData : LinkData.values()) {
                for (boolean flag : new boolean[] { false, true }) {
                    assertFalse(mode + "/" + linkData + "/" + flag, refused(mode, caseOutput(linkData), flag));
                }
            }
        }
    }

    @Test
    public void case_without_link_data_is_not_affected() throws Exception {
        setPluginAvailable(false);
        for (Mode mode : Mode.values()) {
            for (boolean flag : new boolean[] { false, true }) {
                assertFalse(mode + "/" + flag, refused(mode, caseOutput(LinkData.NONE), flag));
            }
        }
    }

    @Test
    public void new_case_restart_and_yara_only_are_never_refused() throws Exception {
        setPluginAvailable(false);
        for (Mode mode : new Mode[] { Mode.NEW, Mode.RESTART, Mode.YARA_ONLY }) {
            assertTrue(new Args(mode, false).isContinue() == (mode == Mode.YARA_ONLY));
            for (LinkData linkData : LinkData.values()) {
                assertFalse(mode + "/" + linkData, refused(mode, caseOutput(linkData), false));
            }
        }
    }

    @Test
    public void restart_without_plugin_deletes_link_data() throws Exception {
        setPluginAvailable(false);
        File output = caseOutput(LinkData.DATABASE);
        assertTrue(new File(output, GraphTask.CSVS_PATH).mkdirs());
        GraphPluginGuard.handleRestart(new Args(Mode.RESTART, false), output);
        assertFalse(new File(output, GraphTask.DB_HOME_DIR).exists());
    }

    @Test
    public void restart_with_plugin_keeps_link_data() throws Exception {
        setPluginAvailable(true);
        File output = caseOutput(LinkData.DATABASE);
        GraphPluginGuard.handleRestart(new Args(Mode.RESTART, false), output);
        assertTrue(GraphPluginGuard.hasLinkData(output));
    }

    @Test
    public void other_modes_never_delete_link_data() throws Exception {
        setPluginAvailable(false);
        for (Mode mode : new Mode[] { Mode.NEW, Mode.APPEND, Mode.CONTINUE, Mode.REMOVE, Mode.YARA_ONLY }) {
            File output = caseOutput(LinkData.DATABASE);
            GraphPluginGuard.handleRestart(new Args(mode, true), output);
            assertTrue(mode.toString(), GraphPluginGuard.hasLinkData(output));
        }
    }

}

package iped.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.graph.GraphPluginState;
import iped.engine.graph.GraphTask;

/**
 * Unit tests for the decision of {@link GraphPluginNotice} (no Swing involved): the notice is shown only
 * without an available graph plugin and when at least one source of the case has a graph database.
 */
public class GraphPluginNoticeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File moduleDir(boolean withGraphDatabase, boolean withGraphCsvOnly) throws Exception {
        File dir = tmp.newFolder();
        if (withGraphDatabase) {
            assertTrue(new File(dir, GraphTask.DB_DATA_PATH).mkdirs());
        }
        if (withGraphCsvOnly) {
            assertTrue(new File(dir, GraphTask.CSVS_PATH).mkdirs());
        }
        return dir;
    }

    @Test
    public void shown_without_plugin_when_case_has_graph_database() throws Exception {
        File withGraph = moduleDir(true, false);
        assertTrue(GraphPluginNotice.shouldShow(Arrays.asList(withGraph), GraphPluginState.ABSENT));
        assertTrue(GraphPluginNotice.shouldShow(Arrays.asList(withGraph), GraphPluginState.REJECTED));
    }

    @Test
    public void not_shown_with_plugin() throws Exception {
        assertFalse(GraphPluginNotice.shouldShow(Arrays.asList(moduleDir(true, false)), GraphPluginState.AVAILABLE));
    }

    @Test
    public void not_shown_when_case_has_no_graph_database() throws Exception {
        assertFalse(GraphPluginNotice.shouldShow(Arrays.asList(moduleDir(false, false)), GraphPluginState.ABSENT));
        // CSV files alone are not a usable graph (no database to show)
        assertFalse(GraphPluginNotice.shouldShow(Arrays.asList(moduleDir(false, true)), GraphPluginState.ABSENT));
        assertFalse(GraphPluginNotice.shouldShow(Collections.emptyList(), GraphPluginState.REJECTED));
        assertFalse(GraphPluginNotice.shouldShow(null, GraphPluginState.ABSENT));
    }

    @Test
    public void multi_case_shows_when_any_source_has_graph_database() throws Exception {
        File without1 = moduleDir(false, false);
        File with = moduleDir(true, false);
        File without2 = moduleDir(false, true);
        assertTrue(GraphPluginNotice.shouldShow(Arrays.asList(without1, with, without2), GraphPluginState.ABSENT));
        assertFalse(GraphPluginNotice.shouldShow(Arrays.asList(without1, without2), GraphPluginState.ABSENT));
        assertFalse(GraphPluginNotice.shouldShow(Arrays.asList(without1, with), GraphPluginState.AVAILABLE));
    }

}

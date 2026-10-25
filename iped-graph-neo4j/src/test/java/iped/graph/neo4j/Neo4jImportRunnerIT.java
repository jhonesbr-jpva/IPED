package iped.graph.neo4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.graph.FreeQueryListener;
import iped.engine.graph.GraphFileWriter;
import iped.engine.graph.GraphTask;

/**
 * Integration test of the padding of short CSV rows (research R-15) with the Neo4j 5 bulk importer of
 * the extracted plugin package: a padded column must become an absent property, as the missing trailing
 * columns did with Neo4j 4.4, never an empty string.
 *
 * <p>
 * Gated: runs only with {@code IPED_GRAPH_PLUGIN_HOME} pointing to the folder where the plugin zip was
 * extracted ({@code mvn -pl iped-graph-neo4j verify -Pgraph-it}).
 * </p>
 */
public class Neo4jImportRunnerIT {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File write(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Runs a query that returns a single row with a single column and returns that value. */
    private static Object single(Neo4jGraphService service, String query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        service.advancedSearch(query, new FreeQueryListener() {
            @Override
            public void columnsFound(List<String> columns) {
            }

            @Override
            public void resultFound(Map<String, Object> row) {
                rows.add(row);
            }
        });
        assertEquals(query, 1, rows.size());
        assertEquals(query, 1, rows.get(0).size());
        return rows.get(0).values().iterator().next();
    }

    @Test
    public void padded_columns_are_imported_as_absent_properties() throws Exception {
        String pluginFolderPath = System.getenv("IPED_GRAPH_PLUGIN_HOME");
        assumeTrue("IPED_GRAPH_PLUGIN_HOME not set: import integration test skipped", pluginFolderPath != null);
        File pluginHome = new File(pluginFolderPath, Neo4jGraphServiceProvider.DISPLAY_NAME).getCanonicalFile();
        assertTrue("no Neo4j engine in " + pluginHome,
                new File(pluginHome, Neo4jChildLauncher.NEO4J_LIB_DIR).isDirectory());

        File dbHome = tmp.newFolder(GraphTask.DB_HOME_DIR).getCanonicalFile();
        File csvDir = new File(dbHome, GraphTask.CSVS_DIR);
        assertTrue(csvDir.mkdir());
        write(csvDir, "nodes_PERSON_headers_iped.csv", "nodeId:ID,label:LABEL,name:string,userAccount:string");
        File data = write(csvDir, "nodes_PERSON_iped.csv", "\"p1\",\"PERSON\",\"Alice\",\"alice@example.com\"\r\n"
                + "\"p2\",\"PERSON\",\"Bob\"\r\n"); // written before userAccount first appeared
        write(csvDir, GraphFileWriter.ARGS_FILE_NAME + "-iped.txt",
                "--nodes=nodes_PERSON_headers_iped.csv,nodes_PERSON_iped.csv\r\n");

        Neo4jGraphServiceProvider.setPluginHomeForTesting(pluginHome.toPath());
        try {
            new Neo4jImportRunner(null, csvDir).run(dbHome, GraphTask.DB_NAME, false);

            assertEquals("\"p1\",\"PERSON\",\"Alice\",\"alice@example.com\"\r\n\"p2\",\"PERSON\",\"Bob\",\"\"\r\n",
                    new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8));
            assertEquals(0, csvDir.list((dir, name) -> name.endsWith(".padding.tmp")).length);

            Neo4jGraphService service = new Neo4jGraphService();
            try {
                service.start(dbHome);
                assertEquals(2L, single(service, "MATCH (n:PERSON) RETURN count(n) AS nodes"));
                assertEquals(Boolean.TRUE,
                        single(service, "MATCH (n:PERSON {nodeId: 'p2'}) RETURN n.userAccount IS NULL AS absent"));
                assertEquals("Bob", single(service, "MATCH (n:PERSON {nodeId: 'p2'}) RETURN n.name AS name"));
                assertEquals("alice@example.com",
                        single(service, "MATCH (n:PERSON {nodeId: 'p1'}) RETURN n.userAccount AS account"));
                assertEquals(0L, single(service,
                        "MATCH (n) UNWIND keys(n) AS key WITH n, key WHERE n[key] = '' RETURN count(*) AS empty"));
            } finally {
                service.stop();
            }
        } finally {
            Neo4jGraphServiceProvider.setPluginHomeForTesting(null);
        }
    }

}

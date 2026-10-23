package iped.graph.neo4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.graph.GraphPluginException;
import iped.engine.graph.GraphServiceProvider;

/**
 * Unit tests for {@link Neo4jGraphServiceProvider}: declared extension point version, manifest reading and
 * validation of the installed package layout (plugin home next to the jar).
 */
public class Neo4jGraphServiceProviderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetPluginHome() {
        Neo4jGraphServiceProvider.setPluginHomeForTesting(null);
    }

    private static Manifest manifest(String spiVersion) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (spiVersion != null) {
            attributes.putValue(Neo4jGraphServiceProvider.SPI_VERSION_ATTRIBUTE, spiVersion);
        }
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "4.4.0-test");
        attributes.putValue(Neo4jGraphServiceProvider.TARGET_VERSION_ATTRIBUTE, "4.4.0-ipedtest");
        return manifest;
    }

    /** Plugin home with the given jars in its neo4j/ folder (null: no neo4j/ folder at all). */
    private Path pluginHome(String... neo4jJars) throws IOException {
        Path home = tmp.newFolder(Neo4jGraphServiceProvider.DISPLAY_NAME).toPath();
        if (neo4jJars != null) {
            Path neo4j = Files.createDirectories(home.resolve(Neo4jChildLauncher.NEO4J_LIB_DIR));
            for (String jar : neo4jJars) {
                Files.createFile(neo4j.resolve(jar));
            }
        }
        return home;
    }

    @Test
    public void declared_spi_version_matches_core() throws Exception {
        Properties build = new Properties();
        try (InputStream in = getClass().getResourceAsStream("build.properties");
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            build.load(reader);
        }
        int declared = Integer.parseInt(build.getProperty("graph.spi.version"));
        assertEquals("graph.spi.version of iped-graph-neo4j/pom.xml must follow GraphServiceProvider.CURRENT_SPI_VERSION",
                GraphServiceProvider.CURRENT_SPI_VERSION, declared);

        Neo4jGraphServiceProvider provider = new Neo4jGraphServiceProvider(manifest(Integer.toString(declared)), null);
        assertEquals(GraphServiceProvider.CURRENT_SPI_VERSION, provider.getSpiVersion());
    }

    @Test
    public void versions_are_read_from_the_manifest() {
        Neo4jGraphServiceProvider provider = new Neo4jGraphServiceProvider(manifest("7"), null);
        assertEquals(7, provider.getSpiVersion());
        assertEquals("4.4.0-test", provider.getPluginVersion());
        assertEquals("4.4.0-ipedtest", provider.getBuiltForIpedVersion());
        assertEquals("iped-graph-neo4j", provider.getDisplayName());
    }

    @Test
    public void missing_spi_version_is_a_load_error() {
        Neo4jGraphServiceProvider provider = new Neo4jGraphServiceProvider(manifest(null), null);
        try {
            provider.getSpiVersion();
            fail("a manifest without " + Neo4jGraphServiceProvider.SPI_VERSION_ATTRIBUTE + " must be rejected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(Neo4jGraphServiceProvider.SPI_VERSION_ATTRIBUTE));
        }
    }

    @Test
    public void validate_accepts_a_complete_layout() throws Exception {
        Path home = pluginHome("iped-graph-server-4.4.0-SNAPSHOT.jar", "neo4j-kernel-5.26.0.jar", "antlr4-runtime-4.13.2.jar");
        new Neo4jGraphServiceProvider(manifest("1"), home).validate();
    }

    @Test
    public void validate_rejects_a_missing_neo4j_folder() throws Exception {
        Path home = pluginHome((String[]) null);
        try {
            new Neo4jGraphServiceProvider(manifest("1"), home).validate();
            fail("missing neo4j/ folder must be rejected");
        } catch (GraphPluginException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(home.resolve("neo4j").toString()));
        }
    }

    @Test
    public void validate_rejects_a_missing_graph_server_jar() throws Exception {
        Path home = pluginHome("neo4j-kernel-5.26.0.jar");
        try {
            new Neo4jGraphServiceProvider(manifest("1"), home).validate();
            fail("missing iped-graph-server jar must be rejected");
        } catch (GraphPluginException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("iped-graph-server-*.jar"));
            assertTrue(e.getMessage(), e.getMessage().contains(home.resolve("neo4j").toString()));
        }
    }

    @Test
    public void validate_rejects_missing_engine_jars() throws Exception {
        Path home = pluginHome("iped-graph-server-4.4.0-SNAPSHOT.jar");
        try {
            new Neo4jGraphServiceProvider(manifest("1"), home).validate();
            fail("missing neo4j engine jars must be rejected");
        } catch (GraphPluginException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("neo4j-*.jar"));
        }
    }

    @Test
    public void child_jvms_use_the_engine_of_the_plugin_home() throws Exception {
        Path home = pluginHome("iped-graph-server-4.4.0-SNAPSHOT.jar", "neo4j-kernel-5.26.0.jar");
        Neo4jGraphServiceProvider.setPluginHomeForTesting(home);

        assertEquals(home.resolve("neo4j").toFile(), Neo4jChildLauncher.getNeo4jLibDir());
        List<String> cmd = Neo4jChildLauncher.baseCommand();
        int cp = cmd.indexOf("-cp");
        assertTrue(cp > 0);
        assertEquals(home.resolve("neo4j").toString() + File.separator + "*", cmd.get(cp + 1));
        assertTrue(cmd.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
    }

    @Test
    public void child_jvms_fail_clearly_without_the_engine() throws Exception {
        Path home = pluginHome((String[]) null);
        Neo4jGraphServiceProvider.setPluginHomeForTesting(home);
        try {
            Neo4jChildLauncher.baseCommand();
            fail("a missing engine folder must be reported");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(home.resolve("neo4j").toString()));
        }
    }

}

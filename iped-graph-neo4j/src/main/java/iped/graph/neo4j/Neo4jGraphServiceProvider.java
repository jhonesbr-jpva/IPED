package iped.graph.neo4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import iped.engine.graph.GraphDatabaseGenerator;
import iped.engine.graph.GraphPluginException;
import iped.engine.graph.GraphService;
import iped.engine.graph.GraphServiceProvider;
import iped.io.URLUtil;

/**
 * Graph extension point implementation backed by Neo4j 5 (registered in
 * {@code META-INF/services/iped.engine.graph.GraphServiceProvider}).
 *
 * <p>
 * Versions come from the manifest of the plugin jar ({@code IPED-Graph-SPI-Version},
 * {@code Implementation-Version}, {@code IPED-Target-Version}). The plugin home, holding the isolated
 * Neo4j engine, is the {@code iped-graph-neo4j/} folder next to the plugin jar, which keeps the package
 * relocatable (installation plugin folder or the copy inside a case).
 * </p>
 */
public class Neo4jGraphServiceProvider implements GraphServiceProvider {

    /** Plugin name, also the name of the plugin home folder. */
    public static final String DISPLAY_NAME = "iped-graph-neo4j";

    static final String SPI_VERSION_ATTRIBUTE = "IPED-Graph-SPI-Version";
    static final String TARGET_VERSION_ATTRIBUTE = "IPED-Target-Version";
    static final String SERVER_JAR_PREFIX = "iped-graph-server-";
    static final String ENGINE_JAR_PREFIX = "neo4j-";

    private static volatile Path pluginHomeOverride;

    private Manifest manifest;
    private final Path home;

    public Neo4jGraphServiceProvider() {
        this(null, null);
    }

    /**
     * For tests: uses the given manifest and plugin home instead of the ones of the plugin jar.
     */
    Neo4jGraphServiceProvider(Manifest manifest, Path home) {
        this.manifest = manifest;
        this.home = home;
    }

    /**
     * @return the jar (or classes folder, when not packaged) this plugin was loaded from, or
     *         {@code null} if unknown.
     */
    static Path pluginLocation() {
        try {
            URL url = URLUtil.getURL(Neo4jGraphServiceProvider.class);
            return url != null ? Paths.get(url.toURI()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return the plugin home ({@code iped-graph-neo4j/} next to the plugin jar), or {@code null} if the
     *         plugin location is unknown.
     */
    public static Path pluginHome() {
        Path override = pluginHomeOverride;
        if (override != null) {
            return override;
        }
        Path location = pluginLocation();
        return location != null && location.getParent() != null ? location.getParent().resolve(DISPLAY_NAME) : null;
    }

    /**
     * For tests only: forces the plugin home returned by {@link #pluginHome()} (null restores the default).
     */
    static void setPluginHomeForTesting(Path home) {
        pluginHomeOverride = home;
    }

    private synchronized Manifest getManifest() {
        if (manifest == null) {
            manifest = readOwnManifest();
        }
        return manifest;
    }

    /** Reads the manifest of the plugin jar (UTF-8, as defined by the JAR specification). */
    private static Manifest readOwnManifest() {
        Path location = pluginLocation();
        if (location == null) {
            throw new IllegalStateException("Cannot locate the " + DISPLAY_NAME + " jar to read its manifest");
        }
        try {
            if (Files.isDirectory(location)) {
                // not packaged (e.g. running from an IDE): use a manifest file if the folder has one
                Path file = location.resolve(JarFile.MANIFEST_NAME);
                if (!Files.isRegularFile(file)) {
                    return new Manifest();
                }
                try (InputStream in = Files.newInputStream(file)) {
                    return new Manifest(in);
                }
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                Manifest jarManifest = jar.getManifest();
                return jarManifest != null ? jarManifest : new Manifest();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the manifest of " + location + ": " + e, e);
        }
    }

    private String getAttribute(String name, boolean required) {
        String value = getManifest().getMainAttributes().getValue(name);
        if (value == null && required) {
            throw new IllegalStateException("Missing " + name + " in the manifest of " + pluginLocation());
        }
        return value != null ? value.trim() : null;
    }

    @Override
    public int getSpiVersion() {
        String value = getAttribute(SPI_VERSION_ATTRIBUTE, true);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid " + SPI_VERSION_ATTRIBUTE + " '" + value + "' in the manifest of "
                    + pluginLocation());
        }
    }

    @Override
    public String getPluginVersion() {
        String value = getAttribute(Attributes.Name.IMPLEMENTATION_VERSION.toString(), false);
        return value != null ? value : "unknown";
    }

    @Override
    public String getBuiltForIpedVersion() {
        String value = getAttribute(TARGET_VERSION_ATTRIBUTE, false);
        return value != null ? value : "unknown";
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    /**
     * Requires the isolated Neo4j stack in the plugin home: {@code neo4j/iped-graph-server-*.jar} and at
     * least one {@code neo4j/neo4j-*.jar}.
     */
    @Override
    public void validate() throws GraphPluginException {
        Path pluginHome = home != null ? home : pluginHome();
        if (pluginHome == null) {
            throw new GraphPluginException("cannot locate the plugin home: unknown location of the " + DISPLAY_NAME
                    + " jar");
        }
        Path neo4jDir = pluginHome.resolve(Neo4jChildLauncher.NEO4J_LIB_DIR);
        if (!Files.isDirectory(neo4jDir)) {
            throw new GraphPluginException("missing folder " + neo4jDir
                    + " (extract the whole plugin zip into the plugin folder, next to the plugin jar)");
        }
        boolean serverFound = false;
        boolean engineFound = false;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(neo4jDir, "*.jar")) {
            for (Path jar : jars) {
                String name = jar.getFileName().toString();
                if (name.startsWith(SERVER_JAR_PREFIX)) {
                    serverFound = true;
                } else if (name.startsWith(ENGINE_JAR_PREFIX)) {
                    engineFound = true;
                }
            }
        } catch (IOException e) {
            throw new GraphPluginException("cannot list " + neo4jDir + ": " + e, e);
        }
        // glob patterns are not valid paths on every platform: build them as text
        if (!serverFound) {
            throw new GraphPluginException("missing " + neo4jDir + File.separator + SERVER_JAR_PREFIX + "*.jar");
        }
        if (!engineFound) {
            throw new GraphPluginException(
                    "missing Neo4j engine jars " + neo4jDir + File.separator + ENGINE_JAR_PREFIX + "*.jar");
        }
    }

    @Override
    public GraphService createGraphService() {
        return new Neo4jGraphService();
    }

    @Override
    public GraphDatabaseGenerator createDatabaseGenerator() {
        return new Neo4jDatabaseGenerator();
    }

}

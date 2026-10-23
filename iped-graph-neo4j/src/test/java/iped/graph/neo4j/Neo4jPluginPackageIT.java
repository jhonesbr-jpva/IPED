package iped.graph.neo4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test of the assembled plugin package (R-05): from the extracted zip, the registry of an
 * isolated IPED class loader must load the plugin, the Neo4j {@code GraphServer} child JVM must start
 * from the plugin home and the relocated Bolt driver must run a query.
 *
 * <p>
 * Gated: runs only with {@code IPED_GRAPH_PLUGIN_HOME} pointing to the folder where the plugin zip was
 * extracted ({@code mvn -pl iped-graph-neo4j verify -Pgraph-it}).
 * </p>
 */
public class Neo4jPluginPackageIT {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Entries of the test class path, expanding the manifest-only jar used by the test runner. */
    private static Set<File> testClassPath() throws Exception {
        Set<File> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File file = new File(entry);
            entries.add(file);
            if (file.isFile() && file.getName().endsWith(".jar")) {
                try (JarFile jar = new JarFile(file)) {
                    Manifest manifest = jar.getManifest();
                    String classPath = manifest != null ? manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) : null;
                    if (classPath != null) {
                        for (String url : classPath.trim().split("\\s+")) {
                            entries.add(Paths.get(new URL(file.toURI().toURL(), url).toURI()).toFile());
                        }
                    }
                }
            }
        }
        return entries;
    }

    /**
     * IPED side only: nothing built by this module (classes, test classes or the packaged jar, which
     * failsafe puts on the class path) and no unshaded copy of the driver dependencies.
     */
    private static boolean isExcluded(File entry, File moduleTarget) {
        if (entry.equals(moduleTarget) || moduleTarget.equals(entry.getParentFile())) {
            return true;
        }
        String name = entry.getName();
        return name.startsWith("neo4j-java-driver") || name.startsWith("netty-") || name.startsWith("reactor-core")
                || name.startsWith("reactive-streams");
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(method, types);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void graph_server_starts_and_answers_through_the_relocated_driver() throws Exception {
        String pluginFolderPath = System.getenv("IPED_GRAPH_PLUGIN_HOME");
        assumeTrue("IPED_GRAPH_PLUGIN_HOME not set: package integration test skipped", pluginFolderPath != null);

        File pluginFolder = new File(pluginFolderPath).getCanonicalFile();
        File[] pluginJars = pluginFolder.listFiles((dir, name) -> name.startsWith("iped-graph-neo4j-") && name.endsWith(".jar"));
        assertTrue("no plugin jar in " + pluginFolder, pluginJars != null && pluginJars.length == 1);
        assertTrue(new File(pluginFolder, "iped-graph-neo4j/neo4j").isDirectory());

        File testClasses = Paths.get(Neo4jPluginPackageIT.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toFile().getCanonicalFile();
        File moduleTarget = testClasses.getParentFile();
        List<URL> urls = new ArrayList<>();
        urls.add(pluginJars[0].toURI().toURL());
        for (File entry : testClassPath()) {
            File canonical = entry.getCanonicalFile();
            if (!isExcluded(canonical, moduleTarget)) {
                urls.add(canonical.toURI().toURL());
            }
        }

        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            // the unshaded driver dependencies must not be needed: only the relocated copies exist
            Class.forName("iped.graph.neo4j.shaded.io.netty.channel.Channel", false, isolated);
            Class.forName("iped.graph.neo4j.shaded.reactor.core.publisher.Mono", false, isolated);
            try {
                Class.forName("io.netty.channel.Channel", false, isolated);
                fail("unrelocated netty must not be visible");
            } catch (ClassNotFoundException e) {
                // expected
            }

            Class<?> registryClass = Class.forName("iped.engine.graph.GraphPluginRegistry", true, isolated);
            registryClass.getMethod("resetForTesting", ClassLoader.class).invoke(null, isolated);
            Object registry = registryClass.getMethod("get").invoke(null);
            String detail = (String) registryClass.getMethod("getDetail").invoke(registry);
            assertEquals(detail, Boolean.TRUE, registryClass.getMethod("isAvailable").invoke(registry));

            Object service = registryClass.getMethod("getGraphService").invoke(registry);
            assertEquals("iped.graph.neo4j.Neo4jGraphService", service.getClass().getName());
            assertEquals(pluginJars[0].getCanonicalFile(),
                    Paths.get(service.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toFile()
                            .getCanonicalFile());

            Class<?> listenerClass = Class.forName("iped.engine.graph.FreeQueryListener", true, isolated);
            List<Map<String, Object>> rows = new ArrayList<>();
            Object listener = Proxy.newProxyInstance(isolated, new Class<?>[] { listenerClass }, (proxy, method, args) -> {
                if ("resultFound".equals(method.getName())) {
                    rows.add((Map<String, Object>) args[0]);
                }
                return null;
            });

            File dbHome = tmp.newFolder("neo4j");
            invoke(service, "start", new Class<?>[] { File.class }, dbHome);
            try {
                invoke(service, "advancedSearch", new Class<?>[] { String.class, listenerClass }, "RETURN 1 AS one", listener);
            } finally {
                invoke(service, "stop", new Class<?>[0]);
            }
            assertEquals(1, rows.size());
            assertEquals(1L, ((Number) rows.get(0).get("one")).longValue());
        }
    }

}

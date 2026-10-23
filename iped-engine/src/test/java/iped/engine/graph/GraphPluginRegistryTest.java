package iped.engine.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.graph.testproviders.AvailableTestProvider;
import iped.engine.graph.testproviders.IncompleteTestProvider;

/**
 * Unit tests for {@link GraphPluginRegistry}: discovery states, rejection reasons and the single log
 * line emitted when the plugin is resolved.
 */
public class GraphPluginRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CapturingAppender appender;

    @Before
    public void attachAppender() {
        appender = new CapturingAppender();
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig loggerConfig = new LoggerConfig(GraphPluginRegistry.class.getName(), Level.ALL, false);
        loggerConfig.addAppender(appender, Level.ALL, null);
        context.getConfiguration().addLogger(GraphPluginRegistry.class.getName(), loggerConfig);
        context.updateLoggers();
    }

    @After
    public void detachAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().removeLogger(GraphPluginRegistry.class.getName());
        context.updateLoggers();
        appender.stop();
        GraphPluginRegistry.resetForTesting(null);
    }

    /** Class loader that sees the test classes plus the given folders/jars. */
    private ClassLoader classLoaderWith(URL... urls) {
        return new URLClassLoader(urls, GraphPluginRegistryTest.class.getClassLoader());
    }

    /** Test resource folder holding the service descriptor of a fixture provider. */
    private URL providerFolder(String fixture) {
        URL url = GraphPluginRegistryTest.class.getResource("providers/" + fixture + "/");
        assertNotNull("missing fixture " + fixture, url);
        return url;
    }

    /** Temporary jar holding the service descriptor of a fixture provider. */
    private URL providerJar(String fixture, String jarName) throws Exception {
        Path descriptor = Paths.get(providerFolder(fixture).toURI()).resolve(GraphPluginRegistry.SERVICE_RESOURCE);
        File jar = new File(tmp.getRoot(), jarName);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry(GraphPluginRegistry.SERVICE_RESOURCE));
            out.write(Files.readAllBytes(descriptor));
            out.closeEntry();
        }
        return jar.toURI().toURL();
    }

    private void assertSingleLog(Level level, String prefix) {
        assertEquals(1, appender.events.size());
        LogEvent event = appender.events.get(0);
        assertEquals(level, event.getLevel());
        String message = event.getMessage().getFormattedMessage();
        assertTrue(message, message.startsWith(prefix));
    }

    private void assertRejected(GraphPluginRegistry registry, GraphPluginRejection reason) {
        assertEquals(GraphPluginState.REJECTED, registry.getState());
        assertFalse(registry.isAvailable());
        assertEquals(reason, registry.getRejection().get());
        assertNull(registry.getPluginHome());
        try {
            registry.getProvider();
            fail("getProvider() must fail when the plugin is rejected");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void available_when_one_valid_and_compatible_provider() throws Exception {
        URL jar = providerJar("available", "test-graph-plugin-9.9.9.jar");
        GraphPluginRegistry.resetForTesting(classLoaderWith(jar));
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertEquals(GraphPluginState.AVAILABLE, registry.getState());
        assertTrue(registry.isAvailable());
        assertFalse(registry.getRejection().isPresent());
        assertTrue(registry.getProvider() instanceof AvailableTestProvider);
        assertTrue(registry.getDetail(),
                registry.getDetail().startsWith("test-graph-plugin 9.9.9-test (SPI v1, built for IPED 9.9.9) from "));
        // services are created once per JVM
        assertSame(registry.getGraphService(), registry.getGraphService());
        assertSame(registry.getDatabaseGenerator(), registry.getDatabaseGenerator());
        assertSame(registry.getGraphService(), GraphServiceFactoryImpl.getInstance().getGraphService());
        // the plugin home is the folder named after the plugin next to the jar of the provider
        assertNotNull(registry.getPluginJar());
        assertEquals(registry.getPluginJar().getParent().resolve("test-graph-plugin"), registry.getPluginHome());
        assertSingleLog(Level.INFO, "Graph plugin loaded: test-graph-plugin 9.9.9-test (SPI v1, built for IPED 9.9.9) from ");
    }

    @Test
    public void rejected_when_two_jars_provide_the_same_plugin() throws Exception {
        URL jar1 = providerJar("available", "test-graph-plugin-9.9.8.jar");
        URL jar2 = providerJar("available", "test-graph-plugin-9.9.9.jar");
        ClassLoader classLoader = classLoaderWith(jar1, jar2);

        // ServiceLoader alone would silently yield a single provider (same class name in both jars)
        int loaded = 0;
        for (GraphServiceProvider provider : ServiceLoader.load(GraphServiceProvider.class, classLoader)) {
            assertNotNull(provider);
            loaded++;
        }
        assertEquals(1, loaded);

        GraphPluginRegistry.resetForTesting(classLoader);
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertRejected(registry, GraphPluginRejection.MULTIPLE_COPIES);
        assertTrue(registry.getDetail(), registry.getDetail().contains("test-graph-plugin-9.9.8.jar"));
        assertTrue(registry.getDetail(), registry.getDetail().contains("test-graph-plugin-9.9.9.jar"));
        assertSingleLog(Level.ERROR, "Graph plugin rejected (multiple copies): ");
    }

    @Test
    public void rejected_when_spi_version_differs() {
        GraphPluginRegistry.resetForTesting(classLoaderWith(providerFolder("spimismatch")));
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertRejected(registry, GraphPluginRejection.SPI_MISMATCH);
        assertTrue(registry.getDetail(), registry.getDetail().startsWith("plugin SPI v"
                + (GraphServiceProvider.CURRENT_SPI_VERSION + 1) + " (test-graph-plugin 9.9.9-test), core SPI v"
                + GraphServiceProvider.CURRENT_SPI_VERSION + " (IPED "));
        assertSingleLog(Level.ERROR, "Graph plugin rejected (SPI mismatch): plugin SPI v");
    }

    @Test
    public void rejected_when_installation_is_incomplete() {
        GraphPluginRegistry.resetForTesting(classLoaderWith(providerFolder("incomplete")));
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertRejected(registry, GraphPluginRejection.INCOMPLETE);
        assertEquals(IncompleteTestProvider.DETAIL, registry.getDetail());
        assertSingleLog(Level.ERROR, "Graph plugin rejected (incomplete): " + IncompleteTestProvider.DETAIL);
    }

    @Test
    public void rejected_when_provider_cannot_be_loaded() {
        GraphPluginRegistry.resetForTesting(classLoaderWith(providerFolder("loaderror")));
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertRejected(registry, GraphPluginRejection.LOAD_ERROR);
        assertTrue(registry.getDetail(), registry.getDetail().contains("DoesNotExistTestProvider"));
        assertSingleLog(Level.ERROR, "Graph plugin rejected (load error): ");
    }

    @Test
    public void absent_when_no_provider_is_on_the_classpath() {
        GraphPluginRegistry.resetForTesting(classLoaderWith());
        GraphPluginRegistry registry = GraphPluginRegistry.get();

        assertEquals(GraphPluginState.ABSENT, registry.getState());
        assertFalse(registry.isAvailable());
        assertFalse(registry.getRejection().isPresent());
        assertNull(registry.getPluginJar());
        assertNull(registry.getPluginHome());
        assertTrue(registry.getDetail().startsWith("plugin folder: "));
        try {
            registry.getProvider();
            fail("getProvider() must fail without plugin");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            registry.getGraphService();
            fail("getGraphService() must fail without plugin");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            registry.getDatabaseGenerator();
            fail("getDatabaseGenerator() must fail without plugin");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void facade_fails_without_plugin() {
        GraphPluginRegistry.resetForTesting(classLoaderWith());
        try {
            GraphServiceFactoryImpl.getInstance().getGraphService();
            fail("the graph service facade must fail without plugin");
        } catch (IllegalStateException e) {
            assertEquals("Graph plugin not available", e.getMessage());
        }
    }

    @Test
    public void state_is_resolved_and_logged_once() {
        GraphPluginRegistry.resetForTesting(classLoaderWith());
        GraphPluginRegistry first = GraphPluginRegistry.get();
        GraphPluginRegistry second = GraphPluginRegistry.get();
        GraphPluginRegistry.get().isAvailable();

        assertSame(first, second);
        List<LogEvent> events = appender.events;
        assertEquals(1, events.size());
        assertEquals(Level.INFO, events.get(0).getLevel());
        assertTrue(events.get(0).getMessage().getFormattedMessage().startsWith("Graph plugin not installed (plugin folder: "));
    }

    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("graph-plugin-registry-test", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

}

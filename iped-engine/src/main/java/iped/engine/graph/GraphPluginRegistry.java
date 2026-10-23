package iped.engine.graph;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.Version;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.PluginConfig;
import iped.io.URLUtil;

/**
 * Discovers, validates and holds the graph plugin ({@link GraphServiceProvider}) of this JVM.
 *
 * <p>
 * The plugin is resolved once, on the first call to {@link #get()}. The entry points that use the
 * graph call it right after loading the configuration (processing and analysis UI), so the outcome
 * is logged exactly once, early: {@code INFO} when the plugin is loaded or not installed (the default
 * state of the standard distribution), {@code ERROR} when a plugin was found but rejected. Any state
 * other than {@link GraphPluginState#AVAILABLE} means "no graph": the graph processing step is
 * skipped and the graph UI is not created.
 * </p>
 *
 * <p>
 * Copies are counted through the service descriptors visible on the classpath before the
 * {@link ServiceLoader} is used, because the latter silently ignores providers whose class name was
 * already seen, so two versions of the same plugin would otherwise yield a single provider.
 * </p>
 */
public final class GraphPluginRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphPluginRegistry.class);

    /** Service descriptor through which graph plugins register their provider. */
    static final String SERVICE_RESOURCE = "META-INF/services/" + GraphServiceProvider.class.getName();

    private static GraphPluginRegistry instance;

    private final GraphPluginState state;
    private final GraphPluginRejection rejection;
    private final String detail;
    private final GraphServiceProvider provider;
    private final Path pluginJar;

    /** Extension point version declared by the plugin found, -1 if unknown. */
    private int pluginSpiVersion = -1;

    private GraphService graphService;
    private GraphDatabaseGenerator databaseGenerator;

    private GraphPluginRegistry(GraphPluginState state, GraphPluginRejection rejection, String detail,
            GraphServiceProvider provider, Path pluginJar) {
        this.state = state;
        this.rejection = rejection;
        this.detail = detail;
        this.provider = provider;
        this.pluginJar = pluginJar;
    }

    /**
     * @return the graph plugin registry of this JVM, resolving the plugin on the first call.
     */
    public static synchronized GraphPluginRegistry get() {
        if (instance == null) {
            instance = resolve(GraphPluginRegistry.class.getClassLoader());
        }
        return instance;
    }

    /**
     * For tests only: discards the resolved plugin. If {@code classLoader} is not null, the plugin is
     * resolved again (and logged) using it; otherwise the next {@link #get()} resolves it with the
     * default class loader.
     */
    public static synchronized void resetForTesting(ClassLoader classLoader) {
        instance = classLoader != null ? resolve(classLoader) : null;
    }

    private static GraphPluginRegistry resolve(ClassLoader classLoader) {
        GraphPluginRegistry registry = discover(classLoader);
        registry.logState();
        return registry;
    }

    private static GraphPluginRegistry discover(ClassLoader classLoader) {
        List<String> origins;
        try {
            origins = findServiceOrigins(classLoader);
        } catch (IOException e) {
            return rejected(GraphPluginRejection.LOAD_ERROR, "cannot list " + SERVICE_RESOURCE + ": " + e, null);
        }
        if (origins.isEmpty()) {
            return new GraphPluginRegistry(GraphPluginState.ABSENT, null, "plugin folder: " + describePluginFolder(),
                    null, null);
        }
        if (origins.size() > 1) {
            return rejected(GraphPluginRejection.MULTIPLE_COPIES, String.join(", ", origins), null);
        }
        Path origin = toPath(origins.get(0));

        List<GraphServiceProvider> providers = new ArrayList<>();
        try {
            for (GraphServiceProvider loaded : ServiceLoader.load(GraphServiceProvider.class, classLoader)) {
                providers.add(loaded);
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            return rejected(GraphPluginRejection.LOAD_ERROR, e.toString(), origin);
        }
        if (providers.isEmpty()) {
            return rejected(GraphPluginRejection.LOAD_ERROR, "no provider declared in " + origins.get(0), origin);
        }
        if (providers.size() > 1) {
            return rejected(GraphPluginRejection.MULTIPLE_COPIES,
                    providers.stream().map(p -> p.getClass().getName()).collect(Collectors.joining(", ")), origin);
        }

        GraphServiceProvider provider = providers.get(0);
        Path jar = locate(provider, origin);
        int spiVersion;
        String name, version, builtFor;
        try {
            spiVersion = provider.getSpiVersion();
            name = provider.getDisplayName();
            version = provider.getPluginVersion();
            builtFor = provider.getBuiltForIpedVersion();
        } catch (RuntimeException | LinkageError e) {
            return rejected(GraphPluginRejection.LOAD_ERROR, e.toString(), jar);
        }
        if (spiVersion != GraphServiceProvider.CURRENT_SPI_VERSION) {
            return rejected(GraphPluginRejection.SPI_MISMATCH,
                    "plugin SPI v" + spiVersion + " (" + name + " " + version + "), core SPI v"
                            + GraphServiceProvider.CURRENT_SPI_VERSION + " (IPED " + Version.APP_VERSION + ")",
                    jar).withPluginSpiVersion(spiVersion);
        }
        try {
            provider.validate();
        } catch (GraphPluginException | RuntimeException e) {
            return rejected(GraphPluginRejection.INCOMPLETE, String.valueOf(e.getMessage()), jar)
                    .withPluginSpiVersion(spiVersion);
        } catch (LinkageError e) {
            return rejected(GraphPluginRejection.LOAD_ERROR, e.toString(), jar).withPluginSpiVersion(spiVersion);
        }
        String loaded = name + " " + version + " (SPI v" + spiVersion + ", built for IPED " + builtFor + ") from "
                + jar;
        return new GraphPluginRegistry(GraphPluginState.AVAILABLE, null, loaded, provider, jar)
                .withPluginSpiVersion(spiVersion);
    }

    private GraphPluginRegistry withPluginSpiVersion(int version) {
        this.pluginSpiVersion = version;
        return this;
    }

    private static GraphPluginRegistry rejected(GraphPluginRejection rejection, String detail, Path jar) {
        return new GraphPluginRegistry(GraphPluginState.REJECTED, rejection, detail, null, jar);
    }

    /**
     * @return the distinct jars (or class folders) that contain the graph service descriptor.
     */
    private static List<String> findServiceOrigins(ClassLoader classLoader) throws IOException {
        Set<String> origins = new LinkedHashSet<>();
        Enumeration<URL> urls = classLoader.getResources(SERVICE_RESOURCE);
        while (urls.hasMoreElements()) {
            origins.add(originOf(urls.nextElement()));
        }
        return new ArrayList<>(origins);
    }

    private static String originOf(URL url) {
        String location = url.toString();
        if (location.startsWith("jar:")) {
            int separator = location.indexOf("!/");
            if (separator > 0) {
                location = location.substring("jar:".length(), separator);
            }
        } else if (location.endsWith(SERVICE_RESOURCE)) {
            location = location.substring(0, location.length() - SERVICE_RESOURCE.length());
        }
        Path path = toPath(location);
        return path != null ? path.toString() : location;
    }

    private static Path toPath(String location) {
        try {
            return location.startsWith("file:") ? Paths.get(new URI(location)) : Paths.get(location);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path locate(GraphServiceProvider provider, Path fallback) {
        try {
            URL url = URLUtil.getURL(provider.getClass());
            if (url != null) {
                return Paths.get(url.toURI());
            }
        } catch (Exception e) {
            // no usable code source, use the location of the service descriptor
        }
        return fallback;
    }

    private static String describePluginFolder() {
        try {
            ConfigurationManager configurationManager = ConfigurationManager.get();
            PluginConfig pluginConfig = configurationManager != null
                    ? configurationManager.findObject(PluginConfig.class)
                    : null;
            File pluginFolder = pluginConfig != null ? pluginConfig.getPluginFolder() : null;
            if (pluginFolder != null) {
                return pluginFolder.getPath();
            }
        } catch (RuntimeException e) {
            // plugin folder not configured
        }
        return "unknown";
    }

    private void logState() {
        switch (state) {
            case AVAILABLE:
                LOGGER.info("Graph plugin loaded: {}", detail);
                break;
            case ABSENT:
                LOGGER.info("Graph plugin not installed ({})", detail);
                break;
            default:
                LOGGER.error("Graph plugin rejected ({}): {}", describe(rejection), detail);
                break;
        }
    }

    private static String describe(GraphPluginRejection rejection) {
        switch (rejection) {
            case MULTIPLE_COPIES:
                return "multiple copies";
            case SPI_MISMATCH:
                return "SPI mismatch";
            case INCOMPLETE:
                return "incomplete";
            default:
                return "load error";
        }
    }

    public boolean isAvailable() {
        return state == GraphPluginState.AVAILABLE;
    }

    public GraphPluginState getState() {
        return state;
    }

    /**
     * @return the reason of the rejection, present only in the {@link GraphPluginState#REJECTED} state.
     */
    public Optional<GraphPluginRejection> getRejection() {
        return Optional.ofNullable(rejection);
    }

    /**
     * @return technical description of the state (plugin version and location, or why it was
     *         rejected), meant for logs.
     */
    public String getDetail() {
        return detail;
    }

    /**
     * @return the extension point version declared by the plugin found (also when it was rejected for
     *         {@link GraphPluginRejection#SPI_MISMATCH}), or -1 if unknown.
     */
    public int getPluginSpiVersion() {
        return pluginSpiVersion;
    }

    /**
     * @throws IllegalStateException
     *             if the plugin is not available
     */
    public GraphServiceProvider getProvider() {
        checkAvailable();
        return provider;
    }

    /**
     * @return the graph service of this JVM (single instance).
     * @throws IllegalStateException
     *             if the plugin is not available
     */
    public synchronized GraphService getGraphService() {
        checkAvailable();
        if (graphService == null) {
            graphService = provider.createGraphService();
        }
        return graphService;
    }

    /**
     * @return the graph database generator of this JVM (single instance).
     * @throws IllegalStateException
     *             if the plugin is not available
     */
    public synchronized GraphDatabaseGenerator getDatabaseGenerator() {
        checkAvailable();
        if (databaseGenerator == null) {
            databaseGenerator = provider.createDatabaseGenerator();
        }
        return databaseGenerator;
    }

    /**
     * @return the jar the plugin was loaded from, or {@code null} if unknown (e.g. no plugin installed).
     */
    public Path getPluginJar() {
        return pluginJar;
    }

    /**
     * @return the plugin home (folder named after the plugin next to its jar), or {@code null} if the
     *         plugin is not available.
     */
    public Path getPluginHome() {
        if (!isAvailable() || pluginJar == null || pluginJar.getParent() == null) {
            return null;
        }
        return pluginJar.getParent().resolve(provider.getDisplayName());
    }

    private void checkAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("Graph plugin not available (" + state + "): " + detail);
        }
    }

}

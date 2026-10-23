package iped.engine.graph;

/**
 * Extension point (SPI) through which an optional plugin provides the link analysis (graph)
 * services: graph queries ({@link GraphService}) and graph database generation
 * ({@link GraphDatabaseGenerator}).
 *
 * <p>
 * Implementations are discovered with {@link java.util.ServiceLoader}: the plugin jar, installed in
 * the plugin folder ({@code pluginFolder} in {@code LocalConfig.txt}, whose jars are already on the
 * classpath of the processing and analysis JVMs), registers its implementation in
 * {@code META-INF/services/iped.engine.graph.GraphServiceProvider}. {@link GraphPluginRegistry}
 * resolves and validates the provider once per JVM. The core never references the graph database
 * library itself: queries received as text (advanced search, link queries, post-generation
 * statements) are in the dialect of the current provider.
 * </p>
 *
 * <p>
 * Compatibility is decided only by the extension point version: a plugin is accepted if and only if
 * {@link #getSpiVersion()} equals {@link #CURRENT_SPI_VERSION}. Release numbers of the plugin and of
 * IPED are informative. {@code CURRENT_SPI_VERSION} must be incremented whenever this interface,
 * {@link GraphService}, {@link GraphDatabaseGenerator} or the graph model types change in an
 * incompatible way; additive changes with default methods keep the version.
 * </p>
 *
 * <p>
 * Plugin files that must stay off the main classpath (e.g. an isolated database engine) live in the
 * plugin home: a folder named after {@link #getDisplayName()} next to the plugin jar.
 * </p>
 */
public interface GraphServiceProvider {

    /** Version of the extension point implemented by this IPED core. */
    int CURRENT_SPI_VERSION = 1;

    /**
     * @return the extension point version implemented by the plugin.
     */
    int getSpiVersion();

    /**
     * @return the plugin release version (informative).
     */
    String getPluginVersion();

    /**
     * @return the IPED version the plugin was built for (informative).
     */
    String getBuiltForIpedVersion();

    /**
     * @return the plugin name, e.g. {@code iped-graph-neo4j}. Also the name of the plugin home folder.
     */
    String getDisplayName();

    /**
     * Checks the integrity of the installed plugin package.
     *
     * @throws GraphPluginException
     *             with a description of what is missing if the installation is incomplete
     */
    void validate() throws GraphPluginException;

    GraphService createGraphService();

    GraphDatabaseGenerator createDatabaseGenerator();

}

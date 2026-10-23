package iped.engine.graph;

/**
 * Result of the graph plugin discovery, resolved once per JVM by {@link GraphPluginRegistry}.
 */
public enum GraphPluginState {

    /** No graph plugin installed: the default state of the standard distribution. */
    ABSENT,

    /** A valid and compatible graph plugin was found and can be used. */
    AVAILABLE,

    /** A graph plugin was found but cannot be used, see {@link GraphPluginRejection}. */
    REJECTED

}

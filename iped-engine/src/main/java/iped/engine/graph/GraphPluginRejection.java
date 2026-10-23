package iped.engine.graph;

/**
 * Reason why a graph plugin found in the plugin folder was rejected.
 */
public enum GraphPluginRejection {

    /** More than one copy (jar) of the graph plugin was found: none is loaded. */
    MULTIPLE_COPIES,

    /** The plugin implements another version of the extension point (SPI) than this IPED. */
    SPI_MISMATCH,

    /** The plugin package is incomplete, e.g. its home folder was not installed. */
    INCOMPLETE,

    /** The plugin could not be loaded, e.g. missing classes or a corrupted jar. */
    LOAD_ERROR

}

package iped.engine.graph;

/**
 * Entry point used by the graph UI and the {@code Manager} to obtain the {@link GraphService}. The
 * service is provided by the graph plugin through {@link GraphPluginRegistry} (one instance per JVM).
 */
public class GraphServiceFactoryImpl implements GraphServiceFactory {

    private static GraphServiceFactory instance;

    /**
     * @throws IllegalStateException
     *             if the graph plugin is not available
     */
    @Override
    public GraphService getGraphService() {
        GraphPluginRegistry registry = GraphPluginRegistry.get();
        if (!registry.isAvailable()) {
            throw new IllegalStateException("Graph plugin not available");
        }
        return registry.getGraphService();
    }

    public synchronized static GraphServiceFactory getInstance() {
        if (instance == null) {
            instance = new GraphServiceFactoryImpl();
        }
        return instance;
    }

}

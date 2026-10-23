package iped.engine.graph.testproviders;

import java.lang.reflect.Proxy;

import iped.engine.graph.GraphDatabaseGenerator;
import iped.engine.graph.GraphPluginException;
import iped.engine.graph.GraphService;
import iped.engine.graph.GraphServiceProvider;

/**
 * Valid graph provider used by the registry tests: current SPI version, complete installation and
 * do-nothing services. Subclasses override one aspect to produce each rejection reason.
 */
public class BaseTestProvider implements GraphServiceProvider {

    @Override
    public int getSpiVersion() {
        return CURRENT_SPI_VERSION;
    }

    @Override
    public String getPluginVersion() {
        return "9.9.9-test";
    }

    @Override
    public String getBuiltForIpedVersion() {
        return "9.9.9";
    }

    @Override
    public String getDisplayName() {
        return "test-graph-plugin";
    }

    @Override
    public void validate() throws GraphPluginException {
    }

    @Override
    public GraphService createGraphService() {
        return (GraphService) Proxy.newProxyInstance(GraphService.class.getClassLoader(),
                new Class<?>[] { GraphService.class }, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "TestGraphService";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    @Override
    public GraphDatabaseGenerator createDatabaseGenerator() {
        return (listener, dbHome, csvFolders) -> false;
    }

}

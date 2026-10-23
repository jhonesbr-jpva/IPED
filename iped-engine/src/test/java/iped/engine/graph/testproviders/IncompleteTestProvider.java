package iped.engine.graph.testproviders;

import iped.engine.graph.GraphPluginException;

/**
 * Provider whose installation is incomplete: expected rejection INCOMPLETE.
 */
public class IncompleteTestProvider extends BaseTestProvider {

    public static final String DETAIL = "missing folder test-graph-plugin/engine";

    @Override
    public void validate() throws GraphPluginException {
        throw new GraphPluginException(DETAIL);
    }

}

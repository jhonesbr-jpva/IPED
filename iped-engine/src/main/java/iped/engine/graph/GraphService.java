package iped.engine.graph;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queries and lifecycle of the graph database of a case. Implemented by the graph plugin (see
 * {@link GraphServiceProvider}); methods that receive a query text expect it in the dialect of the
 * current provider.
 */
public interface GraphService {

    void start(File path) throws IOException;

    void stop() throws IOException;

    File getDbHome();

    void getEdges(String[] ids, EdgeQueryListener listener);

    void getNodes(Collection<Long> ids, NodeQueryListener listener);

    void getNeighbours(Long id, NodeEdgeQueryListener listener, int maxNodes);

    void getConnections(Set<Long> ids, EdgeQueryListener listener);

    void getPaths(Long source, Long target, int maxDistance, PathQueryListener listener);

    void search(String param, NodeQueryListener listener);

    void findConnections(Long id, ConnectionQueryListener listener);

    void findRelationships(Long id, ConnectionQueryListener listener);

    void getNeighboursWithLabels(Collection<String> labels, Long nodeId, NodeEdgeQueryListener listener, int maxNodes);

    void getNeighboursWithRelationships(Collection<String> relationships, Long nodeId, NodeEdgeQueryListener listener,
            int maxNodes);

    List<GraphNode> search(GraphLabel label, Map<String, Object> params);

    void search(GraphLabel label, Map<String, Object> params, NodeQueryListener listener, String... ordering);

    void findLabels(LabelQueryListener listener);

    void findLinks(ExportLinksQuery query, LinkQueryListener listener);

    /**
     * Runs a free query typed by the user and reports its columns and rows.
     *
     * @throws GraphQueryException
     *             if the query is invalid or fails
     */
    void advancedSearch(String string, FreeQueryListener listener);

    /**
     * Runs an arbitrary query (in the dialect of the current provider) expected to return a column
     * named {@code path} and reports each resulting path. Used by the {@code SearchLinksQuery} SPI.
     *
     * @throws GraphQueryException
     *             if the query is invalid or fails
     */
    void searchPaths(String query, Map<String, Object> params, PathQueryListener listener);

    void getRelationships(Collection<Long> ids, EdgeQueryListener listener);

    List<Long> getMoreConnectedNodes(int maxNodes);

    int deleteRelationshipsFromDatasource(String evidenceUUID);

}

package iped.engine.graph;

/**
 * Node (entity) of the link analysis graph, such as a person, a phone or an e-mail address.
 */
public interface GraphNode extends GraphEntity {

    Iterable<GraphLabel> getLabels();

    boolean hasLabel(GraphLabel label);

    /**
     * Total number of relationships of this node, captured when the node was queried (snapshot).
     * Used by the graph UI to size nodes and edges.
     *
     * @return the node degree, or 0 when unknown.
     */
    int getDegree();

}

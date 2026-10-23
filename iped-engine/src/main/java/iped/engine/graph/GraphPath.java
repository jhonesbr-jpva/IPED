package iped.engine.graph;

/**
 * Path of the link analysis graph. Iteration yields the alternating sequence of nodes and
 * relationships, starting and ending with a node (node, relationship, node, ...).
 */
public interface GraphPath extends Iterable<GraphEntity> {

    GraphNode startNode();

    GraphNode endNode();

    /**
     * @return the last relationship of the path, or {@code null} for a zero length path.
     */
    GraphRelationship lastRelationship();

    Iterable<GraphRelationship> relationships();

    Iterable<GraphNode> nodes();

    /**
     * @return the number of relationships in the path.
     */
    int length();

}

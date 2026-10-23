package iped.engine.graph;

/**
 * Relationship (link) between two nodes of the link analysis graph, such as an e-mail or a message
 * exchanged between two entities.
 */
public interface GraphRelationship extends GraphEntity {

    GraphRelationshipType getType();

    GraphNode getStartNode();

    GraphNode getEndNode();

    /**
     * @return the node at the other end of this relationship, given one of its ends.
     */
    GraphNode getOtherNode(GraphNode node);

    /**
     * @return the start and end nodes, in this order.
     */
    GraphNode[] getNodes();

}

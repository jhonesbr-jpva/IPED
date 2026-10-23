package iped.graph.neo4j;

import java.util.Map;

import iped.engine.graph.GraphNode;
import iped.engine.graph.GraphRelationship;
import iped.engine.graph.GraphRelationshipType;

/**
 * Read-only {@link GraphRelationship} snapshot backed by a Neo4j Bolt driver relationship. Start/end
 * nodes are carried only when the originating query returned them (queries that feed
 * {@code getStartNode()}/{@code getEndNode()} add {@code startNode(r)}/{@code endNode(r)}); otherwise
 * those accessors throw. See {@link BoltEntity}.
 */
public class BoltRelationship extends BoltEntity implements GraphRelationship {

    private final GraphRelationshipType type;
    private final GraphNode startNode;
    private final GraphNode endNode;

    public BoltRelationship(long id, String elementId, String type, GraphNode startNode, GraphNode endNode,
            Map<String, Object> properties) {
        super(id, elementId, properties);
        this.type = GraphRelationshipType.of(type);
        this.startNode = startNode;
        this.endNode = endNode;
    }

    static BoltRelationship from(org.neo4j.driver.types.Relationship rel, GraphNode startNode, GraphNode endNode) {
        return new BoltRelationship(rel.id(), rel.elementId(), rel.type(), startNode, endNode, rel.asMap());
    }

    @Override
    public GraphRelationshipType getType() {
        return type;
    }

    @Override
    public GraphNode getStartNode() {
        if (startNode == null) {
            throw unsupported();
        }
        return startNode;
    }

    @Override
    public GraphNode getEndNode() {
        if (endNode == null) {
            throw unsupported();
        }
        return endNode;
    }

    @Override
    public GraphNode getOtherNode(GraphNode node) {
        if (startNode != null && node.getId() == startNode.getId()) {
            return endNode;
        }
        return startNode;
    }

    @Override
    public GraphNode[] getNodes() {
        return new GraphNode[] { startNode, endNode };
    }

    @Override
    public String toString() {
        return "BoltRelationship[" + id + "]";
    }
}

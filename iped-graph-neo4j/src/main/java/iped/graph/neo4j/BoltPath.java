package iped.graph.neo4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import iped.engine.graph.GraphEntity;
import iped.engine.graph.GraphNode;
import iped.engine.graph.GraphPath;
import iped.engine.graph.GraphRelationship;

/**
 * Read-only {@link GraphPath} snapshot backed by a Neo4j Bolt driver path. Iteration yields the
 * alternating node/relationship sequence (node, rel, node, ...), and each relationship carries its
 * start/end node (resolved from the driver path segments). See {@link BoltEntity}.
 */
public class BoltPath implements GraphPath {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphRelationship> relationships = new ArrayList<>();
    private final List<GraphEntity> sequence = new ArrayList<>();

    static BoltPath from(org.neo4j.driver.types.Path path) {
        BoltPath result = new BoltPath();
        BoltNode start = BoltNode.from(path.start());
        result.nodes.add(start);
        result.sequence.add(start);
        for (org.neo4j.driver.types.Path.Segment segment : path) {
            BoltNode segStart = BoltNode.from(segment.start());
            BoltNode segEnd = BoltNode.from(segment.end());
            BoltRelationship rel = BoltRelationship.from(segment.relationship(), segStart, segEnd);
            result.relationships.add(rel);
            result.sequence.add(rel);
            result.nodes.add(segEnd);
            result.sequence.add(segEnd);
        }
        return result;
    }

    @Override
    public GraphNode startNode() {
        return nodes.get(0);
    }

    @Override
    public GraphNode endNode() {
        return nodes.get(nodes.size() - 1);
    }

    @Override
    public GraphRelationship lastRelationship() {
        return relationships.isEmpty() ? null : relationships.get(relationships.size() - 1);
    }

    @Override
    public Iterable<GraphRelationship> relationships() {
        return relationships;
    }

    @Override
    public Iterable<GraphNode> nodes() {
        return nodes;
    }

    @Override
    public int length() {
        return relationships.size();
    }

    @Override
    public Iterator<GraphEntity> iterator() {
        return sequence.iterator();
    }

    @Override
    public String toString() {
        return "BoltPath[length=" + length() + "]";
    }
}

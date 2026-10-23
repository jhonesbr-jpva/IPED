package iped.graph.neo4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import iped.engine.graph.GraphLabel;
import iped.engine.graph.GraphNode;

/**
 * Read-only {@link GraphNode} snapshot backed by a Neo4j Bolt driver node. See {@link BoltEntity}.
 */
public class BoltNode extends BoltEntity implements GraphNode {

    private final List<GraphLabel> labels;

    /** Node degree captured by the producing query (COUNT { (n)--() }), or -1 when not requested. */
    private final int degree;

    public BoltNode(long id, String elementId, Iterable<String> labelNames, Map<String, Object> properties) {
        this(id, elementId, labelNames, properties, -1);
    }

    public BoltNode(long id, String elementId, Iterable<String> labelNames, Map<String, Object> properties,
            int degree) {
        super(id, elementId, properties);
        this.degree = degree;
        this.labels = new ArrayList<>();
        if (labelNames != null) {
            for (String name : labelNames) {
                labels.add(GraphLabel.of(name));
            }
        }
    }

    static BoltNode from(org.neo4j.driver.types.Node node) {
        return new BoltNode(node.id(), node.elementId(), node.labels(), node.asMap());
    }

    static BoltNode from(org.neo4j.driver.types.Node node, int degree) {
        return new BoltNode(node.id(), node.elementId(), node.labels(), node.asMap(), degree);
    }

    @Override
    public Iterable<GraphLabel> getLabels() {
        return labels;
    }

    @Override
    public boolean hasLabel(GraphLabel label) {
        return labels.contains(label);
    }

    /**
     * Degree is read by the graph UI (GraphModel) only to size nodes and edges. It is served from the
     * snapshot value carried by the producing query; when that query did not request it we report 0,
     * so rendering falls back to the base node size instead of failing.
     */
    @Override
    public int getDegree() {
        return degree < 0 ? 0 : degree;
    }

    @Override
    public String toString() {
        return "BoltNode[" + id + "]";
    }
}

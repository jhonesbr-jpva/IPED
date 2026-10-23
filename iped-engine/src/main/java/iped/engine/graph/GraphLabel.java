package iped.engine.graph;

import java.util.Objects;

/**
 * Label of a node in the link analysis graph.
 *
 * <p>
 * Provider-neutral replacement for the Neo4j {@code Label} type, so the core can model entities and
 * write the graph CSV files without depending on any graph database library. Labels are immutable
 * and compared by name.
 * </p>
 */
public final class GraphLabel {

    private final String name;

    private GraphLabel(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static GraphLabel of(String name) {
        return new GraphLabel(name);
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GraphLabel)) {
            return false;
        }
        return name.equals(((GraphLabel) obj).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

}

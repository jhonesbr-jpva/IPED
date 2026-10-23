package iped.engine.graph;

import java.util.Objects;

/**
 * Type of a relationship in the link analysis graph (e.g. {@code email}, {@code message}).
 *
 * <p>
 * Provider-neutral replacement for the Neo4j {@code RelationshipType}. Immutable and compared by
 * name.
 * </p>
 */
public final class GraphRelationshipType {

    private final String name;

    private GraphRelationshipType(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static GraphRelationshipType of(String name) {
        return new GraphRelationshipType(name);
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GraphRelationshipType)) {
            return false;
        }
        return name.equals(((GraphRelationshipType) obj).name);
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

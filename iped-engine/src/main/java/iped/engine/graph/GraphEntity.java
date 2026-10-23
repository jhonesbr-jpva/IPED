package iped.engine.graph;

import java.util.Map;

/**
 * Read-only property container shared by graph nodes and relationships returned by a
 * {@link GraphService}.
 *
 * <p>
 * Entities are detached snapshots of the graph database: they carry the properties loaded by the
 * query that produced them and cannot be changed or traversed. Unlike the Neo4j {@code Entity},
 * reading an absent property never throws.
 * </p>
 */
public interface GraphEntity {

    /**
     * @return the internal numeric id assigned by the graph database.
     */
    long getId();

    /**
     * @return the element id assigned by the graph database.
     */
    String getElementId();

    boolean hasProperty(String key);

    /**
     * @return the property value, or {@code null} if the entity has no such property.
     */
    Object getProperty(String key);

    /**
     * @return the property value, or {@code defaultValue} if the entity has no such property.
     */
    Object getProperty(String key, Object defaultValue);

    Iterable<String> getPropertyKeys();

    /**
     * @return a copy of all properties of this entity.
     */
    Map<String, Object> getAllProperties();

}

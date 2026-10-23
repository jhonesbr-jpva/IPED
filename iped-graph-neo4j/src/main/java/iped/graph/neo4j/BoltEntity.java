package iped.graph.neo4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import iped.engine.graph.GraphEntity;

/**
 * Base for the detached, read-only graph entities returned by {@link Neo4jGraphService} over Bolt:
 * snapshots of the properties loaded by the producing query. They implement the provider-neutral
 * graph model of iped-engine, so the graph UI does not depend on Neo4j types.
 */
abstract class BoltEntity implements GraphEntity {

    protected final long id;
    protected final String elementId;
    protected final Map<String, Object> properties;

    BoltEntity(long id, String elementId, Map<String, Object> properties) {
        this.id = id;
        this.elementId = elementId;
        this.properties = properties != null ? properties : Collections.emptyMap();
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getElementId() {
        return elementId;
    }

    @Override
    public boolean hasProperty(String key) {
        return properties.containsKey(key);
    }

    @Override
    public Object getProperty(String key) {
        return properties.get(key);
    }

    @Override
    public Object getProperty(String key, Object defaultValue) {
        return properties.containsKey(key) ? properties.get(key) : defaultValue;
    }

    @Override
    public Iterable<String> getPropertyKeys() {
        return properties.keySet();
    }

    @Override
    public Map<String, Object> getAllProperties() {
        return new HashMap<>(properties);
    }

    static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Graph entities read over Bolt are detached read-only snapshots; "
                + "data not returned by the producing query is not available.");
    }
}

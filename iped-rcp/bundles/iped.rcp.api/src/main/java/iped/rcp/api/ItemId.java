package iped.rcp.api;

/**
 * Identity of an analysis item inside the open case session: the id of the
 * atomic data source (case) plus the item id within that source. Mirrors the
 * engine's item identity without exposing engine types to extensions.
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public record ItemId(int sourceId, int id) implements Comparable<ItemId> {

    @Override
    public int compareTo(ItemId other) {
        int cmp = Integer.compare(sourceId, other.sourceId);
        return cmp != 0 ? cmp : Integer.compare(id, other.id);
    }
}

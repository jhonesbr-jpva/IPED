package iped.rcp.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read and write access to case bookmarks — the ONLY write path available to
 * extensions (FR-004/FR-005). Writes go through the engine's current
 * persistence (same on-disk format and locking discipline as the rest of the
 * IPED tooling), so bookmarks created here remain readable by the report
 * generator, multicase merges and older tools (SC-009).
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public interface IBookmarkService {

    /** @return current bookmark names, sorted. */
    List<String> getBookmarkNames();

    /** Creates an empty bookmark. No-op if it already exists. */
    void createBookmark(String name);

    /** Renames a bookmark, preserving members, color and comment. */
    void renameBookmark(String oldName, String newName);

    /** Deletes a bookmark (its items are untouched). */
    void deleteBookmark(String name);

    /** Adds items to a bookmark, creating it if absent. */
    void addToBookmark(String name, Collection<ItemId> items);

    /** Removes items from a bookmark. */
    void removeFromBookmark(String name, Collection<ItemId> items);

    /** @return ids of the items in the bookmark (may be very large; iterate lazily). */
    Collection<ItemId> getBookmarkItems(String name);

    /** @return the bookmark comment, if any. */
    Optional<String> getComment(String name);

    void setComment(String name, String comment);

    /** @return the bookmark color as 0xRRGGBB, if set. */
    Optional<Integer> getColor(String name);

    void setColor(String name, int rgb);
}

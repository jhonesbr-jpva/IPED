package iped.rcp.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Lazy access to item names, metadata and raw content. All I/O performed
 * through this service is the caller's responsibility to keep off the UI
 * thread; streams use explicit charsets internally (Principle IV).
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public interface IItemAccessService {

    /** @return the display name of the item, or empty if the id is unknown. */
    Optional<String> getName(ItemId item);

    /**
     * @return an immutable snapshot of the item's metadata (basic and extra
     *         properties, multi-valued entries as lists); empty map if the id
     *         is unknown
     */
    Map<String, Object> getMetadata(ItemId item);

    /**
     * Opens the raw content of the item for reading. Caller closes the
     * stream.
     *
     * @throws IOException if the content cannot be read
     */
    InputStream openContent(ItemId item) throws IOException;

    /** @return the content size in bytes, or -1 when unknown. */
    long getSize(ItemId item);
}

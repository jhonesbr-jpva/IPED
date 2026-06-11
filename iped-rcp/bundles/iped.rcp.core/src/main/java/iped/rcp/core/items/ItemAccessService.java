package iped.rcp.core.items;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.rcp.api.IItemAccessService;
import iped.rcp.api.ItemId;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Lazy item access over the open session (T008/T020 supporting service,
 * contract ui-extension-api): resolves {@link ItemId} to the engine item and
 * exposes name/metadata/content without leaking engine types through the
 * provisional API. I/O stays the caller's responsibility to keep off the UI
 * thread.
 */
@Component(service = { IItemAccessService.class, ItemAccessService.class })
public class ItemAccessService implements IItemAccessService {

    @Reference
    private ICaseSessionManager sessionManager;

    /** DS constructor. */
    public ItemAccessService() {
    }

    /** Headless harness constructor (no OSGi injection). */
    public ItemAccessService(ICaseSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public Optional<String> getName(ItemId item) {
        IItem resolved = resolve(item);
        return resolved == null ? Optional.empty() : Optional.ofNullable(resolved.getName());
    }

    @Override
    public Map<String, Object> getMetadata(ItemId item) {
        IItem resolved = resolve(item);
        if (resolved == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        org.apache.tika.metadata.Metadata tikaMetadata = resolved.getMetadata();
        if (tikaMetadata != null) {
            for (String name : tikaMetadata.names()) {
                String[] values = tikaMetadata.getValues(name);
                metadata.put(name, values.length == 1 ? values[0] : List.of(values));
            }
        }
        for (Map.Entry<String, Object> extra : resolved.getExtraAttributeMap().entrySet()) {
            metadata.putIfAbsent(extra.getKey(), normalize(extra.getValue()));
        }
        return metadata;
    }

    @Override
    public InputStream openContent(ItemId item) throws IOException {
        IItem resolved = resolve(item);
        if (resolved == null) {
            throw new IOException("Unknown item: " + item);
        }
        return resolved.getBufferedInputStream();
    }

    @Override
    public long getSize(ItemId item) {
        IItem resolved = resolve(item);
        Long size = resolved == null ? null : resolved.getLength();
        return size == null ? -1 : size;
    }

    /**
     * Engine item for internal consumers (viewers bridge — NOT part of the
     * extension API surface).
     */
    public IItem resolve(ItemId item) {
        CaseSession session = sessionManager != null ? sessionManager.getSession() : null;
        if (session == null) {
            return null;
        }
        IPEDMultiSource source = session.getSource();
        return source.getItemByItemId(new iped.engine.data.ItemId(item.sourceId(), item.id()));
    }

    private static Object normalize(Object value) {
        if (value != null && value.getClass().isArray()) {
            return Arrays.asList((Object[]) value);
        }
        return value;
    }
}

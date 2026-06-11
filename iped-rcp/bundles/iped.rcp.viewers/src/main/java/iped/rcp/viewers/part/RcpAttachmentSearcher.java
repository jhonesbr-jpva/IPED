package iped.rcp.viewers.part;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.QueryBuilder;
import iped.properties.BasicProps;
import iped.rcp.core.session.ICaseSessionManager;
import iped.viewers.api.AttachmentSearcher;

/**
 * {@link AttachmentSearcher} of the RCP viewer host (task T020): resolves
 * chat/e-mail attachment references through the open case session — the same
 * engine search path as the legacy {@code AttachmentSearcherImpl}, without
 * the {@code App} singleton.
 */
public class RcpAttachmentSearcher implements AttachmentSearcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RcpAttachmentSearcher.class);

    private final ICaseSessionManager sessionManager;

    public RcpAttachmentSearcher(ICaseSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public File getTmpFile(String luceneQuery) {
        IItem item = getItem(luceneQuery);
        if (item == null) {
            return null;
        }
        try {
            return item.getTempFile();
        } catch (IOException e) {
            LOGGER.warn("Error materializing attachment for query {}", luceneQuery, e);
            return null;
        }
    }

    @Override
    public IItem getItem(String luceneQuery) {
        List<IItem> items = getItems(luceneQuery);
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    public List<IItem> getItems(String luceneQuery) {
        List<IItem> items = new ArrayList<>();
        IPEDMultiSource source = source();
        if (source == null) {
            return items;
        }
        try {
            MultiSearchResult result = new IPEDSearcher(source, luceneQuery).multiSearch();
            for (IItemId itemId : result.getIterator()) {
                IItem item = source.getItemByItemId(itemId);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Attachment query failed: {}", luceneQuery, e);
        }
        return items;
    }

    @Override
    public void checkItem(String luceneQuery, boolean checked) {
        IPEDMultiSource source = source();
        if (source == null) {
            return;
        }
        try {
            MultiSearchResult result = new IPEDSearcher(source, luceneQuery).multiSearch();
            for (IItemId itemId : result.getIterator()) {
                source.getMultiBookmarks().setChecked(checked, itemId);
            }
            source.getMultiBookmarks().saveState();
        } catch (Exception e) {
            LOGGER.warn("Error checking items of query {}", luceneQuery, e);
        }
    }

    @Override
    public boolean isChecked(String hash) {
        IPEDMultiSource source = source();
        if (source == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            MultiSearchResult result = new IPEDSearcher(source,
                    BasicProps.HASH + ":" + hash.toLowerCase()).multiSearch();
            for (IItemId itemId : result.getIterator()) {
                if (source.getMultiBookmarks().isChecked(itemId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error reading checked state of hash {}", hash, e);
        }
        return false;
    }

    @Override
    public String getHash(IItemId itemId) {
        IPEDMultiSource source = source();
        IItem item = source == null ? null : source.getItemByItemId(itemId);
        return item == null ? null : item.getHash();
    }

    @Override
    public void updateSelectionCache() {
        // no selection cache in the RCP host: checked state is read straight
        // from the bookmarks model
    }

    @Override
    public String escapeQuery(String query) {
        return QueryBuilder.escape(query);
    }

    private IPEDMultiSource source() {
        return sessionManager.getSession() == null ? null : sessionManager.getSession().getSource();
    }
}

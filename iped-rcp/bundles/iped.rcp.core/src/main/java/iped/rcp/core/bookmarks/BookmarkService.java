package iped.rcp.core.bookmarks;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import iped.data.IItemId;
import iped.data.IMultiBookmarks;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.rcp.api.IBookmarkService;
import iped.rcp.api.ItemId;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.events.IUiEventPublisher;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.session.SessionReloadListener;
import iped.search.IMultiSearchResult;

/**
 * Bookmark service over the engine's {@link IMultiBookmarks} (tasks
 * T021/T064, FR-005/FR-014, SC-009): the ONLY write path of the UI layer.
 * Persistence keeps the current on-disk format and discipline — every
 * mutation is followed by the engine's asynchronous state save
 * ({@code SaveStateThread}), exactly like the current UI; tests and shutdown
 * use {@link #flush()} for a synchronous save.
 *
 * <p>
 * Every mutation publishes {@link UiEventTopics#BOOKMARKS_CHANGED} with the
 * bookmark name (or {@code null} for bulk changes such as checked-state
 * edits).
 */
@Component(service = { IBookmarkService.class, BookmarkService.class })
public class BookmarkService implements IBookmarkService {

    @Reference
    private ICaseSessionManager sessionManager;

    /** Optional so the service also runs headless (parity harness). */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.STATIC)
    private IUiEventPublisher eventPublisher;

    private Runnable reloadUnsubscriber;

    /**
     * Near-live integration (T063): the reloaded source loads the bookmark
     * state fresh from disk, so any pending asynchronous save must land
     * BEFORE the new source opens, or recent edits would be silently lost.
     */
    private final SessionReloadListener reloadListener = new SessionReloadListener() {
        @Override
        public void beforeReload() {
            try {
                flush();
            } catch (RuntimeException e) {
                // read-only media or already-closing session: nothing to save
            }
        }
    };

    /** DS constructor. */
    public BookmarkService() {
    }

    /** Headless harness constructor (no OSGi injection). */
    public BookmarkService(ICaseSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        activate();
    }

    @Activate
    void activate() {
        if (sessionManager != null) {
            reloadUnsubscriber = sessionManager.addReloadListener(reloadListener);
        }
    }

    @Deactivate
    void deactivate() {
        if (reloadUnsubscriber != null) {
            reloadUnsubscriber.run();
            reloadUnsubscriber = null;
        }
    }

    @Override
    public List<String> getBookmarkNames() {
        return new ArrayList<>(new TreeSet<>(bookmarks().getBookmarkSet()));
    }

    @Override
    public void createBookmark(String name) {
        if (!bookmarks().getBookmarkSet().contains(name)) {
            bookmarks().newBookmark(name);
            save(name);
        }
    }

    @Override
    public void renameBookmark(String oldName, String newName) {
        bookmarks().renameBookmark(oldName, newName);
        save(newName);
    }

    @Override
    public void deleteBookmark(String name) {
        bookmarks().delBookmark(name);
        save(name);
    }

    @Override
    public void addToBookmark(String name, Collection<ItemId> items) {
        if (!bookmarks().getBookmarkSet().contains(name)) {
            bookmarks().newBookmark(name);
        }
        bookmarks().addBookmark(toEngineIds(items), name);
        save(name);
    }

    @Override
    public void removeFromBookmark(String name, Collection<ItemId> items) {
        bookmarks().removeBookmark(toEngineIds(items), name);
        save(name);
    }

    @Override
    public Collection<ItemId> getBookmarkItems(String name) {
        try {
            // same listing path as the current UI: filter a match-all result
            // through the bookmark (works uniformly for multicase)
            MultiSearchResult all = new IPEDSearcher(source(), "*").multiSearch();
            IMultiSearchResult members = bookmarks().filterBookmarks(all, Set.of(name));
            List<ItemId> ids = new ArrayList<>(members.getLength());
            for (IItemId member : members.getIterator()) {
                ids.add(new ItemId(member.getSourceId(), member.getId()));
            }
            return ids;
        } catch (IOException e) {
            throw new IllegalStateException("Error listing bookmark items: " + name, e);
        }
    }

    @Override
    public Optional<String> getComment(String name) {
        String comment = bookmarks().getBookmarkComment(name);
        return comment == null || comment.isEmpty() ? Optional.empty() : Optional.of(comment);
    }

    @Override
    public void setComment(String name, String comment) {
        bookmarks().setBookmarkComment(name, comment);
        save(name);
    }

    @Override
    public Optional<Integer> getColor(String name) {
        Color color = bookmarks().getBookmarkColor(name);
        return color == null ? Optional.empty() : Optional.of(color.getRGB() & 0xFFFFFF);
    }

    @Override
    public void setColor(String name, int rgb) {
        bookmarks().setBookmarkColor(name, new Color(rgb & 0xFFFFFF));
        save(name);
    }

    // ------------------------------------------------------------------
    // Internal surface consumed by the workbench parts (not extension API)
    // ------------------------------------------------------------------

    /** Item count of a bookmark, without materializing its members. */
    public int getBookmarkCount(String name) {
        return bookmarks().getBookmarkCount(name);
    }

    /** Bookmark names of one item (results table bookmark column). */
    public List<String> getBookmarksOf(ItemId item) {
        return bookmarks().getBookmarkList(toEngineId(item));
    }

    /** Checked (checkbox) state of an item — current selection semantics. */
    public boolean isChecked(ItemId item) {
        return bookmarks().isChecked(toEngineId(item));
    }

    /** Sets the checked state of an item and schedules the async save. */
    public void setChecked(ItemId item, boolean checked) {
        bookmarks().setChecked(checked, toEngineId(item));
        save(null);
    }

    /**
     * Bulk checked-state update with a single save/event at the end — the
     * legacy multi-setting discipline of the check-with-related shortcuts
     * (T046, FR-021).
     */
    public void setCheckedEngine(Collection<IItemId> engineIds, boolean checked) {
        IMultiBookmarks marks = bookmarks();
        for (IItemId id : engineIds) {
            marks.setChecked(checked, id);
        }
        save(null);
    }

    /** Total of checked items in the session. */
    public int getTotalChecked() {
        return bookmarks().getTotalChecked();
    }

    /** Synchronous state save (shutdown, tests). */
    public void flush() {
        bookmarks().saveState(true);
    }

    private IMultiBookmarks bookmarks() {
        return source().getMultiBookmarks();
    }

    private IPEDMultiSource source() {
        CaseSession session = sessionManager != null ? sessionManager.getSession() : null;
        if (session == null) {
            throw new IllegalStateException("No case session is open");
        }
        return session.getSource();
    }

    private Set<IItemId> toEngineIds(Collection<ItemId> items) {
        Set<IItemId> ids = new HashSet<>(items.size() * 2);
        for (ItemId item : items) {
            ids.add(toEngineId(item));
        }
        return ids;
    }

    private static IItemId toEngineId(ItemId item) {
        return new iped.engine.data.ItemId(item.sourceId(), item.id());
    }

    /** Async save (current SaveStateThread discipline) + change event. */
    private void save(String bookmarkName) {
        bookmarks().saveState();
        IUiEventPublisher publisher = eventPublisher;
        if (publisher != null) {
            publisher.post(UiEventTopics.BOOKMARKS_CHANGED, bookmarkName);
        }
    }
}

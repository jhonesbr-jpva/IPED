package iped.rcp.core.filters;

import java.util.LinkedHashSet;
import java.util.Set;

import iped.engine.data.IPEDMultiSource;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IResultSetFilter;

/**
 * Bookmark selection filters (task T029, AR-03, legacy
 * {@code BookmarksTreeListener}): keep items belonging to any of the
 * selected bookmarks and/or items with no bookmarks. Uses the engine's
 * {@code IMultiBookmarks} filtering — the union semantics of the legacy
 * bitmap path ({@code filterBookmarksOrNoBookmarks}).
 */
public final class BookmarkFilters {

    private BookmarkFilters() {
    }

    /**
     * @param names              selected bookmark names (may be empty when
     *                           only "no bookmarks" is selected)
     * @param includeNoBookmarks true when the "no bookmarks" node is selected
     */
    public static IResultSetFilter selection(IPEDMultiSource source, Set<String> names, boolean includeNoBookmarks) {
        Set<String> selected = new LinkedHashSet<>(names);
        return new IResultSetFilter() {
            @Override
            public IMultiSearchResult filterResult(IMultiSearchResult src) {
                if (includeNoBookmarks && selected.isEmpty()) {
                    return source.getMultiBookmarks().filterNoBookmarks(src);
                }
                if (includeNoBookmarks) {
                    return source.getMultiBookmarks().filterBookmarksOrNoBookmarks(src, selected);
                }
                return source.getMultiBookmarks().filterBookmarks(src, selected);
            }

            @Override
            public String toString() {
                return includeNoBookmarks && selected.isEmpty() ? "[no bookmarks]" : selected.toString();
            }
        };
    }
}

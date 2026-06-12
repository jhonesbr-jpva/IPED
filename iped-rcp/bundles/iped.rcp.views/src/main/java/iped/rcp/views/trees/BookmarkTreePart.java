package iped.rcp.views.trees;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import iped.rcp.api.UiEventTopics;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.filters.BookmarkFilters;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.views.SearchBarPart;
import iped.rcp.views.SearchJobs;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Bookmarks tree part (task T029, FR-009/AR-03): root + "[No Bookmarks]" +
 * the bookmark names sorted with a collator (legacy
 * {@code BookmarksTreeModel}); labels carry the item count. Multi-selection
 * filters the result through the engine bookmark unions (legacy
 * {@code BookmarksTreeListener} bitmap-path semantics); root selection
 * clears. The tree follows {@code bookmarks/CHANGED} events.
 */
public class BookmarkTreePart {

    /** SWTBot widget id. */
    public static final String TREE_WIDGET_ID = "iped.rcp.views.bookmarks.tree";

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private BookmarkService bookmarkService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    private TreeViewer viewer;
    private boolean filterApplied;

    private final Object root = new Object() {
        @Override
        public String toString() {
            return Messages.getString("BookmarksTreeModel.RootName");
        }
    };
    private final Object noBookmarks = new Object() {
        @Override
        public String toString() {
            return Messages.getString("BookmarksTreeModel.NoBookmarks");
        }
    };

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        viewer.getTree().setData(SearchBarPart.SWTBOT_KEY, TREE_WIDGET_ID);
        viewer.setContentProvider(new BookmarkContentProvider());
        viewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof String name) {
                    try {
                        return name + " (" + bookmarkService.getBookmarkCount(name) + ")";
                    } catch (RuntimeException e) {
                        return name;
                    }
                }
                return String.valueOf(element);
            }
        });
        viewer.addSelectionChangedListener(event -> onSelectionChanged());

        if (sessionManager.getSession() != null) {
            // input wraps the root element (it must not BE the input,
            // see CategoryTreePart.setInput)
            viewer.setInput(new Object[] { root });
            viewer.expandToLevel(2);
        }
    }

    private void onSelectionChanged() {
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        boolean rootSelected = false;
        boolean noBookmarksSelected = false;
        for (Object element : viewer.getStructuredSelection().toList()) {
            if (element == root) {
                rootSelected = true;
            } else if (element == noBookmarks) {
                noBookmarksSelected = true;
            } else if (element instanceof String name) {
                names.add(name);
            }
        }

        boolean active = !rootSelected && (!names.isEmpty() || noBookmarksSelected);
        if (!active) {
            if (filterApplied) {
                filterApplied = false;
                filterState.clear(FilterStateService.BOOKMARKS);
                SearchJobs.refresh(searchService);
            }
            return;
        }
        filterApplied = true;
        String label = noBookmarksSelected && names.isEmpty() ? noBookmarks.toString() : names.toString();
        filterState.setResultSetFilter(FilterStateService.BOOKMARKS,
                BookmarkFilters.selection(session.getSource(), names, noBookmarksSelected), label);
        SearchJobs.refresh(searchService);
    }

    @Inject
    @Optional
    public void onBookmarksChanged(@UIEventTopic(UiEventTopics.BOOKMARKS_CHANGED) Object payload) {
        refreshTree();
    }

    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        uiSync.asyncExec(() -> {
            if (viewer != null && !viewer.getTree().isDisposed() && sessionManager.getSession() != null) {
                viewer.setInput(new Object[] { root });
                viewer.expandToLevel(2);
            }
        });
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        uiSync.asyncExec(() -> {
            if (viewer != null && !viewer.getTree().isDisposed()) {
                filterApplied = false;
                viewer.setInput(null);
            }
        });
    }

    private void refreshTree() {
        uiSync.asyncExec(() -> {
            if (viewer != null && !viewer.getTree().isDisposed() && viewer.getInput() != null) {
                viewer.refresh();
            }
        });
    }

    private class BookmarkContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            return input instanceof Object[] elements ? elements : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent != root) {
                return new Object[0];
            }
            List<Object> children = new ArrayList<>();
            children.add(noBookmarks);
            try {
                List<String> names = new ArrayList<>(bookmarkService.getBookmarkNames());
                names.sort(Collator.getInstance());
                children.addAll(names);
            } catch (RuntimeException e) {
                // no case open
            }
            return children.toArray();
        }

        @Override
        public Object getParent(Object element) {
            return element == root ? null : root;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element == root;
        }
    }
}

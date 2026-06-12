package iped.rcp.views.trees;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.Category;
import iped.engine.task.index.IndexItem;
import iped.rcp.api.UiEventTopics;
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
 * Category tree part (task T028, FR-009/AR-02): the engine category tree
 * with item counts ({@code Category.toString} keeps the legacy
 * {@code name (count)} labels), multi-selection combined as SHOULD term
 * queries — the exact filter of the legacy {@code CategoryTreeListener}.
 * Selecting the root (or nothing) clears the filter.
 */
public class CategoryTreePart {

    /** SWTBot widget id (contract of FiltersGalleryTest - T024). */
    public static final String TREE_WIDGET_ID = "iped.rcp.views.categories.tree";

    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryTreePart.class);

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    private TreeViewer viewer;
    private Query lastApplied;

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        viewer.getTree().setData(SearchBarPart.SWTBOT_KEY, TREE_WIDGET_ID);
        viewer.setContentProvider(new CategoryContentProvider());
        viewer.setLabelProvider(new LabelProvider());
        viewer.addSelectionChangedListener(event -> onSelectionChanged());

        CaseSession session = sessionManager.getSession();
        if (session != null) {
            setInput(session);
        }
    }

    private void setInput(CaseSession session) {
        Category root = session.getSource().getCategoryTree();
        if (root != null) {
            // legacy CategoryTreeModel renames the root to the localized label
            root.setName(Messages.getString("CategoryTreeModel.RootName"));
            StringBuilder dump = new StringBuilder();
            int i = 0;
            for (Category child : root.getChildren()) {
                if (i++ >= 8) {
                    dump.append("...");
                    break;
                }
                dump.append('[').append(child.getName()).append("] ");
            }
            LOGGER.info("Category tree root '{}' with {} children: {}", root.getName(), root.getChildren().size(),
                    dump);
        }
        // input must NOT be the root element itself: JFace resolves children
        // of an element equal to the input through getElements(), which would
        // make the root its own child (infinite chain)
        viewer.setInput(root != null ? new Object[] { root } : null);
        if (root != null) {
            viewer.expandToLevel(2);
        }
    }

    private void onSelectionChanged() {
        List<Category> selected = new ArrayList<>();
        boolean rootSelected = false;
        for (Object element : viewer.getStructuredSelection().toList()) {
            Category category = (Category) element;
            if (category.getParent() == null) {
                rootSelected = true;
            } else {
                selected.add(category);
            }
        }

        Query query = null;
        if (!rootSelected && !selected.isEmpty()) {
            // exact legacy CategoryTreeListener query: SHOULD term per category
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Category category : selected) {
                String name = IndexItem.normalize(category.getName(), true);
                builder.add(new TermQuery(new Term(IndexItem.CATEGORY, name)), Occur.SHOULD);
            }
            query = builder.build();
        }
        if (query == null && lastApplied == null) {
            return; // nothing was applied, nothing to clear
        }
        lastApplied = query;
        if (query == null) {
            filterState.clear(FilterStateService.CATEGORIES);
        } else {
            filterState.setQueryFilter(FilterStateService.CATEGORIES, query, selectionLabel(selected));
        }
        SearchJobs.refresh(searchService);
    }

    private static String selectionLabel(List<Category> selected) {
        StringBuilder label = new StringBuilder();
        for (Category category : selected) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(category.getName());
        }
        return label.toString();
    }

    /** Case lifecycle: (re)load or clear the tree. */
    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        if (viewer == null || viewer.getTree().isDisposed()) {
            return;
        }
        uiSync.asyncExec(() -> {
            CaseSession session = sessionManager.getSession();
            if (session != null && !viewer.getTree().isDisposed()) {
                setInput(session);
            }
        });
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        if (viewer == null || viewer.getTree().isDisposed()) {
            return;
        }
        uiSync.asyncExec(() -> {
            if (!viewer.getTree().isDisposed()) {
                lastApplied = null;
                viewer.setInput(null);
            }
        });
    }

    private static class CategoryContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            return input instanceof Object[] roots ? roots : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            return ((Category) parent).getChildren().toArray();
        }

        @Override
        public Object getParent(Object element) {
            return ((Category) element).getParent();
        }

        @Override
        public boolean hasChildren(Object element) {
            return !((Category) element).getChildren().isEmpty();
        }
    }
}

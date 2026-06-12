package iped.rcp.views.filters;

import java.util.Map;

import org.apache.lucene.search.Query;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.search.QueryBuilder;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.filters.FilterTreeNode;
import iped.rcp.core.filters.SavedFiltersStore;
import iped.rcp.core.filters.SimilarityFilters;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.views.SearchBarPart;
import iped.rcp.views.SearchJobs;
import iped.viewers.api.IQueryFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

/**
 * Filters panel part (task T031, FR-016/FR-005/FI-01..04): saved filters in
 * the current on-disk format (legacy {@code FilterManager} via
 * {@link SavedFiltersStore}), the on-demand duplicates filter (legacy
 * {@code DuplicatesFilterer} checkbox) and the combined AND/OR/NOT tree
 * (legacy {@code FiltersPanel}/{@code CombinedFilterer}) built from saved
 * filter expressions.
 */
public class FiltersPanelPart {

    /** SWTBot widget ids (contract of FiltersGalleryTest - T024). */
    public static final String SAVED_LIST_WIDGET_ID = "iped.rcp.views.filters.saved";
    public static final String EXPRESSION_WIDGET_ID = "iped.rcp.views.filters.expression";
    public static final String APPLY_WIDGET_ID = "iped.rcp.views.filters.apply";
    public static final String DUPLICATES_WIDGET_ID = "iped.rcp.views.filters.duplicates";
    public static final String CLEAR_ALL_WIDGET_ID = "iped.rcp.views.filters.clearall";
    public static final String COMBINED_TREE_WIDGET_ID = "iped.rcp.views.filters.combined";

    private static final Logger LOGGER = LoggerFactory.getLogger(FiltersPanelPart.class);

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    private SavedFiltersStore store;
    private List savedList;
    private Text expressionText;
    private Button duplicatesToggle;
    private Label activeFiltersLabel;

    private TreeViewer combinedViewer;
    private FilterTreeNode combinedRoot = FilterTreeNode.group(FilterTreeNode.Operand.OR);

    private Runnable unsubscribe;

    @PostConstruct
    public void createComposite(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(1, false));

        // --- saved filters (legacy FilterManager dialog surface) ---
        Group savedGroup = new Group(area, SWT.NONE);
        savedGroup.setText(Messages.getString("FilterManager.Filters"));
        savedGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        savedGroup.setLayout(new GridLayout(3, false));

        savedList = new List(savedGroup, SWT.SINGLE | SWT.V_SCROLL | SWT.BORDER);
        savedList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
        savedList.setData(SearchBarPart.SWTBOT_KEY, SAVED_LIST_WIDGET_ID);
        savedList.addListener(SWT.Selection, event -> showSelectedExpression());

        expressionText = new Text(savedGroup, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData expressionData = new GridData(SWT.FILL, SWT.FILL, true, false, 3, 1);
        expressionData.heightHint = 48;
        expressionText.setLayoutData(expressionData);
        expressionText.setToolTipText(Messages.getString("FilterManager.Expression.Tip"));
        expressionText.setData(SearchBarPart.SWTBOT_KEY, EXPRESSION_WIDGET_ID);

        Button applyButton = new Button(savedGroup, SWT.PUSH);
        applyButton.setText(Messages.getString("App.Update"));
        applyButton.setToolTipText(Messages.getString("FilterManager.Expression.Tip"));
        applyButton.setData(SearchBarPart.SWTBOT_KEY, APPLY_WIDGET_ID);
        applyButton.addListener(SWT.Selection, event -> applySavedFilter());

        Button newButton = new Button(savedGroup, SWT.PUSH);
        newButton.setText(Messages.getString("FilterManager.New"));
        newButton.setToolTipText(Messages.getString("FilterManager.New.Tip"));
        newButton.addListener(SWT.Selection, event -> newFilter());

        Button deleteButton = new Button(savedGroup, SWT.PUSH);
        deleteButton.setText(Messages.getString("FilterManager.Delete"));
        deleteButton.setToolTipText(Messages.getString("FilterManager.Del.Tip"));
        deleteButton.addListener(SWT.Selection, event -> deleteFilter());

        // --- combined AND/OR/NOT tree (legacy FiltersPanel) ---
        Group combinedGroup = new Group(area, SWT.NONE);
        combinedGroup.setText(Messages.getString("FilterManager.Title"));
        combinedGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        combinedGroup.setLayout(new GridLayout(6, false));

        combinedViewer = new TreeViewer(combinedGroup, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 6, 1);
        treeData.heightHint = 90;
        combinedViewer.getTree().setLayoutData(treeData);
        combinedViewer.getTree().setData(SearchBarPart.SWTBOT_KEY, COMBINED_TREE_WIDGET_ID);
        combinedViewer.setContentProvider(new CombinedContentProvider());
        combinedViewer.setLabelProvider(new LabelProvider());
        // input wraps the root element (it must not BE the input, see
        // CategoryTreePart.setInput)
        combinedViewer.setInput(new Object[] { combinedRoot });
        combinedViewer.expandAll();

        Button andButton = new Button(combinedGroup, SWT.PUSH);
        andButton.setText("AND");
        andButton.addListener(SWT.Selection, event -> addGroup(FilterTreeNode.Operand.AND));

        Button orButton = new Button(combinedGroup, SWT.PUSH);
        orButton.setText("OR");
        orButton.addListener(SWT.Selection, event -> addGroup(FilterTreeNode.Operand.OR));

        Button addLeafButton = new Button(combinedGroup, SWT.PUSH);
        addLeafButton.setText("+");
        addLeafButton.setToolTipText(Messages.getString("FilterManager.New.Tip"));
        addLeafButton.addListener(SWT.Selection, event -> addSavedFilterLeaf());

        Button notButton = new Button(combinedGroup, SWT.PUSH);
        notButton.setText("NOT");
        notButton.addListener(SWT.Selection, event -> toggleNegated());

        Button removeButton = new Button(combinedGroup, SWT.PUSH);
        removeButton.setText("X");
        removeButton.setToolTipText(Messages.getString("FilterManager.Del.Tip"));
        removeButton.addListener(SWT.Selection, event -> removeNode());

        Button applyTreeButton = new Button(combinedGroup, SWT.PUSH);
        applyTreeButton.setText(Messages.getString("App.Update"));
        applyTreeButton.addListener(SWT.Selection, event -> applyCombinedTree());

        // --- standalone toggles + global clear ---
        duplicatesToggle = new Button(area, SWT.CHECK);
        duplicatesToggle.setText(Messages.getString("App.FilterDuplicates"));
        duplicatesToggle.setToolTipText(Messages.getString("App.FilterDuplicatesTip"));
        duplicatesToggle.setData(SearchBarPart.SWTBOT_KEY, DUPLICATES_WIDGET_ID);
        duplicatesToggle.addListener(SWT.Selection, event -> toggleDuplicates());

        Button clearAllButton = new Button(area, SWT.PUSH);
        clearAllButton.setText(Messages.getString("MetadataPanel.Clear"));
        clearAllButton.setData(SearchBarPart.SWTBOT_KEY, CLEAR_ALL_WIDGET_ID);
        clearAllButton.addListener(SWT.Selection, event -> clearAll());

        activeFiltersLabel = new Label(area, SWT.WRAP);
        activeFiltersLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        unsubscribe = filterState.addChangeListener(this::updateActiveFiltersLabel);

        if (sessionManager.getSession() != null) {
            loadStore();
        }
    }

    private void loadStore() {
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            return;
        }
        store = new SavedFiltersStore(session.getSource());
        reloadSavedList();
    }

    private void reloadSavedList() {
        savedList.removeAll();
        if (store == null) {
            return;
        }
        for (String name : store.getAll().keySet()) {
            savedList.add(SavedFiltersStore.localizedName(name));
            savedList.setData(SavedFiltersStore.localizedName(name), name);
        }
    }

    private String selectedRawName() {
        int index = savedList.getSelectionIndex();
        if (index < 0) {
            return null;
        }
        Object raw = savedList.getData(savedList.getItem(index));
        return raw != null ? raw.toString() : savedList.getItem(index);
    }

    private void showSelectedExpression() {
        String raw = selectedRawName();
        if (raw != null && store != null) {
            String expression = store.getExpression(raw);
            expressionText.setText(expression != null ? expression : "");
        }
    }

    private void applySavedFilter() {
        CaseSession session = sessionManager.getSession();
        String expression = expressionText.getText();
        if (session == null) {
            return;
        }
        if (expression.isBlank()) {
            filterState.clear(FilterStateService.SAVED_FILTER);
            SearchJobs.refresh(searchService);
            return;
        }
        try {
            Query query = new QueryBuilder(session.getSource()).getQuery(expression);
            String raw = selectedRawName();
            filterState.setQueryFilter(FilterStateService.SAVED_FILTER, query,
                    raw != null ? SavedFiltersStore.localizedName(raw) : expression);
            SearchJobs.refresh(searchService);
        } catch (Exception e) {
            MessageDialog.openError(savedList.getShell(), Messages.getString("UISearcher.Error.Title"),
                    Messages.getString("UISearcher.Error.Msg") + e.getMessage());
        }
    }

    private void newFilter() {
        InputDialog dialog = new InputDialog(savedList.getShell(), Messages.getString("FilterManager.Title"),
                Messages.getString("FilterManager.NewName"), "", null);
        if (dialog.open() == Window.OK && store != null) {
            String name = dialog.getValue();
            if (name != null && !name.isBlank()) {
                store.put(name.trim(), expressionText.getText());
                reloadSavedList();
            }
        }
    }

    private void deleteFilter() {
        String raw = selectedRawName();
        if (raw != null && store != null) {
            store.remove(raw);
            reloadSavedList();
            expressionText.setText("");
        }
    }

    // --- combined tree actions ---

    private FilterTreeNode selectedGroupOrRoot() {
        Object selected = combinedViewer.getStructuredSelection().getFirstElement();
        if (selected instanceof FilterTreeNode node && node.isGroup()) {
            return node;
        }
        return combinedRoot;
    }

    private void addGroup(FilterTreeNode.Operand operand) {
        selectedGroupOrRoot().add(FilterTreeNode.group(operand));
        combinedViewer.refresh();
        combinedViewer.expandAll();
    }

    /** Adds the saved filter selected on the list as a leaf (query filter). */
    private void addSavedFilterLeaf() {
        CaseSession session = sessionManager.getSession();
        String raw = selectedRawName();
        if (session == null || raw == null || store == null) {
            return;
        }
        try {
            Query query = new QueryBuilder(session.getSource()).getQuery(store.getExpression(raw));
            IQueryFilter filter = new IQueryFilter() {
                @Override
                public Query getQuery() {
                    return query;
                }

                @Override
                public String toString() {
                    return SavedFiltersStore.localizedName(raw);
                }
            };
            selectedGroupOrRoot().add(FilterTreeNode.leaf(filter, SavedFiltersStore.localizedName(raw)));
            combinedViewer.refresh();
            combinedViewer.expandAll();
        } catch (Exception e) {
            LOGGER.warn("Could not parse saved filter {}", raw, e);
            MessageDialog.openError(savedList.getShell(), Messages.getString("UISearcher.Error.Title"),
                    Messages.getString("FiltersPanel.addQueryFilterError"));
        }
    }

    private void toggleNegated() {
        Object selected = combinedViewer.getStructuredSelection().getFirstElement();
        if (selected instanceof FilterTreeNode node && node != combinedRoot) {
            node.setNegated(!node.isNegated());
            combinedViewer.refresh();
        }
    }

    private void removeNode() {
        Object selected = combinedViewer.getStructuredSelection().getFirstElement();
        if (selected instanceof FilterTreeNode node && node != combinedRoot && node.getParent() != null) {
            node.getParent().remove(node);
            combinedViewer.refresh();
        }
    }

    private void applyCombinedTree() {
        filterState.setCombinedTree(combinedRoot.isEmpty() ? null : combinedRoot);
        SearchJobs.refresh(searchService);
    }

    // --- toggles / global clear ---

    private void toggleDuplicates() {
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            duplicatesToggle.setSelection(false);
            return;
        }
        if (duplicatesToggle.getSelection()) {
            filterState.setResultSetFilter(FilterStateService.DUPLICATES,
                    SimilarityFilters.duplicates(session.getSource()),
                    Messages.getString("App.FilterDuplicates"));
        } else {
            filterState.clear(FilterStateService.DUPLICATES);
        }
        SearchJobs.refresh(searchService);
    }

    private void clearAll() {
        combinedRoot = FilterTreeNode.group(FilterTreeNode.Operand.OR);
        combinedViewer.setInput(new Object[] { combinedRoot });
        duplicatesToggle.setSelection(false);
        filterState.clearAll();
        SearchJobs.refresh(searchService);
    }

    private void updateActiveFiltersLabel() {
        uiSync.asyncExec(() -> {
            if (activeFiltersLabel == null || activeFiltersLabel.isDisposed()) {
                return;
            }
            Map<String, String> active = filterState.getActiveFilters();
            activeFiltersLabel.setText(active.isEmpty() ? "" : active.toString());
            activeFiltersLabel.getParent().layout();
        });
    }

    @PreDestroy
    public void dispose() {
        if (unsubscribe != null) {
            unsubscribe.run();
        }
    }

    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        uiSync.asyncExec(() -> {
            if (savedList != null && !savedList.isDisposed()) {
                loadStore();
            }
        });
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        uiSync.asyncExec(() -> {
            if (savedList != null && !savedList.isDisposed()) {
                store = null;
                savedList.removeAll();
                expressionText.setText("");
                duplicatesToggle.setSelection(false);
                combinedRoot = FilterTreeNode.group(FilterTreeNode.Operand.OR);
                combinedViewer.setInput(new Object[] { combinedRoot });
            }
        });
    }

    private static class CombinedContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            return input instanceof Object[] elements ? elements : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            return ((FilterTreeNode) parent).getChildren().toArray();
        }

        @Override
        public Object getParent(Object element) {
            return ((FilterTreeNode) element).getParent();
        }

        @Override
        public boolean hasChildren(Object element) {
            return !((FilterTreeNode) element).getChildren().isEmpty();
        }
    }
}

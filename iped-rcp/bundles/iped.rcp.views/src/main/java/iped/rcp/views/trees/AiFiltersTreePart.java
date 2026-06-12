package iped.rcp.views.trees;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.search.Query;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import iped.engine.data.SimpleFilterNode;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.trees.AiFiltersModel;
import iped.rcp.views.SearchBarPart;
import iped.rcp.views.SearchJobs;
import iped.utils.LocalizedFormat;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * AI filters tree part (task T029, FR-009/AR-04): the configured
 * {@code AIFiltersConfig.json} tree counted/expanded against the open case
 * (legacy {@code AIFiltersLoader} via {@link AiFiltersModel}); selection is
 * combined as SHOULD node queries (legacy {@code AIFiltersTreeListener}).
 * Cases without the AI filters config simply render an empty tree.
 */
public class AiFiltersTreePart {

    /** SWTBot widget id. */
    public static final String TREE_WIDGET_ID = "iped.rcp.views.aifilters.tree";

    /** Localization catalog of the AI filter names (legacy AIFiltersLocalization). */
    private static final String AI_FILTERS_BUNDLE = "iped-ai-filters";

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    private TreeViewer viewer;
    private AiFiltersModel model;
    private boolean filterApplied;

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        viewer.getTree().setData(SearchBarPart.SWTBOT_KEY, TREE_WIDGET_ID);
        viewer.setContentProvider(new NodeContentProvider());
        viewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                SimpleFilterNode node = (SimpleFilterNode) element;
                String name = localizedName(node);
                return node.getNumItems() >= 0 ? name + " (" + LocalizedFormat.format(node.getNumItems()) + ")"
                        : name;
            }
        });
        viewer.addSelectionChangedListener(event -> onSelectionChanged());

        if (sessionManager.getSession() != null) {
            loadModel();
        }
    }

    /** Counting/expanding the configured tree runs engine queries: Job. */
    private void loadModel() {
        Job job = Job.create("Loading AI filters", (IProgressMonitor monitor) -> {
            CaseSession session = sessionManager.getSession();
            if (session == null) {
                return Status.OK_STATUS;
            }
            AiFiltersModel newModel = new AiFiltersModel(session.getSource());
            uiSync.asyncExec(() -> {
                if (!viewer.getTree().isDisposed()) {
                    model = newModel;
                    filterApplied = false;
                    // input wraps the root element (it must not BE the
                    // input, see CategoryTreePart.setInput)
                    viewer.setInput(newModel.getRoot() != null ? new Object[] { newModel.getRoot() } : null);
                    viewer.expandToLevel(2);
                }
            });
            return Status.OK_STATUS;
        });
        job.schedule();
    }

    private void onSelectionChanged() {
        if (model == null || model.getRoot() == null) {
            return;
        }
        List<SimpleFilterNode> selected = new ArrayList<>();
        boolean rootSelected = false;
        for (Object element : viewer.getStructuredSelection().toList()) {
            SimpleFilterNode node = (SimpleFilterNode) element;
            if (node == model.getRoot()) {
                rootSelected = true;
            } else {
                selected.add(node);
            }
        }

        Query query = rootSelected || selected.isEmpty() ? null : model.selectionQuery(selected);
        if (query == null) {
            if (filterApplied) {
                filterApplied = false;
                filterState.clear(FilterStateService.AI_FILTERS);
                SearchJobs.refresh(searchService);
            }
            return;
        }
        filterApplied = true;
        filterState.setQueryFilter(FilterStateService.AI_FILTERS, query, selectionLabel(selected));
        SearchJobs.refresh(searchService);
    }

    private static String localizedName(SimpleFilterNode node) {
        String localized = Messages.getString(AI_FILTERS_BUNDLE, node.getFullName());
        return localized.startsWith("!") && localized.endsWith("!") ? node.getName() : localized;
    }

    private static String selectionLabel(List<SimpleFilterNode> selected) {
        StringBuilder label = new StringBuilder();
        for (SimpleFilterNode node : selected) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(localizedName(node));
        }
        return label.toString();
    }

    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        if (viewer != null && !viewer.getTree().isDisposed()) {
            loadModel();
        }
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        uiSync.asyncExec(() -> {
            if (viewer != null && !viewer.getTree().isDisposed()) {
                model = null;
                filterApplied = false;
                viewer.setInput(null);
            }
        });
    }

    private static class NodeContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            return input instanceof Object[] elements ? elements : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            return ((SimpleFilterNode) parent).getChildren().toArray();
        }

        @Override
        public Object getParent(Object element) {
            return ((SimpleFilterNode) element).getParent();
        }

        @Override
        public boolean hasChildren(Object element) {
            return !((SimpleFilterNode) element).getChildren().isEmpty();
        }
    }
}

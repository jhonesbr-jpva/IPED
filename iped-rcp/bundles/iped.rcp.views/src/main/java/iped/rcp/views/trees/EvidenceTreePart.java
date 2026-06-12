package iped.rcp.views.trees;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import iped.data.IItemId;
import iped.engine.task.index.IndexItem;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.trees.EvidenceTreeModel;
import iped.rcp.views.SearchBarPart;
import iped.rcp.views.SearchJobs;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Evidence/filesystem tree part (task T027, FR-009/AR-01): lazy children
 * over the legacy {@code TreeViewModel} queries (via
 * {@link EvidenceTreeModel}) and subtree filtering with the legacy
 * {@code TreeListener} semantics — "recursive listing" toggle switches
 * between the whole-subtree query and the direct-children listing; root
 * selection lists the evidence roots (non-recursive) or clears the filter.
 * Selecting a single node also drives the viewers (legacy
 * {@code FileProcessor} behavior) through the selection service.
 */
public class EvidenceTreePart {

    /** SWTBot widget id. */
    public static final String TREE_WIDGET_ID = "iped.rcp.views.evidence.tree";

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ESelectionService selectionService;

    @Inject
    private UISynchronize uiSync;

    @Inject
    private MPart part;

    private TreeViewer viewer;
    private Button recursiveToggle;
    private EvidenceTreeModel model;
    private List<EvidenceTreeModel.Node> roots = List.of();
    private final Object treeRoot = new Object() {
        @Override
        public String toString() {
            return Messages.getString("TreeViewModel.RootName");
        }
    };
    private boolean filterApplied;

    @PostConstruct
    public void createComposite(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        area.setLayout(layout);

        recursiveToggle = new Button(area, SWT.CHECK);
        recursiveToggle.setText(Messages.getString("App.RecursiveListing"));
        recursiveToggle.setSelection(true); // legacy default (App.java)
        recursiveToggle.setData(SearchBarPart.SWTBOT_KEY, "iped.rcp.views.evidence.recursive");
        recursiveToggle.addListener(SWT.Selection, event -> onSelectionChanged());

        viewer = new TreeViewer(area, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        viewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.getTree().setData(SearchBarPart.SWTBOT_KEY, TREE_WIDGET_ID);
        viewer.setContentProvider(new NodeContentProvider());
        viewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof EvidenceTreeModel.Node node ? node.getName() : String.valueOf(element);
            }
        });
        viewer.addSelectionChangedListener(event -> onSelectionChanged());

        if (sessionManager.getSession() != null) {
            loadRoots();
        }
    }

    /** Roots query runs off the UI thread (engine search). */
    private void loadRoots() {
        Job job = Job.create("Loading evidence tree", (IProgressMonitor monitor) -> {
            CaseSession session = sessionManager.getSession();
            if (session == null) {
                return Status.OK_STATUS;
            }
            EvidenceTreeModel newModel = new EvidenceTreeModel(session.getSource());
            List<EvidenceTreeModel.Node> newRoots = newModel.getRoots();
            uiSync.asyncExec(() -> {
                if (viewer.getTree().isDisposed()) {
                    return;
                }
                model = newModel;
                roots = newRoots;
                filterApplied = false;
                // input wraps the root element (it must not BE the input,
                // see CategoryTreePart.setInput)
                viewer.setInput(new Object[] { treeRoot });
                viewer.expandToLevel(2);
            });
            return Status.OK_STATUS;
        });
        job.schedule();
    }

    private void onSelectionChanged() {
        if (model == null) {
            return;
        }
        List<EvidenceTreeModel.Node> selected = new ArrayList<>();
        boolean rootSelected = false;
        for (Object element : viewer.getStructuredSelection().toList()) {
            if (element == treeRoot) {
                rootSelected = true;
            } else if (element instanceof EvidenceTreeModel.Node node) {
                selected.add(node);
            }
        }
        boolean recursive = recursiveToggle.getSelection();

        Query query = null;
        if (rootSelected || selected.isEmpty()) {
            if (!recursive && rootSelected) {
                // legacy: root in navigation mode lists the evidence roots
                query = new TermQuery(new Term(IndexItem.ISROOT, "true"));
            }
        } else {
            query = recursive ? model.recursiveQuery(selected) : model.directChildrenQuery(selected);
        }

        if (query == null) {
            if (filterApplied) {
                filterApplied = false;
                filterState.clear(FilterStateService.EVIDENCE_TREE);
                SearchJobs.refresh(searchService);
            }
        } else {
            filterApplied = true;
            filterState.setQueryFilter(FilterStateService.EVIDENCE_TREE, query, selectionLabel(selected));
            SearchJobs.refresh(searchService);
        }

        // single node selection drives the viewers (legacy FileProcessor)
        if (selected.size() == 1) {
            publishNodeSelection(selected.get(0));
        }
    }

    private void publishNodeSelection(EvidenceTreeModel.Node node) {
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            return;
        }
        try {
            IItemId itemId = session.getSource().getItemId(node.docId);
            if (itemId != null) {
                ItemId apiId = new ItemId(itemId.getSourceId(), itemId.getId());
                selectionService.setSelection(new SelectionContext(apiId, List.of(apiId), part.getElementId()));
            }
        } catch (RuntimeException e) {
            // tree node without a resolvable item: viewers just keep state
        }
    }

    private static String selectionLabel(List<EvidenceTreeModel.Node> selected) {
        StringBuilder label = new StringBuilder();
        for (EvidenceTreeModel.Node node : selected) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(node.getName());
        }
        return label.toString();
    }

    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        if (viewer != null && !viewer.getTree().isDisposed()) {
            loadRoots();
        }
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        if (viewer == null || viewer.getTree().isDisposed()) {
            return;
        }
        uiSync.asyncExec(() -> {
            if (!viewer.getTree().isDisposed()) {
                model = null;
                roots = List.of();
                filterApplied = false;
                viewer.setInput(null);
            }
        });
    }

    private class NodeContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            return input instanceof Object[] elements ? elements : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent == treeRoot) {
                return roots.toArray();
            }
            // lazy per-node children, same engine query the legacy tree runs
            return ((EvidenceTreeModel.Node) parent).getChildren().toArray();
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            // legacy TreeViewModel.isLeaf is always false: expandable until
            // the children query proves otherwise
            return true;
        }
    }
}

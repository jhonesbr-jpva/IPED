package iped.rcp.views.metadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.localization.LocalizedProperties;
import iped.properties.BasicProps;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.metadata.CountComparator;
import iped.rcp.core.metadata.MetadataAggregator;
import iped.rcp.core.metadata.ValueCount;
import iped.rcp.core.metadata.ValueCountFilter;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.views.ResultColumns;
import iped.rcp.views.SearchBarPart;
import iped.rcp.views.SearchJobs;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Metadata facets part (task T030, FR-010/MD-01..03): per-field value/range
 * aggregation over the ACTIVE result (legacy {@code MetadataPanel} via the
 * ported {@link MetadataAggregator}) with multi-selection filtering
 * ({@link ValueCountFilter} on the {@code metadata} slot). Counting runs in
 * a Job on demand ("Update", legacy discipline), values are listed in a
 * virtual table sorted by count.
 */
public class MetadataPanelPart {

    /** SWTBot widget ids. */
    public static final String FIELD_WIDGET_ID = "iped.rcp.views.metadata.field";
    public static final String UPDATE_WIDGET_ID = "iped.rcp.views.metadata.update";
    public static final String VALUES_WIDGET_ID = "iped.rcp.views.metadata.values";
    public static final String FILTER_WIDGET_ID = "iped.rcp.views.metadata.filter";
    public static final String CLEAR_WIDGET_ID = "iped.rcp.views.metadata.clear";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataPanelPart.class);

    @Inject
    private FilterStateService filterState;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    private Combo fieldCombo;
    private Table valuesTable;
    private Button updateButton;
    private Button filterButton;
    private Button clearButton;

    private MetadataAggregator aggregator;
    private String aggregatedField;
    private List<ValueCount> values = new ArrayList<>();

    @PostConstruct
    public void createComposite(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(3, false));

        Label propertyLabel = new Label(area, SWT.NONE);
        propertyLabel.setText(Messages.getString("MetadataPanel.Property"));

        fieldCombo = new Combo(area, SWT.DROP_DOWN | SWT.READ_ONLY);
        fieldCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        fieldCombo.setData(SearchBarPart.SWTBOT_KEY, FIELD_WIDGET_ID);

        updateButton = new Button(area, SWT.PUSH);
        updateButton.setText(Messages.getString("MetadataPanel.Update"));
        updateButton.setData(SearchBarPart.SWTBOT_KEY, UPDATE_WIDGET_ID);
        updateButton.addListener(SWT.Selection, event -> updateCounts());

        valuesTable = new Table(area, SWT.VIRTUAL | SWT.MULTI | SWT.BORDER | SWT.FULL_SELECTION);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        valuesTable.setLayoutData(tableData);
        valuesTable.setData(SearchBarPart.SWTBOT_KEY, VALUES_WIDGET_ID);
        valuesTable.addListener(SWT.SetData, this::fillRow);

        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        buttons.setLayout(new GridLayout(2, true));

        filterButton = new Button(buttons, SWT.PUSH);
        filterButton.setText(Messages.getString("MetadataPanel.FilterValues"));
        filterButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterButton.setData(SearchBarPart.SWTBOT_KEY, FILTER_WIDGET_ID);
        filterButton.addListener(SWT.Selection, event -> applyFilter());

        clearButton = new Button(buttons, SWT.PUSH);
        clearButton.setText(Messages.getString("MetadataPanel.Clear"));
        clearButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        clearButton.setData(SearchBarPart.SWTBOT_KEY, CLEAR_WIDGET_ID);
        clearButton.addListener(SWT.Selection, event -> clearFilter());

        loadFields();
    }

    private void loadFields() {
        fieldCombo.removeAll();
        if (sessionManager.getSession() == null) {
            return;
        }
        List<String> fields = new ArrayList<>();
        for (String field : ResultColumns.availableFields()) {
            if (!ResultColumns.SCORE.equals(field) && !ResultColumns.BOOKMARK.equals(field)) {
                fields.add(LocalizedProperties.getLocalizedField(field));
            }
        }
        for (String field : fields) {
            fieldCombo.add(field);
        }
        int defaultIndex = fields.indexOf(LocalizedProperties.getLocalizedField(BasicProps.CATEGORY));
        fieldCombo.select(Math.max(0, defaultIndex));
    }

    /** Aggregation over the active result, engine-side (Job). */
    private void updateCounts() {
        CaseSession session = sessionManager.getSession();
        String field = fieldCombo.getText();
        if (session == null || field.isBlank()) {
            return;
        }
        updateButton.setEnabled(false);
        Job job = Job.create(Messages.getString("MetadataPanel.Update"), (IProgressMonitor monitor) -> {
            try {
                MetadataAggregator newAggregator = new MetadataAggregator(session.getSource());
                ResultSet active = searchService.getCurrent();
                MultiSearchResult result = active != null ? active.result()
                        : new IPEDSearcher(session.getSource(), "").multiSearch();
                List<ValueCount> counts = newAggregator.countValues(field, result);
                counts.sort(new CountComparator());
                uiSync.asyncExec(() -> {
                    if (valuesTable.isDisposed()) {
                        return;
                    }
                    aggregator = newAggregator;
                    aggregatedField = field;
                    values = counts;
                    valuesTable.setItemCount(counts.size());
                    valuesTable.clearAll();
                    updateButton.setEnabled(true);
                });
                return Status.OK_STATUS;
            } catch (Exception e) {
                LOGGER.error("Metadata aggregation failed for field {}", field, e);
                uiSync.asyncExec(() -> {
                    if (!updateButton.isDisposed()) {
                        updateButton.setEnabled(true);
                    }
                });
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    private void fillRow(Event event) {
        TableItem item = (TableItem) event.item;
        int row = event.index;
        if (row < values.size()) {
            item.setText(values.get(row).toString());
            item.setData(values.get(row));
        }
    }

    private void applyFilter() {
        if (aggregator == null || aggregatedField == null) {
            return;
        }
        Set<Integer> ords = new LinkedHashSet<>();
        StringBuilder label = new StringBuilder(aggregatedField).append(": ");
        for (TableItem item : valuesTable.getSelection()) {
            if (item.getData() instanceof ValueCount value) {
                ords.add(value.getOrd());
                label.append(value.getVal()).append(' ');
            }
        }
        if (ords.isEmpty()) {
            clearFilter();
            return;
        }
        filterState.setResultSetFilter(FilterStateService.METADATA,
                new ValueCountFilter(aggregator, aggregatedField, ords), label.toString().trim());
        SearchJobs.refresh(searchService);
    }

    private void clearFilter() {
        valuesTable.deselectAll();
        filterState.clear(FilterStateService.METADATA);
        SearchJobs.refresh(searchService);
    }

    @Inject
    @Optional
    public void onCaseOpened(@UIEventTopic(UiEventTopics.CASE_OPENED) Object payload) {
        uiSync.asyncExec(() -> {
            if (fieldCombo != null && !fieldCombo.isDisposed()) {
                loadFields();
            }
        });
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        uiSync.asyncExec(() -> {
            if (valuesTable != null && !valuesTable.isDisposed()) {
                aggregator = null;
                aggregatedField = null;
                values = new ArrayList<>();
                valuesTable.setItemCount(0);
                valuesTable.clearAll();
                fieldCombo.removeAll();
            }
        });
    }
}

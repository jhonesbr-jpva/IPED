package iped.rcp.views;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

/**
 * Search bar part (task T016, FR-006): query field with the current syntax
 * and execution history (parity {@code QueryComboBox}), running searches
 * through {@link SearchService} off the UI thread (Jobs API, Principle V).
 * Each successful run swaps the active result set and publishes
 * {@code iped/rcp/results/CHANGED} (done inside the service).
 */
public class SearchBarPart {

    /** SWTBot widget ids (consumed by TriageFlowTest - T014). */
    public static final String QUERY_WIDGET_ID = "iped.rcp.views.searchbar.query";
    public static final String RUN_WIDGET_ID = "iped.rcp.views.searchbar.run";

    static final String SWTBOT_KEY = "org.eclipse.swtbot.widget.key";

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchBarPart.class);

    private static final String PREFS_NODE = "iped.rcp.views";
    private static final String PREF_HISTORY = "search.history";
    private static final String HISTORY_SEPARATOR = "\n";
    private static final int HISTORY_LIMIT = 50;

    @Inject
    private SearchService searchService;

    @Inject
    private UISynchronize uiSync;

    private Combo queryCombo;
    private Button runButton;

    @PostConstruct
    public void createComposite(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 4;
        bar.setLayout(layout);

        queryCombo = new Combo(bar, SWT.DROP_DOWN);
        queryCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        queryCombo.setData(SWTBOT_KEY, QUERY_WIDGET_ID);
        queryCombo.setToolTipText(Messages.getString("SearchBar.Hint"));
        for (String entry : loadHistory()) {
            queryCombo.add(entry);
        }
        queryCombo.addListener(SWT.DefaultSelection, event -> runSearch());

        runButton = new Button(bar, SWT.PUSH);
        runButton.setText(Messages.getString("SearchBar.Run"));
        runButton.setData(SWTBOT_KEY, RUN_WIDGET_ID);
        runButton.addListener(SWT.Selection, event -> runSearch());
    }

    private void runSearch() {
        String queryText = queryCombo.getText();
        runButton.setEnabled(false);
        Job job = Job.create(Messages.getString("SearchBar.Searching"), (IProgressMonitor monitor) -> {
            try {
                searchService.runSearch(queryText);
                uiSync.asyncExec(() -> {
                    if (!queryCombo.isDisposed()) {
                        addToHistory(queryText);
                        runButton.setEnabled(true);
                    }
                });
                return Status.OK_STATUS;
            } catch (IllegalArgumentException e) {
                uiSync.asyncExec(() -> {
                    if (!runButton.isDisposed()) {
                        runButton.setEnabled(true);
                        MessageDialog.openError(runButton.getShell(),
                                Messages.getString("UISearcher.Error.Title"),
                                Messages.getString("UISearcher.Error.Msg") + e.getMessage());
                    }
                });
                return Status.CANCEL_STATUS;
            } catch (RuntimeException e) {
                LOGGER.error("Search failed", e);
                uiSync.asyncExec(() -> {
                    if (!runButton.isDisposed()) {
                        runButton.setEnabled(true);
                    }
                });
                return new Status(IStatus.ERROR, "iped.rcp.views", e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    /** Most recent first, no duplicates, capped (QueryComboBox parity). */
    private void addToHistory(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return;
        }
        List<String> history = new ArrayList<>(List.of(queryCombo.getItems()));
        history.remove(queryText);
        history.add(0, queryText);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
        String text = queryCombo.getText();
        queryCombo.removeAll();
        for (String entry : history) {
            queryCombo.add(entry);
        }
        queryCombo.setText(text);
        saveHistory(history);
    }

    private List<String> loadHistory() {
        String stored = prefs().get(PREF_HISTORY, "");
        List<String> history = new ArrayList<>();
        for (String entry : stored.split(HISTORY_SEPARATOR)) {
            if (!entry.isBlank()) {
                history.add(entry);
            }
        }
        return history;
    }

    private void saveHistory(List<String> history) {
        Preferences prefs = prefs();
        prefs.put(PREF_HISTORY, String.join(HISTORY_SEPARATOR, history));
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            LOGGER.warn("Could not persist search history", e);
        }
    }

    private static Preferences prefs() {
        return InstanceScope.INSTANCE.getNode(PREFS_NODE);
    }
}

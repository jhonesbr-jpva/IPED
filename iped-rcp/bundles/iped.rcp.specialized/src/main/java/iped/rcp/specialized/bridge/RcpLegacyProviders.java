package iped.rcp.specialized.bridge;

import java.awt.FileDialog;
import java.util.Collections;
import java.util.Set;

import javax.swing.JTable;
import javax.swing.SortOrder;

import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.data.IIPEDSource;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.LoadIndexFields;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.QueryBuilder;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.search.IIPEDSearcher;
import iped.search.IMultiSearchResult;
import iped.viewers.api.GUIProvider;
import iped.viewers.api.IColumnsManager;
import iped.viewers.api.IMultiSearchResultProvider;

/**
 * Adapter giving the bridged legacy views the two provider interfaces the
 * legacy {@code App} used to implement (tasks T034-T036): results access
 * over the workbench's active {@link ResultSet} (with the shared mirror
 * table standing in for the legacy results {@code JTable}) and the GUI
 * resources of {@link GUIProvider}.
 *
 * <p>
 * Divergences from the legacy implementation (parity inventory SV rows):
 * {@link #getSelectedBookmarks()}/{@link #getSelectedCategories()} return
 * empty sets (the timeline then plots the default "Items" series; split by
 * bookmark/category follows tree-selection integration in a later
 * iteration), and {@link #createFileDialog(String, int)} parents on the
 * invisible legacy frame (dialogs open centered on screen).
 */
public class RcpLegacyProviders implements IMultiSearchResultProvider, GUIProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RcpLegacyProviders.class);

    private final SearchService searchService;
    private final ICaseSessionManager sessionManager;
    private final FilterStateService filterState;
    private final JTable mirrorTable;

    private static final IMultiSearchResult EMPTY = new MultiSearchResult(new iped.engine.data.ItemId[0],
            new float[0]);

    public RcpLegacyProviders(SearchService searchService, ICaseSessionManager sessionManager,
            FilterStateService filterState, JTable mirrorTable) {
        this.searchService = searchService;
        this.sessionManager = sessionManager;
        this.filterState = filterState;
        this.mirrorTable = mirrorTable;
    }

    @Override
    public IMultiSearchResult getResults() {
        ResultSet current = searchService.getCurrent();
        return current == null ? EMPTY : current.result();
    }

    @Override
    public JTable getResultsTable() {
        return mirrorTable;
    }

    @Override
    public String getSortColumn() {
        ResultSet current = searchService.getCurrent();
        return current == null ? null : current.sortField();
    }

    @Override
    public SortOrder getSortOrder() {
        ResultSet current = searchService.getCurrent();
        if (current == null || current.sortField() == null) {
            return SortOrder.UNSORTED;
        }
        return current.sortAscending() ? SortOrder.ASCENDING : SortOrder.DESCENDING;
    }

    @Override
    public IIPEDSource getIPEDSource() {
        return source();
    }

    @Override
    public IIPEDSearcher createNewSearch(String query) {
        return createNewSearch(query, true);
    }

    @Override
    public IIPEDSearcher createNewSearch(String query, boolean applyFilters) {
        IPEDMultiSource source = source();
        if (applyFilters && filterState != null && filterState.hasQueryFilters()) {
            // legacy CaseSearcherFilter.applyUIQueryFilters discipline
            try {
                Query base = new QueryBuilder(source).getQuery(query);
                return new IPEDSearcher(source, filterState.composeQuery(base));
            } catch (Exception e) {
                LOGGER.warn("Could not compose UI filters into '{}', falling back to the plain query", query, e);
            }
        }
        return new IPEDSearcher(source, query);
    }

    @Override
    public IIPEDSearcher createNewSearch(String query, String[] sortFields) {
        IPEDMultiSource source = source();
        return sortFields == null ? new IPEDSearcher(source, query) : new IPEDSearcher(source, query, sortFields);
    }

    @Override
    public FileDialog createFileDialog(String title, int mode) {
        return new FileDialog(App.get(), title, mode);
    }

    @Override
    public IColumnsManager getColumnsManager() {
        IPEDMultiSource source = source();
        return () -> LoadIndexFields.getFields(source.getAtomicSources());
    }

    @Override
    public Set<String> getSelectedBookmarks() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getSelectedCategories() {
        return Collections.emptySet();
    }

    private IPEDMultiSource source() {
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            throw new IllegalStateException("No case session is open");
        }
        return session.getSource();
    }
}

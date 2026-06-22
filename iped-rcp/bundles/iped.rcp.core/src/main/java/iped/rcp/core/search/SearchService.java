package iped.rcp.core.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.search.Query;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.QueryBuilder;
import iped.engine.search.TimelineResults;
import iped.exception.ParseException;
import iped.exception.QueryNodeException;
import iped.rcp.api.ISearchService;
import iped.rcp.api.ItemId;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.events.IUiEventPublisher;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.session.SessionReloadListener;

/**
 * Search service over the open case session (tasks T015/T016, FR-006): same
 * query syntax and engine code path as the current UI
 * ({@code CaseSearcherFilter} without UI filters — composed filters arrive
 * with US2/T031).
 *
 * <p>
 * Besides the provisional extension API ({@link ISearchService}), this
 * service owns the ACTIVE result set of the workbench (data-model
 * "ResultSetModel" backing): {@link #runSearch(String)} and
 * {@link #sortBy(String, boolean)} swap an immutable {@link ResultSet}
 * atomically and publish {@link UiEventTopics#RESULTS_CHANGED} with the new
 * generation. Both are synchronous and potentially slow — UI callers run
 * them off the UI thread (Jobs); the parity harness calls them directly.
 */
@Component(service = { ISearchService.class, SearchService.class })
public class SearchService implements ISearchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);

    private final AtomicLong generation = new AtomicLong();
    private volatile ResultSet current;
    private volatile String lastQueryText;

    /**
     * Timeline table view (legacy {@code TimelineListener}): when on, the
     * active result set is expanded to one row per timestamp/event of each item
     * ({@link TimelineResults#expandTimestamps}). It is a view mode of the
     * active result only, so it is applied in {@link #runSearch} (not in the
     * {@link ISearchService} count/search API used by third parties).
     */
    private volatile boolean timelineView;

    @Reference
    private ICaseSessionManager sessionManager;

    /** Optional so the service also runs headless (parity harness). */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.STATIC)
    private IUiEventPublisher eventPublisher;

    /**
     * Filter composition (US2/T031); optional so the plain search path of US1
     * keeps working without it (greedy: binds whenever the service exists).
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY)
    private FilterStateService filterState;

    private Runnable reloadUnsubscriber;

    /**
     * Near-live integration (T063, FR-029): when the commit monitor swaps the
     * session source, re-run the last query so the active result set reflects
     * the new consolidation and {@code results/CHANGED} reaches the parts.
     * Runs on the monitor thread (never the UI thread). Before the first
     * search there is nothing to refresh — parts resolve the source
     * dynamically on the first run anyway.
     */
    private final SessionReloadListener reloadListener = new SessionReloadListener() {
        @Override
        public void afterReload() {
            if (lastQueryText != null) {
                try {
                    refresh();
                } catch (RuntimeException e) {
                    LOGGER.error("Near-live result refresh failed", e);
                }
            }
        }
    };

    /** DS constructor. */
    public SearchService() {
    }

    /** Headless harness constructor (no OSGi injection). */
    public SearchService(ICaseSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        activate();
    }

    /** Headless harness constructor with filter composition (T025). */
    public SearchService(ICaseSessionManager sessionManager, FilterStateService filterState) {
        this.sessionManager = sessionManager;
        this.filterState = filterState;
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
    public long count(String query) {
        return doSearch(query).getLength();
    }

    @Override
    public List<ItemId> search(String query, int offset, int limit) {
        MultiSearchResult result = doSearch(query);
        return page(result, offset, limit);
    }

    /**
     * Runs the query and makes the outcome the active result set, publishing
     * {@code results/CHANGED}.
     */
    public ResultSet runSearch(String queryText) {
        MultiSearchResult result = doSearch(queryText);
        lastQueryText = queryText == null ? "" : queryText;
        if (timelineView) {
            result = expandTimeline(result);
        }
        ResultSet next = new ResultSet(lastQueryText, result, generation.incrementAndGet(), null, true);
        current = next;
        publishResultsChanged(next);
        return next;
    }

    /** Whether the timeline table view is active. */
    public boolean isTimelineView() {
        return timelineView;
    }

    /**
     * Toggles the timeline table view and re-runs the active query so the
     * result set is (un)expanded. No-op when the flag does not change.
     */
    public ResultSet setTimelineView(boolean enabled) {
        if (timelineView == enabled) {
            return current;
        }
        timelineView = enabled;
        return refresh();
    }

    /**
     * Expands each item of the result into one row per timestamp/event
     * (legacy {@code TimelineResults}). Cases without indexed time events have
     * no expansion data — fall back to the original result (logged) instead of
     * failing the listing.
     */
    private MultiSearchResult expandTimeline(MultiSearchResult result) {
        try {
            IPEDMultiSource source = activeSource();
            MultiSearchResult expanded = new TimelineResults(source).expandTimestamps(result);
            expanded.setIPEDSource(source);
            return expanded;
        } catch (Exception e) {
            LOGGER.error("Could not expand the timeline table view; showing the plain result", e);
            return result;
        }
    }

    /**
     * Re-runs the last executed query with the current filter composition
     * (legacy {@code updateFileListing()} discipline — parts call this after
     * mutating {@link FilterStateService} slots). Match-all when no search
     * ran yet.
     */
    public ResultSet refresh() {
        String text = lastQueryText;
        return runSearch(text == null ? "" : text);
    }

    /**
     * Reorders the active result set by an indexed field (or a special
     * {@link ResultSorter} key), engine-side. No-op returning the current set
     * when no search ran yet.
     */
    public ResultSet sortBy(String field, boolean ascending) {
        ResultSet base = current;
        if (base == null) {
            return null;
        }
        try {
            MultiSearchResult sorted = ResultSorter.sort(activeSource(), base.result(), field, ascending);
            sorted.setIPEDSource(activeSource());
            ResultSet next = new ResultSet(base.queryText(), sorted, generation.incrementAndGet(), field, ascending);
            current = next;
            publishResultsChanged(next);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("Error sorting results by " + field, e);
        }
    }

    /** The active result set, or null before the first search. */
    public ResultSet getCurrent() {
        return current;
    }

    /** Clears the active result set (case closed). */
    public void clear() {
        current = null;
        timelineView = false;
    }

    private MultiSearchResult doSearch(String queryText) {
        IPEDMultiSource source = activeSource();
        String text = queryText == null ? "" : queryText;
        long start = System.currentTimeMillis();
        FilterStateService filters = filterState;
        try {
            IPEDSearcher searcher;
            if (filters != null && filters.hasQueryFilters()) {
                // legacy CaseSearcherFilter path: parse the text and AND the
                // active UI query filters into it
                Query base = new QueryBuilder(source).getQuery(text);
                searcher = new IPEDSearcher(source, filters.composeQuery(base));
            } else {
                // verbatim text path (T015 learning: ""/"*" parse differently)
                searcher = new IPEDSearcher(source, text);
            }
            MultiSearchResult result = searcher.multiSearch();
            result.setIpedSearcher(searcher);
            result.setIPEDSource(source);
            if (filters != null && filters.hasResultSetFilters()) {
                result = filters.postProcess(source, result);
                result.setIpedSearcher(searcher);
                result.setIPEDSource(source);
            }
            LOGGER.info("Search took {}ms, {} hits", System.currentTimeMillis() - start, result.getLength());
            return result;
        } catch (ParseException | QueryNodeException e) {
            // QueryBuilder throws checked exceptions for syntax errors
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (RuntimeException e) {
            // IPEDSearcher wraps query syntax errors in RuntimeException
            Throwable cause = e.getCause();
            if (cause instanceof ParseException || cause instanceof QueryNodeException) {
                throw new IllegalArgumentException(cause.getMessage(), cause);
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Search failed: " + e.getMessage(), e);
        }
    }

    private IPEDMultiSource activeSource() {
        CaseSession session = sessionManager != null ? sessionManager.getSession() : null;
        if (session == null) {
            throw new IllegalStateException("No case session is open");
        }
        return session.getSource();
    }

    private static List<ItemId> page(MultiSearchResult result, int offset, int limit) {
        int from = Math.max(0, offset);
        long to = Math.min(result.getLength(), (long) from + Math.max(0, limit));
        List<ItemId> page = new ArrayList<>((int) (to - from));
        for (int i = from; i < to; i++) {
            IItemId item = result.getItem(i);
            page.add(new ItemId(item.getSourceId(), item.getId()));
        }
        return page;
    }

    private void publishResultsChanged(ResultSet resultSet) {
        IUiEventPublisher publisher = eventPublisher;
        if (publisher != null) {
            publisher.post(UiEventTopics.RESULTS_CHANGED, resultSet.generation());
        }
    }
}

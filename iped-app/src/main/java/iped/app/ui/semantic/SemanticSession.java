package iped.app.ui.semantic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.data.IItemId;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.data.ItemId;
import iped.search.IMultiSearchResult;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.search.CaseItems;
import iped.semantic.search.MultiCaseSearch;

/**
 * The search session of the analysis UI: one {@link MultiCaseSearch} over the cases currently
 * open, rebuilt when another case is opened. The UI computes nothing itself: it hands requests to
 * the same searcher the MCP uses (SC-011).
 */
final class SemanticSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticSession.class);

    private IPEDMultiSource forCase;
    private MultiCaseSearch search;
    private List<IPEDSource> sources = new ArrayList<>();
    private boolean fresh;

    /** The session of the open case(s); rebuilt when the open case changed. */
    synchronized MultiCaseSearch get() {
        IPEDMultiSource current = App.get().appCase;
        if (current != forCase || search == null) {
            close();
            forCase = current;
            sources = new ArrayList<>(current.getAtomicSources());
            List<MultiCaseSearch.CaseRef> refs = new ArrayList<>();
            for (IPEDSource s : sources) {
                refs.add(new MultiCaseSearch.CaseRef(s.getCaseDir().getName(), s.getModuleDir(), CaseItems.of(s)));
            }
            SemanticSearchConfig cfg = config();
            search = new MultiCaseSearch(refs, cfg, new EmbedderFactory(cfg));
            fresh = true;
        }
        return search;
    }

    /** Whether no question was asked in this session yet (the model loads on the first one). */
    synchronized boolean consumeFresh() {
        boolean f = fresh;
        fresh = false;
        return f;
    }

    static SemanticSearchConfig config() {
        ConfigurationManager cm = ConfigurationManager.get();
        SemanticSearchConfig cfg = cm == null ? null : cm.findObject(SemanticSearchConfig.class);
        return cfg != null ? cfg : new SemanticSearchConfig();
    }

    synchronized List<IPEDSource> sources() {
        get();
        return sources;
    }

    /** Index of a case in this session, from the source id of an item. */
    synchronized int caseIndexOf(int sourceId) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getSourceId() == sourceId) {
                return i;
            }
        }
        return -1;
    }

    /** The item id, in the UI's terms, of an entry's item. */
    synchronized IItemId itemId(int caseIndex, int itemId) {
        return new ItemId(sources.get(caseIndex).getSourceId(), itemId);
    }

    /**
     * The scope of each case: the items of the current result of the table (FR-012). The table is
     * what the examiner already narrowed down; an unfiltered case is its whole result.
     */
    synchronized List<BitSet> scopesOfCurrentResult() {
        get();
        List<BitSet> scopes = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            scopes.add(new BitSet());
        }
        IMultiSearchResult result = App.get().getResults();
        for (int i = 0; i < result.getLength(); i++) {
            IItemId id = result.getItem(i);
            int idx = caseIndexOf(id.getSourceId());
            if (idx >= 0) {
                scopes.get(idx).set(id.getId());
            }
        }
        return scopes;
    }

    synchronized void close() {
        if (search != null) {
            try {
                search.close();
            } catch (IOException e) {
                LOGGER.warn("Closing the semantic search session failed", e);
            }
            search = null;
        }
    }
}

package iped.semantic.search;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.search.SemanticRequest.Target;

/**
 * Semantic search over several cases opened together (FR-017). Each case is evaluated against the
 * model in use in this installation - availability and fingerprint per case - and only the
 * compatible ones take part: their units are consolidated into one ordering with the same
 * deterministic tie-break, and the others are declared in a {@code CASES_EXCLUDED} notice with their
 * reason. Coverage is the sum of the included cases' scopes.
 *
 * <p>
 * Entries carry the index of their case ({@link OrderingEntry#getCaseIndex()}), in the order the
 * cases were given; item ids are those of that case's main index.
 * </p>
 */
public final class MultiCaseSearch implements Closeable {

    /** One opened case: a display name, its {@code iped} folder and the ids of its main index. */
    public static final class CaseRef {
        final String name;
        final File moduleDir;
        final BitSet items;

        public CaseRef(String name, File moduleDir, BitSet items) {
            this.name = name;
            this.moduleDir = moduleDir;
            this.items = items;
        }
    }

    private final List<String> names = new ArrayList<>();
    private final List<SemanticSearcher> searchers = new ArrayList<>();

    public MultiCaseSearch(List<CaseRef> cases, SemanticSearchConfig config, EmbedderProvider provider) {
        for (int i = 0; i < cases.size(); i++) {
            CaseRef c = cases.get(i);
            names.add(c.name);
            searchers.add(new SemanticSearcher(c.moduleDir, c.items, config, provider, i));
        }
    }

    public int size() {
        return searchers.size();
    }

    public SemanticSearcher member(int caseIndex) {
        return searchers.get(caseIndex);
    }

    public List<String> names() {
        return Collections.unmodifiableList(names);
    }

    /** Availability of a modality in each case, in case order. */
    public List<Availability> availability(Modality m) {
        List<Availability> out = new ArrayList<>();
        for (SemanticSearcher s : searchers) {
            out.add(s.availability(m));
        }
        return out;
    }

    /**
     * Answers a question: one ordering per modality of the target, never merged (FR-034).
     *
     * @param requestFor
     *            the request for each case index: the same question with that case's scope
     */
    public List<Ordering> search(Target target, IntFunction<SemanticRequest> requestFor)
            throws SemanticUnavailableException, IOException {
        List<Ordering> out = new ArrayList<>();
        if (target != Target.VISUAL) {
            out.add(search(Modality.TEXT, requestFor));
        }
        if (target != Target.TEXT) {
            out.add(search(Modality.VISUAL, requestFor));
        }
        return out;
    }

    public Ordering search(Modality modality, IntFunction<SemanticRequest> requestFor)
            throws SemanticUnavailableException, IOException {
        return SearchRun.execute(searchers, names, modality, requestFor);
    }

    public void resetSession() {
        for (SemanticSearcher s : searchers) {
            s.resetSession();
        }
    }

    @Override
    public void close() throws IOException {
        for (SemanticSearcher s : searchers) {
            s.close();
        }
    }
}

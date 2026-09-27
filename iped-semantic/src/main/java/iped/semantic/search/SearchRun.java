package iped.semantic.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;

/**
 * The one execution path of a question, for one case or several (FR-017): evaluate each case,
 * leave out the incompatible ones with a notice, collect each case's scored units, consolidate once
 * and declare. {@link SemanticSearcher#search} runs it with a single case, so a question asked from
 * the UI (always through {@link MultiCaseSearch}) and from the MCP (a single searcher) follows the
 * same steps and gives the same ordering (SC-011).
 */
final class SearchRun {

    private SearchRun() {
    }

    static Ordering execute(List<SemanticSearcher> members, List<String> names, Modality modality,
            IntFunction<SemanticRequest> requestFor) throws SemanticUnavailableException, IOException {
        List<SemanticSearcher.Plan> plans = new ArrayList<>();
        List<Notice> excluded = new ArrayList<>();
        Availability firstUnavailable = null;
        for (int i = 0; i < members.size(); i++) {
            SemanticSearcher s = members.get(i);
            Availability a = s.availability(modality);
            if (!a.isAvailable()) {
                if (firstUnavailable == null) {
                    firstUnavailable = a;
                }
                excluded.add(new Notice(Notice.Code.CASES_EXCLUDED, names.get(i), a.getReason().name()));
                continue;
            }
            plans.add(s.plan(requestFor.apply(i), modality, a));
        }
        if (plans.isEmpty()) {
            throw new SemanticUnavailableException(firstUnavailable);
        }

        List<Hit> hits = new ArrayList<>();
        Map<Integer, java.util.BitSet> scopes = new HashMap<>();
        for (SemanticSearcher.Plan p : plans) {
            hits.addAll(p.searcher().collect(p));
            scopes.put(p.searcher().getCaseIndex(), p.scope);
        }
        SemanticSearcher.Plan first = plans.get(0);
        int ceiling = first.searcher().getConfig().getMaxDepth();
        List<OrderingEntry> entries = Consolidator.consolidate(hits, ceiling, first.proximityKind(), scopes);

        if (plans.size() == 1 && excluded.isEmpty()) {
            return first.searcher().ordering(first, entries);
        }
        // several cases: sum the coverage, merge the declarations
        List<Coverage> coverages = new ArrayList<>();
        Ordering.Method method = Ordering.Method.EXACT;
        int lexical = 0;
        boolean anyHybrid = false;
        Set<Notice.Code> seen = new LinkedHashSet<>();
        List<Notice> notices = new ArrayList<>();
        for (SemanticSearcher.Plan p : plans) {
            coverages.add(p.searcher().coverage(modality, p.scope));
            if (p.method == Ordering.Method.APPROXIMATE) {
                method = Ordering.Method.APPROXIMATE;
            }
            if (p.mode == TextMode.HYBRID) {
                anyHybrid = true;
                lexical += Math.max(0, p.lexicalMatches);
            }
            for (Notice n : p.notices) {
                if (seen.add(n.getCode())) {
                    notices.add(n);
                }
            }
        }
        if (anyHybrid && lexical == 0 && seen.add(Notice.Code.LEXICAL_NONE)) {
            notices.add(new Notice(Notice.Code.LEXICAL_NONE));
        }
        notices.addAll(excluded);
        Ordering single = first.searcher().ordering(first, entries);
        return new Ordering(modality, first.mode, method, anyHybrid ? lexical : -1, ceiling, first.request.getDepth(),
                entries, Coverage.sum(coverages), single.getModel(), notices);
    }
}

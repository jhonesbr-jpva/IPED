package iped.semantic.eval;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Test;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.eval.EvalCase.Question;
import iped.semantic.search.Ordering;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Reference;

/**
 * SC-003 on the eval case (T050): starting from a pertinent item, "more like this" puts another item
 * pertinent to the <b>same subject</b> in the first ten. A subject's pertinent items are those of all
 * its text questions (gabarito version 2). A subject counts when its items are in at least two units -
 * chats or files - because the reference's own conversation is left out of its ordering; each unit is
 * tried once as the reference, and success is an item of another unit.
 */
public class SimilarQualityEval {

    @Test
    public void moreLikeThisFindsAnotherItemOfTheSubject() throws Exception {
        try (EvalCase ec = EvalCase.open(false)) {
            Map<String, Set<Integer>> relevant = new TreeMap<>();
            Map<String, Map<Integer, Set<Integer>>> units = new TreeMap<>();
            for (Question q : ec.questions) {
                if (!q.type.equals("conceptual") && !q.type.equals("mixed") && !q.type.equals("crosslingual")) {
                    continue;
                }
                relevant.computeIfAbsent(q.subject, k -> new HashSet<>()).addAll(q.relevant);
                Map<Integer, Set<Integer>> u = units.computeIfAbsent(q.subject, k -> new LinkedHashMap<>());
                q.units.forEach((unit, items) -> u.computeIfAbsent(unit, k -> new HashSet<>()).addAll(items));
            }
            int hits = 0;
            int attempts = 0;
            List<String> misses = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, Set<Integer>>> s : units.entrySet()) {
                if (s.getValue().size() < 2) {
                    continue;
                }
                for (Set<Integer> unit : s.getValue().values()) {
                    int reference = unit.iterator().next();
                    Set<Integer> others = new HashSet<>(relevant.get(s.getKey()));
                    others.removeAll(unit);
                    Ordering o = ec.searcher.search(SemanticRequest.builder().reference(Reference.item(reference))
                            .build(ec.config), Modality.TEXT);
                    attempts++;
                    if (EvalCase.hitInTop(o, others, 10)) {
                        hits++;
                    } else {
                        misses.add(s.getKey() + " from item " + reference);
                    }
                }
            }
            List<String> log = new ArrayList<>();
            log.add("- **SC-003** (≥ 70%): " + EvalCase.pct(hits, attempts) + " of the attempts from a pertinent item, "
                    + "by subject (gabarito version 2).");
            log.add("  - Missed: " + (misses.isEmpty() ? "none" : String.join("; ", misses)) + ".");
            ec.log("SimilarQualityEval — SC-003", log);
            assertTrue("SC-003 has attempts", attempts > 0);
            assertTrue("SC-003: " + EvalCase.pct(hits, attempts), hits >= 0.7 * attempts);
        }
    }
}

package iped.semantic.eval;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.eval.EvalCase.Question;
import iped.semantic.search.Ordering;
import iped.semantic.search.SemanticRequest;

/**
 * SC-001 and SC-002 on the eval case (T022). A conceptual question counts only when keyword search
 * does not find its pertinent items (the condition of SC-001); the others are dropped and listed.
 */
public class TextQualityEval {

    static Ordering ask(EvalCase ec, String question, TextMode mode) throws Exception {
        return ec.searcher.search(SemanticRequest.builder().query(question).mode(mode).build(ec.config), Modality.TEXT);
    }

    @Test
    public void conceptualAndCrossLingualQuestionsFindTheirItems() throws Exception {
        try (EvalCase ec = EvalCase.open(false)) {
            List<String> dropped = new ArrayList<>();
            List<String> misses = new ArrayList<>();
            int hits = 0;
            int total = 0;
            for (Question q : ec.ofType("conceptual")) {
                if (ec.keywordFinds(q)) {
                    dropped.add(q.id);
                    continue;
                }
                total++;
                if (EvalCase.hitInTop(ask(ec, q.text, TextMode.SEMANTIC), q.relevant, 10)) {
                    hits++;
                } else {
                    misses.add(q.id + " \"" + q.text + "\"");
                }
            }
            int xHits = 0;
            int xTotal = 0;
            List<String> xMisses = new ArrayList<>();
            for (Question q : ec.ofType("crosslingual")) {
                xTotal++;
                if (EvalCase.hitInTop(ask(ec, q.text, TextMode.SEMANTIC), q.relevant, 10)) {
                    xHits++;
                } else {
                    xMisses.add(q.id + " \"" + q.text + "\"");
                }
            }
            List<String> log = new ArrayList<>();
            log.add("- **SC-001** (≥ 80%, semantic mode): " + EvalCase.pct(hits, total) + " of the conceptual questions "
                    + "keyword search does not answer.");
            log.add("  - Dropped because keyword search finds a pertinent item (" + dropped.size() + "): "
                    + (dropped.isEmpty() ? "none" : String.join(", ", dropped)) + ".");
            log.add("  - Missed: " + (misses.isEmpty() ? "none" : String.join("; ", misses)) + ".");
            log.add("- **SC-002** (≥ 70%): " + EvalCase.pct(xHits, xTotal) + " of the questions whose pertinent items "
                    + "are in another language.");
            log.add("  - Missed: " + (xMisses.isEmpty() ? "none" : String.join("; ", xMisses)) + ".");
            ec.log("TextQualityEval — SC-001, SC-002", log);

            assertTrue("SC-001 needs at least 30 questions keyword search does not answer; " + total + " remain",
                    total >= 30);
            assertTrue("SC-001: " + EvalCase.pct(hits, total), hits >= 0.8 * total);
            assertTrue("SC-002: " + EvalCase.pct(xHits, xTotal), xTotal >= 10 && xHits >= 0.7 * xTotal);
        }
    }

    /**
     * Acceptance scenario 2 of User Story 1 (gabarito version 3): when the long report comes among the
     * first ten, the excerpt shown for it - the entry's first - is the pertinent paragraph, in both
     * modes.
     */
    @Test
    public void theExcerptShownIsThePertinentParagraph() throws Exception {
        try (EvalCase ec = EvalCase.open(false)) {
            int hits = 0;
            int total = 0;
            List<String> misses = new ArrayList<>();
            for (Question q : ec.questions) {
                if (q.excerpt == null) {
                    continue;
                }
                for (TextMode mode : TextMode.values()) {
                    total++;
                    String shown = shownExcerpt(ask(ec, q.text, mode), q);
                    if (shown != null && shown.replaceAll("\\s+", " ").contains(q.excerpt)) {
                        hits++;
                    } else {
                        misses.add(q.id + " " + mode + (shown == null ? " (item not in the first ten)"
                                : " showed \"" + abbreviate(shown) + "\""));
                    }
                }
            }
            List<String> log = new ArrayList<>();
            log.add("- **US1, acceptance scenario 2**: the excerpt shown is the pertinent paragraph in " + EvalCase.pct(hits,
                    total) + " of the questions on the long report, in both modes (gabarito version 3).");
            log.add("  - Missed: " + (misses.isEmpty() ? "none" : String.join("; ", misses)) + ".");
            ec.log("TextQualityEval — User Story 1, scenario 2", log);
            assertTrue("the gabarito has questions with an excerpt", total > 0);
            assertTrue("US1 scenario 2: " + String.join("; ", misses), misses.isEmpty());
        }
    }

    /** The first excerpt of the first entry, among the first ten, that holds a pertinent item. */
    private static String shownExcerpt(Ordering o, Question q) {
        int n = 0;
        for (iped.semantic.search.OrderingEntry e : o.getAll()) {
            if (n++ >= 10) {
                break;
            }
            if (e.targetItemIds().stream().anyMatch(q.relevant::contains) || q.relevant.contains(e.getItemId())) {
                return e.getExcerpts().isEmpty() ? "" : e.getExcerpts().get(0).getText();
            }
        }
        return null;
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 60 ? one.substring(0, 60) + "…" : one;
    }
}

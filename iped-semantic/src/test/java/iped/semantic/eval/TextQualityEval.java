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
}

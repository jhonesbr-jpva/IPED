package iped.semantic.eval;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.eval.EvalCase.Question;

/**
 * SC-018 on the eval case (T063): the hybrid mode puts a pertinent item in the first ten for at least
 * 80% of the questions that mix a concept with a specific term, and loses at most 5 points against
 * the semantic mode on the questions of SC-001. Records which mode the numbers favour as the
 * factory {@code defaultTextMode} (research D20).
 */
public class HybridQualityEval {

    @Test
    public void mixedQuestionsAndTheCostOnConceptualOnes() throws Exception {
        try (EvalCase ec = EvalCase.open(false)) {
            int mixedHybrid = 0;
            int mixedSemantic = 0;
            int mixedTotal = 0;
            List<String> mixedMisses = new ArrayList<>();
            for (Question q : ec.ofType("mixed")) {
                mixedTotal++;
                boolean h = EvalCase.hitInTop(TextQualityEval.ask(ec, q.text, TextMode.HYBRID), q.relevant, 10);
                boolean s = EvalCase.hitInTop(TextQualityEval.ask(ec, q.text, TextMode.SEMANTIC), q.relevant, 10);
                mixedHybrid += h ? 1 : 0;
                mixedSemantic += s ? 1 : 0;
                if (!h) {
                    mixedMisses.add(q.id + " \"" + q.text + "\"");
                }
            }
            int cHybrid = 0;
            int cSemantic = 0;
            int cTotal = 0;
            for (Question q : ec.ofType("conceptual")) {
                if (ec.keywordFinds(q)) {
                    continue;
                }
                cTotal++;
                cHybrid += EvalCase.hitInTop(TextQualityEval.ask(ec, q.text, TextMode.HYBRID), q.relevant, 10) ? 1 : 0;
                cSemantic += EvalCase.hitInTop(TextQualityEval.ask(ec, q.text, TextMode.SEMANTIC), q.relevant, 10)
                        ? 1 : 0;
            }
            double loss = cTotal == 0 ? 0 : 100.0 * (cSemantic - cHybrid) / cTotal;
            double mixedGain = mixedTotal == 0 ? 0 : 100.0 * (mixedHybrid - mixedSemantic) / mixedTotal;
            String winner = loss <= 5 && mixedGain >= 0 ? "HYBRID" : "SEMANTIC";

            List<String> log = new ArrayList<>();
            log.add("- **SC-018** (≥ 80%, hybrid mode): " + EvalCase.pct(mixedHybrid, mixedTotal)
                    + " of the mixed questions; the semantic mode alone: " + EvalCase.pct(mixedSemantic, mixedTotal)
                    + ".");
            log.add("  - Missed in hybrid mode: " + (mixedMisses.isEmpty() ? "none" : String.join("; ", mixedMisses))
                    + ".");
            log.add("- **SC-018, cost** (≤ 5 points): on the questions of SC-001, hybrid " + EvalCase.pct(cHybrid, cTotal)
                    + " against semantic " + EvalCase.pct(cSemantic, cTotal) + String.format(java.util.Locale.ROOT,
                            " — %.1f points %s.", Math.abs(loss), loss >= 0 ? "lost" : "gained"));
            log.add("- **Factory `defaultTextMode` the numbers favour** (research D20): `" + winner + "` "
                    + "(hybrid wins when it gains on the mixed questions and loses at most 5 points on the others).");
            ec.log("HybridQualityEval — SC-018", log);

            assertTrue("SC-018 needs at least 15 mixed questions", mixedTotal >= 15);
            assertTrue("SC-018: " + EvalCase.pct(mixedHybrid, mixedTotal), mixedHybrid >= 0.8 * mixedTotal);
            assertTrue("SC-018 cost: " + loss + " points", loss <= 5.0);
        }
    }
}

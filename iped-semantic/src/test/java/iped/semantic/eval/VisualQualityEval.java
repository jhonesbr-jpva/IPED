package iped.semantic.eval;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.eval.EvalCase.Question;
import iped.semantic.search.Ordering;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Target;

/**
 * SC-012, SC-014 and FR-036 on the eval case (T074): descriptions in Portuguese find their pictures
 * and their videos in the first ten of the visual ordering; the descriptions in English are counted
 * apart.
 */
public class VisualQualityEval {

    @Test
    public void descriptionsFindTheirPicturesAndVideos() throws Exception {
        try (EvalCase ec = EvalCase.open(true)) {
            int[] pt = new int[2];
            int[] en = new int[2];
            int[] video = new int[2];
            List<String> misses = new ArrayList<>();
            for (Question q : ec.ofType("visual-image")) {
                int[] tally = "en".equals(q.lang) ? en : pt;
                tally[1]++;
                if (EvalCase.hitInTop(ask(ec, q.text), q.relevant, 10)) {
                    tally[0]++;
                } else {
                    misses.add(q.id + " \"" + q.text + "\"");
                }
            }
            for (Question q : ec.ofType("visual-video")) {
                video[1]++;
                if (EvalCase.hitInTop(ask(ec, q.text), q.relevant, 10)) {
                    video[0]++;
                } else {
                    misses.add(q.id + " \"" + q.text + "\"");
                }
            }
            List<String> log = new ArrayList<>();
            log.add("- **SC-012** (≥ 70%): " + EvalCase.pct(pt[0], pt[1]) + " of the descriptions in Portuguese find a "
                    + "pertinent picture.");
            log.add("- **FR-036**, descriptions in English: " + EvalCase.pct(en[0], en[1]) + ".");
            log.add("- **SC-014** (≥ 70%): " + EvalCase.pct(video[0], video[1]) + " of the descriptions find the video.");
            log.add("  - Missed: " + (misses.isEmpty() ? "none" : String.join("; ", misses)) + ".");
            ec.log("VisualQualityEval — SC-012, SC-014, FR-036", log);

            assertTrue("SC-012 needs 30 descriptions", pt[1] >= 30);
            assertTrue("SC-012: " + EvalCase.pct(pt[0], pt[1]), pt[0] >= 0.7 * pt[1]);
            assertTrue("SC-014 needs 15 descriptions", video[1] >= 15);
            assertTrue("SC-014: " + EvalCase.pct(video[0], video[1]), video[0] >= 0.7 * video[1]);
        }
    }

    private static Ordering ask(EvalCase ec, String description) throws Exception {
        return ec.searcher.search(SemanticRequest.builder().target(Target.VISUAL).query(description).build(ec.config),
                Modality.VISUAL);
    }
}

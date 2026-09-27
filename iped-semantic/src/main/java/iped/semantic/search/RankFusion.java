package iped.semantic.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion of the semantic list and the word list (research D20, FR-046): each unit
 * scores {@code sum over the lists of 1 / (k + position)}, positions from 1, over the union of both
 * lists. By position, never by adding a cosine to a BM25 score - those are not comparable, and
 * calibrating them per case and per question is exactly what the spec forbids (FR-034 reasoning).
 * Ties are broken as everywhere else: item, then ordinal ({@link Consolidator#ORDER}).
 */
public final class RankFusion {

    private RankFusion() {
    }

    /**
     * @param semantic
     *            units in semantic order (best first)
     * @param lexical
     *            units in word order (best first)
     * @return the union with fused scores and both positions recorded, sorted
     */
    public static List<Hit> fuse(List<Hit> semantic, List<Hit> lexical, int k) {
        return fuse(semantic, lexical, k, 1.0);
    }

    /**
     * Weighted fusion: the word list counts {@code lexicalWeight} times its reciprocal rank. Below 1,
     * a unit found only by words enters below the head of the semantic list (at 0.5, below about the
     * 60th semantic position with {@code k = 60}), and words reorder what meaning already found
     * plausible - which is what they are for when the question mixes a concept with an exact term.
     * Unweighted, the heads of both lists interleave one to one, and on a question with no exact
     * term the word list is noise that pushes pertinent units out (measured: SC-018, research D20).
     */
    public static List<Hit> fuse(List<Hit> semantic, List<Hit> lexical, int k, double lexicalWeight) {
        Map<Long, Hit> byUnit = new LinkedHashMap<>();
        Map<Long, Double> score = new LinkedHashMap<>();
        for (int i = 0; i < semantic.size(); i++) {
            Hit h = semantic.get(i);
            byUnit.put(h.unitKey(), h);
            h.semanticRank = i + 1;
            score.merge(h.unitKey(), 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < lexical.size(); i++) {
            Hit h = lexical.get(i);
            Hit known = byUnit.get(h.unitKey());
            if (known == null) {
                known = h;
                byUnit.put(h.unitKey(), h);
            }
            known.lexicalRank = i + 1;
            score.merge(h.unitKey(), lexicalWeight / (k + i + 1), Double::sum);
        }
        List<Hit> out = new ArrayList<>(byUnit.size());
        for (Map.Entry<Long, Hit> e : byUnit.entrySet()) {
            Hit h = e.getValue();
            h.score = score.get(e.getKey());
            out.add(h);
        }
        out.sort(Consolidator.ORDER);
        return out;
    }
}

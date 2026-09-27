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
            score.merge(h.unitKey(), 1.0 / (k + i + 1), Double::sum);
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

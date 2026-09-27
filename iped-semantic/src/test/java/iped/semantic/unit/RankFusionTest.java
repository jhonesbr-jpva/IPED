package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import iped.semantic.search.Hit;
import iped.semantic.search.RankFusion;
import iped.semantic.store.SemanticFields;

public class RankFusionTest {

    private static Hit h(int doc, int item) {
        return Hit.passage(0, doc, 0, SemanticFields.KIND_PASSAGE, item, 0, 0, 10, "t" + item);
    }

    private static List<Integer> items(List<Hit> hits) {
        return hits.stream().map(Hit::getItemId).collect(Collectors.toList());
    }

    @Test
    public void scoreIsTheSumOfReciprocalRanksOverTheUnion() {
        Hit a = h(1, 10), b = h(2, 20), c = h(3, 30), d = h(4, 40);
        List<Hit> fused = RankFusion.fuse(List.of(a, b, c), List.of(c, d), 60);
        assertEquals(4, fused.size());
        // c: semantic #3 and lexical #1
        assertEquals(30, fused.get(0).getItemId());
        assertEquals(1.0 / 63 + 1.0 / 61, fused.get(0).getScore(), 1e-12);
        assertEquals(1.0 / 61, fused.get(1).getScore(), 1e-12); // a, semantic #1 only
        assertEquals(10, fused.get(1).getItemId());
    }

    @Test
    public void ranksRecordWhereEachUnitCameFrom() {
        Hit a = h(1, 10), b = h(2, 20);
        Hit onlyLexical = h(3, 30);
        List<Hit> fused = RankFusion.fuse(new ArrayList<>(List.of(a, b)), List.of(onlyLexical, a), 60);
        Hit first = fused.get(0);
        assertEquals(10, first.getItemId());
        assertEquals(Integer.valueOf(1), rank(first, true));
        assertEquals(Integer.valueOf(2), rank(first, false));
        Hit lex = fused.stream().filter(x -> x.getItemId() == 30).findFirst().get();
        assertNull(rank(lex, true));
        assertEquals(Integer.valueOf(1), rank(lex, false));
    }

    @Test
    public void anEmptyWordListKeepsTheSemanticOrder() {
        List<Hit> sem = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sem.add(h(i, 100 - i));
        }
        List<Integer> before = items(sem);
        assertEquals(before, items(RankFusion.fuse(sem, List.of(), 60)));
    }

    @Test
    public void tiesAreBrokenByItemThenOrdinal() {
        // two units, each first in one list: same fused score
        Hit a = h(1, 50), b = h(2, 20);
        List<Hit> fused = RankFusion.fuse(List.of(a), List.of(b), 60);
        assertEquals(List.of(20, 50), items(fused));
    }

    private static Integer rank(Hit h, boolean semantic) {
        try {
            java.lang.reflect.Field f = Hit.class.getDeclaredField(semantic ? "semanticRank" : "lexicalRank");
            f.setAccessible(true);
            return (Integer) f.get(h);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

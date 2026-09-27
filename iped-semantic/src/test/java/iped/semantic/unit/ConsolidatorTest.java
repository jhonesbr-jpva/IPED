package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import iped.semantic.search.Consolidator;
import iped.semantic.search.Hit;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.OrderingEntry.MessageLine;
import iped.semantic.search.OrderingEntry.ProximityKind;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SequenceLine;

public class ConsolidatorTest {

    private static int doc = 0;

    private static Hit p(double score, int item, int ordinal) {
        return Hit.passage(0, doc++, score, SemanticFields.KIND_PASSAGE, item, ordinal, ordinal * 800,
                ordinal * 800 + 1000, "item " + item + " passage " + ordinal);
    }

    private static Hit seq(double score, int chat, int ordinal, int... messages) {
        List<SequenceLine> lines = new ArrayList<>();
        for (int m : messages) {
            lines.add(new SequenceLine(m, "A", 1_700_000_000_000L + m, "msg " + m));
        }
        return Hit.sequence(0, doc++, score, chat, ordinal, lines);
    }

    private static List<OrderingEntry> run(List<Hit> hits, int ceiling) {
        return Consolidator.consolidate(hits, ceiling, ProximityKind.RELATIVE, null);
    }

    @Test
    public void oneEntryPerItemWithBestPassageAndUpToTwoMoreAsContext() {
        List<Hit> hits = List.of(p(0.90, 7, 3), p(0.88, 9, 0), p(0.85, 7, 4), p(0.80, 7, 1), p(0.79, 7, 8),
                p(0.70, 9, 2));
        List<OrderingEntry> out = run(new ArrayList<>(hits), 100);
        assertEquals(2, out.size());
        OrderingEntry first = out.get(0);
        assertEquals(7, first.getItemId());
        assertEquals(1, first.getRank());
        assertEquals(0.90, first.getProximity(), 0);
        assertEquals(3, first.getOrdinal());
        assertEquals(3, first.getExcerpts().size());
        assertEquals(3, first.getExcerpts().get(0).getOrdinal()); // best first
        assertEquals(4, first.getExcerpts().get(1).getOrdinal()); // then by proximity
        assertEquals(1, first.getExcerpts().get(2).getOrdinal());
        assertEquals(2, out.get(1).getRank());
        assertEquals(9, out.get(1).getItemId());
        assertEquals(2, out.get(1).getExcerpts().size());
    }

    @Test
    public void overlappingWindowsAreAbsorbedAndNoMessageAppearsTwice() {
        List<Hit> hits = new ArrayList<>(List.of(
                seq(0.95, 100, 1, 104, 105, 106, 107, 108, 109, 110, 111),
                seq(0.93, 100, 0, 100, 101, 102, 103, 104, 105, 106, 107), // overlaps the first
                seq(0.91, 100, 2, 108, 109, 110, 111, 112, 113, 114, 115), // overlaps the first
                seq(0.60, 100, 4, 116, 117, 118), // disjoint: its own entry
                p(0.92, 5, 0)));
        List<OrderingEntry> out = run(hits, 100);
        assertEquals(3, out.size());
        assertEquals("sequence", out.get(0).getKind());
        assertEquals(2, out.get(0).getSequence().getAbsorbedWindows());
        assertEquals(5, out.get(1).getItemId());
        assertEquals(0, out.get(2).getSequence().getAbsorbedWindows());
        Set<Integer> seen = new HashSet<>();
        for (OrderingEntry e : out) {
            if (e.getSequence() != null) {
                for (MessageLine m : e.getSequence().getMessages()) {
                    assertTrue("message " + m.getItemId() + " in two entries", seen.add(m.getItemId()));
                }
            }
        }
    }

    @Test
    public void tiesAreBrokenByItemThenOrdinal() {
        List<Hit> hits = new ArrayList<>(List.of(p(0.5, 30, 2), p(0.5, 10, 7), p(0.5, 20, 1), p(0.5, 10, 3),
                p(0.6, 99, 0)));
        for (int attempt = 0; attempt < 5; attempt++) {
            Collections.shuffle(hits, new java.util.Random(attempt));
            List<OrderingEntry> out = run(hits, 100);
            assertEquals(List.of(99, 10, 20, 30),
                    out.stream().map(OrderingEntry::getItemId).collect(java.util.stream.Collectors.toList()));
            assertEquals(3, out.get(1).getOrdinal()); // ordinal 3 before 7 on the same score
        }
    }

    @Test
    public void theCeilingLimitsEntriesNotPassagesOfAcceptedItems() {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            hits.add(p(1.0 - i * 0.01, i, 0));
        }
        hits.add(p(0.001, 0, 5)); // context for item 0, far down
        List<OrderingEntry> out = run(hits, 10);
        assertEquals(10, out.size());
        assertEquals(2, out.get(0).getExcerpts().size());
    }

    @Test
    public void videoAppearsOnceByItsBestFrameAndOutOfScopeMessagesAreMarked() {
        List<Hit> hits = new ArrayList<>();
        hits.add(new Hit(0, 1, 0.4, SemanticFields.KIND_VIDEO_FRAME, 70, 2, null, null, null, null, 2, 12, 15_000L));
        hits.add(new Hit(0, 2, 0.6, SemanticFields.KIND_VIDEO_FRAME, 70, 5, null, null, null, null, 5, 12, 42_000L));
        hits.add(seq(0.5, 300, 0, 301, 302, 303));
        BitSet scope = new BitSet();
        scope.set(302);
        List<OrderingEntry> out = Consolidator.consolidate(hits, 100, ProximityKind.RELATIVE, Map.of(0, scope));
        assertEquals(2, out.size());
        assertEquals("video", out.get(0).getKind());
        assertEquals(5, out.get(0).getFrame().getFrameIndex());
        assertEquals(Long.valueOf(42_000L), out.get(0).getFrame().getInstantApproxMs());
        List<MessageLine> lines = out.get(1).getSequence().getMessages();
        assertFalse(lines.get(0).isInScope());
        assertTrue(lines.get(1).isInScope());
        assertEquals(List.of(302), out.get(1).targetItemIds());
    }
}

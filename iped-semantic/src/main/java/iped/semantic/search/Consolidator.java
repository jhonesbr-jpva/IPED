package iped.semantic.search;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import iped.semantic.search.OrderingEntry.Excerpt;
import iped.semantic.search.OrderingEntry.Frame;
import iped.semantic.search.OrderingEntry.MessageLine;
import iped.semantic.search.OrderingEntry.ProximityKind;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SequenceLine;

/**
 * Turns scored units into ordering entries (research D5, FR-009):
 * <ul>
 * <li>passages: one entry per item, with the best passage and up to two more of the same item that
 * come next in the ranking, as context;</li>
 * <li>sequences: greedy absorption - a window of lower proximity that shares a message with an
 * accepted one is absorbed by it, so no message appears in two entries;</li>
 * <li>video frames: one entry per video, by its best frame; images: one entry per item.</li>
 * </ul>
 * Order is deterministic: proximity descending, then case, then item id, then ordinal (FR-015).
 */
public final class Consolidator {

    public static final int MAX_EXCERPTS = 3;

    /** The deterministic order of units and entries. */
    public static final Comparator<Hit> ORDER = Comparator.comparingDouble((Hit h) -> -h.score)
            .thenComparingInt(h -> h.caseIndex).thenComparingInt(h -> h.itemId).thenComparingInt(h -> h.ordinal);

    private Consolidator() {
    }

    private static final class Builder {
        final Hit best;
        final List<Excerpt> excerpts = new ArrayList<>();
        final Set<Integer> messages;
        int absorbed;

        Builder(Hit best) {
            this.best = best;
            this.messages = new HashSet<>();
            for (SequenceLine l : best.lines) {
                messages.add(l.getItemId());
            }
        }
    }

    /**
     * @param hits
     *            scored units (any order; sorted here)
     * @param ceiling
     *            maximum number of entries
     * @param proximityKind
     *            how the proximity reads
     * @param scopes
     *            per case index, the scope used to mark messages of a sequence; a missing or
     *            {@code null} entry means the whole case
     */
    public static List<OrderingEntry> consolidate(List<Hit> hits, int ceiling, ProximityKind proximityKind,
            Map<Integer, BitSet> scopes) {
        List<Hit> sorted = new ArrayList<>(hits);
        sorted.sort(ORDER);

        List<Builder> accepted = new ArrayList<>();
        Map<Long, Builder> byItem = new HashMap<>();
        Map<Integer, List<Builder>> sequencesByCase = new HashMap<>();

        for (Hit h : sorted) {
            if (SemanticFields.KIND_SEQUENCE.equals(h.kind)) {
                List<Builder> seqs = sequencesByCase.computeIfAbsent(h.caseIndex, k -> new ArrayList<>());
                Builder into = null;
                for (Builder b : seqs) {
                    for (SequenceLine l : h.lines) {
                        if (b.messages.contains(l.getItemId())) {
                            into = b;
                            break;
                        }
                    }
                    if (into != null) {
                        break;
                    }
                }
                if (into != null) {
                    into.absorbed++;
                    continue;
                }
                if (accepted.size() >= ceiling) {
                    continue;
                }
                Builder b = new Builder(h);
                seqs.add(b);
                accepted.add(b);
                continue;
            }
            long key = ((long) h.caseIndex << 32) | (h.itemId & 0xFFFFFFFFL);
            Builder existing = byItem.get(key);
            if (existing != null) {
                if (h.text != null && existing.excerpts.size() < MAX_EXCERPTS && isTextKind(existing.best.kind)) {
                    existing.excerpts.add(new Excerpt(h.ordinal, h.charStart, h.charEnd, h.text));
                }
                continue;
            }
            if (accepted.size() >= ceiling) {
                continue;
            }
            Builder b = new Builder(h);
            if (h.text != null) {
                b.excerpts.add(new Excerpt(h.ordinal, h.charStart, h.charEnd, h.text));
            }
            byItem.put(key, b);
            accepted.add(b);
        }

        List<OrderingEntry> out = new ArrayList<>(accepted.size());
        int rank = 1;
        for (Builder b : accepted) {
            Hit h = b.best;
            OrderingEntry.Sequence seq = null;
            if (SemanticFields.KIND_SEQUENCE.equals(h.kind)) {
                BitSet scope = scopes == null ? null : scopes.get(h.caseIndex);
                List<MessageLine> lines = new ArrayList<>();
                for (SequenceLine l : h.lines) {
                    lines.add(new MessageLine(l.getItemId(), l.getSender(), l.getDateMillis(), l.getDate(), l.getText(),
                            scope == null || scope.get(l.getItemId())));
                }
                seq = new OrderingEntry.Sequence(lines, b.absorbed);
            }
            Frame frame = null;
            String kind = h.kind;
            if (SemanticFields.KIND_VIDEO_FRAME.equals(h.kind)) {
                frame = new Frame(h.frameIndex == null ? h.ordinal : h.frameIndex, h.frameCount, h.instantApproxMs);
                kind = "video";
            }
            out.add(new OrderingEntry(rank++, h.score, proximityKind, h.semanticRank, h.lexicalRank, kind, h.caseIndex,
                    h.itemId, h.ordinal, b.excerpts, seq, frame));
        }
        return out;
    }

    private static boolean isTextKind(String kind) {
        return SemanticFields.KIND_PASSAGE.equals(kind) || SemanticFields.KIND_TRANSCRIPT.equals(kind);
    }

    /** Ids of the items or messages every entry targets; used to check disjointness. */
    static Map<Integer, Integer> targets(List<OrderingEntry> entries) {
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        for (OrderingEntry e : entries) {
            for (Integer id : e.targetItemIds()) {
                seen.merge(id, 1, Integer::sum);
            }
        }
        return seen;
    }
}

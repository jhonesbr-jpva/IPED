package iped.semantic.search;

import java.io.IOException;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;

import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;

/**
 * Coverage of a scope in one modality (data-model E9): how many items were within reach, and why
 * the others were not. Invariant: {@code covered + sum(notCovered) = recorteSize}; no item vanishes
 * from the count.
 *
 * <p>
 * The status documents are read once per session into a byte per item id (research D12); each
 * question then counts over the scope's bit set. An item of the scope without a status document gets
 * a reason derived from the build record: {@link CoverageReason#BEFORE_CAPABILITY} when it predates
 * the capability in the case, otherwise {@link CoverageReason#PREP_FAILURE} - its preparation did not
 * complete (an interrupted processing), which the partial flag or this reason declares instead of
 * hiding.
 * </p>
 */
public final class Coverage {

    private static final byte NONE = -1;
    private static final CoverageReason[] REASONS = CoverageReason.values();

    private final int recorteSize;
    private final long covered;
    private final Map<CoverageReason, Long> notCovered;
    private final boolean partial;

    public Coverage(int recorteSize, long covered, Map<CoverageReason, Long> notCovered, boolean partial) {
        this.recorteSize = recorteSize;
        this.covered = covered;
        this.notCovered = Collections.unmodifiableMap(new EnumMap<>(notCovered.isEmpty()
                ? new EnumMap<>(CoverageReason.class) : notCovered));
        this.partial = partial;
    }

    public int getRecorteSize() {
        return recorteSize;
    }

    public long getCovered() {
        return covered;
    }

    public Map<CoverageReason, Long> getNotCovered() {
        return notCovered;
    }

    public long getNotCoveredTotal() {
        long s = 0;
        for (long v : notCovered.values()) {
            s += v;
        }
        return s;
    }

    public boolean isPartial() {
        return partial;
    }

    /** Sum of coverages (several cases in one question). */
    public static Coverage sum(Iterable<Coverage> parts) {
        int size = 0;
        long cov = 0;
        boolean partial = false;
        Map<CoverageReason, Long> nc = new EnumMap<>(CoverageReason.class);
        for (Coverage c : parts) {
            size += c.recorteSize;
            cov += c.covered;
            partial |= c.partial;
            c.notCovered.forEach((k, v) -> nc.merge(k, v, Long::sum));
        }
        return new Coverage(size, cov, nc, partial);
    }

    /** Status of every item of one modality index, loaded once per session. */
    public static final class Table {
        private final byte[] reasonById;
        private final Integer capabilityStartId;
        private final boolean partial;

        private Table(byte[] reasonById, Integer capabilityStartId, boolean partial) {
            this.reasonById = reasonById;
            this.capabilityStartId = capabilityStartId;
            this.partial = partial;
        }

        /** Reads the {@code status} documents of an index. */
        public static Table load(SemanticIndexReader index, ModalityRecord record, int maxItemId) throws IOException {
            byte[] table = new byte[Math.max(0, maxItemId) + 1];
            java.util.Arrays.fill(table, NONE);
            for (LeafReaderContext ctx : index.reader().leaves()) {
                LeafReader leaf = ctx.reader();
                SortedDocValues reasons = leaf.getSortedDocValues(SemanticFields.REASON);
                NumericDocValues ids = leaf.getNumericDocValues(SemanticFields.ITEM_ID);
                if (reasons == null || ids == null) {
                    continue;
                }
                Bits live = leaf.getLiveDocs();
                byte[] ordToReason = new byte[reasons.getValueCount()];
                for (int ord = 0; ord < ordToReason.length; ord++) {
                    CoverageReason r = CoverageReason.fromCode(reasons.lookupOrd(ord).utf8ToString());
                    ordToReason[ord] = r == null ? NONE : (byte) r.ordinal();
                }
                for (int doc = reasons.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = reasons.nextDoc()) {
                    if (live != null && !live.get(doc)) {
                        continue;
                    }
                    if (!ids.advanceExact(doc)) {
                        continue;
                    }
                    int id = (int) ids.longValue();
                    if (id >= 0 && id < table.length) {
                        table[id] = ordToReason[reasons.ordValue()];
                    }
                }
            }
            return new Table(table, record == null ? null : record.capabilityStartId,
                    record != null && record.state != iped.semantic.store.BuildRecord.State.COMPLETE);
        }

        /** Stored or derived reason of an item. */
        public CoverageReason reasonOf(int itemId) {
            byte b = itemId >= 0 && itemId < reasonById.length ? reasonById[itemId] : NONE;
            if (b != NONE) {
                return REASONS[b];
            }
            if (capabilityStartId != null && itemId < capabilityStartId) {
                return CoverageReason.BEFORE_CAPABILITY;
            }
            return CoverageReason.PREP_FAILURE;
        }

        /** Whether the item is within reach (a reference item must be). */
        public boolean isCovered(int itemId) {
            return reasonOf(itemId).isCovered();
        }

        /**
         * Counts a scope. {@code scope == null} means every item of the case, whose ids run from 0 to
         * {@code maxItemId}, restricted to {@code existing} when given (ids present in the main index).
         */
        public Coverage count(BitSet scope, BitSet existing) {
            Map<CoverageReason, Long> nc = new EnumMap<>(CoverageReason.class);
            long covered = 0;
            int size = 0;
            BitSet s = scope != null ? scope : existing;
            if (s == null) {
                throw new IllegalArgumentException("scope or the set of existing ids is required");
            }
            for (int id = s.nextSetBit(0); id >= 0; id = s.nextSetBit(id + 1)) {
                size++;
                CoverageReason r = reasonOf(id);
                if (r.isCovered()) {
                    covered++;
                } else {
                    nc.merge(r, 1L, Long::sum);
                }
            }
            return new Coverage(size, covered, nc, partial);
        }
    }
}

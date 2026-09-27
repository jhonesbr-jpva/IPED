package iped.semantic.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

import iped.semantic.model.EmbeddingException;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;

/** What the text and the visual preparations share. */
final class PreparationSupport {

    private PreparationSupport() {
    }

    /** Status documents per reason code, over the whole committed index (so appends add up). */
    static Map<String, Long> countReasons(Path indexDir) throws IOException {
        Map<CoverageReason, Long> byReason = new EnumMap<>(CoverageReason.class);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            for (LeafReaderContext ctx : reader.leaves()) {
                SortedDocValues dv = ctx.reader().getSortedDocValues(SemanticFields.REASON);
                if (dv == null) {
                    continue;
                }
                Bits live = ctx.reader().getLiveDocs();
                for (int doc = dv.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = dv.nextDoc()) {
                    if (live != null && !live.get(doc)) {
                        continue;
                    }
                    CoverageReason r = CoverageReason.fromCode(dv.lookupOrd(dv.ordValue()).utf8ToString());
                    if (r != null) {
                        byReason.merge(r, 1L, Long::sum);
                    }
                }
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        byReason.forEach((k, v) -> out.put(k.code(), v));
        return out;
    }

    /**
     * The reason an embedding failure leaves on an item: a remote source that stayed unreachable, or
     * that refused the request as misconfigured (a credential refused, an answer without identity),
     * is {@code REMOTE_UNAVAILABLE}; anything else is a failure of this item.
     */
    static CoverageReason failureReason(Exception e) {
        if (e instanceof EmbeddingException) {
            EmbeddingException ee = (EmbeddingException) e;
            if (ee.isRemoteUnavailable() || ee.isConfiguration()) {
                return CoverageReason.REMOTE_UNAVAILABLE;
            }
        }
        return CoverageReason.PREP_FAILURE;
    }

    /**
     * The first configuration failure of the model source during processing, such as a credential
     * the destination refuses (HTTP 401/403). Retrying cannot fix it, so the modality stops sending:
     * the remaining items are recorded as {@code REMOTE_UNAVAILABLE}, the build record as
     * {@code PARTIAL} with the reason, and the processing goes on (contracts/remote-model-protocol.md).
     */
    static final class Stop {
        private volatile String reason;

        /** @return {@code true} the first time a configuration failure is seen */
        synchronized boolean record(Exception e) {
            if (reason == null && e instanceof EmbeddingException && ((EmbeddingException) e).isConfiguration()) {
                reason = e.getMessage();
                return true;
            }
            return false;
        }

        String reason() {
            return reason;
        }
    }
}

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

    /** The reason an embedding failure leaves on an item. */
    static CoverageReason failureReason(Exception e) {
        return e instanceof EmbeddingException && ((EmbeddingException) e).isRemoteUnavailable()
                ? CoverageReason.REMOTE_UNAVAILABLE
                : CoverageReason.PREP_FAILURE;
    }
}

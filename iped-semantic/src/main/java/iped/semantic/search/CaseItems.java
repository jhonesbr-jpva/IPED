package iped.semantic.search;

import java.io.IOException;
import java.util.BitSet;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;

import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;

/**
 * The set of item ids present in a case's main index: the universe of a question without scope, and
 * what coverage counts over. Read through public API only; nothing is written.
 */
public final class CaseItems {

    private CaseItems() {
    }

    /** Ids of an opened case. */
    public static BitSet of(IPEDSource source) {
        BitSet ids = new BitSet(source.getLastId() + 1);
        for (int id = 0; id <= source.getLastId(); id++) {
            if (source.getLuceneId(id) >= 0) {
                ids.set(id);
            }
        }
        return ids;
    }

    /** Ids of a main index read directly (live documents with the {@code id} doc value). */
    public static BitSet of(IndexReader mainIndex) throws IOException {
        BitSet ids = new BitSet();
        for (LeafReaderContext ctx : mainIndex.leaves()) {
            LeafReader leaf = ctx.reader();
            NumericDocValues ndv = leaf.getNumericDocValues(IndexItem.ID);
            if (ndv == null) {
                continue;
            }
            Bits live = leaf.getLiveDocs();
            for (int doc = ndv.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = ndv.nextDoc()) {
                if (live == null || live.get(doc)) {
                    ids.set((int) ndv.longValue());
                }
            }
        }
        return ids;
    }
}

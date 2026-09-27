package iped.semantic.search;

import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import iped.semantic.store.SemanticFields;

/**
 * Matches the semantic documents that belong to a scope: those with at least one anchor id in a
 * bit set of main-index item ids (the scope). A passage is anchored by its item; a sequence by its
 * messages, so it enters the scope when at least one of them is in it (research D7). Same pattern as
 * the MCP's {@code BookmarkQuery}: doc values plus a two-phase check, no expansion into terms.
 *
 * <p>
 * It is the filter of both search paths: the exact scan iterates it, and the HNSW query takes it as
 * its filter, so the scope restricts the semantic list and, in hybrid mode, the word list alike.
 * </p>
 */
public final class ItemIdFilterQuery extends Query {

    private final BitSet ids;
    private final int identity;

    public ItemIdFilterQuery(BitSet ids) {
        this.ids = Objects.requireNonNull(ids);
        this.identity = System.identityHashCode(ids);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                SortedNumericDocValues anchors = DocValues.getSortedNumeric(context.reader(),
                        SemanticFields.ANCHOR_IDS);
                TwoPhaseIterator tpi = new TwoPhaseIterator(anchors) {
                    @Override
                    public boolean matches() throws IOException {
                        int n = anchors.docValueCount();
                        for (int i = 0; i < n; i++) {
                            long v = anchors.nextValue();
                            if (v >= 0 && v <= Integer.MAX_VALUE && ids.get((int) v)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public float matchCost() {
                        return 4;
                    }
                };
                return new ConstantScoreScorer(this, score(), scoreMode, tpi);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                // the bit set is the caller's and may be reused with other contents
                return false;
            }
        };
    }

    @Override
    public String toString(String field) {
        return "ItemIdFilterQuery(" + ids.cardinality() + " ids)";
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemIdFilterQuery && ((ItemIdFilterQuery) other).ids == ids;
    }

    @Override
    public int hashCode() {
        return identity;
    }
}

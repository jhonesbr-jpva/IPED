package iped.semantic.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import iped.semantic.store.SemanticFields;

/**
 * The word part of the hybrid mode (research D20): BM25 over the passages and sequences of the
 * semantic text index - not over the main index, whose keyword search is left untouched (FR-047).
 * The question becomes plain terms through the same analyzer that indexed the passages (IPED's
 * {@code AppAnalyzer}, so accents and case fold as in keyword search); nothing in it is read as
 * IPED query syntax. The scope and the unit are those of the semantic part.
 */
public final class LexicalPassageSearcher {

    private LexicalPassageSearcher() {
    }

    /** The analyzed terms of a question, in order, without repetition. */
    public static Set<String> terms(Analyzer analyzer, String question) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream(SemanticFields.TEXT, question)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                if (term.length() > 0) {
                    out.add(term.toString());
                }
            }
            ts.end();
        }
        return out;
    }

    /** The disjunction of the question terms over the text of passages and sequences (one field). */
    public static Query query(Analyzer analyzer, String question, BitSet scope) throws IOException {
        Set<String> terms = terms(analyzer, question);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder words = new BooleanQuery.Builder();
        for (String t : terms) {
            words.add(new TermQuery(new Term(SemanticFields.TEXT, t)), Occur.SHOULD);
        }
        BooleanQuery.Builder q = new BooleanQuery.Builder().add(words.build(), Occur.MUST);
        if (scope != null) {
            q.add(new ItemIdFilterQuery(scope), Occur.FILTER);
        }
        return q.build();
    }

    /**
     * Doc ids of the best-scoring units, best first, at most {@code limit}; ties by doc id, which is
     * deterministic for a given index.
     */
    public static List<ScoreDoc> search(IndexSearcher searcher, Analyzer analyzer, String question, BitSet scope,
            int limit) throws IOException {
        Query q = query(analyzer, question, scope);
        if (q == null || limit <= 0) {
            return new ArrayList<>();
        }
        TopDocs td = searcher.search(q, limit);
        List<ScoreDoc> out = new ArrayList<>(td.scoreDocs.length);
        for (ScoreDoc sd : td.scoreDocs) {
            out.add(sd);
        }
        return out;
    }
}

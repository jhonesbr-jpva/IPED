package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.semantic.FakeEmbedder;
import iped.semantic.TestSupport;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.store.SemanticIndexWriter;

public class SemanticIndexWriterTest {

    private static Analyzer analyzer;
    private static final FakeEmbedder E = new FakeEmbedder();

    @BeforeClass
    public static void config() {
        TestSupport.ensureConfiguration();
        analyzer = AppAnalyzer.get();
    }

    private static List<org.apache.lucene.document.Document> item(int id, String... passages) {
        java.util.List<org.apache.lucene.document.Document> docs = new java.util.ArrayList<>();
        int pos = 0;
        for (int i = 0; i < passages.length; i++) {
            String p = passages[i];
            docs.add(SemanticIndexWriter.passage(SemanticFields.KIND_PASSAGE, id, i, pos, pos + p.length(), p,
                    E.embed(p)));
            pos += p.length();
        }
        docs.add(SemanticIndexWriter.status(id, CoverageReason.COVERED, null));
        return docs;
    }

    private static int count(SemanticIndexReader r, int itemId) throws Exception {
        return r.searcher().count(new TermQuery(new Term(SemanticFields.ITEM_ID_KEY, Integer.toString(itemId))));
    }

    @Test
    public void replacingAnItemIsIdempotent() throws Exception {
        Path dir = TestSupport.tempDir("idx");
        try (SemanticIndexWriter w = new SemanticIndexWriter(dir, analyzer, 16, 100)) {
            w.replaceItem(5, item(5, "primeiro trecho", "segundo trecho"));
            w.replaceItem(6, item(6, "outro item"));
            w.commit();
            // --continue reprocesses item 5: same documents again, and fewer passages later
            w.replaceItem(5, item(5, "primeiro trecho", "segundo trecho"));
            w.commit();
            try (SemanticIndexReader r = new SemanticIndexReader(dir)) {
                assertEquals(3, count(r, 5));
                assertEquals(2, count(r, 6));
            }
            w.replaceItem(5, item(5, "um trecho só"));
            w.commit();
            try (SemanticIndexReader r = new SemanticIndexReader(dir)) {
                assertEquals(2, count(r, 5));
                assertEquals(2, count(r, 6));
            }
        }
    }

    @Test
    public void textIndexScoresWithBm25AndMatchesAccentsLikeKeywordSearch() throws Exception {
        Path dir = TestSupport.tempDir("idx");
        String shortDoc = "A ação foi rápida.";
        String longDoc = "A acao foi descrita num relatorio muito longo, com muitas palavras sobre outros assuntos, "
                + "datas, nomes, lugares e detalhes que nada tem a ver com o que se procura aqui.";
        try (SemanticIndexWriter w = new SemanticIndexWriter(dir, analyzer, 16, 100)) {
            w.replaceItem(1, item(1, shortDoc));
            w.replaceItem(2, item(2, longDoc));
            w.commit();
        }
        try (SemanticIndexReader r = new SemanticIndexReader(dir)) {
            assertTrue(r.searcher().getSimilarity() instanceof BM25Similarity);
            // "ação" and "acao" meet through AppAnalyzer's folding, as in keyword search
            Query q = new QueryParser(SemanticFields.TEXT, analyzer).parse("acao");
            TopDocs top = r.searcher().search(q, 10);
            assertEquals(2, top.totalHits.value);
            // BM25 normalises by length: the short passage wins. IPED's IndexerSimilarity scores by
            // raw frequency and would tie them (both have the term once).
            ScoreDoc first = top.scoreDocs[0];
            assertEquals(1, SemanticIndexReader.intField(r.document(first.doc), SemanticFields.ITEM_ID, -1));
            assertTrue(top.scoreDocs[0].score > top.scoreDocs[1].score);
            Query q2 = new QueryParser(SemanticFields.TEXT, analyzer).parse("ação");
            assertEquals(2, r.searcher().count(q2));
        }
    }
}

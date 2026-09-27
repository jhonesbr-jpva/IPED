package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.engine.task.index.IndexItem;
import iped.properties.BasicProps;
import iped.semantic.TestSupport;

/**
 * FR-047: keyword search is not reached by the semantic part. The same evidence processed with
 * semantic search off and on gives the same results for the fixed keyword script
 * ({@code eval/keyword-script.txt}), and the same term statistics in the fields keyword search
 * reads - the BM25 of the semantic index lives in its own index and changes nothing here.
 *
 * <p>
 * Results are compared by item path and score, not by id: ids follow the order in which the
 * workers finished, which two runs need not share. The script runs through Lucene with the
 * analyzer that indexed the main index ({@code AppAnalyzer}); the engine's own searcher needs the
 * whole engine configuration, which the processing child had and this JVM has not.
 * </p>
 */
public class KeywordSearchUnchangedIT {

    private static ProcessedCases cases;
    private static Analyzer analyzer;

    @BeforeClass
    public static void process() throws Exception {
        cases = ProcessedCases.get();
        TestSupport.ensureConfiguration();
        analyzer = AppAnalyzer.get();
    }

    @Test
    public void theFixedScriptGivesIdenticalResults() throws Exception {
        List<String> script = script();
        int nonEmpty = 0;
        try (IndexReader off = TextPreparationIT.mainIndex(cases.off);
                IndexReader on = TextPreparationIT.mainIndex(cases.on)) {
            for (String line : script) {
                List<String> a = run(off, line);
                List<String> b = run(on, line);
                assertEquals("results of '" + line + "'", a, b);
                if (!a.isEmpty()) {
                    nonEmpty++;
                }
            }
        }
        assertTrue("the script finds something in the reference evidence", nonEmpty > 0);
    }

    @Test
    public void theMainIndexHasTheSameItemsAndTermStatistics() throws Exception {
        try (IndexReader off = TextPreparationIT.mainIndex(cases.off);
                IndexReader on = TextPreparationIT.mainIndex(cases.on)) {
            assertEquals("same number of documents", off.numDocs(), on.numDocs());
            for (String field : new String[] { IndexItem.CONTENT, BasicProps.NAME }) {
                assertEquals("term statistics of " + field, stats(off, field), stats(on, field));
            }
        }
    }

    private static List<String> run(IndexReader reader, String line) throws Exception {
        QueryParser parser = new QueryParser(IndexItem.CONTENT, analyzer);
        parser.setAllowLeadingWildcard(false);
        Query q = parser.parse(line);
        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs top = searcher.search(q, 1000);
        List<String> out = new ArrayList<>();
        for (ScoreDoc sd : top.scoreDocs) {
            String path = reader.document(sd.doc).get(BasicProps.PATH);
            out.add(String.format(Locale.ROOT, "%.5f %s", sd.score, path));
        }
        // ties: document order may differ between two runs, the pair (score, path) may not
        out.sort((x, y) -> {
            double sx = Double.parseDouble(x.substring(0, x.indexOf(' ')));
            double sy = Double.parseDouble(y.substring(0, y.indexOf(' ')));
            return sx != sy ? Double.compare(sy, sx) : x.compareTo(y);
        });
        return out;
    }

    /** docFreq and totalTermFreq of every term of a field. */
    private static TreeMap<String, String> stats(IndexReader reader, String field) throws Exception {
        TreeMap<String, String> out = new TreeMap<>();
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return out;
        }
        TermsEnum te = terms.iterator();
        for (BytesRef t = te.next(); t != null; t = te.next()) {
            out.put(t.utf8ToString(), te.docFreq() + "/" + te.totalTermFreq());
        }
        return out;
    }

    private static List<String> script() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = KeywordSearchUnchangedIT.class.getResourceAsStream("/eval/keyword-script.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    out.add(l);
                }
            }
        }
        return out;
    }
}

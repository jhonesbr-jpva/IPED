package iped.semantic.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;

import static iped.semantic.store.SemanticFields.*;

/**
 * Read side of a semantic index: a point-in-time reader and a searcher with the same
 * {@link BM25Similarity} the writer used, so the hybrid mode's word part scores passages with BM25
 * here and nowhere else (FR-047).
 */
public final class SemanticIndexReader implements Closeable {

    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    public SemanticIndexReader(Path dir) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(dir));
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(
                new BM25Similarity(SemanticIndexWriter.BM25_K1, SemanticIndexWriter.BM25_B));
    }

    public DirectoryReader reader() {
        return reader;
    }

    public IndexSearcher searcher() {
        return searcher;
    }

    public Document document(int docId) throws IOException {
        return searcher.doc(docId);
    }

    /** Lines of a stored sequence document, in conversation order. */
    public static List<SequenceLine> lines(Document d) {
        List<SequenceLine> out = new ArrayList<>();
        for (IndexableField f : d.getFields(LINES)) {
            out.add(SequenceLine.fromJson(f.stringValue()));
        }
        return out;
    }

    public static int intField(Document d, String field, int fallback) {
        IndexableField f = d.getField(field);
        return f == null || f.numericValue() == null ? fallback : f.numericValue().intValue();
    }

    public static Long longField(Document d, String field) {
        IndexableField f = d.getField(field);
        return f == null || f.numericValue() == null ? null : f.numericValue().longValue();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

package iped.semantic.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import static iped.semantic.store.SemanticFields.*;

/**
 * Writes one modality's semantic index (contracts/case-storage.md). Every write for an item goes
 * through {@link #replaceItem}: {@code updateDocuments(new Term("itemIdKey", id), docs)} replaces the
 * item's passages <i>and</i> its status document in one operation, which is what makes
 * {@code --continue} idempotent (research D15).
 *
 * <p>
 * The text index uses {@link BM25Similarity} (k1 = 1.2, b = 0.75) for the hybrid mode's word part,
 * and the analyzer handed in - IPED's {@code AppAnalyzer}, used as is - for {@code text} and
 * {@code seqText}. That similarity is set here and in {@link SemanticIndexReader} only; the main
 * index's {@code IndexerSimilarity} is untouched (FR-047).
 * </p>
 *
 * <p>
 * Thread-safe: {@link IndexWriter} is, and the commit bookkeeping is synchronized.
 * </p>
 */
public final class SemanticIndexWriter implements Closeable {

    public static final float BM25_K1 = 1.2f;
    public static final float BM25_B = 0.75f;

    private final IndexWriter writer;
    private final Object commitLock = new Object();
    private long lastCommit = System.currentTimeMillis();

    /**
     * @param analyzer
     *            {@code AppAnalyzer.get()} in production; only {@code text} and {@code seqText} are
     *            analyzed
     */
    public SemanticIndexWriter(Path dir, Analyzer analyzer, int maxConn, int beamWidth) throws IOException {
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setCodec(new SemanticCodec(maxConn, beamWidth));
        cfg.setSimilarity(new BM25Similarity(BM25_K1, BM25_B));
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        cfg.setCommitOnClose(false);
        FSDirectory directory = FSDirectory.open(dir);
        boolean existed = DirectoryReader.indexExists(directory);
        this.writer = new IndexWriter(directory, cfg);
        if (!existed) {
            // An empty first commit keeps a preparation interrupted before its first real commit
            // readable: the case then answers with partial coverage instead of failing to open.
            writer.commit();
        }
    }

    /** Replaces every document keyed by {@code keyId} (passages, sequences, frames and status). */
    public void replaceItem(int keyId, List<Document> docs) throws IOException {
        writer.updateDocuments(new Term(ITEM_ID_KEY, Integer.toString(keyId)), docs);
    }

    /** Deletes every document keyed by {@code keyId}. */
    public void deleteItem(int keyId) throws IOException {
        writer.deleteDocuments(new Term(ITEM_ID_KEY, Integer.toString(keyId)));
    }

    public void commit() throws IOException {
        synchronized (commitLock) {
            writer.commit();
            lastCommit = System.currentTimeMillis();
        }
    }

    /** Commits when {@code intervalMillis} have passed since the last commit. Returns whether it did. */
    public boolean commitIfDue(long intervalMillis) throws IOException {
        synchronized (commitLock) {
            if (System.currentTimeMillis() - lastCommit < intervalMillis) {
                return false;
            }
            commit();
            return true;
        }
    }

    /** Uncommitted changes are discarded: only {@link #commit()} makes them durable. */
    @Override
    public void close() throws IOException {
        writer.close();
    }

    /** Rolls back uncommitted changes and closes. */
    public void rollback() throws IOException {
        writer.rollback();
    }

    // ---------------------------------------------------------------------------------------------
    // Document builders
    // ---------------------------------------------------------------------------------------------

    private static Document base(String kind, int itemId) {
        Document d = new Document();
        d.add(new StringField(KIND, kind, Field.Store.YES));
        d.add(new SortedDocValuesField(KIND, new BytesRef(kind)));
        d.add(new IntPoint(ITEM_ID, itemId));
        d.add(new NumericDocValuesField(ITEM_ID, itemId));
        d.add(new StoredField(ITEM_ID, itemId));
        d.add(new StringField(ITEM_ID_KEY, Integer.toString(itemId), Field.Store.NO));
        return d;
    }

    private static void anchor(Document d, int id) {
        d.add(new SortedNumericDocValuesField(ANCHOR_IDS, id));
    }

    private static void ordinal(Document d, int ordinal) {
        d.add(new NumericDocValuesField(ORDINAL, ordinal));
        d.add(new StoredField(ORDINAL, ordinal));
    }

    private static void vector(Document d, float[] v) {
        d.add(new KnnVectorField(VECTOR, v, VectorSimilarityFunction.DOT_PRODUCT));
    }

    /**
     * A passage or transcript passage. {@code text} must equal {@code [charStart, charEnd)} of the
     * item's text after the metadata header is cut (data-model E3, SC-006).
     */
    public static Document passage(String kind, int itemId, int ordinal, int charStart, int charEnd, String text,
            float[] v) {
        Document d = base(kind, itemId);
        anchor(d, itemId);
        ordinal(d, ordinal);
        d.add(new StoredField(CHAR_START, charStart));
        d.add(new StoredField(CHAR_END, charEnd));
        d.add(new TextField(TEXT, text, Field.Store.YES));
        vector(d, v);
        return d;
    }

    /**
     * A conversation sequence, keyed by its chat. Anchored by its messages: it enters a scope when at
     * least one of them is in it (research D7).
     */
    public static Document sequence(int chatId, int ordinal, List<SequenceLine> lines, float[] v) {
        Document d = base(KIND_SEQUENCE, chatId);
        ordinal(d, ordinal);
        StringBuilder seqText = new StringBuilder();
        for (SequenceLine line : lines) {
            d.add(new StoredField(MESSAGE_IDS, line.getItemId()));
            d.add(new IntPoint(MESSAGE_IDS, line.getItemId()));
            d.add(new StoredField(LINES, line.toJson()));
            anchor(d, line.getItemId());
            seqText.append(line.getText()).append('\n');
        }
        d.add(new TextField(SEQ_TEXT, seqText.toString(), Field.Store.NO));
        vector(d, v);
        return d;
    }

    /** An image, or one frame of a video (then {@code frameIndex}, {@code frameCount}, instant). */
    public static Document visual(String kind, int itemId, Integer frameIndex, Integer frameCount,
            Long instantApproxMs, float[] v) {
        Document d = base(kind, itemId);
        anchor(d, itemId);
        if (frameIndex != null) {
            ordinal(d, frameIndex);
            d.add(new StoredField(FRAME_INDEX, frameIndex));
        }
        if (frameCount != null) {
            d.add(new StoredField(FRAME_COUNT, frameCount));
        }
        if (instantApproxMs != null) {
            d.add(new StoredField(INSTANT_APPROX_MS, instantApproxMs));
        }
        vector(d, v);
        return d;
    }

    /** The coverage status of an item (data-model E6). No vector. */
    public static Document status(int itemId, CoverageReason reason, String detail) {
        Document d = base(KIND_STATUS, itemId);
        anchor(d, itemId);
        d.add(new StoredField(REASON, reason.code()));
        d.add(new SortedDocValuesField(REASON, new BytesRef(reason.code())));
        if (detail != null) {
            d.add(new StoredField(DETAIL, detail));
        }
        return d;
    }
}

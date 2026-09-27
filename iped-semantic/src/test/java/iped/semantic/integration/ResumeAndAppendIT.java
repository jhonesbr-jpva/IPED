package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.search.Availability;
import iped.semantic.search.Coverage;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.State;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/**
 * The state machine of data-model E2 and research D16: an interrupted preparation, its
 * {@code --continue}, an append to a case without the capability, and an append with another model.
 * Hermetic, on the processing's own code.
 */
public class ResumeAndAppendIT {

    private static final FakeEmbedder E = new FakeEmbedder();

    private static List<TextItem> docs(SyntheticCase c, int from, int to) {
        List<TextItem> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(c.doc(i, "Documento " + i + " sobre o pagamento da carga, parágrafo único."));
        }
        return out;
    }

    private static State state(SyntheticCase c) throws Exception {
        return BuildRecord.read(c.semanticDir().resolve("build-record.json")).get(Modality.TEXT).state;
    }

    private static int documentsOf(SyntheticCase c, int itemId) throws Exception {
        try (DirectoryReader r = DirectoryReader.open(FSDirectory.open(c.semanticDir().resolve("text")))) {
            return new IndexSearcher(r).count(new TermQuery(new Term(SemanticFields.ITEM_ID_KEY, Integer.toString(itemId))));
        }
    }

    @Test
    public void interruptedThenContinuedEndsCompleteWithoutDuplicates() throws Exception {
        SyntheticCase c = new SyntheticCase("resume");
        TextPreparation first = c.open(E, null);
        c.prepareAll(first, docs(c, 0, 40));
        first.commitWithMainIndex(); // the main index committed items 0..39 with them
        c.prepareAll(first, docs(c, 40, 60)); // then the processing dies
        first.close();
        assertEquals(State.PREPARING, state(c));
        try (SemanticSearcher s = c.searcher(E)) {
            Availability a = s.availability(Modality.TEXT);
            assertTrue(a.isAvailable());
            assertTrue("an unfinished preparation is declared partial", a.isPartial());
        }

        // --continue: items not in the main commit (40..59) are processed again, others are skipped
        TextPreparation resumed = c.open(E, null);
        c.prepareAll(resumed, docs(c, 40, 80));
        resumed.finish();
        assertEquals(State.COMPLETE, state(c));
        for (int id : new int[] { 0, 39, 40, 59, 79 }) {
            assertEquals("item " + id + ": one passage and one status", 2, documentsOf(c, id));
        }
        try (SemanticSearcher s = c.searcher(E)) {
            Coverage cov = s.coverage(Modality.TEXT, null);
            assertEquals(80, cov.getCovered());
            assertEquals(80, cov.getRecorteSize());
        }
    }

    @Test
    public void appendToACaseWithoutTheCapabilityDeclaresTheOldItems() throws Exception {
        SyntheticCase c = new SyntheticCase("append-old");
        for (int i = 0; i < 50; i++) {
            c.items.set(i); // items of the original processing, without semantic search
        }
        TextPreparation p = c.open(E, 50);
        c.prepareAll(p, docs(c, 50, 60));
        p.finish();
        try (SemanticSearcher s = c.searcher(E)) {
            Coverage cov = s.coverage(Modality.TEXT, null);
            assertEquals(10, cov.getCovered());
            assertEquals(Long.valueOf(50), cov.getNotCovered().get(CoverageReason.BEFORE_CAPABILITY));
        }
    }

    @Test
    public void appendWithAnotherModelIsRefusedAndTheRestGoesOn() throws Exception {
        SyntheticCase c = new SyntheticCase("append-mismatch");
        TextPreparation p = c.open(E, null);
        c.prepareAll(p, docs(c, 0, 10));
        p.finish();

        FakeEmbedder other = new FakeEmbedder("fake/model@1+unit", "00ff", 77, 0, false); // same name, other weights
        TextPreparation append = c.open(other, null);
        assertTrue(append.isDisabledByMismatch());
        c.prepareAll(append, docs(c, 10, 15));
        append.finish();

        BuildRecord r = BuildRecord.read(c.semanticDir().resolve("build-record.json"));
        assertEquals(State.DISABLED_MISMATCH, r.get(Modality.TEXT).state);
        assertNotNull(r.get(Modality.TEXT).disabledReason);
        try (SemanticSearcher s = c.searcher(E)) {
            Coverage cov = s.coverage(Modality.TEXT, null);
            assertEquals(10, cov.getCovered());
            assertEquals(Long.valueOf(5), cov.getNotCovered().get(CoverageReason.MODEL_MISMATCH));
            assertTrue("what was built stays searchable, declared partial", s.availability(Modality.TEXT).isPartial());
        }
    }
}

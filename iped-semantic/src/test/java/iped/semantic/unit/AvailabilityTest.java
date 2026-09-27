package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.Fingerprint;
import iped.semantic.search.Availability;
import iped.semantic.search.Availability.Reason;
import iped.semantic.search.Compatibility;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.BuildRecord.ModelRecord;
import iped.semantic.store.BuildRecord.State;
import iped.semantic.store.SemanticStore;

/** One test per row of the table "Disponibilidade na hora da pergunta" (data-model E2). */
public class AvailabilityTest {

    private static final SemanticSearchConfig CONFIG = new SemanticSearchConfig();

    /** A provider that serves one embedder, or fails as the configured source would. */
    private static final class Provider implements EmbedderProvider {
        final Source source;
        final Embedder embedder;
        final String failure;
        final AtomicInteger opens = new AtomicInteger();

        Provider(Source source, Embedder embedder, String failure) {
            this.source = source;
            this.embedder = embedder;
            this.failure = failure;
        }

        @Override
        public Embedder open(Modality m) throws EmbeddingException {
            opens.incrementAndGet();
            if (failure != null) {
                throw new EmbeddingException(failure, null, true);
            }
            return embedder;
        }

        @Override
        public Source source(Modality m) {
            return source;
        }

        @Override
        public String destination(Modality m) {
            return source == Source.REMOTE ? "https://gpu-lab.example/iped" : null;
        }

        @Override
        public String describeLocation(Modality m) {
            return source == Source.REMOTE ? "remote model at https://gpu-lab.example/iped"
                    : "local model folder models/semantic/text/x";
        }
    }

    /** A case whose text modality was prepared by {@code preparedBy}, from source {@code source}. */
    private static SemanticStore preparedCase(Embedder preparedBy, String source, State state) throws Exception {
        Path module = TestSupport.tempDir("case").resolve("iped");
        SemanticStore store = SemanticStore.of(module);
        Files.createDirectories(store.index(Modality.TEXT));
        BuildRecord r = new BuildRecord();
        ModalityRecord t = r.getOrCreate(Modality.TEXT);
        t.state = state;
        t.source = source;
        t.model = ModelRecord.of(preparedBy.identity());
        t.fingerprint = Fingerprint.compute(preparedBy, false, 0.9995);
        r.write(store.buildRecord());
        return store;
    }

    private static Availability eval(SemanticStore s, Provider p) {
        return Availability.evaluate(s, Modality.TEXT, CONFIG, p, new Compatibility());
    }

    @Test
    public void row1_caseWithoutSemanticFolder() {
        SemanticStore s = SemanticStore.of(TestSupport.tempDir("case").resolve("iped"));
        Availability a = eval(s, new Provider(Source.LOCAL, new FakeEmbedder(), null));
        assertFalse(a.isAvailable());
        assertEquals(Reason.CASE_WITHOUT_CAPABILITY, a.getReason());
        assertEquals("SemanticSearch.Remedy.CASE_WITHOUT_CAPABILITY", a.getReason().remedyKey());
    }

    @Test
    public void row2_modalityWithoutSection() throws Exception {
        SemanticStore s = preparedCase(new FakeEmbedder(), "local", State.COMPLETE);
        Availability a = Availability.evaluate(s, Modality.VISUAL, CONFIG,
                new Provider(Source.LOCAL, new FakeEmbedder(), null), new Compatibility());
        assertEquals(Reason.MODALITY_NOT_PREPARED, a.getReason());
    }

    @Test
    public void row3_localModelMissingNamesTheModel() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        SemanticStore s = preparedCase(e, "local", State.COMPLETE);
        Availability a = eval(s, new Provider(Source.LOCAL, null, "local model missing: model.json does not exist"));
        assertEquals(Reason.LOCAL_MODEL_MISSING, a.getReason());
        assertTrue(a.getDetail(), a.getDetail().contains("local model folder"));
        assertEquals(e.identity(), a.getCaseModel());
    }

    @Test
    public void row4_remoteUnreachableDoesNotFallBack() throws Exception {
        SemanticStore s = preparedCase(new FakeEmbedder(), "remote", State.COMPLETE);
        Provider p = new Provider(Source.REMOTE, null, "connection refused");
        Availability a = eval(s, p);
        assertEquals(Reason.SOURCE_UNREACHABLE, a.getReason());
        assertTrue(a.getDetail(), a.getDetail().contains("https://gpu-lab.example/iped"));
        assertNull(a.getEmbedder());
        assertEquals("only the configured source was tried", 1, p.opens.get());
    }

    @Test
    public void row5_otherSourceSameModelIsAvailable() throws Exception {
        FakeEmbedder remoteArtifact = new FakeEmbedder();
        FakeEmbedder localCopy = new FakeEmbedder(); // identical artifact, other source
        SemanticStore s = preparedCase(remoteArtifact, "remote", State.COMPLETE);
        Availability a = eval(s, new Provider(Source.LOCAL, localCopy, null));
        assertTrue(a.getDetail(), a.isAvailable());
        assertEquals("remote", a.getPreparedWith());
        assertEquals(Source.LOCAL, a.getSource());
        assertNull(a.getDestination());
    }

    @Test
    public void row6_fingerprintOutOfToleranceNamesBothModels() throws Exception {
        FakeEmbedder original = new FakeEmbedder("svc/model", null, 0, 0, false);
        FakeEmbedder swapped = new FakeEmbedder("svc/model", null, 41, 0, false);
        SemanticStore s = preparedCase(original, "remote", State.COMPLETE);
        Availability a = eval(s, new Provider(Source.REMOTE, swapped, null));
        assertEquals(Reason.MODEL_INCOMPATIBLE, a.getReason());
        assertNotNull(a.getCaseModel());
        assertNotNull(a.getCurrentModel());
        assertNull("no embedder is handed out for an incompatible model", a.getEmbedder());

        FakeEmbedder other = new FakeEmbedder("another/model@2", "ff", 0, 0, false);
        Availability b = eval(s, new Provider(Source.LOCAL, other, null));
        assertEquals(Reason.MODEL_INCOMPATIBLE, b.getReason());
        assertTrue(b.getDetail(), b.getDetail().contains("another/model@2") && b.getDetail().contains("svc/model"));
    }

    @Test
    public void row7_partialIsAvailableAndDeclaredPartial() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        SemanticStore s = preparedCase(e, "local", State.PARTIAL);
        Availability a = eval(s, new Provider(Source.LOCAL, e, null));
        assertTrue(a.isAvailable());
        assertTrue(a.isPartial());
    }

    @Test
    public void row8_completeIsAvailable() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        SemanticStore s = preparedCase(e, "local", State.COMPLETE);
        Availability a = eval(s, new Provider(Source.LOCAL, e, null));
        assertTrue(a.isAvailable());
        assertFalse(a.isPartial());
        assertEquals(e, a.getEmbedder());
    }

    @Test
    public void fingerprintIsComputedOncePerSession() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        SemanticStore s = preparedCase(e, "local", State.COMPLETE);
        Compatibility session = new Compatibility();
        Provider p = new Provider(Source.LOCAL, e, null);
        Availability.evaluate(s, Modality.TEXT, CONFIG, p, session);
        int calls = e.calls.get();
        Availability.evaluate(s, Modality.TEXT, CONFIG, p, session);
        assertEquals(calls, e.calls.get());
    }
}

package iped.semantic.unit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.SemanticPaths;
import iped.semantic.TestSupport;
import iped.semantic.model.Embedder.Role;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.LocalOnnxEmbedder;
import iped.semantic.model.VectorMath;

/**
 * The shipped text model, run by ONNX Runtime with the tokenizer in the graph. Skipped unless
 * {@code -Diped.semantic.test.textModel=<folder>} names an exported model (export-models tool).
 */
public class LocalOnnxEmbedderTest {

    private static LocalOnnxEmbedder model;

    @BeforeClass
    public static void load() throws Exception {
        Path dir = TestSupport.requireTextModel();
        model = LocalOnnxEmbedder.open(dir, SemanticPaths.nativeDir());
    }

    @Test
    public void identityIsTheArtifactsAndVectorsAreUnitNorm() throws Exception {
        assertTrue(model.identity().getId(), model.identity().getId().startsWith("intfloat/multilingual-e5-small@"));
        assertEquals(64, model.identity().getSha256().length());
        assertEquals(384, model.dimensions());
        float[][] v = model.embedTexts(Role.PASSAGE, List.of("fechado, amanhã na portaria", "ok"));
        assertEquals(2, v.length);
        for (float[] row : v) {
            assertEquals(384, row.length);
            assertEquals(1.0, Math.sqrt(VectorMath.dot(row, row)), 1e-5);
        }
    }

    @Test
    public void sameInputSameVectorAndRolesDiffer() throws Exception {
        float[] a = model.embedTexts(Role.PASSAGE, List.of("quanto?")).clone()[0];
        float[] b = model.embedTexts(Role.PASSAGE, List.of("quanto?"))[0];
        assertArrayEquals(a, b, 0f);
        float[] q = model.embedTexts(Role.QUERY, List.of("quanto?"))[0];
        assertNotEquals(1.0, VectorMath.dot(a, q), 1e-6);
    }

    @Test
    public void aVectorDoesNotDependOnItsBatchNeighbours() throws Exception {
        float[] alone = model.embedTexts(Role.PASSAGE, List.of("o acerto é amanhã"))[0];
        float[] inBatch = model.embedTexts(Role.PASSAGE,
                List.of("um texto bem mais longo que muda a escala de ativação do lote inteiro, "
                        + "com números 1234567 e símbolos %%%", "o acerto é amanhã"))[1];
        assertArrayEquals(alone, inBatch, 0f);
    }

    @Test
    public void meaningBeatsWordsOnTheSpecExample() throws Exception {
        float[] q = model.embedTexts(Role.QUERY, List.of("entrega de mercadoria e pagamento combinado"))[0];
        float[][] p = model.embedTexts(Role.PASSAGE, List.of(
                "deixa o pacote com o moleque da portaria, o acerto é amanhã",
                "receita de bolo de cenoura com cobertura de chocolate",
                "The shipment will be left at the gate; payment tomorrow.",
                "Weather forecast: heavy rain expected over the weekend."));
        double relevant = VectorMath.dot(q, p[0]);
        double unrelated = VectorMath.dot(q, p[1]);
        assertTrue(relevant + " vs " + unrelated, relevant > unrelated);
        // Across languages, meaning must still order passages of the other language. It is NOT
        // asserted that an English passage beats an unrelated Portuguese one: the model has a
        // same-language bias (fp32 reference, 2026-09-26: 0.801 vs 0.834), which is what SC-002
        // measures on the evaluation set rather than a unit test.
        double english = VectorMath.dot(q, p[2]);
        double englishUnrelated = VectorMath.dot(q, p[3]);
        assertTrue(english + " vs " + englishUnrelated, english > englishUnrelated);
    }

    @Test
    public void concurrentCallsGiveTheSameVectorsAsSequentialOnes() throws Exception {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            texts.add("mensagem número " + i + " sobre pagamento e entrega");
        }
        float[][] sequential = model.embedTexts(Role.PASSAGE, texts);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<float[]>> futures = new ArrayList<>();
            for (String t : texts) {
                futures.add(pool.submit(() -> model.embedTexts(Role.PASSAGE, List.of(t))[0]));
            }
            for (int i = 0; i < texts.size(); i++) {
                assertArrayEquals(sequential[i], futures.get(i).get(), 0f);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void sharedPerFolderAndFingerprintIsStable() throws Exception {
        assertSame(model, LocalOnnxEmbedder.open(model.getModelDir(), SemanticPaths.nativeDir()));
        Fingerprint a = Fingerprint.compute(model, false, 0.9995);
        Fingerprint b = Fingerprint.compute(model, false, 0.9995);
        Fingerprint.Comparison c = a.compare(model.identity(), b, model.identity(), 0.9995);
        assertTrue(c.reason(), c.isCompatible());
        assertEquals(1.0, c.minCosine(), 1e-9);
    }
}

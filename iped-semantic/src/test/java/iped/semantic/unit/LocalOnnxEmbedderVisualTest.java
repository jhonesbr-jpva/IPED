package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import iped.semantic.SemanticPaths;
import iped.semantic.TestSupport;
import iped.semantic.model.Embedder.Role;
import iped.semantic.model.FingerprintProbes;
import iped.semantic.model.LocalOnnxEmbedder;
import iped.semantic.model.VectorMath;

/**
 * The visual model (T076): the Java image path - {@code ImagePreprocessor} feeding
 * {@code image.onnx} - reproduces the embeddings the export computed with CLIPImageProcessor, and
 * the text encoder pairs descriptions with pictures in Portuguese and in English (FR-036). Skipped
 * unless {@code -Diped.semantic.test.visualModel=<folder>} names an exported visual model.
 */
public class LocalOnnxEmbedderVisualTest {

    private static LocalOnnxEmbedder model;
    private static JsonNode manifest;
    private static Path dir;

    @BeforeClass
    public static void load() throws Exception {
        dir = TestSupport.requireVisualModel();
        model = LocalOnnxEmbedder.open(dir, SemanticPaths.nativeDir());
        manifest = new ObjectMapper()
                .readTree(new String(Files.readAllBytes(dir.resolve("model.json")), StandardCharsets.UTF_8));
    }

    @Test
    public void identityIsTheArtifactsAndBothEncodersAreThere() throws Exception {
        assertEquals("the identity is what the files are, computed as the export computes it",
                manifest.path("sha256").asText(), model.identity().getSha256());
        assertTrue(model.supportsImages());
        assertEquals(512, model.dimensions());
    }

    @Test
    public void theJavaPreprocessingReproducesTheExport() throws Exception {
        // what CLIPImageProcessor + the fp32/int8 graph gave in Python, for the same pictures
        for (JsonNode probe : manifest.path("export").path("verification").path("imageProbes")) {
            BufferedImage image = ImageIO.read(dir.resolve(probe.path("file").asText()).toFile());
            float[] expected = new float[probe.path("embedding").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (float) probe.path("embedding").get(i).asDouble();
            }
            float[] actual = model.embedImages(List.of(image))[0];
            double cos = VectorMath.dot(actual, expected) / Math.sqrt(VectorMath.dot(expected, expected));
            assertTrue(probe.path("file").asText() + ": cosine " + cos, cos >= 0.999);
            assertEquals(1.0, Math.sqrt(VectorMath.dot(actual, actual)), 1e-4);
        }
    }

    @Test
    public void descriptionsFindTheirPicturesInPortugueseAndEnglish() throws Exception {
        List<BufferedImage> probes = new ArrayList<>();
        for (JsonNode probe : manifest.path("export").path("verification").path("imageProbes")) {
            probes.add(ImageIO.read(dir.resolve(probe.path("file").asText()).toFile()));
        }
        float[][] pictures = model.embedImages(probes);
        String[][] cases = { { "uma praia ao pôr do sol", "0" }, { "a beach with the sun in the sky", "0" },
                { "um quadrado vermelho", "1" }, { "a red square on a white background", "1" } };
        for (String[] c : cases) {
            float[] q = model.embedTexts(Role.QUERY, List.of(c[0]))[0];
            int best = -1;
            double bestScore = -2;
            for (int i = 0; i < pictures.length; i++) {
                double s = VectorMath.dot(q, pictures[i]);
                if (s > bestScore) {
                    bestScore = s;
                    best = i;
                }
            }
            assertEquals("'" + c[0] + "' ranks its picture first", Integer.parseInt(c[1]), best);
        }
    }

    @Test
    public void theFingerprintProbesEmbed() throws Exception {
        // the eight synthetic pictures of the fingerprint (research D10) go through the same path
        float[][] v = model.embedImages(FingerprintProbes.images());
        assertEquals(8, v.length);
        for (float[] row : v) {
            assertEquals(512, row.length);
            assertEquals(1.0, Math.sqrt(VectorMath.dot(row, row)), 1e-4);
        }
        float[] again = model.embedImages(List.of(FingerprintProbes.images().get(3)))[0];
        assertEquals("deterministic", 1.0, VectorMath.dot(again, v[3]), 1e-6);
    }
}

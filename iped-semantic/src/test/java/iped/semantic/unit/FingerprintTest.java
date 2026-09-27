package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.FingerprintProbes;

public class FingerprintTest {

    @Test
    public void probeSetIsSixteenSentencesAndEightFixedImages() {
        assertEquals(16, FingerprintProbes.texts().size());
        List<BufferedImage> a = FingerprintProbes.images();
        List<BufferedImage> b = FingerprintProbes.images();
        assertEquals(8, a.size());
        for (int k = 0; k < 8; k++) {
            assertEquals(224, a.get(k).getWidth());
            for (int y = 0; y < 224; y += 11) {
                for (int x = 0; x < 224; x += 13) {
                    assertEquals(a.get(k).getRGB(x, y), b.get(k).getRGB(x, y));
                }
            }
        }
    }

    @Test
    public void sameModelIsCompatible() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        Fingerprint recorded = Fingerprint.compute(e, true, 0.9995);
        assertEquals(24, recorded.size());
        Fingerprint.Comparison c = recorded.compare(e.identity(), Fingerprint.compute(e, true, 0.9995), e.identity(),
                0.9995);
        assertTrue(c.reason(), c.isCompatible());
        assertEquals(1.0, c.minCosine(), 1e-9);
    }

    @Test
    public void toleranceAbsorbsSmallNumericDifferencesOnly() throws Exception {
        FakeEmbedder cpuA = new FakeEmbedder("m@1+x", "aa", 0, 0, false);
        FakeEmbedder cpuB = new FakeEmbedder("m@1+x", "aa", 0, 0.002, false);
        Fingerprint recorded = Fingerprint.compute(cpuA, false, 0.9995);
        Fingerprint now = Fingerprint.compute(cpuB, false, 0.9995);
        Fingerprint.Comparison c = recorded.compare(cpuA.identity(), now, cpuB.identity(), 0.9995);
        assertTrue("min cosine " + c.minCosine(), c.minCosine() < 1.0 && c.minCosine() >= 0.9995);
        assertTrue(c.isCompatible());
        Fingerprint.Comparison strict = recorded.compare(cpuA.identity(), now, cpuB.identity(), 0.9999999);
        assertFalse(strict.isCompatible());
        assertTrue(strict.reason(), strict.reason().contains("tolerance"));
    }

    @Test
    public void sameNameDifferentWeightsIsIncompatible() throws Exception {
        FakeEmbedder original = new FakeEmbedder("svc/model", null, 0, 0, false);
        FakeEmbedder swapped = new FakeEmbedder("svc/model", null, 99, 0, false);
        Fingerprint recorded = Fingerprint.compute(original, false, 0.9995);
        Fingerprint.Comparison c = recorded.compare(original.identity(), Fingerprint.compute(swapped, false, 0.9995),
                swapped.identity(), 0.9995);
        assertTrue(c.identityMatches());
        assertFalse(c.probesMatch());
        assertFalse(c.isCompatible());
    }

    @Test
    public void differentIdentityIsIncompatibleEvenWithIdenticalProbes() throws Exception {
        FakeEmbedder a = new FakeEmbedder("model-a@1", "aa", 0, 0, false);
        FakeEmbedder b = new FakeEmbedder("model-b@1", "aa", 0, 0, false);
        Fingerprint recorded = Fingerprint.compute(a, false, 0.9995);
        Fingerprint.Comparison c = recorded.compare(a.identity(), Fingerprint.compute(b, false, 0.9995), b.identity(),
                0.9995);
        assertTrue(c.probesMatch());
        assertFalse(c.isCompatible());
        assertTrue(c.reason(), c.reason().contains("model-a@1") && c.reason().contains("model-b@1"));
    }

    @Test
    public void differentArtifactHashIsIncompatible() throws Exception {
        FakeEmbedder a = new FakeEmbedder("m@1", "aaaa", 0, 0, false);
        FakeEmbedder b = new FakeEmbedder("m@1", "bbbb", 0, 0, false);
        Fingerprint recorded = Fingerprint.compute(a, false, 0.9995);
        assertFalse(recorded.compare(a.identity(), Fingerprint.compute(b, false, 0.9995), b.identity(), 0.9995)
                .isCompatible());
    }
}

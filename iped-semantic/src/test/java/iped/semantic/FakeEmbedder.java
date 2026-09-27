package iped.semantic;

import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import iped.semantic.model.Embedder;
import iped.semantic.model.ModelIdentity;
import iped.semantic.model.VectorMath;

/**
 * Deterministic stand-in for a model: a hashed bag of words (accents folded, lower case), so texts
 * that share words are close. The {@code seed} changes the hashing, which gives "same name,
 * different weights" for fingerprint tests; {@code noise} perturbs every vector slightly, which
 * gives "same model on another CPU".
 */
public final class FakeEmbedder implements Embedder {

    public static final int DIMS = 64;

    private final ModelIdentity identity;
    private final int seed;
    private final double noise;
    private final boolean images;
    public final AtomicInteger calls = new AtomicInteger();

    public FakeEmbedder() {
        this("fake/model@1+unit", "00ff", 0, 0, true);
    }

    public FakeEmbedder(String id, String sha, int seed, double noise, boolean images) {
        this.identity = new ModelIdentity(id, sha, DIMS, ModelIdentity.DOT_PRODUCT, "query: ", "passage: ");
        this.seed = seed;
        this.noise = noise;
        this.images = images;
    }

    @Override
    public float[][] embedTexts(Role role, List<String> texts) {
        calls.incrementAndGet();
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            out[i] = embed(texts.get(i));
        }
        return out;
    }

    public float[] embed(String text) {
        float[] v = new float[DIMS];
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        for (String token : folded.split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int h = (token.hashCode() * 31 + seed) & 0x7fffffff;
            v[h % DIMS] += 1f;
            v[(h / DIMS) % DIMS] += 0.5f;
        }
        if (noise > 0) {
            for (int d = 0; d < DIMS; d++) {
                v[d] += (float) (noise * (((d * 7919 + seed) % 13) - 6) / 6.0);
            }
        }
        v[DIMS - 1] += 0.01f; // never a zero vector
        return VectorMath.normalizeInPlace(v);
    }

    @Override
    public boolean supportsImages() {
        return images;
    }

    @Override
    public float[][] embedImages(List<BufferedImage> imgs) {
        calls.incrementAndGet();
        float[][] out = new float[imgs.size()][];
        for (int i = 0; i < imgs.size(); i++) {
            BufferedImage img = imgs.get(i);
            float[] v = new float[DIMS];
            for (int y = 0; y < img.getHeight(); y += 7) {
                for (int x = 0; x < img.getWidth(); x += 7) {
                    int p = img.getRGB(x, y);
                    v[(((p >> 16) & 0xFF) + seed) % DIMS] += 1;
                    v[(((p >> 8) & 0xFF) + 3 + seed) % DIMS] += 1;
                    v[((p & 0xFF) + 7 + seed) % DIMS] += 1;
                }
            }
            v[0] += 0.01f;
            out[i] = VectorMath.normalizeInPlace(v);
        }
        return out;
    }

    @Override
    public ModelIdentity identity() {
        return identity;
    }
}

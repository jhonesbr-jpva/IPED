package iped.semantic.model;

/**
 * Vector helpers. All vectors stored and compared by this module are L2-normalised, so the dot
 * product is the cosine similarity (Lucene {@code DOT_PRODUCT}).
 */
public final class VectorMath {

    private VectorMath() {
    }

    /** Normalises in place to unit L2 norm; a zero vector is left as is. Returns the same array. */
    public static float[] normalizeInPlace(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum > 0) {
            float inv = (float) (1.0 / Math.sqrt(sum));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    /** Dot product, accumulated in double for stability across call sites. */
    public static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** Cosine similarity, computed from the norms (does not assume unit vectors). */
    public static double cosine(float[] a, float[] b) {
        double na = Math.sqrt(dot(a, a));
        double nb = Math.sqrt(dot(b, b));
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot(a, b) / (na * nb);
    }

    /** Normalised mean of vectors (used for a reference item with several passages). */
    public static float[] normalizedMean(float[][] vectors) {
        if (vectors.length == 0) {
            throw new IllegalArgumentException("no vectors");
        }
        float[] mean = new float[vectors[0].length];
        for (float[] v : vectors) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += v[i];
            }
        }
        return normalizeInPlace(mean);
    }
}

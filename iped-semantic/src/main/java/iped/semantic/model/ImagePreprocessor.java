package iped.semantic.model;

import java.awt.image.BufferedImage;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * CLIP-style image pre-processing, declared in {@code model.json}: resize the shortest side to
 * {@code size} with bicubic resampling, centre crop to {@code size x size}, scale to [0, 1] and
 * normalise each channel by mean and standard deviation, as NCHW floats.
 *
 * <p>
 * The resampling is a pure-Java port of Pillow's 8-bit separable resampler (horizontal pass, then
 * vertical, fixed-point coefficients, uint8 rounding between passes), which is what the upstream
 * processor and the reference remote server use. It deliberately avoids Java2D, whose interpolation
 * is not specified pixel for pixel: the same thumbnail must give the same pixels on every JDK, or
 * the fingerprint would drift for reasons that are not the model.
 * </p>
 */
final class ImagePreprocessor {

    private static final float[] CLIP_MEAN = { 0.48145466f, 0.4578275f, 0.40821073f };
    private static final float[] CLIP_STD = { 0.26862954f, 0.26130258f, 0.27577711f };

    /** Pillow: {@code PRECISION_BITS = 32 - 8 - 2}. */
    private static final int PRECISION_BITS = 22;
    private static final double BICUBIC_SUPPORT = 2.0;
    private static final double BICUBIC_A = -0.5;

    private final int size;
    private final float[] mean;
    private final float[] std;

    ImagePreprocessor(int size, float[] mean, float[] std) {
        this.size = size;
        this.mean = mean;
        this.std = std;
    }

    static ImagePreprocessor fromManifest(JsonNode image) {
        int size = image.path("size").asInt(224);
        return new ImagePreprocessor(size, floats(image.path("mean"), CLIP_MEAN), floats(image.path("std"), CLIP_STD));
    }

    private static float[] floats(JsonNode n, float[] fallback) {
        if (!n.isArray() || n.size() != 3) {
            return fallback;
        }
        return new float[] { (float) n.get(0).asDouble(), (float) n.get(1).asDouble(), (float) n.get(2).asDouble() };
    }

    int size() {
        return size;
    }

    /** Pre-processes an image into a {@code [3, size, size]} float array in channel-major order. */
    float[] toChw(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[][] rgb = readRgb(src);

        // Shortest side to `size`, longest side scaled and truncated, as the upstream processor does.
        int newW, newH;
        if (w <= h) {
            newW = size;
            newH = (int) ((long) size * h / w);
        } else {
            newH = size;
            newW = (int) ((long) size * w / h);
        }
        int[][] resized = resize(rgb, w, h, newW, newH);

        int top = (newH - size) / 2;
        int left = (newW - size) / 2;
        float[] out = new float[3 * size * size];
        int plane = size * size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = resized[top + y][left + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int i = y * size + x;
                out[i] = (r / 255f - mean[0]) / std[0];
                out[plane + i] = (g / 255f - mean[1]) / std[1];
                out[2 * plane + i] = (b / 255f - mean[2]) / std[2];
            }
        }
        return out;
    }

    private static int[][] readRgb(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[][] out = new int[h][w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, out[y], 0, w);
            for (int x = 0; x < w; x++) {
                out[y][x] &= 0xFFFFFF; // drop alpha: Pillow converts to RGB first
            }
        }
        return out;
    }

    /** Pillow's two-pass 8-bit bicubic resample. Pixels are packed 0xRRGGBB. */
    static int[][] resize(int[][] in, int inW, int inH, int outW, int outH) {
        int[][] tmp = inW == outW ? in : horizontal(in, inW, inH, outW);
        return inH == outH ? tmp : vertical(tmp, outW, inH, outH);
    }

    private static int[][] horizontal(int[][] in, int inW, int inH, int outW) {
        Coefficients c = coefficients(inW, outW);
        int[][] out = new int[inH][outW];
        for (int y = 0; y < inH; y++) {
            int[] row = in[y];
            for (int x = 0; x < outW; x++) {
                int min = c.bounds[2 * x], count = c.bounds[2 * x + 1];
                int base = x * c.kmax;
                long r = 1L << (PRECISION_BITS - 1), g = r, b = r;
                for (int k = 0; k < count; k++) {
                    int p = row[min + k];
                    long kk = c.kk[base + k];
                    r += ((p >> 16) & 0xFF) * kk;
                    g += ((p >> 8) & 0xFF) * kk;
                    b += (p & 0xFF) * kk;
                }
                out[y][x] = (clip8(r) << 16) | (clip8(g) << 8) | clip8(b);
            }
        }
        return out;
    }

    private static int[][] vertical(int[][] in, int w, int inH, int outH) {
        Coefficients c = coefficients(inH, outH);
        int[][] out = new int[outH][w];
        for (int y = 0; y < outH; y++) {
            int min = c.bounds[2 * y], count = c.bounds[2 * y + 1];
            int base = y * c.kmax;
            for (int x = 0; x < w; x++) {
                long r = 1L << (PRECISION_BITS - 1), g = r, b = r;
                for (int k = 0; k < count; k++) {
                    int p = in[min + k][x];
                    long kk = c.kk[base + k];
                    r += ((p >> 16) & 0xFF) * kk;
                    g += ((p >> 8) & 0xFF) * kk;
                    b += (p & 0xFF) * kk;
                }
                out[y][x] = (clip8(r) << 16) | (clip8(g) << 8) | clip8(b);
            }
        }
        return out;
    }

    private static int clip8(long in) {
        if (in >= (1L << PRECISION_BITS << 8)) {
            return 255;
        }
        if (in <= 0) {
            return 0;
        }
        return (int) (in >> PRECISION_BITS);
    }

    private static final class Coefficients {
        int kmax;
        int[] bounds;
        long[] kk;
    }

    /** Pillow's {@code precompute_coeffs} followed by {@code normalize_coeffs_8bpc}. */
    private static Coefficients coefficients(int inSize, int outSize) {
        double scale = (double) inSize / outSize;
        double filterscale = Math.max(scale, 1.0);
        double support = BICUBIC_SUPPORT * filterscale;
        int ksize = (int) Math.ceil(support) * 2 + 1;
        Coefficients c = new Coefficients();
        c.kmax = ksize;
        c.bounds = new int[outSize * 2];
        c.kk = new long[outSize * ksize];
        double[] k = new double[ksize];
        for (int xx = 0; xx < outSize; xx++) {
            double center = (xx + 0.5) * scale;
            double ww = 0.0;
            double ss = 1.0 / filterscale;
            int xmin = (int) (center - support + 0.5);
            if (xmin < 0) {
                xmin = 0;
            }
            int xmax = (int) (center + support + 0.5);
            if (xmax > inSize) {
                xmax = inSize;
            }
            xmax -= xmin;
            for (int x = 0; x < xmax; x++) {
                double w = bicubic((x + xmin - center + 0.5) * ss);
                k[x] = w;
                ww += w;
            }
            for (int x = 0; x < xmax; x++) {
                if (ww != 0.0) {
                    k[x] /= ww;
                }
            }
            for (int x = 0; x < ksize; x++) {
                double v = x < xmax ? k[x] : 0.0;
                c.kk[xx * ksize + x] = (long) (v < 0 ? (v * (1 << PRECISION_BITS) - 0.5)
                        : (v * (1 << PRECISION_BITS) + 0.5));
            }
            c.bounds[xx * 2] = xmin;
            c.bounds[xx * 2 + 1] = xmax;
        }
        return c;
    }

    private static double bicubic(double x) {
        if (x < 0.0) {
            x = -x;
        }
        if (x < 1.0) {
            return ((BICUBIC_A + 2.0) * x - (BICUBIC_A + 3.0)) * x * x + 1;
        }
        if (x < 2.0) {
            return (((x - 5) * x + 8) * x - 4) * BICUBIC_A;
        }
        return 0.0;
    }
}

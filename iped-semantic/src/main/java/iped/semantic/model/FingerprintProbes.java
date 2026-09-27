package iped.semantic.model;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The fixed inputs of the model fingerprint (research D10): sixteen sentences, versioned with the
 * code, and eight synthetic images for the visual modality.
 *
 * <p>
 * The images are computed pixel by pixel with integer arithmetic, never drawn with Java2D, so they
 * do not depend on the JDK's rasteriser, antialiasing or font availability. The same version gives
 * the same pixels on every machine.
 * </p>
 */
public final class FingerprintProbes {

    /** Version of the probe set recorded in the build record. */
    public static final int VERSION = 1;

    public static final int IMAGE_SIZE = 224;
    public static final int IMAGE_COUNT = 8;

    private static final String TEXT_RESOURCE = "/iped/semantic/probes/text-probes-v" + VERSION + ".txt";

    private static volatile List<String> texts;

    private FingerprintProbes() {
    }

    /** The sixteen probe sentences, in file order. */
    public static List<String> texts() {
        List<String> t = texts;
        if (t == null) {
            t = Collections.unmodifiableList(loadTexts());
            texts = t;
        }
        return t;
    }

    private static List<String> loadTexts() {
        try (InputStream in = FingerprintProbes.class.getResourceAsStream(TEXT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("probe resource missing: " + TEXT_RESOURCE);
            }
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        out.add(line.strip());
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The eight synthetic probe images, 224 x 224 RGB, in a fixed order. */
    public static List<BufferedImage> images() {
        List<BufferedImage> out = new ArrayList<>(IMAGE_COUNT);
        for (int k = 0; k < IMAGE_COUNT; k++) {
            out.add(image(k));
        }
        return out;
    }

    static BufferedImage image(int k) {
        int n = IMAGE_SIZE;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int c = n / 2;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int r, g, b;
                switch (k) {
                    case 0: // horizontal gradient, red to blue
                        r = 255 - x * 255 / (n - 1);
                        g = 32;
                        b = x * 255 / (n - 1);
                        break;
                    case 1: // vertical gradient, dark to yellow
                        r = y * 255 / (n - 1);
                        g = y * 255 / (n - 1);
                        b = 16;
                        break;
                    case 2: // checkerboard, 28 px squares
                        boolean on = ((x / 28) + (y / 28)) % 2 == 0;
                        r = on ? 240 : 20;
                        g = on ? 240 : 20;
                        b = on ? 240 : 20;
                        break;
                    case 3: { // concentric rings (integer squared distance)
                        int d2 = (x - c) * (x - c) + (y - c) * (y - c);
                        boolean ring = (isqrt(d2) / 12) % 2 == 0;
                        r = ring ? 200 : 30;
                        g = ring ? 60 : 30;
                        b = ring ? 40 : 90;
                        break;
                    }
                    case 4: // diagonal stripes
                        boolean stripe = ((x + y) / 16) % 2 == 0;
                        r = stripe ? 20 : 250;
                        g = stripe ? 160 : 250;
                        b = stripe ? 60 : 250;
                        break;
                    case 5: { // green filled disc on white
                        int d2 = (x - c) * (x - c) + (y - c) * (y - c);
                        boolean in = d2 <= 70 * 70;
                        r = in ? 30 : 255;
                        g = in ? 170 : 255;
                        b = in ? 70 : 255;
                        break;
                    }
                    case 6: // four colour quadrants
                        if (x < c && y < c) {
                            r = 220; g = 30; b = 30;
                        } else if (x >= c && y < c) {
                            r = 30; g = 200; b = 30;
                        } else if (x < c) {
                            r = 30; g = 30; b = 220;
                        } else {
                            r = 230; g = 220; b = 40;
                        }
                        break;
                    default: // black square frame on grey
                        boolean frame = (x >= 40 && x < 184 && y >= 40 && y < 184)
                                && !(x >= 60 && x < 164 && y >= 60 && y < 164);
                        r = frame ? 0 : 128;
                        g = frame ? 0 : 128;
                        b = frame ? 0 : 128;
                        break;
                }
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static int isqrt(int v) {
        int r = (int) Math.sqrt(v);
        while (r * r > v) {
            r--;
        }
        while ((r + 1) * (r + 1) <= v) {
            r++;
        }
        return r;
    }
}

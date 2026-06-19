package iped.rcp.views.gallery;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;

/**
 * AWT to SWT image conversion for the gallery thumbnails (task T026,
 * research R12: the legacy {@code GalleryModel} produces AWT
 * {@code BufferedImage}s; the Nebula gallery needs SWT {@code ImageData}).
 *
 * <p>
 * Also exposes a high-quality (bicubic, alpha-preserving) scaler reused by the
 * tree decorators (e.g. category icons) — matching the legacy
 * {@code QualityIcon} rendering hints.
 */
public final class SwtImages {

    private SwtImages() {
    }

    /** Converts any BufferedImage to SWT ImageData (through INT_ARGB). */
    public static ImageData toImageData(BufferedImage image) {
        BufferedImage argb;
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = image;
        } else {
            argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = argb.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = ((DataBufferInt) argb.getRaster().getDataBuffer()).getData();

        PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
        ImageData data = new ImageData(width, height, 32, palette);
        for (int y = 0; y < height; y++) {
            data.setPixels(0, y, width, pixels, y * width);
        }
        byte[] alpha = new byte[width * height];
        for (int i = 0; i < alpha.length; i++) {
            alpha[i] = (byte) ((pixels[i] >> 24) & 0xFF);
        }
        data.alphaData = alpha;
        return data;
    }

    /** Converts SWT ImageData to an INT_ARGB BufferedImage (alpha preserved). */
    public static BufferedImage toBufferedImage(ImageData data) {
        BufferedImage image = new BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB);
        PaletteData palette = data.palette;
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                RGB rgb = palette.getRGB(data.getPixel(x, y));
                int alpha = data.getAlpha(x, y);
                image.setRGB(x, y, (alpha << 24) | (rgb.red << 16) | (rgb.green << 8) | rgb.blue);
            }
        }
        return image;
    }

    /**
     * High-quality (bicubic) scale preserving alpha — same rendering hints as
     * the legacy {@code QualityIcon}. Returns the source unchanged when it is
     * already the requested size.
     */
    public static ImageData scaledTo(ImageData source, int width, int height) {
        if (source.width == width && source.height == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(toBufferedImage(source), 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return toImageData(scaled);
    }
}

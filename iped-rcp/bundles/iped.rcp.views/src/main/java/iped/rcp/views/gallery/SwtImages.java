package iped.rcp.views.gallery;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;

/**
 * AWT to SWT image conversion for the gallery thumbnails (task T026,
 * research R12: the legacy {@code GalleryModel} produces AWT
 * {@code BufferedImage}s; the Nebula gallery needs SWT {@code ImageData}).
 */
final class SwtImages {

    private SwtImages() {
    }

    /** Converts any BufferedImage to SWT ImageData (through INT_ARGB). */
    static ImageData toImageData(BufferedImage image) {
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
}

package iped.semantic.prep;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import iped.data.IItemReader;
import iped.engine.preview.PreviewRepositoryManager;
import iped.utils.ImageUtil;

/**
 * The frames of a video, cut from the mosaic that {@code VideoThumbTask} keeps as the item's
 * preview: a JPEG whose comment {@code Frames=RxC} gives the grid (research D8). The cells are cut
 * by {@link ImageUtil#getFrames(BufferedImage, String)}, the same geometry the mosaic was drawn
 * with. Read only: the video is never decoded again.
 */
public final class VideoMosaicSplitter {

    private VideoMosaicSplitter() {
    }

    /** Rows and columns of a mosaic comment, or {@code null} when it is not one. */
    public static int[] grid(String comment) {
        if (comment == null || !comment.startsWith("Frames")) {
            return null;
        }
        int p1 = comment.indexOf('=');
        int p2 = comment.indexOf('x');
        if (p1 <= 0 || p2 <= p1) {
            return null;
        }
        try {
            int rows = Integer.parseInt(comment.substring(p1 + 1, p2).trim());
            int cols = Integer.parseInt(comment.substring(p2 + 1).trim());
            return rows > 0 && cols > 0 ? new int[] { rows, cols } : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Frames of a mosaic JPEG in cell order (row by row); {@code null} when it is not a mosaic. */
    public static List<BufferedImage> frames(InputStream mosaicJpeg) throws IOException {
        Object[] read = ImageUtil.readJpegWithMetaData(mosaicJpeg);
        if (read == null || read.length < 2 || read[0] == null) {
            return null;
        }
        return frames((BufferedImage) read[0], (String) read[1]);
    }

    /** Frames of a decoded mosaic with its comment; {@code null} when it is not a mosaic. */
    public static List<BufferedImage> frames(BufferedImage mosaic, String comment) {
        if (grid(comment) == null) {
            return null;
        }
        return ImageUtil.getFrames(mosaic, comment);
    }

    /**
     * The mosaic of a video item, from the case's preview repository; {@code null} when the item has
     * no mosaic (video thumbnails off, or extraction failed).
     *
     * @param moduleDir
     *            the case's {@code iped} folder
     */
    public static List<BufferedImage> framesOf(File moduleDir, IItemReader item) throws IOException {
        if (!item.hasPreview()) {
            return null;
        }
        try (InputStream in = PreviewRepositoryManager.get(moduleDir).readPreview(item, false)) {
            return in == null ? null : frames(in);
        } catch (java.sql.SQLException e) {
            throw new IOException(e);
        }
    }

    /** One frame of a video item, or {@code null}. */
    public static BufferedImage frameOf(File moduleDir, IItemReader item, int index) throws IOException {
        List<BufferedImage> frames = framesOf(moduleDir, item);
        return frames == null || index < 0 || index >= frames.size() ? null : frames.get(index);
    }
}

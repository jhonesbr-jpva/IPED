package iped.semantic.unit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

import iped.semantic.prep.VideoMosaicSplitter;
import iped.utils.ImageUtil;

/**
 * The frames of a video mosaic (research D8), over a synthetic mosaic drawn exactly as
 * {@code VideoThumbsMaker} draws it: width {@code 2 + cols * (w + border) + border}, cell
 * {@code (i, j)} at {@code x = 1 + j * (w + border) + border}, {@code y = 1 + i * (h + border) +
 * border}, border 2, and the grid in the JPEG comment {@code Frames=RxC}.
 */
public class VideoMosaicSplitterTest {

    private static final int BORDER = 2;
    private static final int W = 64;
    private static final int H = 48;
    private static final Color[] COLORS = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA,
            Color.CYAN };

    /** A 2 x 3 mosaic whose cell k is filled with COLORS[k], on a black background. */
    private static BufferedImage mosaic(int rows, int cols) {
        BufferedImage img = new BufferedImage(2 + cols * (W + BORDER) + BORDER, 2 + rows * (H + BORDER) + BORDER,
                BufferedImage.TYPE_INT_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < rows; i++) {
            int y = 1 + i * (H + BORDER) + BORDER;
            for (int j = 0; j < cols; j++) {
                int x = 1 + j * (W + BORDER) + BORDER;
                g.setColor(COLORS[i * cols + j]);
                g.fillRect(x, y, W, H);
            }
        }
        g.dispose();
        return img;
    }

    @Test
    public void theGridIsReadFromTheComment() {
        assertArrayEquals(new int[] { 2, 3 }, VideoMosaicSplitter.grid("Frames=2x3"));
        assertArrayEquals(new int[] { 10, 12 }, VideoMosaicSplitter.grid("Frames=10x12"));
        assertNull("not a mosaic", VideoMosaicSplitter.grid(null));
        assertNull(VideoMosaicSplitter.grid("some other comment"));
        assertNull(VideoMosaicSplitter.grid("Frames=0x3"));
        assertNull(VideoMosaicSplitter.grid("Frames=ax3"));
    }

    @Test
    public void cellsAreCutWithTheGeometryTheyWereDrawnWith() {
        List<BufferedImage> frames = VideoMosaicSplitter.frames(mosaic(2, 3), "Frames=2x3");
        assertNotNull(frames);
        assertEquals(6, frames.size());
        for (int k = 0; k < frames.size(); k++) {
            BufferedImage f = frames.get(k);
            assertEquals("cell width", W, f.getWidth());
            assertEquals("cell height", H, f.getHeight());
            // every pixel of the cell is its colour: no border, no neighbour
            for (int y = 0; y < H; y += 7) {
                for (int x = 0; x < W; x += 7) {
                    assertEquals("frame " + k + " at " + x + "," + y, COLORS[k].getRGB() & 0xFFFFFF,
                            f.getRGB(x, y) & 0xFFFFFF);
                }
            }
            assertEquals(COLORS[k].getRGB() & 0xFFFFFF, f.getRGB(W - 1, H - 1) & 0xFFFFFF);
        }
    }

    @Test
    public void aMosaicSavedAsTheTaskSavesItIsReadBack() throws Exception {
        File jpeg = File.createTempFile("mosaic", ".jpg");
        try {
            ImageUtil.saveJpegWithMetadata(mosaic(2, 3), jpeg, "Frames=2x3", 95);
            List<BufferedImage> frames = VideoMosaicSplitter.frames(new ByteArrayInputStream(Files.readAllBytes(
                    jpeg.toPath())));
            assertNotNull(frames);
            assertEquals(6, frames.size());
            for (int k = 0; k < frames.size(); k++) {
                int rgb = frames.get(k).getRGB(W / 2, H / 2);
                assertTrue("frame " + k + " keeps its colour through JPEG", close(rgb, COLORS[k].getRGB()));
            }
        } finally {
            jpeg.delete();
        }
    }

    @Test
    public void anImageWithoutTheCommentIsNotAMosaic() {
        assertNull(VideoMosaicSplitter.frames(mosaic(2, 3), null));
        assertNull(VideoMosaicSplitter.frames(mosaic(2, 3), "Created with GIMP"));
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 24) {
                return false;
            }
        }
        return true;
    }
}

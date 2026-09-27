package iped.semantic.task;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * What the visual preparation needs from an item, independent of {@code IItem}: the task builds it
 * from the processing item, tests build it directly. The pixels are read lazily, only when the
 * item is actually going to be embedded.
 */
public final class VisualItem {

    /** What the item is, for the visual modality. */
    public enum Kind {
        IMAGE,
        VIDEO,
        /** A frame of a video extracted as its own subitem ({@code VideoThumbTask} with subitems on). */
        VIDEO_FRAME_SUBITEM,
        /** Neither an image nor a video. */
        OTHER
    }

    /** The item's thumbnail, as {@code ImageThumbTask} left it; {@code null} when there is none. */
    public interface ThumbSource {
        byte[] read() throws IOException;
    }

    /** The frames of a video's mosaic, in cell order; {@code null} when there is no mosaic. */
    public interface FramesSource {
        List<BufferedImage> read() throws IOException;
    }

    final int id;
    final Kind kind;
    final Set<String> categories;
    final Integer width;
    final Integer height;
    final ThumbSource thumb;
    final FramesSource frames;
    final Long durationMs;

    private VisualItem(int id, Kind kind, Set<String> categories, Integer width, Integer height, ThumbSource thumb,
            FramesSource frames, Long durationMs) {
        this.id = id;
        this.kind = kind;
        this.categories = categories == null ? Set.of() : categories;
        this.width = width;
        this.height = height;
        this.thumb = thumb;
        this.frames = frames;
        this.durationMs = durationMs;
    }

    /**
     * @param width
     *            original width, {@code null} when the metadata does not say
     */
    public static VisualItem image(int id, Set<String> categories, Integer width, Integer height, ThumbSource thumb) {
        return new VisualItem(id, Kind.IMAGE, categories, width, height, thumb, null, null);
    }

    /** A frame subitem: prepared as an image only while videos are off. */
    public static VisualItem videoFrameSubitem(int id, Set<String> categories, Integer width, Integer height,
            ThumbSource thumb) {
        return new VisualItem(id, Kind.VIDEO_FRAME_SUBITEM, categories, width, height, thumb, null, null);
    }

    public static VisualItem video(int id, Set<String> categories, FramesSource frames, Long durationMs) {
        return new VisualItem(id, Kind.VIDEO, categories, null, null, null, frames, durationMs);
    }

    public static VisualItem other(int id, Set<String> categories) {
        return new VisualItem(id, Kind.OTHER, categories, null, null, null, null, null);
    }

    public int getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }
}

package iped.semantic.prep;

/**
 * Approximate instant of a video frame of the thumbnail mosaic (research D8, amendment A2). IPED
 * does not record when each frame was taken. MPlayer extracts them from 1 s on ({@code -ss 1}),
 * skipping {@code frequency} seconds between frames ({@code -sstep}), with
 * {@code frequency = (duration - 1000) * 0.00095 / (maxThumbs + 2)} (at least 1), as
 * {@code VideoThumbsMaker} computes it; the first extracted frame is dropped when there is one more
 * than the grid holds. Cell {@code k} is then about {@code 1000 + (k + 1) * frequency * 1000} ms.
 *
 * <p>
 * Seeking goes to key frames, and some formats fall back to a frame step, so the real error is of
 * the order of one step: the instant is always <b>approximate</b> and always shown as such.
 * </p>
 */
public final class FrameInstant {

    private FrameInstant() {
    }

    /** The MPlayer step in seconds for a video of {@code durationMs} and a grid of {@code frameCount}. */
    public static int stepSeconds(long durationMs, int frameCount) {
        int frequency = (int) ((durationMs - 1000) * 0.00095 / (frameCount + 2));
        return Math.max(1, frequency);
    }

    /**
     * Approximate instant, in milliseconds, of cell {@code frameIndex} of a mosaic of
     * {@code frameCount} cells; {@code null} when the duration is unknown.
     */
    public static Long approxMillis(Long durationMs, int frameIndex, int frameCount) {
        if (durationMs == null || durationMs <= 0 || frameIndex < 0 || frameCount <= 0) {
            return null;
        }
        long t = 1000L + (long) (frameIndex + 1) * stepSeconds(durationMs, frameCount) * 1000L;
        return Math.min(t, durationMs);
    }

    /** {@code mm:ss}, or {@code h:mm:ss} from one hour on; callers add the approximate marker. */
    public static String format(long millis) {
        long s = Math.max(0, millis) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    /** The instant as the UI and the MCP show it: {@code "≈ mm:ss"}. */
    public static String approxLabel(long millis) {
        return "≈ " + format(millis);
    }
}

package iped.semantic.prep;

/**
 * Approximate instant of a video frame of the thumbnail mosaic (research D8, amendment A2). IPED
 * does not record when each frame was taken, so it is recomputed from how {@code VideoThumbsMaker}
 * asks MPlayer for them. {@code maxThumbs + 1} frames are extracted and the first is dropped, so
 * cell {@code k} holds extracted frame {@code k + 1}. The extraction takes one of two paths:
 *
 * <ul>
 * <li><b>by seconds</b>, when {@code frequency = (duration - 1000) * 0.00095 / (maxThumbs + 2)}
 * is at least 2: {@code -ss 1 -sstep frequency}, so frame {@code j} is at about
 * {@code 1 + j * frequency} s;</li>
 * <li><b>by frames</b>, when it is below 2 - short videos: no initial seek, and every
 * {@code frameStep = min(240, fps) * (duration - 1) * 0.001 / (maxThumbs + 2)} frames (between 1
 * and 600), so frame {@code j} is at about {@code j * frameStep / fps} s.</li>
 * </ul>
 *
 * <p>
 * Seeking goes to key frames, and the engine falls back to the other path when one fails, so the
 * real error is of the order of one step: the instant is always <b>approximate</b> and always shown
 * as such.
 * </p>
 */
public final class FrameInstant {

    private FrameInstant() {
    }

    /**
     * The MPlayer step in seconds of the extraction by seconds, as {@code VideoThumbsMaker} computes
     * it (at least 1). A value of 1 means the extraction is by frames.
     */
    public static int stepSeconds(long durationMs, int frameCount) {
        int frequency = (int) ((durationMs - 1000) * 0.00095 / (frameCount + 2));
        return Math.max(1, frequency);
    }

    /** Whether {@code VideoThumbsMaker} extracts by frames rather than by seconds. */
    public static boolean byFrames(long durationMs, int frameCount) {
        return stepSeconds(durationMs, frameCount) <= 1;
    }

    /** The {@code framestep} of the extraction by frames, as {@code VideoThumbsMaker} computes it. */
    public static int frameStep(long durationMs, double fps, int frameCount) {
        // float, as there: the product is rounded as VideoThumbsMaker rounds it
        float rate = (float) Math.min(240, fps);
        int step = (int) (rate * (durationMs - 1) * 0.001 / (frameCount + 2));
        return Math.max(1, Math.min(600, step));
    }

    /**
     * Approximate instant, in milliseconds, of cell {@code frameIndex} of a mosaic of
     * {@code frameCount} cells; {@code null} when the duration is unknown.
     *
     * @param fps
     *            the video's frame rate, {@code null} or not positive when unknown: then a short
     *            video's step is taken as the duration over {@code frameCount + 2}
     */
    public static Long approxMillis(Long durationMs, Double fps, int frameIndex, int frameCount) {
        if (durationMs == null || durationMs <= 0 || frameIndex < 0 || frameCount <= 0) {
            return null;
        }
        int extracted = frameIndex + 1;
        long t;
        if (!byFrames(durationMs, frameCount)) {
            t = 1000L + (long) extracted * stepSeconds(durationMs, frameCount) * 1000L;
        } else if (fps != null && fps > 0) {
            t = Math.round(extracted * frameStep(durationMs, fps, frameCount) * 1000d / fps);
        } else {
            t = Math.round(extracted * (durationMs - 1) / (double) (frameCount + 2));
        }
        return Math.min(t, durationMs);
    }

    /** As {@link #approxMillis(Long, Double, int, int)} with the frame rate unknown. */
    public static Long approxMillis(Long durationMs, int frameIndex, int frameCount) {
        return approxMillis(durationMs, null, frameIndex, frameCount);
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

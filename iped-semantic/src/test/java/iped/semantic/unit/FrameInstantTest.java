package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import iped.semantic.prep.FrameInstant;

/**
 * The approximate instant of a mosaic frame (research D8, amendment A2), against the formula of
 * {@code VideoThumbsMaker}: MPlayer from 1 s on ({@code -ss 1}), a step of
 * {@code frequency = (duration - 1000) * 0.00095 / (maxThumbs + 2)} seconds ({@code -sstep}, at
 * least 1), {@code maxThumbs + 1} frames extracted and the first one dropped.
 */
public class FrameInstantTest {

    /** VideoThumbsMaker, line for line. */
    private static int videoThumbsMakerFrequency(long durationMs, int maxThumbs) {
        int frequency = (int) ((durationMs - 1000) * 0.00095 / (maxThumbs + 2));
        if (frequency < 1) {
            frequency = 1;
        }
        return frequency;
    }

    @Test
    public void theStepIsVideoThumbsMakers() {
        long[] durations = { 1_500, 10_000, 65_000, 600_000, 3_600_000, 7_265_000 };
        int[] grids = { 4, 9, 12, 16, 30 };
        for (long d : durations) {
            for (int g : grids) {
                assertEquals(d + " ms, " + g + " frames", videoThumbsMakerFrequency(d, g),
                        FrameInstant.stepSeconds(d, g));
            }
        }
    }

    @Test
    public void cellKIsOneSecondPlusKPlusOneSteps() {
        // 10 minutes, 12 frames: frequency = (600000 - 1000) * 0.00095 / 14 = 40 s
        assertEquals(40, FrameInstant.stepSeconds(600_000, 12));
        assertEquals(Long.valueOf(1_000 + 40_000), FrameInstant.approxMillis(600_000L, 0, 12));
        assertEquals(Long.valueOf(1_000 + 6 * 40_000), FrameInstant.approxMillis(600_000L, 5, 12));
        assertEquals(Long.valueOf(1_000 + 12 * 40_000), FrameInstant.approxMillis(600_000L, 11, 12));
    }

    /** VideoThumbsMaker's frameStep, line for line. */
    private static int videoThumbsMakerFrameStep(long durationMs, float fps, int maxThumbs) {
        fps = Math.min(240, fps);
        int frameStep = (int) (fps * (durationMs - 1) * 0.001 / (maxThumbs + 2));
        if (frameStep < 1) {
            frameStep = 1;
        } else if (frameStep > 600) {
            frameStep = 600;
        }
        return frameStep;
    }

    @Test
    public void aShortVideoIsExtractedByFramesWithoutTheInitialSeek() {
        // 40 s at 25 fps, 20 cells: frequency = 39000 * 0.00095 / 22 = 1.68 -> 1, so by frames:
        // frameStep = 25 * 39.959 / 22 = 45 frames = 1.8 s, and cell k is extracted frame k + 1
        assertTrue(FrameInstant.byFrames(39_960, 20));
        assertEquals(videoThumbsMakerFrameStep(39_960, 25f, 20), FrameInstant.frameStep(39_960, 25.0, 20));
        assertEquals(45, FrameInstant.frameStep(39_960, 25.0, 20));
        assertEquals(Long.valueOf(6 * 1_800), FrameInstant.approxMillis(39_960L, 25.0, 5, 20));
        assertEquals(Long.valueOf(17 * 1_800), FrameInstant.approxMillis(39_960L, 25.0, 16, 20));
        // an unknown frame rate falls back to the duration over the cells plus two
        assertEquals(Long.valueOf(Math.round(6 * 39_959 / 22.0)), FrameInstant.approxMillis(39_960L, null, 5, 20));
        // the frame rate is capped at 240 and the step at 600 frames
        assertEquals(videoThumbsMakerFrameStep(3_000_000, 1000f, 1), FrameInstant.frameStep(3_000_000, 1000.0, 1));
        // a long video keeps the extraction by seconds whatever the frame rate
        assertEquals(Long.valueOf(1_000 + 6 * 40_000), FrameInstant.approxMillis(600_000L, 25.0, 5, 12));
    }

    @Test
    public void theFrameStepIsVideoThumbsMakers() {
        long[] durations = { 1_500, 10_000, 39_960, 45_000 };
        float[] rates = { 15f, 25f, 29.97f, 60f, 300f };
        for (long d : durations) {
            for (float f : rates) {
                assertEquals(d + " ms at " + f, videoThumbsMakerFrameStep(d, f, 20), FrameInstant.frameStep(d, f, 20));
            }
        }
    }

    @Test
    public void neverPastTheEndAndUnknownWithoutDuration() {
        // 3 s, 12 cells: extracted by frames, the last cell falls inside the video
        long last = FrameInstant.approxMillis(3_000L, 11, 12);
        assertEquals(Math.round(12 * 2_999 / 14.0), last);
        assertTrue(last <= 3_000);
        for (int k = 0; k < 30; k++) {
            assertTrue(FrameInstant.approxMillis(10_000L, 30.0, k, 30) <= 10_000);
        }
        assertNull(FrameInstant.approxMillis(null, 0, 12));
        assertNull(FrameInstant.approxMillis(0L, 0, 12));
        assertNull(FrameInstant.approxMillis(600_000L, -1, 12));
        assertNull(FrameInstant.approxMillis(600_000L, 0, 0));
    }

    @Test
    public void theLabelAlwaysSaysApproximate() {
        assertEquals("≈ 01:12", FrameInstant.approxLabel(72_000));
        assertEquals("≈ 00:00", FrameInstant.approxLabel(0));
        assertEquals("≈ 1:02:03", FrameInstant.approxLabel(3_723_000));
        assertTrue(FrameInstant.approxLabel(599_999).startsWith("≈ "));
        assertEquals("09:59", FrameInstant.format(599_999));
    }
}

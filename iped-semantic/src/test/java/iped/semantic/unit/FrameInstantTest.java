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

    @Test
    public void neverPastTheEndAndUnknownWithoutDuration() {
        assertEquals(Long.valueOf(3_000), FrameInstant.approxMillis(3_000L, 11, 12));
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

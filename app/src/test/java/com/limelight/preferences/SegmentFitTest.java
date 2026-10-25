package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SegmentFitTest {

    // Label widths in px at density 2.625 for "Auto 720p 1080p 1440p 4K More" at 14sp
    private static final float[] RESOLUTION_1_0 = {74f, 82f, 104f, 104f, 46f, 90f};
    private static final float PADDING = 8 * 2.625f;

    private static float[] scaled(float[] widths, float scale) {
        float[] result = new float[widths.length];
        for (int i = 0; i < widths.length; i++) {
            result[i] = widths[i] * scale;
        }
        return result;
    }

    @Test
    public void resolutionSegmentsFitAFullWidthRow() {
        // 600dp of track
        assertTrue(SegmentFit.fits(RESOLUTION_1_0, 600 * 2.625f, PADDING));
    }

    @Test
    public void largeTextInANarrowPanelFallsBackToADropdown() {
        // 340dp panel minus row padding leaves about 300dp of track
        float available = 300 * 2.625f;
        assertTrue(SegmentFit.fits(RESOLUTION_1_0, available, PADDING));
        assertFalse(SegmentFit.fits(scaled(RESOLUTION_1_0, 1.3f), available, PADDING));
    }

    @Test
    public void moreCountsAsASegment() {
        float[] withoutMore = {100f, 100f, 100f};
        float[] withMore = {100f, 100f, 100f, 100f};
        float available = 3 * (100f + 2 * PADDING);
        assertTrue(SegmentFit.fits(withoutMore, available, PADDING));
        assertFalse(SegmentFit.fits(withMore, available, PADDING));
    }

    @Test
    public void unknownWidthCountsAsFitting() {
        assertTrue(SegmentFit.fits(RESOLUTION_1_0, 0f, PADDING));
        assertTrue(SegmentFit.fits(new float[0], 100f, PADDING));
    }
}

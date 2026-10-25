package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeekBarPreferenceFormatTest {

    @Test
    public void bitrateUsesOneDecimalAndASpacedSuffix() {
        assertEquals("39.0 Mbps", SeekBarPreference.formatValue(39000, 1000, "Mbps"));
        assertEquals("0.5 Mbps", SeekBarPreference.formatValue(500, 1000, "Mbps"));
    }

    @Test
    public void percentSuffixHasNoSpace() {
        assertEquals("90%", SeekBarPreference.formatValue(90, 1, "%"));
        assertEquals("-20%", SeekBarPreference.formatValue(-20, 1, "%"));
    }

    @Test
    public void divisorOneShowsTheInteger() {
        assertEquals("250 ms", SeekBarPreference.formatValue(250, 1, "ms"));
        assertEquals("7", SeekBarPreference.formatValue(7, 1, null));
    }

    @Test
    public void snapRoundsToTheStepAndClampsToTheRange() {
        assertEquals(1500, SeekBarPreference.snap(1400, 500, 300000, 500));
        assertEquals(500, SeekBarPreference.snap(100, 500, 300000, 500));
        assertEquals(300000, SeekBarPreference.snap(301000, 500, 300000, 500));
        assertEquals(-20, SeekBarPreference.snap(-25, -20, 20, 1));
    }
}

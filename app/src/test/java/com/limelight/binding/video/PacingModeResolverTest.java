package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PacingModeResolverTest {

    private static final int[] ALL_MODES = {
            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
            PreferenceConfiguration.FRAME_PACING_BALANCED,
            PreferenceConfiguration.FRAME_PACING_CAP_FPS,
            PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS,
            PreferenceConfiguration.FRAME_PACING_NEWEST,
    };

    /** The cap-fps block exactly as Game.java had it inline in 1.2.2, for comparison. */
    private static PacingModeResolver.Result legacyInlineBlock(int framePacing, float fps, int roundedRefreshRate) {
        String log = null;
        float chosenFrameRate = fps;
        if (framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (fps >= roundedRefreshRate) {
                if (fps > roundedRefreshRate + 3) {
                    framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    log = "Using drop mode for FPS > Hz";
                } else if (roundedRefreshRate <= 49) {
                    framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    log = "Bogus refresh rate: " + roundedRefreshRate;
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    log = "Adjusting FPS target for screen to " + chosenFrameRate;
                }
            }
        }
        return new PacingModeResolver.Result(framePacing, chosenFrameRate, log);
    }

    @Test
    public void identicalToTheLegacyInlineBlock() {
        float[] extraFps = {23.976f, 29.97f, 59.94f, 119.88f, 143.856f};
        int checked = 0;
        for (int mode : ALL_MODES) {
            for (int hz = 1; hz <= 240; hz++) {
                for (int tenths = 10; tenths <= 2500; tenths += 5) {
                    checked += compare(mode, tenths / 10f, hz);
                }
                for (float fps : extraFps) {
                    checked += compare(mode, fps, hz);
                }
            }
        }
        assertTrue(checked > 100000);
    }

    private static int compare(int mode, float fps, int hz) {
        PacingModeResolver.Result expected = legacyInlineBlock(mode, fps, hz);
        PacingModeResolver.Result actual = PacingModeResolver.resolveForDisplay(mode, fps, hz);
        String where = "mode " + mode + " fps " + fps + " hz " + hz;
        assertEquals(where, expected.framePacing, actual.framePacing);
        assertEquals(where, Float.floatToIntBits(expected.frameRate), Float.floatToIntBits(actual.frameRate));
        assertEquals(where, expected.logMessage, actual.logMessage);
        assertEquals(where, expected.framePacing, PacingModeResolver.resolve(mode, fps, hz));
        return 1;
    }

    @Test
    public void everyModePassesThroughWhenFpsIsAtMostHzPlusThree() {
        for (int mode : ALL_MODES) {
            for (int hz : new int[] {60, 90, 120, 144}) {
                for (float fps = 30; fps <= hz + 3; fps += 0.5f) {
                    assertEquals(mode, PacingModeResolver.resolve(mode, fps, hz));
                }
            }
        }
    }

    @Test
    public void capFpsAt120On60HzBecomesBalanced() {
        PacingModeResolver.Result r = PacingModeResolver.resolveForDisplay(
                PreferenceConfiguration.FRAME_PACING_CAP_FPS, 120, 60);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, r.framePacing);
        assertEquals(120f, r.frameRate, 0f);
    }

    @Test
    public void capFpsOn48HzPanelBecomesBalanced() {
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED,
                PacingModeResolver.resolve(PreferenceConfiguration.FRAME_PACING_CAP_FPS, 48, 48));
    }

    @Test
    public void capFpsAtDisplayRateTargetsOneBelow() {
        PacingModeResolver.Result r = PacingModeResolver.resolveForDisplay(
                PreferenceConfiguration.FRAME_PACING_CAP_FPS, 120, 120);
        assertEquals(PreferenceConfiguration.FRAME_PACING_CAP_FPS, r.framePacing);
        assertEquals(119f, r.frameRate, 0f);
        assertEquals("Adjusting FPS target for screen to 119.0", r.logMessage);
    }

    @Test
    public void newestIsNeverRewritten() {
        for (int hz = 1; hz <= 240; hz++) {
            for (float fps : new float[] {24, 30, 48, 60, 90, 120, 144, 240}) {
                PacingModeResolver.Result r = PacingModeResolver.resolveForDisplay(
                        PreferenceConfiguration.FRAME_PACING_NEWEST, fps, hz);
                assertEquals(PreferenceConfiguration.FRAME_PACING_NEWEST, r.framePacing);
                assertEquals(fps, r.frameRate, 0f);
                assertNull(r.logMessage);
            }
        }
    }

    @Test
    public void otherModesAreNeverRewritten() {
        for (int mode : ALL_MODES) {
            if (mode == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                continue;
            }
            PacingModeResolver.Result r = PacingModeResolver.resolveForDisplay(mode, 240, 30);
            assertEquals(mode, r.framePacing);
            assertEquals(240f, r.frameRate, 0f);
            assertNull(r.logMessage);
        }
    }

    @Test
    public void smoothestAndCapFpsSkipTheLatestOnlyPath() {
        assertTrue(PacingModeResolver.usesLatestOnlyPath(PreferenceConfiguration.FRAME_PACING_BALANCED));
        assertTrue(PacingModeResolver.usesLatestOnlyPath(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY));
        assertFalse(PacingModeResolver.usesLatestOnlyPath(PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS));
        assertFalse(PacingModeResolver.usesLatestOnlyPath(PreferenceConfiguration.FRAME_PACING_CAP_FPS));
        assertFalse(PacingModeResolver.usesLatestOnlyPath(PreferenceConfiguration.FRAME_PACING_NEWEST));
    }

    @Test
    public void modeNamesMatchTheStoredValues() {
        assertEquals("latency", PacingModeResolver.modeName(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY));
        assertEquals("balanced", PacingModeResolver.modeName(PreferenceConfiguration.FRAME_PACING_BALANCED));
        assertEquals("cap-fps", PacingModeResolver.modeName(PreferenceConfiguration.FRAME_PACING_CAP_FPS));
        assertEquals("smoothness", PacingModeResolver.modeName(PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS));
        assertEquals("newest", PacingModeResolver.modeName(PreferenceConfiguration.FRAME_PACING_NEWEST));
    }
}

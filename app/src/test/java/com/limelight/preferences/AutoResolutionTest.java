package com.limelight.preferences;

import com.limelight.preferences.AutoResolution.DecoderCaps;
import com.limelight.preferences.AutoResolution.DisplayInfo;
import com.limelight.preferences.AutoResolution.DisplayMode;
import com.limelight.preferences.AutoResolution.Request;
import com.limelight.preferences.AutoResolution.Result;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AutoResolutionTest {

    // Built-in panel reported in portrait, as on the target handheld: 1080x1920, 60 and 120 Hz
    private static DisplayInfo handheldPanel(float currentRate) {
        return new DisplayInfo(0, true, 1080, 1920, currentRate,
                Arrays.asList(new DisplayMode(1080, 1920, 60f), new DisplayMode(1080, 1920, 120f)),
                false, false, false, true);
    }

    // Small second panel that may carry the presentation flag
    private static DisplayInfo bottomPanel() {
        return new DisplayInfo(2, false, 1080, 1240, 60f,
                Arrays.asList(new DisplayMode(1080, 1240, 60f)), true, false, false, true);
    }

    private static DisplayInfo external(int id, int width, int height, float rate, boolean isDefault) {
        return new DisplayInfo(id, isDefault, width, height, rate,
                Arrays.asList(new DisplayMode(width, height, rate), new DisplayMode(width, height, 30f)),
                true, false, true, true);
    }

    private static Request auto(DisplayInfo activityDisplay, DisplayInfo... others) {
        Request r = new Request();
        r.autoResolution = true;
        r.autoFps = true;
        r.width = 1920;
        r.height = 1080;
        r.fps = 60;
        r.activityDisplay = activityDisplay;
        r.otherDisplays = new ArrayList<>(Arrays.asList(others));
        r.preferExternalWhenMirrored = true;
        return r;
    }

    /** Decoder limited to a maximum size and a pixel rate. */
    private static DecoderCaps caps(final int maxWidth, final int maxHeight, final long maxPixelsPerSecond) {
        return new DecoderCaps() {
            @Override
            public boolean trustworthy() {
                return true;
            }

            @Override
            public boolean isSizeSupported(int width, int height) {
                return width <= maxWidth && height <= maxHeight;
            }

            @Override
            public boolean isSizeAndRateSupported(int width, int height, float fps) {
                return isSizeSupported(width, height) && (long) width * height * fps <= maxPixelsPerSecond;
            }
        };
    }

    private static void assertSize(Result result, int width, int height, float fps) {
        assertEquals("width (" + result + ")", width, result.width);
        assertEquals("height (" + result + ")", height, result.height);
        assertEquals("fps (" + result + ")", fps, result.fps, 0.001f);
    }

    @Test
    public void explicitValuesPassThroughUntouched() {
        Request r = auto(handheldPanel(120f), external(5, 3840, 2160, 60f, false));
        r.autoResolution = false;
        r.autoFps = false;
        r.width = 1280;
        r.height = 720;
        r.fps = 90;
        r.caps = caps(1, 1, 1);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1280, 720, 90);
        assertEquals(AutoResolution.Mode.EXPLICIT, result.mode);
        assertEquals(AutoResolution.Clamp.NONE, result.clamp);
    }

    @Test
    public void portraitPanelIsNormalizedToLandscape() {
        Result result = AutoResolution.resolve(auto(handheldPanel(120f)));
        assertSize(result, 1920, 1080, 120);
        assertEquals(AutoResolution.Mode.ACTIVITY_DISPLAY, result.mode);
        assertEquals(0, result.targetDisplayId);
    }

    @Test
    public void idlePanelAt60HzStillPicks120() {
        Result result = AutoResolution.resolve(auto(handheldPanel(60f)));
        assertSize(result, 1920, 1080, 120);
    }

    @Test
    public void bottomPanelWithPresentationFlagIsIgnored() {
        Result result = AutoResolution.resolve(auto(handheldPanel(120f), bottomPanel()));
        assertSize(result, 1920, 1080, 120);
        assertEquals(AutoResolution.Mode.ACTIVITY_DISPLAY, result.mode);
    }

    @Test
    public void appOnTheBottomPanelUsesThatPanel() {
        Result result = AutoResolution.resolve(auto(bottomPanel(), handheldPanel(120f)));
        assertSize(result, 1240, 1080, 60);
    }

    @Test
    public void external4kAsActivityDisplay() {
        Result result = AutoResolution.resolve(auto(external(5, 3840, 2160, 60f, false), handheldPanel(120f)));
        assertSize(result, 3840, 2160, 60);
        assertEquals(5, result.targetDisplayId);
    }

    @Test
    public void mirrored4kWithPreferExternalOn() {
        Result result = AutoResolution.resolve(auto(handheldPanel(120f), external(5, 3840, 2160, 60f, false)));
        assertSize(result, 3840, 2160, 60);
        assertEquals(AutoResolution.Mode.MIRRORED_EXTERNAL, result.mode);
        assertEquals(5, result.targetDisplayId);
    }

    @Test
    public void mirrored4kWithPreferExternalOff() {
        Request r = auto(handheldPanel(120f), external(5, 3840, 2160, 60f, false));
        r.preferExternalWhenMirrored = false;
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1920, 1080, 120);
        assertEquals(AutoResolution.Mode.ACTIVITY_DISPLAY, result.mode);
    }

    @Test
    public void mirroredUltrawideKeepsThePanelAspect() {
        Result result = AutoResolution.resolve(auto(handheldPanel(120f), external(5, 3440, 1440, 100f, false)));
        assertSize(result, 2560, 1440, 100);
    }

    @Test
    public void privateOrOffDisplaysAreNotMirrorTargets() {
        DisplayInfo privateDisplay = new DisplayInfo(6, false, 3840, 2160, 60f, null, true, true, true, true);
        DisplayInfo offDisplay = new DisplayInfo(7, false, 3840, 2160, 60f, null, true, false, true, false);
        DisplayInfo internalOnly = new DisplayInfo(8, false, 3840, 2160, 60f, null, false, false, false, true);
        Result result = AutoResolution.resolve(auto(handheldPanel(120f), privateDisplay, offDisplay, internalOnly));
        assertSize(result, 1920, 1080, 120);
    }

    @Test
    public void ultrawideAsActivityDisplayKeepsItsShape() {
        Result result = AutoResolution.resolve(auto(external(5, 3440, 1440, 144f, false)));
        assertSize(result, 3440, 1440, 144);
    }

    @Test
    public void fiveKTwoKIsClampedByPolicy() {
        Result result = AutoResolution.resolve(auto(external(5, 5120, 2160, 60f, false)));
        assertSize(result, 4096, 1728, 60);
        assertEquals(AutoResolution.Clamp.POLICY, result.clamp);
    }

    @Test
    public void eightKIsClampedTo4k() {
        Result result = AutoResolution.resolve(auto(external(5, 7680, 4320, 60f, false)));
        assertSize(result, 3840, 2160, 60);
        assertEquals(AutoResolution.Clamp.POLICY, result.clamp);
    }

    @Test
    public void oddSizesAreAligned() {
        Result result = AutoResolution.resolve(auto(external(5, 1366, 768, 60f, false)));
        assertSize(result, 1360, 768, 60);
    }

    @Test
    public void tinyDisplaysNeverGoBelowTheMinimum() {
        Result result = AutoResolution.resolve(auto(external(5, 480, 320, 60f, false)));
        assertSize(result, 640, 360, 60);
    }

    @Test
    public void refreshRatesAreRoundedAndClamped() {
        assertEquals(120f, AutoResolution.normalizeFps(119.99f), 0.0001f);
        assertEquals(59.94f, AutoResolution.normalizeFps(59.94f), 0.0001f);
        assertEquals(30f, AutoResolution.normalizeFps(24f), 0.0001f);
        assertEquals(240f, AutoResolution.normalizeFps(360f), 0.0001f);
    }

    @Test
    public void decoderWithout4kStepsDownKeepingAspect() {
        Request r = auto(external(5, 3840, 2160, 60f, false));
        r.caps = caps(2560, 1440, Long.MAX_VALUE);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 2560, 1440, 60);
        assertEquals(AutoResolution.Clamp.DECODER, result.clamp);
    }

    @Test
    public void decoderWith4k60OnlyLowersAutoFpsOn120HzDisplay() {
        DisplayInfo tv = new DisplayInfo(5, false, 3840, 2160, 120f,
                Arrays.asList(new DisplayMode(3840, 2160, 120f), new DisplayMode(3840, 2160, 60f)),
                true, false, true, true);
        Request r = auto(tv);
        r.caps = caps(3840, 2160, 3840L * 2160L * 60L);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 3840, 2160, 60);
        assertEquals(AutoResolution.Clamp.DECODER, result.clamp);
    }

    @Test
    public void decoderWith4k60OnlyAndExplicit120StepsTheSizeDown() {
        DisplayInfo tv = new DisplayInfo(5, false, 3840, 2160, 120f,
                Arrays.asList(new DisplayMode(3840, 2160, 120f), new DisplayMode(3840, 2160, 60f)),
                true, false, true, true);
        Request r = auto(tv);
        r.autoFps = false;
        r.fps = 120;
        r.caps = caps(3840, 2160, 3840L * 2160L * 60L);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 2560, 1440, 120);
    }

    @Test
    public void untrustworthyDecoderCapsAreIgnored() {
        Request r = auto(external(5, 3840, 2160, 60f, false));
        final DecoderCaps strict = caps(1280, 720, 1);
        r.caps = new DecoderCaps() {
            @Override
            public boolean trustworthy() {
                return false;
            }

            @Override
            public boolean isSizeSupported(int width, int height) {
                return strict.isSizeSupported(width, height);
            }

            @Override
            public boolean isSizeAndRateSupported(int width, int height, float fps) {
                return strict.isSizeAndRateSupported(width, height, fps);
            }
        };
        Result result = AutoResolution.resolve(r);
        assertSize(result, 3840, 2160, 60);
    }

    @Test
    public void decoderNeverClampsAt1080p() {
        Request r = auto(handheldPanel(120f));
        r.caps = caps(1280, 720, 1);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1920, 1080, 120);
    }

    @Test
    public void autoFpsWithExplicitSize() {
        Request r = auto(handheldPanel(60f));
        r.autoResolution = false;
        r.width = 1280;
        r.height = 720;
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1280, 720, 120);
    }

    @Test
    public void autoSizeWithExplicitFps() {
        Request r = auto(handheldPanel(120f));
        r.autoFps = false;
        r.fps = 90;
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1920, 1080, 90);
    }

    @Test
    public void unknownRefreshRateKeepsThePlaceholder() {
        DisplayInfo legacy = new DisplayInfo(0, true, 1280, 800, 0f, null, false, false, false, true);
        Result result = AutoResolution.resolve(auto(legacy));
        assertSize(result, 1280, 800, 60);
    }

    @Test
    public void missingDisplayFallsBackToTheRequestValues() {
        Request r = auto(null);
        Result result = AutoResolution.resolve(r);
        assertSize(result, 1920, 1080, 60);
    }

    @Test
    public void stepDownLadder() {
        List<int[]> steps = new ArrayList<>();
        int height = 2160;
        int[] next;
        while ((next = AutoResolution.stepDown(height, 16.0 / 9.0)) != null) {
            steps.add(next);
            height = next[1];
        }
        assertEquals(4, steps.size());
        assertEquals(2840, steps.get(0)[0]);
        assertEquals(1600, steps.get(0)[1]);
        assertEquals(2560, steps.get(1)[0]);
        assertEquals(1440, steps.get(1)[1]);
        assertEquals(1920, steps.get(3)[0]);
        assertEquals(1080, steps.get(3)[1]);
    }
}

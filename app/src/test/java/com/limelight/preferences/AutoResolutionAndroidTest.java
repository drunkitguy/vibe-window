package com.limelight.preferences;

import android.app.Activity;
import android.media.MediaCodecInfo;
import android.util.Range;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AutoResolutionAndroidTest {

    private final AutoResolutionAndroid.Probe originalProbe = AutoResolutionAndroid.probe;

    @After
    public void restoreProbe() {
        AutoResolutionAndroid.probe = originalProbe;
    }

    /** Fails the test on any display or decoder query. */
    private static class ForbiddenProbe implements AutoResolutionAndroid.Probe {
        @Override
        public AutoResolution.DisplayInfo activityDisplay(Activity activity) {
            throw new AssertionError("display queried");
        }

        @Override
        public List<AutoResolution.DisplayInfo> otherDisplays(Activity activity, int activityDisplayId) {
            throw new AssertionError("displays listed");
        }

        @Override
        public AutoResolution.DecoderCaps decoderCaps(Activity activity, PreferenceConfiguration prefConfig) {
            throw new AssertionError("codec queried");
        }
    }

    /** Built-in 1080x1920 panel at 60 Hz with a 120 Hz mode, nothing else connected. */
    private static class PanelProbe implements AutoResolutionAndroid.Probe {
        boolean failOthers;

        @Override
        public AutoResolution.DisplayInfo activityDisplay(Activity activity) {
            return new AutoResolution.DisplayInfo(0, true, 1080, 1920, 60f,
                    Arrays.asList(new AutoResolution.DisplayMode(1080, 1920, 60f),
                            new AutoResolution.DisplayMode(1080, 1920, 120f)),
                    false, false, false, true);
        }

        @Override
        public List<AutoResolution.DisplayInfo> otherDisplays(Activity activity, int activityDisplayId) {
            if (failOthers) {
                throw new IllegalStateException("display service died");
            }
            return Collections.emptyList();
        }

        @Override
        public AutoResolution.DecoderCaps decoderCaps(Activity activity, PreferenceConfiguration prefConfig) {
            return null;
        }
    }

    private static PreferenceConfiguration explicitConfig() {
        PreferenceConfiguration config = new PreferenceConfiguration();
        config.width = 1920;
        config.height = 1080;
        config.fps = 120;
        config.bitrate = 28000;
        config.meteredBitrate = 7000;
        config.bitrateFollowsResolution = true;
        config.meteredBitrateDerived = true;
        config.autoResPreferExternal = true;
        return config;
    }

    private static PreferenceConfiguration autoConfig() {
        PreferenceConfiguration config = explicitConfig();
        config.autoResolution = true;
        config.autoFps = true;
        config.fps = PreferenceConfiguration.AUTO_PLACEHOLDER_FPS;
        config.bitrate = 20000;
        config.meteredBitrate = 5000;
        return config;
    }

    @Test
    public void explicitSettingsMakeNoDisplayOrCodecCalls() {
        AutoResolutionAndroid.probe = new ForbiddenProbe();
        Activity activity = mock(Activity.class);
        PreferenceConfiguration config = explicitConfig();

        AutoResolutionAndroid.resolveInto(activity, config);

        verifyNoInteractions(activity);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(120f, config.fps, 0.001f);
        assertEquals(28000, config.bitrate);
        assertEquals(7000, config.meteredBitrate);
    }

    @Test
    public void explicitSettingsAreUntouchedForEveryExplicitChoice() {
        AutoResolutionAndroid.probe = new ForbiddenProbe();
        int[][] sizes = {{640, 360}, {1280, 720}, {2560, 1440}, {3840, 2160}, {2400, 1080}};
        for (int[] size : sizes) {
            Activity activity = mock(Activity.class);
            PreferenceConfiguration config = explicitConfig();
            config.width = size[0];
            config.height = size[1];
            config.fps = 59.94f;
            config.bitrate = 12345;
            AutoResolutionAndroid.resolveInto(activity, config);
            verifyNoInteractions(activity);
            assertEquals(size[0], config.width);
            assertEquals(size[1], config.height);
            assertEquals(59.94f, config.fps, 0.0001f);
            assertEquals(12345, config.bitrate);
        }
    }

    @Test
    public void autoResolvesToThePanelAndFollowsTheBitrate() {
        AutoResolutionAndroid.probe = new PanelProbe();
        PreferenceConfiguration config = autoConfig();
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(120f, config.fps, 0.001f);
        assertEquals(PreferenceConfiguration.getDefaultBitrate(1920, 1080, 120), config.bitrate);
        assertEquals(config.bitrate / 4, config.meteredBitrate);
    }

    @Test
    public void manualBitrateIsKept() {
        AutoResolutionAndroid.probe = new PanelProbe();
        PreferenceConfiguration config = autoConfig();
        config.bitrateFollowsResolution = false;
        config.bitrate = 15000;
        config.meteredBitrate = 3000;
        config.meteredBitrateDerived = false;
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(120f, config.fps, 0.001f);
        assertEquals(15000, config.bitrate);
        assertEquals(3000, config.meteredBitrate);
    }

    @Test
    public void failingOtherDisplaysStillUseTheActivityDisplayModes() {
        PanelProbe probe = new PanelProbe();
        probe.failOthers = true;
        AutoResolutionAndroid.probe = probe;
        PreferenceConfiguration config = autoConfig();
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        // The 120 Hz mode of the panel is still found
        assertEquals(120f, config.fps, 0.001f);
    }

    @Test
    public void failingActivityDisplayFallsBackToTheDefaults() {
        AutoResolutionAndroid.probe = new PanelProbe() {
            @Override
            public AutoResolution.DisplayInfo activityDisplay(Activity activity) {
                throw new IllegalStateException("display gone");
            }
        };
        PreferenceConfiguration config = autoConfig();
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(60f, config.fps, 0.001f);
    }

    /** Fake decoder rate data. */
    private static final class FakeRates implements AutoResolutionAndroid.RateSource {
        List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points;
        Range<Double> achievable;
        boolean throwOnAchievable;
        boolean sizeAndRate;
        int achievableCalls;
        int sizeAndRateCalls;

        @Override
        public List<MediaCodecInfo.VideoCapabilities.PerformancePoint> performancePoints() {
            return points;
        }

        @Override
        public Range<Double> achievableFrameRates(int width, int height) {
            achievableCalls++;
            if (throwOnAchievable) {
                throw new IllegalArgumentException("unsupported size");
            }
            return achievable;
        }

        @Override
        public boolean areSizeAndRateSupported(int width, int height, double fps) {
            sizeAndRateCalls++;
            return sizeAndRate;
        }
    }

    @Test
    public void performancePointsDecideWhenPresent() {
        FakeRates rates = new FakeRates();
        rates.points = Collections.singletonList(
                new MediaCodecInfo.VideoCapabilities.PerformancePoint(3840, 2160, 60));
        rates.sizeAndRate = true;
        assertTrue(AutoResolutionAndroid.rateSupported(33, rates, 3840, 2160, 60));
        assertFalse(AutoResolutionAndroid.rateSupported(33, rates, 3840, 2160, 120));
        assertEquals(0, rates.achievableCalls);
        assertEquals(0, rates.sizeAndRateCalls);
    }

    @Test
    public void emptyPerformancePointsFallBackToAchievableRates() {
        FakeRates rates = new FakeRates();
        rates.points = Collections.emptyList();
        rates.achievable = new Range<>(1.0, 90.0);
        assertTrue(AutoResolutionAndroid.rateSupported(33, rates, 3840, 2160, 60));
        assertFalse(AutoResolutionAndroid.rateSupported(33, rates, 3840, 2160, 120));
        assertEquals(2, rates.achievableCalls);
    }

    @Test
    public void missingRateDataFallsBackToSizeAndRate() {
        FakeRates rates = new FakeRates();
        rates.points = null;
        rates.achievable = null;
        rates.sizeAndRate = true;
        assertTrue(AutoResolutionAndroid.rateSupported(33, rates, 1920, 1080, 120));
        assertEquals(1, rates.sizeAndRateCalls);

        // Before Android 6 only areSizeAndRateSupported() exists
        FakeRates old = new FakeRates();
        old.achievable = new Range<>(1.0, 30.0);
        old.sizeAndRate = true;
        assertTrue(AutoResolutionAndroid.rateSupported(22, old, 1920, 1080, 60));
        assertEquals(0, old.achievableCalls);
    }

    @Test
    public void sizeWithoutAnyRateIsUnsupported() {
        FakeRates rates = new FakeRates();
        rates.points = Collections.emptyList();
        rates.throwOnAchievable = true;
        rates.sizeAndRate = true;
        assertFalse(AutoResolutionAndroid.rateSupported(33, rates, 7680, 4320, 30));
    }

    @Test
    public void eitherDecoderIsEnoughForANonWhitelistedHevcFallback() {
        AutoResolution.DecoderCaps avc = new AutoResolution.DecoderCaps() {
            @Override public boolean trustworthy() { return true; }
            @Override public boolean isSizeSupported(int w, int h) { return w <= 1920; }
            @Override public boolean isSizeAndRateSupported(int w, int h, float fps) { return w <= 1920; }
        };
        AutoResolution.DecoderCaps hevc = new AutoResolution.DecoderCaps() {
            @Override public boolean trustworthy() { return false; }
            @Override public boolean isSizeSupported(int w, int h) { return w <= 3840; }
            @Override public boolean isSizeAndRateSupported(int w, int h, float fps) { return w <= 3840 && fps <= 60; }
        };
        AutoResolution.DecoderCaps either = AutoResolutionAndroid.eitherOf(avc, hevc);
        assertTrue(either.trustworthy());
        assertTrue(either.isSizeSupported(3840, 2160));
        assertTrue(either.isSizeAndRateSupported(3840, 2160, 60));
        assertFalse(either.isSizeAndRateSupported(3840, 2160, 120));
        assertFalse(either.isSizeSupported(5120, 2880));
    }

    @Test
    public void everythingFailingFallsBackTo1080p60() {
        AutoResolutionAndroid.probe = new ForbiddenProbe() {
            @Override
            public AutoResolution.DisplayInfo activityDisplay(Activity activity) {
                throw new RuntimeException("no display");
            }
        };
        PreferenceConfiguration config = autoConfig();
        config.width = 1;
        config.height = 1;
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(60f, config.fps, 0.001f);
    }

    @Test
    public void onlyTheAutoPartIsReplaced() {
        AutoResolutionAndroid.probe = new PanelProbe();
        PreferenceConfiguration config = autoConfig();
        config.autoResolution = false;
        config.width = 1280;
        config.height = 720;
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1280, config.width);
        assertEquals(720, config.height);
        assertEquals(120f, config.fps, 0.001f);
        assertFalse(config.autoResolution);
    }
}

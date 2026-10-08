package com.limelight.preferences;

import android.app.Activity;

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
    private static final class PanelProbe implements AutoResolutionAndroid.Probe {
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
    public void failingProbeFallsBackToTheCurrentMode() {
        PanelProbe probe = new PanelProbe();
        probe.failOthers = true;
        AutoResolutionAndroid.probe = probe;
        PreferenceConfiguration config = autoConfig();
        AutoResolutionAndroid.resolveInto(mock(Activity.class), config);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(60f, config.fps, 0.001f);
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

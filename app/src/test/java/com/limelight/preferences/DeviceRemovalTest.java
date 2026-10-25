package com.limelight.preferences;

import android.app.GameManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.view.Display;

import androidx.fragment.app.Fragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowDisplay;

import java.time.Duration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Rows that a device cannot use are removed by key, wherever they sit in the list, and
 * the removals never throw.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class DeviceRemovalTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        Shadows.shadowOf((android.app.Application) ctx)
                .setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
    }

    private static void writeGlCache() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();
    }

    private static void setFeatures(boolean touchscreen, boolean pip) {
        PackageManager pm = ApplicationProvider.getApplicationContext().getPackageManager();
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, touchscreen);
        Shadows.shadowOf(pm).setSystemFeature("android.software.picture_in_picture", pip);
    }

    private static void setHdr10(boolean hdr10) {
        Display display = ShadowDisplay.getDefaultDisplay();
        if (hdr10) {
            Shadows.shadowOf(display).setDisplayHdrCapabilities(Display.DEFAULT_DISPLAY, 1000f, 500f, 0.1f,
                    Display.HdrCapabilities.HDR_TYPE_HDR10);
        }
        else {
            Shadows.shadowOf(display).setDisplayHdrCapabilities(Display.DEFAULT_DISPLAY, 1000f, 500f, 0.1f);
        }
    }

    private static StreamSettings.SettingsFragment launch() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertNotNull(fragment);
        return (StreamSettings.SettingsFragment) fragment;
    }

    @Test
    public void noTouchscreenNoPipNoHdr10RemovesTheRows() {
        writeGlCache();
        setFeatures(false, false);
        setHdr10(false);
        StreamSettings.SettingsFragment fragment = launch();
        assertNull(fragment.findPreference("checkbox_show_onscreen_controls"));
        assertNull(fragment.findPreference("seekbar_osc_opacity"));
        assertNull(fragment.findPreference("checkbox_enable_pip"));
        assertNull(fragment.findPreference("checkbox_enable_hdr"));
        assertNull(fragment.findPreference("category_onscreen_controls"));
        assertNull(fragment.findPreference("category_special_key_layout"));
        // Everything else is still there
        assertNotNull(fragment.findPreference("list_resolution"));
        assertNotNull(fragment.findPreference("checkbox_window_only"));
    }

    @Test
    public void touchscreenPipAndHdr10KeepTheRows() {
        writeGlCache();
        setFeatures(true, true);
        setHdr10(true);
        StreamSettings.SettingsFragment fragment = launch();
        assertNotNull(fragment.findPreference("checkbox_show_onscreen_controls"));
        assertNotNull(fragment.findPreference("seekbar_osc_opacity"));
        assertNotNull(fragment.findPreference("checkbox_enable_pip"));
        assertNotNull(fragment.findPreference("checkbox_enable_hdr"));
    }

    @Test
    public void brokenShieldFirmwareDisablesHdrWithoutACrash() {
        ShadowBuild.setManufacturer("NVIDIA");
        ShadowBuild.setFingerprint("NVIDIA/foster_e/foster:9/PPR1.180610.011/4079208_2235.1395:user/release-keys");
        writeGlCache();
        setFeatures(true, true);
        setHdr10(true);
        StreamSettings.SettingsFragment fragment = launch();
        CheckBoxPreference hdr = fragment.findPreference("checkbox_enable_hdr");
        assertNotNull(hdr);
        assertFalse(hdr.isEnabled());
        assertFalse(hdr.isChecked());
    }
}

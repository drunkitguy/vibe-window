package com.limelight.preferences;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class InlineSliderTest {

    private SharedPreferences prefs;
    private int followed;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        SettingsTestUtil.resetEnvironment();
        Context ctx = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(ctx.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true);
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        followed = PreferenceConfiguration.getDefaultBitrate("1280x720", "60");
        prefs.edit().putString("list_resolution", "1280x720").putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", followed)
                .putBoolean("bitrate_follows_resolution", true).commit();
    }

    private static StreamSettings.SettingsFragment launch() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        SettingsTestUtil.expandAll(fragment);
        return fragment;
    }

    private StreamSettings activity;

    private StreamSettings.SettingsFragment launchAndFocus(String key) {
        activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        SettingsTestUtil.expandAll(fragment);
        SettingsTestUtil.focusRow(fragment, key);
        return fragment;
    }

    private boolean press(int keyCode) {
        return SettingsTestUtil.press(activity, keyCode);
    }

    @Test
    public void rightRaisesTheBitrateAndCommitsAfterThePause() {
        StreamSettings.SettingsFragment fragment = launchAndFocus("seekbar_bitrate_kbps");
        View row = SettingsTestUtil.shownRow(fragment, "seekbar_bitrate_kbps");
        assertNotNull(row.findViewById(R.id.vw_pref_seekbar));

        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT));
        TextView value = row.findViewById(R.id.vw_pref_value);
        assertEquals(SeekBarPreference.formatValue(followed + 1000, 1000, "Mbps"), value.getText().toString());
        // Not stored yet
        assertEquals(followed, prefs.getInt("seekbar_bitrate_kbps", -1));
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", false));

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekBarPreference.KEY_COMMIT_DELAY_MS + 50));
        assertEquals(followed + 1000, prefs.getInt("seekbar_bitrate_kbps", -1));
        assertFalse("A new value stops following the resolution", prefs.getBoolean("bitrate_follows_resolution", true));
    }

    @Test
    public void severalPressesAreOneChange() {
        launchAndFocus("seekbar_bitrate_kbps");
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("Still waiting for the pause", followed, prefs.getInt("seekbar_bitrate_kbps", -1));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(followed + 2000, prefs.getInt("seekbar_bitrate_kbps", -1));
    }

    @Test
    public void leavingTheRowCommitsAtOnce() {
        StreamSettings.SettingsFragment fragment = launchAndFocus("seekbar_bitrate_kbps");
        View row = SettingsTestUtil.shownRow(fragment, "seekbar_bitrate_kbps");
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        row.getOnFocusChangeListener().onFocusChange(row, false);
        assertEquals(followed - 1000, prefs.getInt("seekbar_bitrate_kbps", -1));
    }

    @Test
    public void aDragThatEndsWhereItStartedKeepsFollowing() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.bindRow(fragment, "seekbar_bitrate_kbps").itemView;
        SeekBar slider = row.findViewById(R.id.vw_pref_seekbar);
        SeekBar.OnSeekBarChangeListener listener = Shadows.shadowOf(slider).getOnSeekBarChangeListener();
        assertNotNull(listener);
        int start = slider.getProgress();
        listener.onStartTrackingTouch(slider);
        slider.setProgress(start + 4000);
        listener.onProgressChanged(slider, start + 4000, true);
        slider.setProgress(start);
        listener.onProgressChanged(slider, start, true);
        listener.onStopTrackingTouch(slider);
        SettingsTestUtil.idle();
        assertEquals(followed, prefs.getInt("seekbar_bitrate_kbps", -1));
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", false));
    }

    @Test
    public void aDragCommitsOnRelease() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.bindRow(fragment, "seekbar_bitrate_kbps").itemView;
        SeekBar slider = row.findViewById(R.id.vw_pref_seekbar);
        SeekBar.OnSeekBarChangeListener listener = Shadows.shadowOf(slider).getOnSeekBarChangeListener();
        listener.onStartTrackingTouch(slider);
        slider.setProgress(20000 - 500);
        listener.onProgressChanged(slider, 20000 - 500, true);
        assertEquals("Not stored while dragging", followed, prefs.getInt("seekbar_bitrate_kbps", -1));
        listener.onStopTrackingTouch(slider);
        assertEquals(20000, prefs.getInt("seekbar_bitrate_kbps", -1));
    }

    @Test
    public void aOnTheRowOpensTheDialog() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.bindRow(fragment, "seekbar_bitrate_kbps").itemView;
        row.performClick();
        SettingsTestUtil.idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
    }

    @Test
    public void theEndOfTheRangeConsumesTheKey() {
        prefs.edit().putInt("seekbar_keyboard_axi_opacity", 0).commit();
        launchAndFocus("seekbar_keyboard_axi_opacity");
        assertTrue("Consumed, so the focus stays on the row", press(KeyEvent.KEYCODE_DPAD_LEFT));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekBarPreference.KEY_COMMIT_DELAY_MS + 50));
        assertEquals(0, prefs.getInt("seekbar_keyboard_axi_opacity", -1));
    }

    @Test
    public void theSliderFollowsTheDependencysEnabledState() {
        prefs.edit().putBoolean("checkbox_show_onscreen_controls", false).commit();
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.bindRow(fragment, "seekbar_osc_opacity").itemView;
        assertFalse(row.findViewById(R.id.vw_pref_seekbar).isEnabled());
        assertFalse("Disabled rows ignore Left and Right", PrefRowBinder.onRowKey(row,
                fragment.findPreference("seekbar_osc_opacity"), KeyEvent.KEYCODE_DPAD_RIGHT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
    }

    @Test
    public void usingTheSlidersWritesNoNewKey() {
        StreamSettings.SettingsFragment fragment = launchAndFocus("seekbar_bitrate_kbps");
        Set<String> before = new HashSet<>(prefs.getAll().keySet());
        View row = SettingsTestUtil.shownRow(fragment, "seekbar_bitrate_kbps");
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        row.getOnFocusChangeListener().onFocusChange(row, false);
        Set<String> after = new HashSet<>(prefs.getAll().keySet());
        after.removeAll(before);
        assertTrue("New keys: " + after, after.isEmpty());
    }
}

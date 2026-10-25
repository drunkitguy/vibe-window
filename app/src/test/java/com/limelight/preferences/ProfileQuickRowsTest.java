package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.EditProfileActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The quick settings rows inside the profile editor change only the profile. */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfileQuickRowsTest {

    private SharedPreferences prefs;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        SettingsTestUtil.resetEnvironment();
        prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext());
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 20000).putBoolean("bitrate_follows_resolution", false)
                .putBoolean("checkbox_host_audio", false).commit();
        resetProfiles();
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    private static void resetProfiles() throws Exception {
        Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private EditProfileActivity openProfile(Map<String, Object> changes) {
        Context ctx = ApplicationProvider.getApplicationContext();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>(prefs.getAll());
        options.putAll(changes);
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);
        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        EditProfileActivity editor = Robolectric.buildActivity(EditProfileActivity.class, intent).setup().get();
        SettingsTestUtil.idle();
        return editor;
    }

    private static StreamSettings.SettingsFragment fragmentOf(EditProfileActivity editor) {
        return (StreamSettings.SettingsFragment)
                editor.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
    }

    @Test
    public void changedQuickRowsAreMarked() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("list_resolution", "1280x720");
        changes.put("checkbox_host_audio", true);
        StreamSettings.SettingsFragment fragment = fragmentOf(openProfile(changes));
        assertTrue(fragment.findPreference("list_resolution").getTitle().toString().startsWith("*"));
        assertTrue(fragment.findPreference("checkbox_host_audio").getTitle().toString().startsWith("*"));
        assertFalse(fragment.findPreference("list_fps").getTitle().toString().startsWith("*"));
        // The marked rows keep their inline controls
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        assertNotNull(row.findViewById(R.id.vw_pref_segments));
    }

    @Test
    public void theInlineSliderWritesOnlyTheProfile() {
        EditProfileActivity editor = openProfile(new HashMap<String, Object>());
        StreamSettings.SettingsFragment fragment = fragmentOf(editor);
        View row = SettingsTestUtil.bindRow(fragment, "seekbar_bitrate_kbps").itemView;
        SeekBar slider = row.findViewById(R.id.vw_pref_seekbar);
        SeekBar.OnSeekBarChangeListener listener = Shadows.shadowOf(slider).getOnSeekBarChangeListener();
        listener.onStartTrackingTouch(slider);
        slider.setProgress(30000 - 500);
        listener.onProgressChanged(slider, 30000 - 500, true);
        listener.onStopTrackingTouch(slider);
        SettingsTestUtil.idle();
        assertEquals(30000, editor.getInMemoryPrefs().getInt("seekbar_bitrate_kbps", -1));
        assertEquals(20000, prefs.getInt("seekbar_bitrate_kbps", -1));
    }

    @Test
    public void theUiStateNeverReachesAProfile() {
        EditProfileActivity editor = openProfile(new HashMap<String, Object>());
        StreamSettings.SettingsFragment fragment = fragmentOf(editor);
        fragment.setShowDescriptions(true);
        assertFalse(editor.getInMemoryPrefs().contains(UiState.SHOW_DESCRIPTIONS));
        assertFalse(prefs.contains(UiState.SHOW_DESCRIPTIONS));
        assertFalse(editor.getInMemoryPrefs().contains(StatsOverlayPreference.KEY));
    }
}

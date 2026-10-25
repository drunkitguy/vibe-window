package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import androidx.preference.CheckBoxPreference;
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
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class StatsOverlayCompositeTest {

    private SharedPreferences prefs;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        SettingsTestUtil.resetEnvironment();
        prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext());
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

    private static void tap(StreamSettings.SettingsFragment fragment, String value) {
        StatsOverlayPreference overlay = fragment.findPreference(StatsOverlayPreference.KEY);
        List<SegmentedListPreference.Segment> segments = overlay.segments();
        int index = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (value.equals(segments.get(i).value)) {
                index = i;
            }
        }
        assertTrue(index >= 0);
        View row = SettingsTestUtil.bindRow(fragment, StatsOverlayPreference.KEY).itemView;
        ((ViewGroup) row.findViewById(R.id.vw_pref_segments)).getChildAt(index).performClick();
        SettingsTestUtil.idle();
    }

    @Test
    public void valueIsReadFromTheTwoSwitches() {
        assertEquals("off", StatsOverlayPreference.valueFor(false, true));
        assertEquals("lite", StatsOverlayPreference.valueFor(true, true));
        assertEquals("full", StatsOverlayPreference.valueFor(true, false));

        prefs.edit().putBoolean("checkbox_enable_perf_overlay", true)
                .putBoolean("checkbox_enable_perf_overlay_lite", true).commit();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        StatsOverlayPreference overlay = fragment.findPreference(StatsOverlayPreference.KEY);
        assertEquals("lite", overlay.getValue());
        assertEquals(fragment.getString(R.string.stats_overlay_lite), overlay.getEntry().toString());
    }

    @Test
    public void segmentsWriteTheTwoSwitchesAndOffKeepsTheLiteChoice() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        StatsOverlayPreference overlay = fragment.findPreference(StatsOverlayPreference.KEY);
        assertEquals("off", overlay.getValue());

        tap(fragment, "lite");
        assertTrue(prefs.getBoolean("checkbox_enable_perf_overlay", false));
        assertTrue(prefs.getBoolean("checkbox_enable_perf_overlay_lite", false));

        tap(fragment, "off");
        assertFalse(prefs.getBoolean("checkbox_enable_perf_overlay", true));
        assertTrue("Off remembers Lite", prefs.getBoolean("checkbox_enable_perf_overlay_lite", false));
        assertEquals("off", overlay.getValue());

        tap(fragment, "full");
        assertTrue(prefs.getBoolean("checkbox_enable_perf_overlay", false));
        assertFalse(prefs.getBoolean("checkbox_enable_perf_overlay_lite", true));
        assertEquals("full", overlay.getValue());

        assertFalse("The composite row stores nothing itself", prefs.contains(StatsOverlayPreference.KEY));
    }

    @Test
    public void theLiteDetailsRowIsEnabledOnlyForLite() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        CheckBoxPreference liteDialog = fragment.findPreference("checkbox_enable_perf_overlay_lite_dialog");
        CheckBoxPreference bottom = fragment.findPreference("checkbox_enable_perf_overlay_bottom");
        assertNotNull(liteDialog);
        assertFalse(liteDialog.isEnabled());
        assertFalse(bottom.isEnabled());

        tap(fragment, "lite");
        assertTrue(liteDialog.isEnabled());
        assertTrue(bottom.isEnabled());

        tap(fragment, "full");
        assertFalse(liteDialog.isEnabled());
        assertTrue(bottom.isEnabled());

        tap(fragment, "lite");
        tap(fragment, "off");
        assertFalse(liteDialog.isEnabled());
        assertFalse(bottom.isEnabled());
    }

    @Test
    public void inAProfileOnlyTheProfileChangesAndTheRowIsMarked() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs.edit().putBoolean("checkbox_enable_perf_overlay", false).commit();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>();
        options.put("checkbox_enable_perf_overlay", true);
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);
        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        EditProfileActivity editor = Robolectric.buildActivity(EditProfileActivity.class, intent).setup().get();
        SettingsTestUtil.idle();
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                editor.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        StatsOverlayPreference overlay = fragment.findPreference(StatsOverlayPreference.KEY);
        assertEquals("full", overlay.getValue());
        assertTrue("Changed in the profile", overlay.getTitle().toString().startsWith("*"));

        tap(fragment, "lite");
        assertEquals(Boolean.TRUE, editor.getInMemoryPrefs().getAll().get("checkbox_enable_perf_overlay_lite"));
        assertFalse(prefs.getBoolean("checkbox_enable_perf_overlay", false));
        assertFalse(prefs.getBoolean("checkbox_enable_perf_overlay_lite", false));
        assertFalse(editor.getInMemoryPrefs().contains(StatsOverlayPreference.KEY));
    }
}

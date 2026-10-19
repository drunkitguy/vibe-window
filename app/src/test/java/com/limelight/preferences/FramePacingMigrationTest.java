package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class FramePacingMigrationTest {
    private static final String KEY = "frame_pacing";
    private static final String FLAG = "migrated_frame_pacing_newest_v130";

    private Context context;
    private SharedPreferences prefs;

    private static void resetProfiles() throws Exception {
        Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        resetProfiles();
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    @Test
    public void legacyDefaultBecomesNewest() {
        prefs.edit().putString(KEY, "latency").commit();
        assertTrue(PreferenceConfiguration.migrateFramePacingToNewest(context));
        assertEquals("newest", prefs.getString(KEY, null));
        assertTrue(prefs.getBoolean(FLAG, false));
        assertEquals(PreferenceConfiguration.FRAME_PACING_NEWEST,
                PreferenceConfiguration.readPreferences(context).framePacing);
    }

    @Test
    public void missingValueBecomesNewest() {
        assertTrue(PreferenceConfiguration.migrateFramePacingToNewest(context));
        assertEquals("newest", prefs.getString(KEY, null));
        assertTrue(prefs.getBoolean(FLAG, false));
    }

    @Test
    public void deliberateChoicesAreKept() {
        for (String choice : new String[] {"balanced", "smoothness", "cap-fps", "warp", "warp2"}) {
            prefs.edit().clear().putString(KEY, choice).commit();
            assertFalse(choice, PreferenceConfiguration.migrateFramePacingToNewest(context));
            assertEquals(choice, prefs.getString(KEY, null));
            assertTrue(choice, prefs.getBoolean(FLAG, false));
        }
    }

    @Test
    public void secondRunIsANoOp() {
        prefs.edit().putString(KEY, "latency").commit();
        assertTrue(PreferenceConfiguration.migrateFramePacingToNewest(context));

        // Picking the legacy mode after the migration is a deliberate choice and is kept
        prefs.edit().putString(KEY, "latency").commit();
        assertFalse(PreferenceConfiguration.migrateFramePacingToNewest(context));
        assertEquals("latency", prefs.getString(KEY, null));
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                PreferenceConfiguration.readPreferences(context).framePacing);
    }

    @Test
    public void profileOverlayIsUntouched() {
        Map<String, Object> options = new HashMap<>();
        options.put(KEY, "latency");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        ProfilesManager.getInstance().add(profile);
        ProfilesManager.getInstance().setActive(profile.getUuid());

        prefs.edit().putString(KEY, "latency").commit();
        assertTrue(PreferenceConfiguration.migrateFramePacingToNewest(context));

        // Base preferences moved to newest, the active profile keeps its explicit value
        assertEquals("newest", prefs.getString(KEY, null));
        assertEquals("latency", profile.getOptions().get(KEY));
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                PreferenceConfiguration.readPreferences(context).framePacing);
    }

    @Test
    public void freshInstallDefaultsToNewest() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertEquals("newest", prefs.getString(KEY, null));
        assertFalse(PreferenceConfiguration.migrateFramePacingToNewest(context));
        assertEquals("newest", prefs.getString(KEY, null));
        assertEquals(PreferenceConfiguration.FRAME_PACING_NEWEST,
                PreferenceConfiguration.readPreferences(context).framePacing);
    }

    @Test
    public void overlayPacingLineFormat() {
        assertEquals("Frame pacing: newest, dropped by pacing: 0.50%",
                context.getString(com.limelight.R.string.perf_overlay_pacing, "newest", 0.5f));
        assertEquals("Frame pacing: balanced",
                context.getString(com.limelight.R.string.perf_overlay_pacing_mode, "balanced"));
    }

    @Test
    public void newestIsTheFirstListEntry() {
        String[] values = context.getResources().getStringArray(com.limelight.R.array.video_frame_pacing_values);
        String[] names = context.getResources().getStringArray(com.limelight.R.array.video_frame_pacing_names);
        assertEquals(values.length, names.length);
        assertEquals("newest", values[0]);
        assertEquals("Lowest latency (newest frame)", names[0]);
    }
}

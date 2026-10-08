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

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AutoPreferencesTest {
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
    public void freshInstallDefaultsToAuto() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.autoResolution);
        assertTrue(config.autoFps);
        assertEquals(PreferenceConfiguration.AUTO_PLACEHOLDER_WIDTH, config.width);
        assertEquals(PreferenceConfiguration.AUTO_PLACEHOLDER_HEIGHT, config.height);
        assertEquals(PreferenceConfiguration.AUTO_PLACEHOLDER_FPS, config.fps, 0.001f);
        assertTrue(config.bitrateFollowsResolution);
        assertTrue(config.autoResPreferExternal);
    }

    @Test
    public void defaultValuesFromXmlAreAuto() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertEquals("auto", prefs.getString("list_resolution", null));
        assertEquals("auto", prefs.getString("list_fps", null));
        assertTrue(prefs.getBoolean("checkbox_auto_res_prefer_external", false));
    }

    @Test
    public void autoIsNeverRewrittenTo720p() {
        prefs.edit().putString("list_resolution", "auto").putString("list_fps", "auto").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.autoResolution);
        assertEquals(1920, config.width);
        assertEquals("auto", prefs.getString("list_resolution", null));
        assertEquals("auto", prefs.getString("list_fps", null));
    }

    @Test
    public void invalidLegacyResolutionStillMapsTo720p() {
        prefs.edit().putString("list_resolution", "invalid_resolution").putString("list_fps", "60").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.autoResolution);
        assertEquals(1280, config.width);
        assertEquals(720, config.height);
        assertEquals("1280x720", prefs.getString("list_resolution", null));
    }

    @Test
    public void explicitValuesAreReadAsBefore() {
        prefs.edit().putString("list_resolution", "2560x1440").putString("list_fps", "90").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.autoResolution);
        assertFalse(config.autoFps);
        assertEquals(2560, config.width);
        assertEquals(1440, config.height);
        assertEquals(90f, config.fps, 0.001f);
        assertEquals(PreferenceConfiguration.getDefaultBitrate("2560x1440", "90"), config.bitrate);
    }

    @Test
    public void defaultBitrateWithAutoDoesNotThrow() {
        prefs.edit().putString("list_resolution", "auto").putString("list_fps", "auto").commit();
        assertEquals(PreferenceConfiguration.getDefaultBitrate(1920, 1080, 60),
                PreferenceConfiguration.getDefaultBitrate(context));
        assertEquals(PreferenceConfiguration.getDefaultBitrate(1920, 1080, 120),
                PreferenceConfiguration.getDefaultBitrate("auto", "120"));
        assertEquals(PreferenceConfiguration.getDefaultBitrate(2560, 1440, 60),
                PreferenceConfiguration.getDefaultBitrate("2560x1440", "auto"));
    }

    @Test
    public void defaultBitrateOfTheOldDefaultsIs28Mbps() {
        assertEquals(28000, PreferenceConfiguration.getDefaultBitrate(1920, 1080, 120));
        assertEquals(28000, PreferenceConfiguration.getDefaultBitrate("1920x1080", "120"));
    }

    @Test
    public void migrationConvertsTheOldDefaultsOnce() {
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "120").commit();
        assertTrue(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
        assertEquals("auto", prefs.getString("list_resolution", null));
        assertEquals("auto", prefs.getString("list_fps", null));
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", false));

        // Once only: a later explicit 1080p/120 choice is kept
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "120").commit();
        assertFalse(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
        assertEquals("1920x1080", prefs.getString("list_resolution", null));
    }

    @Test
    public void migrationAcceptsTheStoredDefaultBitrate() {
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "120")
                .putInt("seekbar_bitrate_kbps", 28000).commit();
        assertTrue(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
        assertEquals("auto", prefs.getString("list_resolution", null));
    }

    @Test
    public void migrationKeepsACustomBitrate() {
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "120")
                .putInt("seekbar_bitrate_kbps", 50000).commit();
        assertFalse(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
        assertEquals("1920x1080", prefs.getString("list_resolution", null));
        assertEquals("120", prefs.getString("list_fps", null));
        assertEquals(50000, prefs.getInt("seekbar_bitrate_kbps", 0));
        assertTrue(prefs.getBoolean("migrated_auto_display_v120", false));
    }

    @Test
    public void migrationKeepsOtherChoices() {
        String[][] choices = {{"2560x1440", "120"}, {"1920x1080", "60"}, {"1280x720", "120"}, {"3840x2160", "60"}};
        for (String[] choice : choices) {
            prefs.edit().clear().putString("list_resolution", choice[0]).putString("list_fps", choice[1]).commit();
            assertFalse(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
            assertEquals(choice[0], prefs.getString("list_resolution", null));
            assertEquals(choice[1], prefs.getString("list_fps", null));
        }
    }

    @Test
    public void migrationNeverTouchesProfileOverlays() {
        Map<String, Object> options = new HashMap<>();
        options.put("list_resolution", "1920x1080");
        options.put("list_fps", "120");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        ProfilesManager.getInstance().add(profile);
        ProfilesManager.getInstance().setActive(profile.getUuid());

        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "120").commit();
        assertTrue(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));

        // Base preferences moved to auto, the active profile keeps its explicit values
        assertEquals("auto", prefs.getString("list_resolution", null));
        assertEquals("1920x1080", profile.getOptions().get("list_resolution"));
        assertEquals("120", profile.getOptions().get("list_fps"));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.autoResolution);
        assertFalse(config.autoFps);
        assertEquals(1920, config.width);
        assertEquals(120f, config.fps, 0.001f);
    }

    @Test
    public void freshInstallIsNotChangedByMigration() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertFalse(PreferenceConfiguration.migrateToAutoDisplayDefaults(context));
        assertEquals("auto", prefs.getString("list_resolution", null));
    }

    @Test
    public void resetStreamingSettingsLandsOnAuto() {
        prefs.edit().putString("list_resolution", "1280x720").putString("list_fps", "30")
                .putInt("seekbar_bitrate_kbps", 5000)
                .putBoolean("bitrate_follows_resolution", false).commit();
        PreferenceConfiguration.resetStreamingSettings(context);
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.autoResolution);
        assertTrue(config.autoFps);
        assertTrue(config.bitrateFollowsResolution);
    }

    @Test
    public void meteredBitrateDerivedFlag() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.meteredBitrateDerived);
        prefs.edit().putInt("seekbar_metered_bitrate_kbps", 4000).commit();
        config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.meteredBitrateDerived);
        assertEquals(4000, config.meteredBitrate);
    }
}

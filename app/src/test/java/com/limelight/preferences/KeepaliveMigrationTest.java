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
public class KeepaliveMigrationTest {
    private static final String KEY = "checkbox_prevent_packet_loss";
    private static final String FLAG = "migrated_prevent_packet_loss_v130";

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
    public void storedOffBecomesOnOnce() {
        prefs.edit().putBoolean(KEY, false).commit();
        assertTrue(PreferenceConfiguration.migratePreventPacketLossToOn(context));
        assertTrue(prefs.getBoolean(KEY, false));
        assertTrue(prefs.getBoolean(FLAG, false));
        assertTrue(PreferenceConfiguration.readPreferences(context).preventPacketLoss);
    }

    @Test
    public void explicitOffAfterTheMigrationIsKept() {
        prefs.edit().putBoolean(KEY, false).commit();
        assertTrue(PreferenceConfiguration.migratePreventPacketLossToOn(context));

        prefs.edit().putBoolean(KEY, false).commit();
        assertFalse(PreferenceConfiguration.migratePreventPacketLossToOn(context));
        assertFalse(prefs.getBoolean(KEY, true));
        assertFalse(PreferenceConfiguration.readPreferences(context).preventPacketLoss);
    }

    @Test
    public void storedOnOrMissingIsLeftAlone() {
        prefs.edit().putBoolean(KEY, true).commit();
        assertFalse(PreferenceConfiguration.migratePreventPacketLossToOn(context));
        assertTrue(prefs.getBoolean(KEY, false));
        assertTrue(prefs.getBoolean(FLAG, false));

        prefs.edit().clear().commit();
        assertFalse(PreferenceConfiguration.migratePreventPacketLossToOn(context));
        assertFalse(prefs.contains(KEY));
        assertTrue(prefs.getBoolean(FLAG, false));
        // Nothing stored means the new default, which is on
        assertTrue(PreferenceConfiguration.readPreferences(context).preventPacketLoss);
    }

    @Test
    public void profileOverlayIsUntouched() {
        Map<String, Object> options = new HashMap<>();
        options.put(KEY, false);
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        ProfilesManager.getInstance().add(profile);
        ProfilesManager.getInstance().setActive(profile.getUuid());

        prefs.edit().putBoolean(KEY, false).commit();
        assertTrue(PreferenceConfiguration.migratePreventPacketLossToOn(context));

        // Base preferences turned on, the active profile keeps its explicit off
        assertTrue(prefs.getBoolean(KEY, false));
        assertEquals(false, profile.getOptions().get(KEY));
        assertFalse(PreferenceConfiguration.readPreferences(context).preventPacketLoss);
    }

    @Test
    public void oneTimeMigrationsRunEveryMigrationOnce() {
        prefs.edit().putBoolean(KEY, false).putString("frame_pacing", "latency").commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(KEY, false));
        assertEquals("newest", prefs.getString("frame_pacing", null));
        assertTrue(prefs.getBoolean("migrated_auto_display_v120", false));

        // A second entry point (a shortcut launch) changes nothing
        prefs.edit().putBoolean(KEY, false).putString("frame_pacing", "latency").commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertFalse(prefs.getBoolean(KEY, true));
        assertEquals("latency", prefs.getString("frame_pacing", null));
    }

    @Test
    public void freshInstallDefaultsToOn() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertTrue(prefs.getBoolean(KEY, false));
        assertFalse(PreferenceConfiguration.migratePreventPacketLossToOn(context));
        assertTrue(PreferenceConfiguration.readPreferences(context).preventPacketLoss);
    }
}

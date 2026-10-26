package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 1.4.3: device motion is on by default, set once for existing installs and profiles. */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class MotionMigrationTest {
    private static final String SENSORS = "checkbox_gamepad_motion_sensors";
    private static final String FALLBACK = "checkbox_gamepad_motion_fallback";
    private static final String FORCE = "checkbox_force_device_motion";
    private static final String FLAG = "motion_default_on_v143";

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
        setDeviceSensors(true);
    }

    private void setDeviceSensors(boolean present) {
        PackageManager pm = context.getPackageManager();
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, present);
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE, present);
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    @Test
    public void cleanInstallStreamsWithMotionOn() {
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(FLAG, false));
        assertEquals(Boolean.TRUE, prefs.getAll().get(FALLBACK));
        assertEquals(Boolean.TRUE, prefs.getAll().get(SENSORS));
        assertFalse("The force option is never touched", prefs.contains(FORCE));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.gamepadMotionSensorsFallbackToDevice);
        assertTrue(config.gamepadMotionSensors);
    }

    @Test
    public void nothingStoredReadsAsMotionOn() {
        // Even before the migration (or with its flag set), the new default is on
        prefs.edit().putBoolean(FLAG, true).commit();
        assertTrue(PreferenceConfiguration.readPreferences(context).gamepadMotionSensorsFallbackToDevice);
    }

    @Test
    public void anUpgradeWithMotionTurnedOffGetsItOnOnce() {
        prefs.edit().putBoolean(FALLBACK, false).putBoolean(SENSORS, false).putBoolean(FORCE, true).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(FALLBACK, false));
        assertTrue(prefs.getBoolean(SENSORS, false));
        assertTrue("The force option keeps its value", prefs.getBoolean(FORCE, false));
    }

    @Test
    public void theNextLaunchKeepsAUserOff() {
        PreferenceConfiguration.runOneTimeMigrations(context);
        prefs.edit().putBoolean(FALLBACK, false).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertFalse(prefs.getBoolean(FALLBACK, true));
        assertFalse(PreferenceConfiguration.readPreferences(context).gamepadMotionSensorsFallbackToDevice);
    }

    @Test
    public void profilesAreTurnedOnOnceAndSaved() throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put(FALLBACK, false);
        options.put(SENSORS, false);
        options.put("list_resolution", "1280x720");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Handheld", 0, 0, options);
        ProfilesManager.getInstance().add(profile);

        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals(true, profile.getOptions().get(FALLBACK));
        assertEquals(true, profile.getOptions().get(SENSORS));
        assertEquals("1280x720", profile.getOptions().get("list_resolution"));

        resetProfiles();
        assertTrue(ProfilesManager.getInstance().load(context));
        SettingsProfile reloaded = ProfilesManager.getInstance().getProfiles().get(0);
        assertEquals(true, reloaded.getOptions().get(FALLBACK));

        // Off in the profile afterwards sticks
        reloaded.getOptions().put(FALLBACK, false);
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals(false, reloaded.getOptions().get(FALLBACK));
    }

    @Test
    public void android12KeepsControllerSensorsOff() {
        prefs.edit().putBoolean(SENSORS, false).putBoolean(FALLBACK, false).commit();
        assertTrue(PreferenceConfiguration.migrateMotionDefaultOn(prefs, ProfilesManager.getInstance(), context,
                Build.VERSION_CODES.S, true));
        assertTrue(prefs.getBoolean(FALLBACK, false));
        assertFalse(prefs.getBoolean(SENSORS, true));
    }

    @Test
    public void aDeviceWithoutSensorsKeepsTheFallbackOff() {
        setDeviceSensors(false);
        // A clean install: the PC list writes the XML defaults first, then migrates
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertTrue(prefs.getBoolean(FALLBACK, false));
        Map<String, Object> options = new HashMap<>();
        options.put(FALLBACK, true);
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        ProfilesManager.getInstance().add(profile);

        PreferenceConfiguration.runOneTimeMigrations(context);
        assertFalse(prefs.getBoolean(FALLBACK, true));
        assertFalse(PreferenceConfiguration.readPreferences(context).gamepadMotionSensorsFallbackToDevice);
        assertEquals("Profiles are left alone", true, profile.getOptions().get(FALLBACK));
    }

    @Test
    public void aDeviceWithoutSensorsThatRan143IsTurnedOffToo() {
        setDeviceSensors(false);
        // 1.4.3 already migrated this install and turned the fallback on
        prefs.edit().putBoolean(FLAG, true).putBoolean(FALLBACK, true).putBoolean(SENSORS, true).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertFalse(prefs.getBoolean(FALLBACK, true));
        assertTrue("Controller sensors are left as they are", prefs.getBoolean(SENSORS, false));
    }

    @Test
    public void aDeviceWithOnlyAnAccelerometerCountsAsHavingSensors() {
        PackageManager pm = context.getPackageManager();
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE, false);
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, true);
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(FALLBACK, false));
    }
}

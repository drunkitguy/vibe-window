package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class MotionRowTest {
    private static final String SENSORS = "checkbox_gamepad_motion_sensors";
    private static final String FALLBACK = "checkbox_gamepad_motion_fallback";
    private static final String FORCE = "checkbox_force_device_motion";

    private SharedPreferences prefs;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        SettingsTestUtil.resetEnvironment();
        PackageManager pm = ApplicationProvider.getApplicationContext().getPackageManager();
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE, true);
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, true);
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
        MotionPreference motion = fragment.findPreference(MotionPreference.KEY);
        List<SegmentedListPreference.Segment> segments = motion.segments();
        int index = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (value.equals(segments.get(i).value)) {
                index = i;
            }
        }
        assertTrue(index >= 0);
        View row = SettingsTestUtil.bindRow(fragment, MotionPreference.KEY).itemView;
        ((ViewGroup) row.findViewById(R.id.vw_pref_segments)).getChildAt(index).performClick();
        SettingsTestUtil.idle();
    }

    @Test
    public void valueIsOnOnlyWithBothSwitches() {
        assertEquals("on", MotionPreference.valueFor(true, true, 33));
        assertEquals("off", MotionPreference.valueFor(false, true, 33));
        assertEquals("off", MotionPreference.valueFor(true, false, 33));
        // Android 12 keeps controller sensors off, so the fallback decides there
        assertEquals("on", MotionPreference.valueFor(false, true, Build.VERSION_CODES.S));
    }

    @Test
    public void theRowSitsInTouchAndControlsAndExplainsItself() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        MotionPreference motion = fragment.findPreference(MotionPreference.KEY);
        assertNotNull(motion);
        assertEquals(fragment.findPreference("category_quick_controls"), motion.getParent());
        assertEquals(fragment.getString(R.string.title_quick_motion), motion.getTitle().toString());
        assertTrue(PrefRowBinder.hasInfo(motion));
        assertEquals("A clean install shows Motion on", "on", motion.getValue());
    }

    @Test
    public void onSetsBothAndOffOnlyTheFallback() {
        prefs.edit().putBoolean(SENSORS, true).putBoolean(FALLBACK, false).putBoolean(FORCE, false).commit();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        MotionPreference motion = fragment.findPreference(MotionPreference.KEY);
        assertEquals("off", motion.getValue());

        tap(fragment, "on");
        assertTrue(prefs.getBoolean(SENSORS, false));
        assertTrue(prefs.getBoolean(FALLBACK, false));
        assertEquals("on", motion.getValue());

        tap(fragment, "off");
        assertFalse(prefs.getBoolean(FALLBACK, true));
        assertTrue("A controller's own sensors keep working", prefs.getBoolean(SENSORS, false));
        assertFalse("The force option is never touched", prefs.getBoolean(FORCE, true));
        assertEquals("off", motion.getValue());
        assertFalse("The row stores nothing itself", prefs.contains(MotionPreference.KEY));
    }

    @Test
    public void onTurnsControllerSensorsOnToo() {
        prefs.edit().putBoolean(SENSORS, false).putBoolean(FALLBACK, true).commit();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        assertEquals("off", ((MotionPreference) fragment.findPreference(MotionPreference.KEY)).getValue());
        tap(fragment, "on");
        assertTrue(prefs.getBoolean(SENSORS, false));
        assertTrue(((CheckBoxPreference) fragment.findPreference(SENSORS)).isChecked());
    }

    @Test
    public void noDeviceSensorsNoMotionRow() {
        PackageManager pm = ApplicationProvider.getApplicationContext().getPackageManager();
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE, false);
        Shadows.shadowOf(pm).setSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, false);
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        assertNull(fragment.findPreference(MotionPreference.KEY));
    }

    @Test
    public void inAProfileOnlyTheProfileChangesAndTheRowIsMarked() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs.edit().putBoolean(SENSORS, true).putBoolean(FALLBACK, true).commit();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>(prefs.getAll());
        options.put(FALLBACK, false);
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);
        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        EditProfileActivity editor = Robolectric.buildActivity(EditProfileActivity.class, intent).setup().get();
        SettingsTestUtil.idle();
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                editor.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        MotionPreference motion = fragment.findPreference(MotionPreference.KEY);
        assertEquals("off", motion.getValue());
        assertTrue("Changed in the profile", motion.getTitle().toString().startsWith("*"));

        tap(fragment, "on");
        assertEquals(Boolean.TRUE, editor.getInMemoryPrefs().getAll().get(FALLBACK));
        assertTrue("The base settings are unchanged", prefs.getBoolean(FALLBACK, false));
        assertFalse(editor.getInMemoryPrefs().contains(MotionPreference.KEY));
    }
}

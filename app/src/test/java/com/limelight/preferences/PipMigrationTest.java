package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 1.4.9: the mini player is on by default, set once for existing installs and profiles. */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PipMigrationTest {
    private static final String PIP = "checkbox_enable_pip";
    private static final String FLAG = "pip_default_on_v149";
    private static final String RETURN = "checkbox_return_to_stream";

    private Context context;
    private SharedPreferences prefs;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static void resetProfiles() throws Exception {
        Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private void setPipFeature(boolean present) {
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature("android.software.picture_in_picture", present);
    }

    @Before
    public void setUp() throws Exception {
        resetProfiles();
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        setPipFeature(true);
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    @Test
    public void cleanInstallHasTheMiniPlayerOn() {
        // The PC list writes the XML defaults first, then migrates
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        assertTrue(prefs.getBoolean(PIP, false));
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(FLAG, false));
        assertTrue(prefs.getBoolean(PIP, false));
        assertTrue(PreferenceConfiguration.readPreferences(context).enablePip);
        assertTrue(PreferenceConfiguration.readPreferences(context).returnToStream);
    }

    @Test
    public void anUpgradeWithTheMiniPlayerOffGetsItOnOnce() {
        prefs.edit().putBoolean(PIP, false).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue(prefs.getBoolean(PIP, false));
        assertTrue(PreferenceConfiguration.readPreferences(context).enablePip);
    }

    @Test
    public void theNextLaunchKeepsAUserOff() {
        PreferenceConfiguration.runOneTimeMigrations(context);
        prefs.edit().putBoolean(PIP, false).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertFalse(prefs.getBoolean(PIP, true));
        assertFalse(PreferenceConfiguration.readPreferences(context).enablePip);
    }

    @Test
    public void profilesAreTurnedOnOnceAndSaved() throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put(PIP, false);
        options.put("list_resolution", "1280x720");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Handheld", 0, 0, options);
        ProfilesManager.getInstance().add(profile);

        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals(true, profile.getOptions().get(PIP));
        assertEquals("1280x720", profile.getOptions().get("list_resolution"));
        assertTrue(prefs.getBoolean(FLAG, false));

        resetProfiles();
        assertTrue(ProfilesManager.getInstance().load(context));
        SettingsProfile reloaded = ProfilesManager.getInstance().getProfiles().get(0);
        assertEquals(true, reloaded.getOptions().get(PIP));

        // Off in the profile afterwards sticks
        reloaded.getOptions().put(PIP, false);
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals(false, reloaded.getOptions().get(PIP));
    }

    @Test
    public void aDeviceWithoutPictureInPictureNeverUsesIt() {
        setPipFeature(false);
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertTrue("The setting is stored as on", prefs.getBoolean(PIP, false));
        assertFalse("but the stream never asks for picture-in-picture",
                PreferenceConfiguration.readPreferences(context).enablePip);
    }

    @Test
    public void fireOsNeverUsesIt() {
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature("com.amazon.software.fireos", true);
        assertFalse(PreferenceConfiguration.readPreferences(context).enablePip);
    }

    @Test
    public void bothRowsSitInTheQuickAppGroup() {
        SettingsTestUtil.resetEnvironment();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        CheckBoxPreference pip = fragment.findPreference(PIP);
        CheckBoxPreference ret = fragment.findPreference(RETURN);
        assertNotNull(pip);
        assertNotNull(ret);
        assertEquals(fragment.findPreference("category_quick_app"), pip.getParent());
        assertEquals(fragment.findPreference("category_quick_app"), ret.getParent());
        assertEquals("Mini player", pip.getTitle().toString());
        assertEquals("Return to stream", ret.getTitle().toString());
        assertTrue(pip.isChecked());
        assertTrue(ret.isChecked());
        assertFalse("No icon space, like every other row", pip.isIconSpaceReserved());
        assertFalse(ret.isIconSpaceReserved());
        String info = fragment.getString(R.string.summary_checkbox_enable_pip);
        assertTrue(info.contains("small window"));
        assertTrue(info.contains("session keeps running on the PC"));
    }
}

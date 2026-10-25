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

/** The 1.3.4 return to the 1.2.2 streaming defaults and its one-time migration. */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class Restore122DefaultsTest {
    private static final String PACING = "frame_pacing";
    private static final String VENDOR = "checkbox_vendor_decoder_keys";
    private static final String KEEPALIVE = "checkbox_prevent_packet_loss";
    private static final String FLAG = "restored_122_defaults_v134";
    private static final String RAN_13_PACING = "migrated_frame_pacing_newest_v130";
    private static final String RAN_13_KEEPALIVE = "migrated_prevent_packet_loss_v130";

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

    private void as13xInstall() {
        prefs.edit().putBoolean(RAN_13_PACING, true).putBoolean(RAN_13_KEEPALIVE, true).commit();
    }

    @Test
    public void freshInstallUses122Defaults() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertEquals("balanced", prefs.getString(PACING, null));
        assertFalse(prefs.getBoolean(VENDOR, true));
        assertFalse(prefs.getBoolean(KEEPALIVE, true));

        assertFalse(PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, config.framePacing);
        assertFalse(config.vendorDecoderKeys);
        assertFalse(config.preventPacketLoss);
    }

    @Test
    public void nothingStoredReadsAs122Defaults() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, config.framePacing);
        assertFalse(config.vendorDecoderKeys);
        assertFalse(config.preventPacketLoss);
    }

    @Test
    public void the13xAutoMigratedValuesGoBack() {
        as13xInstall();
        prefs.edit().putString(PACING, "newest").putBoolean(VENDOR, true).putBoolean(KEEPALIVE, true).commit();
        assertTrue(PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
        assertEquals("balanced", prefs.getString(PACING, null));
        assertFalse(prefs.getBoolean(VENDOR, true));
        assertFalse(prefs.getBoolean(KEEPALIVE, true));
        assertTrue(prefs.getBoolean(FLAG, false));
    }

    @Test
    public void legacyLatencyPickedAfter130IsKept() {
        as13xInstall();
        prefs.edit().putString(PACING, "latency").commit();
        assertFalse(PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
        assertEquals("latency", prefs.getString(PACING, null));
    }

    @Test
    public void the122StoredPacingValuesHadNoEffectAndBecomeBalanced() {
        for (String stored : new String[] {"latency", "smoothness", "cap-fps"}) {
            prefs.edit().clear().putString(PACING, stored).commit();
            assertTrue(stored, PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
            assertEquals(stored, "balanced", prefs.getString(PACING, null));
        }
    }

    @Test
    public void balancedAndWarpAreKept() {
        for (boolean ran13 : new boolean[] {false, true}) {
            for (String stored : new String[] {"balanced", "warp", "warp2"}) {
                prefs.edit().clear().putString(PACING, stored).commit();
                if (ran13) {
                    as13xInstall();
                }
                assertFalse(stored, PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
                assertEquals(stored, prefs.getString(PACING, null));
            }
        }
    }

    @Test
    public void warpRunsAsBalancedWithItsFactorAsIn122() {
        prefs.edit().putString(PACING, "warp2").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, config.framePacing);
        assertEquals(4, config.framePacingWarpFactor);
    }

    @Test
    public void keepaliveTurnedOnIn122IsKept() {
        // Never ran 1.3.x: an "on" here was the user's own 1.2.2 choice
        prefs.edit().putBoolean(KEEPALIVE, true).commit();
        assertFalse(PreferenceConfiguration.restoreStreamingDefaultsOf122(context));
        assertTrue(prefs.getBoolean(KEEPALIVE, false));
    }

    @Test
    public void runsOnceAndLaterChoicesAreKept() {
        as13xInstall();
        prefs.edit().putString(PACING, "newest").commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals("balanced", prefs.getString(PACING, null));

        // Opting in afterwards sticks, also through a second entry point (shortcut launch)
        prefs.edit().putString(PACING, "newest").putBoolean(VENDOR, true).putBoolean(KEEPALIVE, true).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals("newest", prefs.getString(PACING, null));
        assertTrue(prefs.getBoolean(VENDOR, false));
        assertTrue(prefs.getBoolean(KEEPALIVE, false));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_NEWEST, config.framePacing);
        assertTrue(config.vendorDecoderKeys);
        assertTrue(config.preventPacketLoss);
    }

    @Test
    public void the130MigrationsNoLongerRun() {
        // A 1.2.2 install upgrading straight to 1.3.4 must not be moved to newest or keepalive on
        prefs.edit().putString(PACING, "latency").putBoolean(KEEPALIVE, false).commit();
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals("balanced", prefs.getString(PACING, null));
        assertFalse(prefs.getBoolean(KEEPALIVE, true));
        assertFalse(prefs.contains(RAN_13_PACING));
        assertFalse(prefs.contains(RAN_13_KEEPALIVE));
    }

    private static final String PROFILES_FLAG = "restored_122_profiles_v135";

    private SettingsProfile addProfile(String name, Map<String, Object> options) {
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), name, 0, 0, options);
        ProfilesManager.getInstance().add(profile);
        return profile;
    }

    /** What EditProfileActivity saved with 1.3.3: a full copy of the base preferences. */
    private static Map<String, Object> profileFrom133() {
        Map<String, Object> options = new HashMap<>();
        options.put("list_resolution", "auto");
        options.put("list_fps", "auto");
        options.put("seekbar_bitrate_kbps", 50000);
        options.put(PACING, "newest");
        options.put(VENDOR, true);
        options.put(KEEPALIVE, true);
        return options;
    }

    @Test
    public void activeProfileOverlayIsRestoredToo() {
        SettingsProfile profile = addProfile("Profile", profileFrom133());
        ProfilesManager.getInstance().setActive(profile.getUuid());
        as13xInstall();
        prefs.edit().putString(PACING, "newest").putBoolean(KEEPALIVE, true).commit();

        PreferenceConfiguration.runOneTimeMigrations(context);

        assertEquals("balanced", prefs.getString(PACING, null));
        assertEquals("balanced", profile.getOptions().get(PACING));
        assertFalse(profile.getOptions().containsKey(VENDOR));
        assertEquals(false, profile.getOptions().get(KEEPALIVE));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, config.framePacing);
        assertFalse(config.vendorDecoderKeys);
        assertFalse(config.preventPacketLoss);
    }

    @Test
    public void profileCreatedWith133IsRestoredAndSaved() throws Exception {
        SettingsProfile profile = addProfile("Docked", profileFrom133());
        // A 1.3.4 install: the base preferences were already restored, profiles were not
        prefs.edit().putBoolean(FLAG, true).commit();

        assertTrue(PreferenceConfiguration.restoreProfilesOf122(context));
        assertTrue(prefs.getBoolean(PROFILES_FLAG, false));

        // Only the 1.3 settings changed
        Map<String, Object> options = profile.getOptions();
        assertEquals("balanced", options.get(PACING));
        assertFalse(options.containsKey(VENDOR));
        assertEquals(false, options.get(KEEPALIVE));
        assertEquals("auto", options.get("list_resolution"));
        assertEquals(50000, options.get("seekbar_bitrate_kbps"));

        // And the change was written to the profiles file
        resetProfiles();
        assertTrue(ProfilesManager.getInstance().load(context));
        SettingsProfile reloaded = ProfilesManager.getInstance().getProfiles().get(0);
        assertEquals("balanced", reloaded.getOptions().get(PACING));
        assertFalse(reloaded.getOptions().containsKey(VENDOR));
        assertEquals(false, reloaded.getOptions().get(KEEPALIVE));
    }

    @Test
    public void profilePacingValuesFollowThe122Rule() {
        String[][] cases = {
                {"newest", "balanced"}, {"latency", "balanced"}, {"smoothness", "balanced"},
                {"cap-fps", "balanced"}, {"balanced", "balanced"}, {"warp", "warp"}, {"warp2", "warp2"},
        };
        for (String[] c : cases) {
            Map<String, Object> options = new HashMap<>();
            options.put(PACING, c[0]);
            PreferenceConfiguration.restoreProfileOptionsOf122(options);
            assertEquals(c[0], c[1], options.get(PACING));
        }

        // A profile without these settings is left alone
        Map<String, Object> empty = new HashMap<>();
        empty.put("list_fps", "60");
        assertFalse(PreferenceConfiguration.restoreProfileOptionsOf122(empty));
        assertEquals(1, empty.size());
        assertFalse(empty.containsKey(KEEPALIVE));
    }

    @Test
    public void profileRestoreRunsOnce() {
        SettingsProfile profile = addProfile("Profile", profileFrom133());
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals("balanced", profile.getOptions().get(PACING));

        // Opting in again in the profile sticks
        profile.getOptions().put(PACING, "newest");
        profile.getOptions().put(KEEPALIVE, true);
        PreferenceConfiguration.runOneTimeMigrations(context);
        assertEquals("newest", profile.getOptions().get(PACING));
        assertEquals(true, profile.getOptions().get(KEEPALIVE));
    }

    @Test
    public void newestStaysSelectableAndLabelledExperimental() {
        String[] values = context.getResources().getStringArray(com.limelight.R.array.video_frame_pacing_values);
        String[] names = context.getResources().getStringArray(com.limelight.R.array.video_frame_pacing_names);
        assertEquals(values.length, names.length);
        int newest = java.util.Arrays.asList(values).indexOf("newest");
        assertTrue(newest >= 0);
        assertEquals("Lowest latency (newest frame, experimental)", names[newest]);
    }

    @Test
    public void overlayPacingLineFormat() {
        assertEquals("Frame pacing: newest, dropped by pacing: 0.50%",
                context.getString(com.limelight.R.string.perf_overlay_pacing, "newest", 0.5f));
        assertEquals("Frame pacing: balanced",
                context.getString(com.limelight.R.string.perf_overlay_pacing_mode, "balanced"));
    }
}

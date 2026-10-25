package com.limelight.preferences;

import android.app.GameManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import androidx.fragment.app.Fragment;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The settings fragments are recreated by the FragmentManager after process death, which
 * needs a public no-argument constructor.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProcessDeathTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        ctx.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();
        Shadows.shadowOf((android.app.Application) ctx)
                .setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
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

    private static void idle() {
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    @Test
    public void settingsFragmentHasANoArgumentConstructor() throws Exception {
        assertNotNull(StreamSettings.SettingsFragment.class.getConstructor().newInstance());
        assertNotNull(EditProfileActivity.ProfilePreferenceFragment.class.getConstructor().newInstance());
    }

    @Test
    public void streamSettingsSurvivesRecreationFromSavedState() {
        ActivityController<StreamSettings> controller = Robolectric.buildActivity(StreamSettings.class).setup();
        idle();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        controller.pause().stop().destroy();

        ActivityController<StreamSettings> restored = Robolectric.buildActivity(StreamSettings.class).setup(saved);
        idle();
        Fragment fragment = restored.get().getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertTrue(fragment instanceof StreamSettings.SettingsFragment);
        assertNotNull(((StreamSettings.SettingsFragment) fragment).findPreference("list_resolution"));
    }

    @Test
    public void profileEditorSurvivesRecreationAndKeepsTheBasePrefs() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>();
        options.put("list_resolution", "1280x720");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);

        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        ActivityController<EditProfileActivity> controller =
                Robolectric.buildActivity(EditProfileActivity.class, intent).setup();
        idle();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        controller.pause().stop().destroy();

        ActivityController<EditProfileActivity> restored =
                Robolectric.buildActivity(EditProfileActivity.class, intent).setup(saved);
        idle();
        Fragment fragment = restored.get().getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        assertTrue(fragment instanceof EditProfileActivity.ProfilePreferenceFragment);
        assertNotNull(((EditProfileActivity.ProfilePreferenceFragment) fragment).findPreference("list_resolution"));
        assertEquals("1280x720", restored.get().getInMemoryPrefs().getString("list_resolution", null));
        // The base settings were not touched by the restored editor
        assertTrue(!"1280x720".equals(
                PreferenceManager.getDefaultSharedPreferences(ctx).getString("list_resolution", null)));
    }
}

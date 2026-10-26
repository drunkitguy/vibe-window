package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import androidx.preference.ListPreference;
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
import java.util.Arrays;
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
public class BackButtonRowTest {
    private static final String KEY = PreferenceConfiguration.LIST_BACK_BUTTON_MODE;

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

    private static void tap(StreamSettings.SettingsFragment fragment, String key, String value) {
        SegmentedListPreference row = fragment.findPreference(key);
        List<SegmentedListPreference.Segment> segments = row.segments();
        int index = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (value.equals(segments.get(i).value)) {
                index = i;
            }
        }
        assertTrue(index >= 0);
        View view = SettingsTestUtil.bindRow(fragment, key).itemView;
        ((ViewGroup) view.findViewById(R.id.vw_pref_segments)).getChildAt(index).performClick();
        SettingsTestUtil.idle();
    }

    private static List<String> values(SegmentedListPreference row) {
        String[] result = new String[row.segments().size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = row.segments().get(i).value;
        }
        return Arrays.asList(result);
    }

    @Test
    public void theSettingSitsInGamepadSettingsWithAndroidBackAsDefault() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        ListPreference setting = fragment.findPreference(KEY);
        assertNotNull(setting);
        assertTrue(setting instanceof SegmentedListPreference);
        assertEquals(fragment.findPreference("category_gamepad_settings"), setting.getParent());
        assertEquals("android", setting.getValue());
        assertEquals("android", prefs.getString(KEY, null));
        assertEquals(Arrays.asList("android", "select", "touchpad", "guide", "key"),
                values((SegmentedListPreference) setting));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_ANDROID,
                PreferenceConfiguration.readPreferences(fragment.requireContext()).backButtonMode);
    }

    @Test
    public void theQuickRowSitsInTouchAndControlsAndExplainsItself() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        BackButtonPreference row = fragment.findPreference(BackButtonPreference.KEY);
        assertNotNull(row);
        assertEquals(fragment.findPreference("category_quick_controls"), row.getParent());
        assertEquals(fragment.getString(R.string.title_back_button_mode), row.getTitle().toString());
        assertTrue(PrefRowBinder.hasInfo(row));
        assertEquals("android", row.getValue());
        assertEquals(Arrays.asList("android", "select", "touchpad", "guide", "key"), values(row));

        String info = fragment.getString(R.string.summary_back_button_mode);
        assertTrue(info.contains("Select (View) button on the PC"));
        assertTrue(info.contains("Back button as Guide"));
        assertTrue(info.contains("F13"));
    }

    @Test
    public void theQuickRowWritesTheSettingAndStoresNothingItself() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        BackButtonPreference row = fragment.findPreference(BackButtonPreference.KEY);
        ListPreference setting = fragment.findPreference(KEY);

        tap(fragment, BackButtonPreference.KEY, "select");
        assertEquals("select", prefs.getString(KEY, null));
        assertEquals("select", setting.getValue());
        assertEquals("select", row.getValue());
        assertEquals(PreferenceConfiguration.BACK_BUTTON_SELECT,
                PreferenceConfiguration.readPreferences(fragment.requireContext()).backButtonMode);

        tap(fragment, BackButtonPreference.KEY, "key");
        assertEquals("key", prefs.getString(KEY, null));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_KEY,
                PreferenceConfiguration.readPreferences(fragment.requireContext()).backButtonMode);

        // The setting in Gamepad Settings drives the row too
        setting.setValue("touchpad");
        assertEquals("touchpad", row.getValue());
        assertFalse("The row stores nothing itself", prefs.contains(BackButtonPreference.KEY));
    }

    @Test
    public void anUnknownStoredValueWorksAsAndroidBack() {
        prefs.edit().putString(KEY, "bogus").commit();
        assertEquals(PreferenceConfiguration.BACK_BUTTON_ANDROID,
                PreferenceConfiguration.readPreferences(ApplicationProvider.getApplicationContext()).backButtonMode);
    }

    @Test
    public void inAProfileOnlyTheProfileChangesAndBothRowsAreMarked() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs.edit().putString(KEY, "android").commit();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>(prefs.getAll());
        options.put(KEY, "guide");
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);
        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        EditProfileActivity editor = Robolectric.buildActivity(EditProfileActivity.class, intent).setup().get();
        SettingsTestUtil.idle();
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                editor.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        BackButtonPreference row = fragment.findPreference(BackButtonPreference.KEY);
        assertEquals("guide", row.getValue());
        assertTrue("Changed in the profile", row.getTitle().toString().startsWith("*"));
        assertTrue(fragment.findPreference(KEY).getTitle().toString().startsWith("*"));

        tap(fragment, BackButtonPreference.KEY, "select");
        assertEquals("select", editor.getInMemoryPrefs().getAll().get(KEY));
        assertEquals("The base settings are unchanged", "android", prefs.getString(KEY, null));
        assertFalse(editor.getInMemoryPrefs().contains(BackButtonPreference.KEY));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_SELECT,
                PreferenceConfiguration.readPreferences(ctx, editor.getInMemoryPrefs()).backButtonMode);
    }
}

package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SegmentedListPreferenceTest {

    private SharedPreferences prefs;
    private StreamSettings activity;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        SettingsTestUtil.resetEnvironment();
        prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext());
        prefs.edit().putString("list_resolution", "1280x720").putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 9000)
                .putBoolean("bitrate_follows_resolution", false).commit();
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

    private StreamSettings.SettingsFragment launch() {
        activity = SettingsTestUtil.launchActivity();
        return SettingsTestUtil.fragmentOf(activity);
    }

    private static View segment(View row, int index) {
        ViewGroup group = row.findViewById(R.id.vw_pref_segments);
        assertNotNull(group);
        return group.getChildAt(index);
    }

    private static int indexOf(SegmentedListPreference pref, String value) {
        List<SegmentedListPreference.Segment> segments = pref.segments();
        for (int i = 0; i < segments.size(); i++) {
            if (value == null ? segments.get(i).isMore() : value.equals(segments.get(i).value)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void resolutionRowShowsTheSegmentsAndMore() {
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        List<String> labels = new ArrayList<>();
        for (SegmentedListPreference.Segment segment : resolution.segments()) {
            labels.add(segment.label.toString());
        }
        assertEquals(Arrays.asList("Auto", "720p", "1080p", "1440p", "4K", "More"), labels);
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        ViewGroup group = row.findViewById(R.id.vw_pref_segments);
        assertEquals(6, group.getChildCount());
        assertFalse("Segments never take the focus", group.getChildAt(0).isFocusable());
    }

    @Test
    public void tappingASegmentRunsTheResolutionListener() {
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        segment(row, indexOf(resolution, "1920x1080")).performClick();
        SettingsTestUtil.idle();
        assertEquals("1920x1080", prefs.getString("list_resolution", null));
        assertEquals("1920x1080", resolution.getValue());
        assertTrue("The bitrate follows the resolution again", prefs.getBoolean("bitrate_follows_resolution", false));
        assertEquals(PreferenceConfiguration.getDefaultBitrate("1920x1080", "60"), prefs.getInt("seekbar_bitrate_kbps", -1));
    }

    @Test
    public void aValueWithoutASegmentSelectsMoreAndNamesTheEntry() {
        prefs.edit().putString("list_resolution", "640x360").commit();
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        assertEquals(indexOf(resolution, null),
                SegmentedListPreference.checkedIndex(resolution.segments(), resolution.getValue()));
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        TextView value = row.findViewById(R.id.vw_pref_value);
        assertEquals(View.VISIBLE, value.getVisibility());
        assertEquals(resolution.getEntry().toString(), value.getText().toString());
        assertTrue(((android.widget.Checkable) segment(row, indexOf(resolution, null))).isChecked());
    }

    @Test
    public void moreOpensTheFullList() {
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        segment(row, indexOf(resolution, null)).performClick();
        SettingsTestUtil.idle();
        Fragment dialog = fragment.getParentFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
        assertTrue("More opens the list dialog", dialog instanceof androidx.preference.ListPreferenceDialogFragmentCompat);
        assertEquals("1280x720", prefs.getString("list_resolution", null));
    }

    @Test
    public void rightTwiceThenACommitsTheSecondNextValue() {
        StreamSettings.SettingsFragment fragment = launch();
        SettingsTestUtil.focusRow(fragment, "list_resolution");
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals("Left and Right only highlight", "1280x720", prefs.getString("list_resolution", null));
        assertEquals(9000, prefs.getInt("seekbar_bitrate_kbps", -1));
        SettingsTestUtil.press(activity, KeyEvent.KEYCODE_BUTTON_A);
        SettingsTestUtil.idle();
        assertEquals("2560x1440", prefs.getString("list_resolution", null));
    }

    @Test
    public void leavingTheRowDropsTheHighlight() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.focusRow(fragment, "list_resolution");
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        SettingsTestUtil.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(resolution.getPendingIndex() >= 0);
        row.getOnFocusChangeListener().onFocusChange(row, false);
        SettingsTestUtil.idle();
        assertEquals(-1, resolution.getPendingIndex());
        assertEquals("1280x720", prefs.getString("list_resolution", null));
    }

    @Test
    public void bDropsTheHighlight() {
        StreamSettings.SettingsFragment fragment = launch();
        SettingsTestUtil.focusRow(fragment, "list_resolution");
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        SettingsTestUtil.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(-1, resolution.getPendingIndex());
        assertFalse("B with a highlight does not close the screen", activity.isFinishing());
        assertEquals("1280x720", prefs.getString("list_resolution", null));
    }

    @Test
    public void leftAtTheFirstSegmentIsConsumed() {
        prefs.edit().putString("list_resolution", "auto").commit();
        StreamSettings.SettingsFragment fragment = launch();
        SettingsTestUtil.focusRow(fragment, "list_resolution");
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(-1, resolution.getPendingIndex());
    }

    @Test
    public void aWithoutAHighlightOpensTheFullList() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = SettingsTestUtil.focusRow(fragment, "list_resolution");
        row.performClick();
        SettingsTestUtil.idle();
        Fragment dialog = fragment.getParentFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
        assertNotNull(dialog);
    }

    @Test
    public void segmentsFollowEntriesRemovedAtRuntime() {
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        List<CharSequence> values = new ArrayList<>(Arrays.asList(resolution.getEntryValues()));
        List<CharSequence> names = new ArrayList<>(Arrays.asList(resolution.getEntries()));
        int k = values.indexOf("3840x2160");
        values.remove(k);
        names.remove(k);
        resolution.setEntries(names.toArray(new CharSequence[0]));
        resolution.setEntryValues(values.toArray(new CharSequence[0]));
        assertEquals(-1, indexOf(resolution, "3840x2160"));
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        assertEquals(resolution.segments().size(), ((ViewGroup) row.findViewById(R.id.vw_pref_segments)).getChildCount());
    }

    @Test
    public void mouseModeShowsTheFullEntryName() {
        prefs.edit().putString("mouse_mode_list", "3").commit();
        StreamSettings.SettingsFragment fragment = launch();
        SegmentedListPreference mouse = fragment.findPreference("mouse_mode_list");
        assertEquals(mouse.getEntry().toString(), mouse.getRowValueText().toString());
        assertEquals(indexOf(mouse, null), SegmentedListPreference.checkedIndex(mouse.segments(), "3"));
    }

    @Test
    public void usingTheSegmentsWritesNoNewKey() {
        StreamSettings.SettingsFragment fragment = launch();
        Set<String> before = new HashSet<>(prefs.getAll().keySet());
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        segment(row, indexOf(resolution, "auto")).performClick();
        SettingsTestUtil.idle();
        Set<String> after = new HashSet<>(prefs.getAll().keySet());
        after.removeAll(before);
        assertTrue("New keys: " + after, after.isEmpty());
    }

    @Test
    public void inAProfileTheSegmentsWriteOnlyTheProfile() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ProfilesManager manager = ProfilesManager.getInstance();
        manager.load(ctx);
        Map<String, Object> options = new HashMap<>(prefs.getAll());
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Profile", 0, 0, options);
        manager.add(profile);
        Intent intent = new Intent(ctx, EditProfileActivity.class);
        intent.putExtra("profileUuid", profile.getUuid().toString());
        EditProfileActivity editor = Robolectric.buildActivity(EditProfileActivity.class, intent).setup().get();
        SettingsTestUtil.idle();
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                editor.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        SegmentedListPreference resolution = fragment.findPreference("list_resolution");
        View row = SettingsTestUtil.bindRow(fragment, "list_resolution").itemView;
        segment(row, indexOf(resolution, "1920x1080")).performClick();
        SettingsTestUtil.idle();
        assertEquals("1920x1080", editor.getInMemoryPrefs().getString("list_resolution", null));
        assertEquals("1280x720", prefs.getString("list_resolution", null));
    }
}

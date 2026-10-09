package com.limelight.preferences;

import android.app.GameManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class StreamSettingsUiTest {

    private StreamSettings activity;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        ctx.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();
        Shadows.shadowOf((android.app.Application) ctx)
                .setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
    }

    private static void idle() {
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    private StreamSettings.SettingsFragment launch() {
        activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        idle();
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertNotNull("Settings fragment should be attached to stream_settings", fragment);
        return (StreamSettings.SettingsFragment) fragment;
    }

    private static RecyclerView.ViewHolder bindRow(RecyclerView list, int position) {
        @SuppressWarnings("unchecked")
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                (RecyclerView.Adapter<RecyclerView.ViewHolder>) list.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list, adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        return holder;
    }

    private static int adapterPosition(RecyclerView list, Preference preference) {
        return ((PreferenceGroup.PreferencePositionCallback) list.getAdapter())
                .getPreferenceAdapterPosition(preference);
    }

    @Test
    public void inflatesWithTopBarAndContainer() {
        launch();
        assertNotNull(activity.findViewById(R.id.settingsBackButton));
        assertNotNull(activity.findViewById(R.id.settingsTitle));
        assertNotNull(activity.findViewById(R.id.stream_settings));
    }

    @Test
    public void resolutionListKeepsExplicitEntries() {
        StreamSettings.SettingsFragment fragment = launch();
        ListPreference resolution = fragment.findPreference("list_resolution");
        assertNotNull(resolution);
        List<CharSequence> values = Arrays.asList(resolution.getEntryValues());
        for (String explicit : new String[] {"640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"}) {
            assertTrue("Missing " + explicit, values.contains(explicit));
        }
    }

    @Test
    public void autoIsTheFirstResolutionAndFpsEntry() {
        StreamSettings.SettingsFragment fragment = launch();
        ListPreference resolution = fragment.findPreference("list_resolution");
        ListPreference fps = fragment.findPreference("list_fps");
        assertEquals("auto", resolution.getEntryValues()[0].toString());
        assertEquals(fragment.getString(R.string.resolution_auto), resolution.getEntries()[0].toString());
        assertEquals("auto", fps.getEntryValues()[0].toString());
        assertEquals("auto", resolution.getValue());
        assertEquals("auto", fps.getValue());
        assertTrue("Auto rows show what auto resolves to",
                resolution.getSummary().toString().startsWith("Currently "));
        // The new switch sits in the video category
        assertNotNull(fragment.findPreference("checkbox_auto_res_prefer_external"));
    }

    @Test
    public void choosingAutoMakesTheBitrateFollowTheResolution() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        prefs.edit().putString("list_resolution", "1280x720").putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 9000)
                .putBoolean("bitrate_follows_resolution", false).commit();

        StreamSettings.SettingsFragment fragment = launch();
        ListPreference resolution = fragment.findPreference("list_resolution");
        assertTrue(resolution.callChangeListener("auto"));
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", false));
        assertTrue(prefs.getInt("seekbar_bitrate_kbps", 0) != 9000);
    }

    @Test
    public void movingTheBitrateStopsFollowing() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        prefs.edit().putBoolean("bitrate_follows_resolution", true).commit();

        StreamSettings.SettingsFragment fragment = launch();
        Preference bitrate = fragment.findPreference("seekbar_bitrate_kbps");
        bitrate.callChangeListener(15000);
        assertTrue(!prefs.getBoolean("bitrate_follows_resolution", true));
    }

    @Test
    public void checkboxRowsShowASwitchBoundToTheStoredValue() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        prefs.edit().putBoolean("checkbox_enable_audiofx", true).commit();

        StreamSettings.SettingsFragment fragment = launch();
        CheckBoxPreference checkbox = fragment.findPreference("checkbox_enable_audiofx");
        assertNotNull(checkbox);
        RecyclerView list = fragment.getListView();
        int position = adapterPosition(list, checkbox);
        assertTrue(position >= 0);

        RecyclerView.ViewHolder holder = bindRow(list, position);
        View widget = holder.itemView.findViewById(android.R.id.checkbox);
        assertTrue("Checkbox rows should use a switch", widget instanceof CompoundButton);
        assertEquals("com.google.android.material.materialswitch.MaterialSwitch", widget.getClass().getName());
        assertTrue(((CompoundButton) widget).isChecked());
        assertTrue("The row, not the switch, handles focus", holder.itemView.isFocusable());
    }

    @Test
    public void categoryHeadersAreNotFocusable() {
        StreamSettings.SettingsFragment fragment = launch();
        RecyclerView list = fragment.getListView();
        Preference video = fragment.findPreference("category_video_settings");
        RecyclerView.ViewHolder holder = bindRow(list, adapterPosition(list, video));
        assertTrue(!holder.itemView.isFocusable());
        assertEquals(fragment.getString(R.string.category_video_settings),
                ((TextView) holder.itemView.findViewById(android.R.id.title)).getText().toString());
    }

    @Test
    public void collapsedGroupsShowAShowMoreRow() {
        StreamSettings.SettingsFragment fragment = launch();
        RecyclerView list = fragment.getListView();
        Preference video = fragment.findPreference("category_video_settings");
        Preference audio = fragment.findPreference("category_audio_settings");
        int videoPosition = adapterPosition(list, video);
        int audioPosition = adapterPosition(list, audio);
        // The row right above the audio header is the video category's expander
        RecyclerView.ViewHolder holder = bindRow(list, audioPosition - 1);
        assertTrue(audioPosition - 1 > videoPosition);
        TextView title = holder.itemView.findViewById(android.R.id.title);
        assertNotNull(title);
        assertEquals(fragment.getString(R.string.expand_button_title), title.getText().toString());
        float density = fragment.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(56 * density), holder.itemView.getMinimumHeight());
    }

    @Test
    public void shoulderButtonsMoveFocusToTheFirstRowOfTheCategory() {
        StreamSettings.SettingsFragment fragment = launch();
        RecyclerView list = fragment.getListView();
        com.limelight.PcViewUiTest.exitTouchMode(list);
        int audioPosition = adapterPosition(list, fragment.findPreference("category_audio_settings"));
        int gamepadPosition = adapterPosition(list, fragment.findPreference("category_gamepad_settings"));

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
        idle();
        View focused = list.getFocusedChild();
        assertNotNull("R1 should focus a row", focused);
        assertEquals(audioPosition + 1, list.getChildAdapterPosition(focused));

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
        idle();
        assertEquals(gamepadPosition + 1, list.getChildAdapterPosition(list.getFocusedChild()));

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
        idle();
        assertEquals(audioPosition + 1, list.getChildAdapterPosition(list.getFocusedChild()));
    }

    @Test
    @Config(sdk = {28})
    public void checkboxRowsUseTheMaterialComponentsSwitchBeforeAndroid10() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        prefs.edit().putBoolean("checkbox_enable_audiofx", true).commit();

        StreamSettings.SettingsFragment fragment = launch();
        RecyclerView list = fragment.getListView();
        int position = adapterPosition(list, fragment.findPreference("checkbox_enable_audiofx"));
        View widget = bindRow(list, position).itemView.findViewById(android.R.id.checkbox);
        assertEquals("com.google.android.material.switchmaterial.SwitchMaterial", widget.getClass().getName());
        assertTrue(((CompoundButton) widget).isChecked());
    }

    @Test
    public void confirmingTheBitrateWithoutChangingItKeepsFollowing() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        StreamSettings.SettingsFragment fragment = launch();
        int followed = prefs.getInt("seekbar_bitrate_kbps", -1);
        assertTrue("Settings store the bitrate auto will use", followed > 0);
        // Absent means following (the default)
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", true));

        fragment.findPreference("seekbar_bitrate_kbps").callChangeListener(followed);
        assertTrue(prefs.getBoolean("bitrate_follows_resolution", true));
    }

    @Test
    public void shoulderButtonsMoveBetweenCategories() {
        StreamSettings.SettingsFragment fragment = launch();
        RecyclerView list = fragment.getListView();
        LinearLayoutManager layoutManager = (LinearLayoutManager) list.getLayoutManager();
        int audioPosition = adapterPosition(list, fragment.findPreference("category_audio_settings"));
        int gamepadPosition = adapterPosition(list, fragment.findPreference("category_gamepad_settings"));

        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)));
        idle();
        assertEquals(audioPosition, layoutManager.findFirstVisibleItemPosition());

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
        idle();
        assertEquals(gamepadPosition, layoutManager.findFirstVisibleItemPosition());

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
        idle();
        assertEquals(audioPosition, layoutManager.findFirstVisibleItemPosition());
    }
}

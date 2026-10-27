package com.limelight.ui;

import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridView;

import android.content.res.Configuration;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.PcView;
import com.limelight.PcViewUiTest;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.StreamSettings;
import com.limelight.preferences.UiState;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Settings beside the PC list on wide landscape screens. */
@Config(sdk = {33}, qualifiers = "w960dp-h540dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsPanelTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();
        UiState.prefs(ctx).edit().clear().commit();
        org.robolectric.shadows.ShadowApplication shadowApp = Shadows.shadowOf((android.app.Application) ctx);
        shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
        shadowApp.setComponentNameAndServiceForBindService(new ComponentName(ctx, ComputerManagerService.class),
                mock(ComputerManagerService.ComputerManagerBinder.class));
    }

    private static void idle() {
        for (int i = 0; i < 8; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    private static PcView launch() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        idle();
        return activity;
    }

    private static void addHost(PcView activity, String uuid) throws Exception {
        Method update = PcView.class.getDeclaredMethod("updateComputer", ComputerDetails.class);
        update.setAccessible(true);
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = "Host " + uuid;
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairingManager.PairState.PAIRED;
        update.invoke(activity, details);
        idle();
    }

    private static boolean inside(View view, int ancestorId) {
        View current = view;
        while (current != null) {
            if (current.getId() == ancestorId) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    /** What the settings list looks like, for failure messages. */
    private static String describe(PcView activity) {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings_panel_list);
        StringBuilder sb = new StringBuilder(" focus=").append(activity.getCurrentFocus())
                .append(" decorFocus=").append(activity.getWindow().getDecorView().findFocus())
                .append(" panelHasFocus=").append(activity.findViewById(R.id.settings_panel).hasFocus())
                .append(" gridHasFocus=").append(activity.findViewById(R.id.fragmentView) != null &&
                        activity.findViewById(R.id.fragmentView).hasFocus());
        if (fragment instanceof StreamSettings.SettingsFragment) {
            androidx.recyclerview.widget.RecyclerView list = ((StreamSettings.SettingsFragment) fragment).getListView();
            sb.append(" list=").append(list.getWidth()).append('x').append(list.getHeight())
                    .append(" children=").append(list.getChildCount())
                    .append(" items=").append(list.getAdapter() == null ? -1 : list.getAdapter().getItemCount())
                    .append(" shown=").append(list.isShown())
                    .append(" layoutRequested=").append(list.isLayoutRequested())
                    .append(" focusedChild=").append(list.getFocusedChild());
            if (list.getChildCount() > 1) {
                View row = list.getChildAt(1);
                sb.append(" row1 focusable=").append(row.isFocusable()).append(" enabled=").append(row.isEnabled())
                        .append(" touchMode=").append(row.isInTouchMode());
            }
        }
        else {
            sb.append(" fragment=").append(fragment);
        }
        return sb.toString();
    }

    private static void press(PcView activity, int keyCode) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        idle();
    }

    private static GridView focusedGrid(PcView activity) throws Exception {
        addHost(activity, "a");
        GridView grid = activity.findViewById(R.id.fragmentView);
        assertNotNull(grid);
        PcViewUiTest.exitTouchMode(grid);
        assertTrue(grid.requestFocus());
        grid.setSelection(0);
        idle();
        return grid;
    }

    @Test
    public void settingsSitBesideTheHosts() {
        PcView activity = launch();
        View panel = activity.findViewById(R.id.settings_panel);
        assertNotNull(panel);
        assertEquals(View.VISIBLE, panel.getVisibility());
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings_panel_list);
        assertTrue(fragment instanceof StreamSettings.SettingsFragment);
        assertNotNull(((StreamSettings.SettingsFragment) fragment).findPreference("list_resolution"));
        assertNotNull(activity.findViewById(R.id.fragmentView));
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(SplitLayout.panelWidthDp(960) * density), panel.getLayoutParams().width);
        // The focus starts on the PC list side, not in the settings
        assertFalse(activity.findViewById(R.id.settings_panel).hasFocus());
    }

    @Test
    public void thePanelButtonHidesTheSettingsAndTheChoiceIsKept() {
        PcView activity = launch();
        activity.findViewById(R.id.settingsBackButton).performClick();
        idle();
        assertEquals(View.GONE, activity.findViewById(R.id.settings_panel).getVisibility());
        assertFalse(UiState.settingsPanelOpen(activity));

        PcView again = launch();
        assertEquals(View.GONE, again.findViewById(R.id.settings_panel).getVisibility());
        again.findViewById(R.id.settingsButton).performClick();
        idle();
        assertEquals(View.VISIBLE, again.findViewById(R.id.settings_panel).getVisibility());
        assertNull("The settings button does not open a screen here", Shadows.shadowOf(again).getNextStartedActivity());
    }

    @Test
    public void startMovesBetweenTheHostsAndTheSettings() throws Exception {
        PcView activity = launch();
        GridView grid = focusedGrid(activity);

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue("Start enters the settings" + describe(activity), activity.findViewById(R.id.settings_panel_list).hasFocus());
        assertNull("Start does not open the settings screen", Shadows.shadowOf(activity).getNextStartedActivity());

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue("Start again returns to the hosts", grid.hasFocus());

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(activity.findViewById(R.id.settings_panel_list).hasFocus());
        press(activity, KeyEvent.KEYCODE_BUTTON_B);
        assertTrue("B returns to the hosts", grid.hasFocus());
    }

    @Test
    public void startOpensACollapsedPanel() throws Exception {
        UiState.setSettingsPanelOpen(ApplicationProvider.getApplicationContext(), false);
        PcView activity = launch();
        focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        idle();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_panel).getVisibility());
        assertTrue(UiState.settingsPanelOpen(activity));
    }

    @Test
    public void leftFromTheFirstHostColumnEntersTheSettings() throws Exception {
        PcView activity = launch();
        focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue("Left enters the settings" + describe(activity), activity.findViewById(R.id.settings_panel_list).hasFocus());
    }

    @Test
    public void xAndYInTheSettingsAreNotHostShortcuts() throws Exception {
        PcView activity = launch();
        focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(activity.findViewById(R.id.settings_panel_list).hasFocus());

        press(activity, KeyEvent.KEYCODE_BUTTON_Y);
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertTrue("Y must not open Profiles from the settings",
                next == null || !ProfilesActivity.class.getName().equals(next.getComponent().getClassName()));
        press(activity, KeyEvent.KEYCODE_BUTTON_X);
        assertTrue("The focus stays in the settings", activity.findViewById(R.id.settings_panel).hasFocus());
    }

    @Test
    public void processDeathRecreatesThePanel() {
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        controller.pause().stop().destroy();

        ActivityController<PcView> restored = Robolectric.buildActivity(PcView.class).setup(saved);
        idle();
        Fragment fragment = restored.get().getSupportFragmentManager().findFragmentById(R.id.settings_panel_list);
        assertTrue(fragment instanceof StreamSettings.SettingsFragment);
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    public void narrowLandscapeKeepsTheSettingsScreen() {
        PcView activity = launch();
        assertNull(activity.findViewById(R.id.settings_panel));
        activity.findViewById(R.id.settingsButton).performClick();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(StreamSettings.class.getName(), next.getComponent().getClassName());
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-port")
    public void portraitKeepsTheSettingsScreen() {
        PcView activity = launch();
        assertNull(activity.findViewById(R.id.settings_panel));
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(StreamSettings.class.getName(), next.getComponent().getClassName());
    }

    private static StreamSettings.SettingsFragment panelFragment(PcView activity) {
        return (StreamSettings.SettingsFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.settings_panel_list);
    }

    private static View bindRow(StreamSettings.SettingsFragment fragment, String key) {
        RecyclerView list = fragment.getListView();
        Preference preference = fragment.findPreference(key);
        int position = ((PreferenceGroup.PreferencePositionCallback) list.getAdapter())
                .getPreferenceAdapterPosition(preference);
        assertTrue(key + " should be in the list", position >= 0);
        @SuppressWarnings("unchecked")
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                (RecyclerView.Adapter<RecyclerView.ViewHolder>) list.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list, adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        return holder.itemView;
    }

    @Test
    public void thePanelShowsSettingsChangedWhileAwayAndATapWrites() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        assertEquals("0", ((androidx.preference.ListPreference) panelFragment(controller.get())
                .findPreference("mouse_mode_list")).getValue());
        Fragment before = panelFragment(controller.get());

        // A stream changes the touchscreen mode in the stored settings
        controller.pause().stop();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("mouse_mode_list", "2").commit();
        controller.restart().start().resume();
        idle();

        StreamSettings.SettingsFragment fragment = panelFragment(controller.get());
        assertTrue("Changed settings rebuild the panel", fragment != before);
        androidx.preference.ListPreference mouse = fragment.findPreference("mouse_mode_list");
        assertEquals("2", mouse.getValue());

        // Touch is the first segment
        View row = bindRow(fragment, "mouse_mode_list");
        ((ViewGroup) row.findViewById(R.id.vw_pref_segments)).getChildAt(0).performClick();
        idle();
        assertEquals("0", PreferenceManager.getDefaultSharedPreferences(ctx).getString("mouse_mode_list", null));
    }

    @Test
    public void backInThePanelReturnsToTheHostsOrDropsAHighlight() throws Exception {
        PcView activity = launch();
        GridView grid = focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(activity.findViewById(R.id.settings_panel_list).hasFocus());

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertTrue("Back returns to the hosts", grid.hasFocus());
        assertFalse(activity.isFinishing());

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(activity.findViewById(R.id.settings_panel_list).hasFocus());
        // Right on the resolution row highlights a segment
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertTrue("The first Back only drops the highlight", activity.findViewById(R.id.settings_panel_list).hasFocus());
        assertFalse(panelFragment(activity).dropPendingChoice());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void aConfigurationChangeKeepsThePanelAndItsFocus() throws Exception {
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        PcView activity = controller.get();
        focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(activity.findViewById(R.id.settings_panel_list).hasFocus());
        Fragment before = panelFragment(activity);

        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = 1.15f;
        controller.configurationChange(config);
        idle();

        assertSame("The same panel fragment is kept", before, panelFragment(activity));
        assertNotNull(before.getView());
        assertTrue("The focus returns to the panel" + describe(activity),
                activity.findViewById(R.id.settings_panel_list).hasFocus());
    }

    @Test
    public void aListDialogRestoredAfterProcessDeathIsDropped() {
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        panelFragment(controller.get()).findPreference("list_resolution").performClick();
        idle();
        assertNotNull(controller.get().getSupportFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG"));
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        controller.pause().stop().destroy();

        ActivityController<PcView> restored = Robolectric.buildActivity(PcView.class).setup(saved);
        idle();
        assertNull(restored.get().getSupportFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG"));
        assertTrue(panelFragment(restored.get()) instanceof StreamSettings.SettingsFragment);
    }

    /** Opens a collapsed section as tapping its "Show N settings" row does. */
    private static void expand(StreamSettings.SettingsFragment fragment, String categoryKey) {
        androidx.preference.PreferenceGroupAdapter adapter =
                (androidx.preference.PreferenceGroupAdapter) fragment.getListView().getAdapter();
        Preference category = fragment.findPreference(categoryKey);
        int position = adapter.getPreferenceAdapterPosition(category);
        Preference expander = adapter.getItem(position + 1);
        assertEquals("ExpandButton", expander.getClass().getSimpleName());
        expander.performClick();
        idle();
    }

    @Test
    public void returningWithoutChangesKeepsThePanelAsItWas() {
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        StreamSettings.SettingsFragment before = panelFragment(controller.get());
        expand(before, "category_video_settings");
        PreferenceGroup video = before.findPreference("category_video_settings");
        assertEquals(Integer.MAX_VALUE, video.getInitialExpandedChildrenCount());

        controller.pause().stop();
        controller.restart().start().resume();
        idle();

        assertSame("No change, no rebuild", before, panelFragment(controller.get()));
        assertEquals("The opened section stays open", Integer.MAX_VALUE,
                ((PreferenceGroup) panelFragment(controller.get()).findPreference("category_video_settings"))
                        .getInitialExpandedChildrenCount());
    }

    @Test
    public void aRebuildWaitsForAnOpenListDialog() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        idle();
        StreamSettings.SettingsFragment before = panelFragment(controller.get());
        before.findPreference("list_resolution").performClick();
        idle();
        androidx.preference.ListPreferenceDialogFragmentCompat dialog =
                (androidx.preference.ListPreferenceDialogFragmentCompat) controller.get().getSupportFragmentManager()
                        .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
        assertNotNull(dialog);

        // Home and back while the list is open, with a setting changed meanwhile
        controller.pause().stop();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("mouse_mode_list", "2").commit();
        controller.restart().start().resume();
        idle();
        assertSame("No rebuild while the dialog is open", before, panelFragment(controller.get()));

        // Pick 1440p in the list
        androidx.preference.ListPreference resolution = before.findPreference("list_resolution");
        int index = resolution.findIndexOfValue("2560x1440");
        assertTrue(index >= 0);
        androidx.appcompat.app.AlertDialog alert = (androidx.appcompat.app.AlertDialog) dialog.getDialog();
        assertNotNull(alert);
        alert.getListView().performItemClick(null, index, index);
        idle();

        assertEquals("2560x1440", PreferenceManager.getDefaultSharedPreferences(ctx).getString("list_resolution", null));
        assertNull(controller.get().getSupportFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG"));
        StreamSettings.SettingsFragment after = panelFragment(controller.get());
        assertTrue("The waiting rebuild ran after the dialog closed", after != before);
        assertEquals("2560x1440", ((androidx.preference.ListPreference) after.findPreference("list_resolution")).getValue());
        assertEquals("2", ((androidx.preference.ListPreference) after.findPreference("mouse_mode_list")).getValue());
    }
}

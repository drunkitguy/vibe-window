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

import androidx.fragment.app.Fragment;
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
        assertFalse(inside(activity.getCurrentFocus(), R.id.settings_panel));
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
        assertTrue("Start enters the settings", inside(activity.getCurrentFocus(), R.id.settings_panel_list));
        assertNull("Start does not open the settings screen", Shadows.shadowOf(activity).getNextStartedActivity());

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue("Start again returns to the hosts", grid.hasFocus());

        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(inside(activity.getCurrentFocus(), R.id.settings_panel_list));
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
        assertTrue(inside(activity.getCurrentFocus(), R.id.settings_panel_list));
    }

    @Test
    public void xAndYInTheSettingsAreNotHostShortcuts() throws Exception {
        PcView activity = launch();
        focusedGrid(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_START);
        assertTrue(inside(activity.getCurrentFocus(), R.id.settings_panel_list));

        press(activity, KeyEvent.KEYCODE_BUTTON_Y);
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertTrue("Y must not open Profiles from the settings",
                next == null || !ProfilesActivity.class.getName().equals(next.getComponent().getClassName()));
        press(activity, KeyEvent.KEYCODE_BUTTON_X);
        assertTrue("The focus stays in the settings", inside(activity.getCurrentFocus(), R.id.settings_panel));
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
}

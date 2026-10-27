package com.limelight.preferences;

import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class InfoButtonTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        SettingsTestUtil.resetEnvironment();
    }

    private static StreamSettings.SettingsFragment launch() {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        SettingsTestUtil.expandAll(fragment);
        return fragment;
    }

    private static RecyclerView.ViewHolder bindRow(StreamSettings.SettingsFragment fragment, String key) {
        return SettingsTestUtil.bindRow(fragment, key);
    }

    private static void idle() {
        SettingsTestUtil.idle();
    }

    @Test
    public void aRowWithASummaryShowsTheInfoButtonInstead() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = bindRow(fragment, "checkbox_enable_audiofx").itemView;
        assertEquals(View.VISIBLE, row.findViewById(R.id.vw_pref_info).getVisibility());
        assertEquals(View.GONE, row.findViewById(android.R.id.summary).getVisibility());
        assertFalse("The row keeps the focus", row.findViewById(R.id.vw_pref_info).isFocusable());
        assertTrue(row.isFocusable());
    }

    @Test
    public void yOnTheFocusedRowOpensTheSummaryInADialog() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        SettingsTestUtil.focusRow(fragment, "checkbox_host_audio");
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_BUTTON_Y));
        idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("Y should open the info dialog", dialog);
        assertTrue(dialog.isShowing());
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(fragment.getString(R.string.summary_checkbox_host_audio), message.getText().toString());
    }

    @Test
    public void theInfoButtonOpensTheDialogWithTheCurrentEntryForLists() {
        StreamSettings.SettingsFragment fragment = launch();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        View row = bindRow(fragment, "frame_pacing").itemView;
        row.findViewById(R.id.vw_pref_info).performClick();
        idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        String message = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.endsWith(fragment.getString(R.string.pref_info_current, pacing.getEntry())));
    }

    @Test
    public void listRowsShowTheirValueOnTheTitleLine() {
        StreamSettings.SettingsFragment fragment = launch();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        TextView value = bindRow(fragment, "frame_pacing").itemView.findViewById(R.id.vw_pref_value);
        assertEquals(View.VISIBLE, value.getVisibility());
        assertEquals(pacing.getEntry().toString(), value.getText().toString());
    }

    @Test
    public void showDescriptionsBringsTheSummariesBack() {
        StreamSettings.SettingsFragment fragment = launch();
        fragment.setShowDescriptions(true);
        View row = bindRow(fragment, "checkbox_enable_audiofx").itemView;
        assertEquals(View.VISIBLE, row.findViewById(android.R.id.summary).getVisibility());
        assertEquals(View.GONE, row.findViewById(R.id.vw_pref_info).getVisibility());

        fragment.setShowDescriptions(false);
        row = bindRow(fragment, "checkbox_enable_audiofx").itemView;
        assertEquals(View.GONE, row.findViewById(android.R.id.summary).getVisibility());

        // The option lives in its own file, never in the settings that profiles copy
        Context ctx = ApplicationProvider.getApplicationContext();
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx).contains(UiState.SHOW_DESCRIPTIONS));
    }

    @Test
    public void actionRowsKeepTheirSummaryAndHaveNoInfoButton() {
        StreamSettings.SettingsFragment fragment = launch();
        View row = bindRow(fragment, "pref_debug_info").itemView;
        assertEquals(View.VISIBLE, row.findViewById(android.R.id.summary).getVisibility());
        assertEquals(View.GONE, row.findViewById(R.id.vw_pref_info).getVisibility());
    }
}

package com.limelight.preferences;

import android.content.Intent;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsHeaderTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        SettingsTestUtil.resetEnvironment();
    }

    private static Menu buildMenu(StreamSettings.SettingsFragment fragment) {
        PopupMenu popup = new PopupMenu(fragment.requireContext(), fragment.requireView());
        fragment.buildMoreMenu(popup.getMenu());
        return popup.getMenu();
    }

    @Test
    public void headerHasBackTitleAndMore() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        assertNotNull(activity.findViewById(R.id.settingsBackButton));
        assertNotNull(activity.findViewById(R.id.settingsTitle));
        View more = activity.findViewById(R.id.settingsMoreButton);
        assertNotNull(more);
        assertTrue(more.isFocusable());
    }

    @Test
    public void moreAndSelectOpenTheMenu() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        activity.findViewById(R.id.settingsMoreButton).performClick();
        assertNotNull("The ... button opens the menu", fragment.shownMoreMenu);

        fragment.shownMoreMenu = null;
        assertTrue(SettingsTestUtil.press(activity, KeyEvent.KEYCODE_BUTTON_SELECT));
        assertNotNull("Select opens the menu", fragment.shownMoreMenu);
    }

    @Test
    public void menuListsTheSectionsAndJumpsToOne() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        Menu menu = buildMenu(fragment);
        MenuItem jump = menu.findItem(StreamSettings.SettingsFragment.MENU_JUMP);
        assertTrue(jump.hasSubMenu());
        assertEquals(fragment.getString(R.string.category_quick_stream), jump.getSubMenu().getItem(0).getTitle().toString());
        assertEquals(fragment.jumpTargets().size(), jump.getSubMenu().size());

        // Jump to the quick Audio group
        MenuItem audio = jump.getSubMenu().getItem(2);
        assertEquals(fragment.getString(R.string.category_quick_audio), audio.getTitle().toString());
        assertTrue(fragment.onMoreMenuItem(audio));
        SettingsTestUtil.idle();
        LinearLayoutManager layoutManager = (LinearLayoutManager) fragment.getListView().getLayoutManager();
        assertEquals(fragment.adapterPositionOf("category_quick_audio"), layoutManager.findFirstVisibleItemPosition());
    }

    @Test
    public void showDescriptionsIsACheckableItem() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        MenuItem descriptions = buildMenu(fragment).findItem(StreamSettings.SettingsFragment.MENU_DESCRIPTIONS);
        assertTrue(descriptions.isCheckable());
        assertFalse(descriptions.isChecked());
        fragment.onMoreMenuItem(descriptions);
        assertTrue(UiState.showDescriptions(ApplicationProvider.getApplicationContext()));
        assertTrue(buildMenu(fragment).findItem(StreamSettings.SettingsFragment.MENU_DESCRIPTIONS).isChecked());
    }

    @Test
    public void profilesOpensTheProfilesScreen() {
        StreamSettings activity = SettingsTestUtil.launchActivity();
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.fragmentOf(activity);
        fragment.onMoreMenuItem(buildMenu(fragment).findItem(StreamSettings.SettingsFragment.MENU_PROFILES));
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }
}

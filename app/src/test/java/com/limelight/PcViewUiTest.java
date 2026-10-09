package com.limelight;

import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PcViewUiTest {

    private static final int[] KEPT_IDS = {
            R.id.settingsButton,
            R.id.helpButton,
            R.id.manuallyAddPc,
            R.id.profilesButton,
            R.id.pcFragmentContainer,
            R.id.no_pc_found_layout,
            R.id.pcs_loading,
    };

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        Context ctx = ApplicationProvider.getApplicationContext();

        // Cached GL renderer so PcView skips the GLSurfaceView probe
        SharedPreferences glPrefs = ctx.getSharedPreferences("GlPreferences", 0);
        glPrefs.edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();

        org.robolectric.shadows.ShadowApplication shadowApp =
                Shadows.shadowOf((android.app.Application) ctx);
        shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager.class));

        ComponentName cn = new ComponentName(ctx, ComputerManagerService.class);
        shadowApp.setComponentNameAndServiceForBindService(cn,
                mock(ComputerManagerService.ComputerManagerBinder.class));
    }

    private void assertKeptIds(PcView activity) {
        for (int id : KEPT_IDS) {
            assertNotNull("Missing view " + activity.getResources().getResourceEntryName(id),
                    activity.findViewById(id));
        }
        assertNotNull(activity.findViewById(R.id.noPcAddButton));
        assertNotNull(activity.findViewById(R.id.pcHeaderTitle));
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp-land")
    public void landscapeLayoutKeepsIds() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        assertKeptIds(activity);
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-port")
    public void portraitLayoutKeepsIds() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        assertKeptIds(activity);
    }

    @Test
    public void emptyStateButtonOpensAddComputer() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        View button = activity.findViewById(R.id.noPcAddButton);
        assertTrue(button.isFocusable());
        button.performClick();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(com.limelight.preferences.AddComputerManually.class.getName(),
                next.getComponent().getClassName());
    }

    @Test
    public void startButtonOpensSettings() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        pressKey(activity, KeyEvent.KEYCODE_BUTTON_START);
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Start should open settings", next);
        assertEquals(StreamSettings.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void yButtonOpensProfiles() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        pressKey(activity, KeyEvent.KEYCODE_BUTTON_Y);
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Y should open profiles", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void xButtonWithoutSelectionDoesNothing() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        pressKey(activity, KeyEvent.KEYCODE_BUTTON_X);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void hostCardShowsStatusAndAction() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        PcGridAdapter adapter = new PcGridAdapter(activity, PreferenceConfiguration.readPreferences(activity));

        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Desk";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairingManager.PairState.PAIRED;
        adapter.addComputer(new PcView.ComputerObject(details));

        View card = adapter.getView(0, null, new FrameLayout(activity));
        assertEquals("Desk", ((TextView) card.findViewById(R.id.grid_text)).getText().toString());
        assertEquals(activity.getString(R.string.host_status_online),
                ((TextView) card.findViewById(R.id.host_status_text)).getText().toString());
        assertEquals(activity.getString(R.string.host_action_open),
                ((TextView) card.findViewById(R.id.host_action_text)).getText().toString());
        assertEquals(View.VISIBLE, card.findViewById(R.id.host_glow).getVisibility());

        details.state = ComputerDetails.State.OFFLINE;
        card = adapter.getView(0, card, new FrameLayout(activity));
        assertEquals(activity.getString(R.string.host_status_offline),
                ((TextView) card.findViewById(R.id.host_status_text)).getText().toString());
        assertEquals(activity.getString(R.string.host_action_options),
                ((TextView) card.findViewById(R.id.host_action_text)).getText().toString());
        assertEquals(View.GONE, card.findViewById(R.id.host_glow).getVisibility());
    }

    /** Records the view whose context menu was requested. */
    public static class RecordingPcView extends PcView {
        View contextMenuView;

        @Override
        public void openContextMenu(View view) {
            contextMenuView = view;
        }
    }

    private static void idle() {
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    private static void addHosts(PcView activity, int count) throws Exception {
        Method update = PcView.class.getDeclaredMethod("updateComputer", ComputerDetails.class);
        update.setAccessible(true);
        for (int i = 0; i < count; i++) {
            ComputerDetails details = new ComputerDetails();
            details.uuid = "uuid-" + i;
            details.name = "Host " + i;
            details.state = ComputerDetails.State.ONLINE;
            details.pairState = PairingManager.PairState.PAIRED;
            update.invoke(activity, details);
        }
        idle();
    }

    private static GridView grid(PcView activity) {
        GridView grid = activity.findViewById(R.id.fragmentView);
        assertNotNull("Host grid should be attached", grid);
        return grid;
    }

    /** Leaves touch mode the way a controller key press does. */
    public static void exitTouchMode(View view) {
        Object attachInfo = ReflectionHelpers.getField(view, "mAttachInfo");
        ReflectionHelpers.setField(attachInfo, "mInTouchMode", false);
    }

    private void assertTwoColumnsWithoutClipping() throws Exception {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        idle();
        addHosts(activity, 2);
        GridView grid = grid(activity);
        assertEquals(2, grid.getNumColumns());
        int used = 2 * grid.getColumnWidth() + grid.getHorizontalSpacing()
                + grid.getPaddingLeft() + grid.getPaddingRight();
        assertTrue("Columns must fit the grid", used <= grid.getWidth());
    }

    @Test
    @Config(qualifiers = "w731dp-h411dp-land")
    public void handheldLandscapeShowsTwoHostsSideBySide() throws Exception {
        assertTwoColumnsWithoutClipping();
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    public void smallLandscapeShowsTwoHostsSideBySide() throws Exception {
        assertTwoColumnsWithoutClipping();
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    public void narrowPortraitCardFitsTheScreen() throws Exception {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        idle();
        addHosts(activity, 2);
        GridView grid = grid(activity);
        assertEquals(1, grid.getNumColumns());
        assertTrue("The card column must not be clipped",
                grid.getColumnWidth() + grid.getPaddingLeft() + grid.getPaddingRight() <= grid.getWidth());
    }

    @Test
    @Config(qualifiers = "w731dp-h411dp-land")
    public void xButtonOpensTheSelectedHostsMenu() throws Exception {
        RecordingPcView activity = Robolectric.buildActivity(RecordingPcView.class).setup().get();
        idle();
        addHosts(activity, 2);
        GridView grid = grid(activity);
        exitTouchMode(grid);
        assertTrue(grid.requestFocus());
        grid.setSelection(1);
        idle();
        assertNotNull(grid.getSelectedView());

        pressKey(activity, KeyEvent.KEYCODE_BUTTON_X);
        assertNotNull("X should open the context menu", activity.contextMenuView);
        assertEquals(grid.getSelectedView(), activity.contextMenuView);
    }

    private static void pressKey(PcView activity, int keyCode) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}

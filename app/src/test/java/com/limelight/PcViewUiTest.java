package com.limelight;

import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
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

    private static void pressKey(PcView activity, int keyCode) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}

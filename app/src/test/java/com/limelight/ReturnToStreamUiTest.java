package com.limelight;

import android.app.Activity;
import android.app.Application;
import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.UiState;
import com.limelight.profiles.ProfilesManager;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Return to stream on the real PC list and app list: a stream that went to the background
 * starts again from the first poll of its host, the way Quick Resume starts it.
 */
@Config(sdk = {33}, qualifiers = "w960dp-h540dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ReturnToStreamUiTest {
    private static final String HOST_UUID = "return-host";
    private static final int APP_ID = 101;

    private Context context;
    private ComputerManagerService.ComputerManagerBinder binder;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static void resetProfiles() throws Exception {
        Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        resetProfiles();
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        UiState.prefs(context).edit().clear().commit();

        // Cached GL renderer so PcView skips the GLSurfaceView probe
        SharedPreferences glPrefs = context.getSharedPreferences("GlPreferences", 0);
        glPrefs.edit().putString("Renderer", "TestRenderer").putString("Fingerprint", Build.FINGERPRINT).commit();
        Shadows.shadowOf((Application) context).setSystemService(Context.GAME_SERVICE, mock(GameManager.class));

        ComputerDetails computer = host(APP_ID);
        binder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.getComputer(HOST_UUID)).thenReturn(computer);
        when(binder.getUniqueId()).thenReturn("0123456789ABCDEF");
        when(binder.createAppListPoller(any(ComputerDetails.class)))
                .thenReturn(mock(ComputerManagerService.ApplistPoller.class));
        Shadows.shadowOf((Application) context).setComponentNameAndServiceForBindService(
                new ComponentName(context, ComputerManagerService.class), binder);
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    private static ComputerDetails host(int runningGameId) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = HOST_UUID;
        details.name = "Example PC";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairingManager.PairState.PAIRED;
        details.activeAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);
        details.httpsPort = 47984;
        details.runningGameId = runningGameId;
        return details;
    }

    /** A stream that went to the background some time ago (before the screen came back). */
    private void backgroundStopMinutesAgo(int minutes) {
        UiState.prefs(context).edit()
                .putString("return_pc_uuid", HOST_UUID)
                .putInt("return_app_id", APP_ID)
                .putString("return_app_name", "Example Game")
                .putLong("return_time", System.currentTimeMillis() - minutes * 60_000L)
                .commit();
    }

    private ComputerManagerListener listener() {
        ArgumentCaptor<ComputerManagerListener> captor = ArgumentCaptor.forClass(ComputerManagerListener.class);
        verify(binder, timeout(5000).atLeastOnce()).startPolling(captor.capture());
        return captor.getValue();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private AppView appView() {
        Intent intent = new Intent(context, AppView.class);
        intent.putExtra(AppView.NAME_EXTRA, "Example PC");
        intent.putExtra(AppView.UUID_EXTRA, HOST_UUID);
        return Robolectric.buildActivity(AppView.class, intent).setup().get();
    }

    private static Intent nextGame(Activity activity) {
        Intent intent;
        while ((intent = shadowOf(activity).getNextStartedActivity()) != null) {
            if (intent.getComponent() != null && intent.getComponent().getClassName().equals(Game.class.getName())) {
                return intent;
            }
        }
        return null;
    }

    private void assertGameFor(Intent game) {
        assertNotNull("Game was started", game);
        assertEquals(APP_ID, game.getIntExtra(Game.EXTRA_APP_ID, 0));
        assertEquals(HOST_UUID, game.getStringExtra(Game.EXTRA_PC_UUID));
        assertEquals("Example Game", game.getStringExtra(Game.EXTRA_APP_NAME));
        assertEquals("192.168.1.20", game.getStringExtra(Game.EXTRA_HOST));
        assertEquals("0123456789ABCDEF", game.getStringExtra(Game.EXTRA_UNIQUEID));
        assertNull("Forgotten before the launch", UiState.prefs(context).getString("return_pc_uuid", null));
    }

    @Test
    public void theAppListGoesBackIntoAFreshStream() {
        backgroundStopMinutesAgo(5);
        AppView activity = appView();
        listener().notifyComputerUpdated(host(APP_ID));
        idle();
        assertGameFor(nextGame(activity));

        // The next poll starts nothing more
        listener().notifyComputerUpdated(host(APP_ID));
        idle();
        assertNull(nextGame(activity));
    }

    @Test
    public void thePcListGoesBackIntoAFreshStream() {
        backgroundStopMinutesAgo(5);
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        listener().notifyComputerUpdated(host(APP_ID));
        idle();
        assertGameFor(nextGame(activity));
    }

    @Test
    public void noLaunchForAStaleRecord() {
        backgroundStopMinutesAgo(31);
        AppView activity = appView();
        listener().notifyComputerUpdated(host(APP_ID));
        idle();
        assertNull(nextGame(activity));
        assertFalse(UiState.prefs(context).contains("return_pc_uuid"));
    }

    @Test
    public void noLaunchWithTheSettingOff() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceConfiguration.RETURN_TO_STREAM_PREF_STRING, false).commit();
        backgroundStopMinutesAgo(5);
        AppView activity = appView();
        listener().notifyComputerUpdated(host(APP_ID));
        idle();
        assertNull(nextGame(activity));
    }

    @Test
    public void noLaunchWhenTheAppIsNotRunning() {
        backgroundStopMinutesAgo(5);
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        listener().notifyComputerUpdated(host(0));
        idle();
        assertNull(nextGame(activity));
        assertFalse("The PC no longer runs it: forgotten", UiState.prefs(context).contains("return_pc_uuid"));
    }

    @Test
    public void noLaunchWhileTheHostIsOfflineButTheRecordWaits() {
        backgroundStopMinutesAgo(5);
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();
        ComputerDetails offline = host(APP_ID);
        offline.state = ComputerDetails.State.OFFLINE;
        listener().notifyComputerUpdated(offline);
        idle();
        assertNull(nextGame(activity));
        assertTrue(UiState.prefs(context).contains("return_pc_uuid"));
    }
}

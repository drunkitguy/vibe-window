package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.UiState;
import com.limelight.profiles.ProfilesManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class ReturnToStreamTest {
    private static final String PC = "pc-uuid";
    private static final int APP = 42;

    private Context context;

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
    }

    @After
    public void tearDown() throws Exception {
        resetProfiles();
    }

    private static ComputerDetails host(int runningGameId) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = PC;
        details.name = "PC";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairingManager.PairState.PAIRED;
        details.activeAddress = new ComputerDetails.AddressTuple("192.168.1.2", 47989);
        details.runningGameId = runningGameId;
        return details;
    }

    private void remember(long time) {
        ReturnToStream.remember(context, PC, APP, "Game", time);
    }

    // --- When a stream is remembered ---

    private ReturnToStream.StreamTracker stop(boolean connected, boolean endedByUser, boolean error) {
        ReturnToStream.StreamTracker tracker = new ReturnToStream.StreamTracker();
        if (endedByUser) {
            tracker.markEndedByUser();
        }
        tracker.onStop(context, connected, error, PC, APP, "Game");
        return tracker;
    }

    @Test
    public void leavingTheAppIsRemembered() {
        // Game is noHistory: leaving the app (or the screen going off, or closing the mini
        // player) stops it while it is already finishing, still connected and not ended by the user
        stop(true, false, false);
        ReturnToStream.Record record = ReturnToStream.pending(context);
        assertNotNull(record);
        assertEquals(PC, record.pcUuid);
        assertEquals(APP, record.appId);
        assertEquals("Game", record.appName);
        assertTrue(System.currentTimeMillis() - record.time < 10_000);
        // Kept in vw_ui_state, never in the settings (so never in profiles)
        assertTrue(UiState.prefs(context).contains("return_pc_uuid"));
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).contains("return_pc_uuid"));
    }

    @Test
    public void quitDisconnectAndBackForgetTheStream() {
        remember(System.currentTimeMillis());
        // Quit Session, Disconnect and Back mark the stream as ended by the user first
        stop(true, true, false);
        assertNull(ReturnToStream.pending(context));
    }

    @Test
    public void anErrorOrTheHostEndingTheStreamForgetsIt() {
        remember(System.currentTimeMillis());
        // An error or a termination by the host leaves the stream disconnected with a dialog
        stop(false, false, true);
        assertNull(ReturnToStream.pending(context));
        remember(System.currentTimeMillis());
        // A graceful end by the host finishes the activity
        stop(false, true, false);
        assertNull(ReturnToStream.pending(context));
    }

    @Test
    public void aSecondStopAfterTheScreenComesBackOnKeepsTheRecord() {
        ReturnToStream.StreamTracker tracker = new ReturnToStream.StreamTracker();
        // Screen off: the stream stops connected and is remembered...
        tracker.onStop(context, true, false, PC, APP, "Game");
        assertNotNull(ReturnToStream.pending(context));
        // ...then the stop's own finish() and a later stop with nothing running change nothing
        tracker.markEndedByUser();
        tracker.onStop(context, false, true, PC, APP, "Game");
        assertNotNull(ReturnToStream.pending(context));
    }

    @Test
    public void aStopWhileStillConnectingKeepsAnEarlierRecord() {
        remember(System.currentTimeMillis());
        stop(false, false, false);
        assertNotNull(ReturnToStream.pending(context));
    }

    @Test
    public void stopTable() {
        assertEquals(ReturnToStream.StopAction.REMEMBER, ReturnToStream.actionForStop(true, false, false));
        assertEquals(ReturnToStream.StopAction.FORGET, ReturnToStream.actionForStop(true, true, false));
        assertEquals(ReturnToStream.StopAction.FORGET, ReturnToStream.actionForStop(false, true, false));
        assertEquals(ReturnToStream.StopAction.FORGET, ReturnToStream.actionForStop(false, false, true));
        assertEquals(ReturnToStream.StopAction.KEEP, ReturnToStream.actionForStop(false, false, false));
    }

    // --- What a host poll decides ---

    @Test
    public void aFreshRecordForARunningAppLaunches() {
        long now = 1_000_000_000L;
        ReturnToStream.Record record = new ReturnToStream.Record(PC, APP, "Game", now - 60_000);
        assertEquals(ReturnToStream.Action.LAUNCH, ReturnToStream.decide(record, true, host(APP), now, now - 1000));
    }

    @Test
    public void staleOffOrNotRunningForgets() {
        long now = 1_000_000_000L;
        ReturnToStream.Record fresh = new ReturnToStream.Record(PC, APP, "Game", now - 60_000);
        ReturnToStream.Record stale = new ReturnToStream.Record(PC, APP, "Game", now - ReturnToStream.MAX_AGE_MS);
        assertEquals(ReturnToStream.Action.FORGET, ReturnToStream.decide(stale, true, host(APP), now, now));
        assertEquals(ReturnToStream.Action.FORGET, ReturnToStream.decide(fresh, false, host(APP), now, now));
        assertEquals(ReturnToStream.Action.FORGET, ReturnToStream.decide(fresh, true, host(0), now, now));
        assertEquals(ReturnToStream.Action.FORGET, ReturnToStream.decide(fresh, true, host(7), now, now));
    }

    @Test
    public void anOfflineOrOtherHostWaits() {
        long now = 1_000_000_000L;
        ReturnToStream.Record fresh = new ReturnToStream.Record(PC, APP, "Game", now - 60_000);
        ComputerDetails offline = host(APP);
        offline.state = ComputerDetails.State.OFFLINE;
        assertEquals(ReturnToStream.Action.NONE, ReturnToStream.decide(fresh, true, offline, now, now));
        ComputerDetails unknown = host(APP);
        unknown.state = ComputerDetails.State.UNKNOWN;
        assertEquals(ReturnToStream.Action.NONE, ReturnToStream.decide(fresh, true, unknown, now, now));
        ComputerDetails other = host(APP);
        other.uuid = "another-pc";
        assertEquals(ReturnToStream.Action.NONE, ReturnToStream.decide(fresh, true, other, now, now));
        assertEquals(ReturnToStream.Action.NONE, ReturnToStream.decide(null, true, host(APP), now, now));
    }

    @Test
    public void aRecordWrittenWhileTheScreenIsInFrontWaitsForTheNextReturn() {
        long now = 1_000_000_000L;
        // The mini player was closed while the app list was in front
        ReturnToStream.Record record = new ReturnToStream.Record(PC, APP, "Game", now - 1000);
        assertEquals(ReturnToStream.Action.NONE, ReturnToStream.decide(record, true, host(APP), now, now - 5000));
    }

    // --- Taking the record ---

    @Test
    public void takeForgetsTheRecordBeforeLaunching() {
        remember(System.currentTimeMillis() - 60_000);
        NvApp app = ReturnToStream.take(context, host(APP), System.currentTimeMillis());
        assertNotNull(app);
        assertEquals(APP, app.getAppId());
        assertEquals("Game", app.getAppName());
        assertNull("Forgotten before the launch, so a failed launch never repeats", ReturnToStream.pending(context));
        assertNull(ReturnToStream.take(context, host(APP), System.currentTimeMillis()));
    }

    @Test
    public void takeUsesTheAppAsThePcListsIt() {
        remember(System.currentTimeMillis() - 60_000);
        ComputerDetails details = host(APP);
        details.rawAppList = "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">" +
                "<App><AppTitle>Real Name</AppTitle><ID>42</ID><IsHdrSupported>1</IsHdrSupported></App></root>";
        NvApp app = ReturnToStream.take(context, details, System.currentTimeMillis());
        assertNotNull(app);
        assertEquals("Real Name", app.getAppName());
        assertTrue(app.isHdrSupported());
    }

    @Test
    public void takeForgetsAStaleRecordAndDoesNotLaunch() {
        remember(System.currentTimeMillis() - ReturnToStream.MAX_AGE_MS - 1000);
        assertNull(ReturnToStream.take(context, host(APP), System.currentTimeMillis()));
        assertNull(ReturnToStream.pending(context));
    }

    @Test
    public void takeDoesNotLaunchWithTheSettingOff() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceConfiguration.RETURN_TO_STREAM_PREF_STRING, false).commit();
        remember(System.currentTimeMillis() - 60_000);
        assertNull(ReturnToStream.take(context, host(APP), System.currentTimeMillis()));
        assertNull(ReturnToStream.pending(context));
    }

    @Test
    public void theSettingIsOnByDefault() {
        assertTrue(ReturnToStream.isEnabled(context));
        assertTrue(PreferenceConfiguration.readPreferences(context).returnToStream);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PreferenceConfiguration.RETURN_TO_STREAM_PREF_STRING, false).commit();
        assertFalse(PreferenceConfiguration.readPreferences(context).returnToStream);
    }
}

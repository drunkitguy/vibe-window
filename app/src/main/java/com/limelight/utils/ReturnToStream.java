package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.UiState;
import com.limelight.profiles.ProfilesManager;

import java.io.StringReader;
import java.util.List;

/**
 * "Return to stream": a stream that ended because the app went to the background (or its
 * mini player was closed) is remembered in vw_ui_state, and the next time the PC list or
 * app list comes to the front the stream starts again, the same way Quick Resume does,
 * when the PC is online and still runs that app. Any other end of a stream forgets it.
 */
public final class ReturnToStream {
    static final String PC_UUID = "return_pc_uuid";
    static final String APP_ID = "return_app_id";
    static final String APP_NAME = "return_app_name";
    static final String TIME = "return_time";

    /** A record older than this is ignored and forgotten. */
    public static final long MAX_AGE_MS = 30 * 60 * 1000L;

    private ReturnToStream() {}

    /** A stream that went to the background. */
    public static final class Record {
        public final String pcUuid;
        public final int appId;
        public final String appName;
        public final long time;

        Record(String pcUuid, int appId, String appName, long time) {
            this.pcUuid = pcUuid;
            this.appId = appId;
            this.appName = appName;
            this.time = time;
        }
    }

    /** What one poll of a host means for the record. */
    enum Action { NONE, LAUNCH, FORGET }

    private static SharedPreferences store(Context context) {
        return UiState.prefs(context);
    }

    /** What a stopping stream means for the record. */
    enum StopAction { REMEMBER, FORGET, KEEP }

    /**
     * A stream still connected that the user did not end went to the background: the user
     * left the app, the screen went off or the mini player was closed (Game is noHistory, so
     * it is finishing in all of these). The user ending it (Quit Session, Disconnect, Back)
     * or an error or the host ending it forgets the record. A stop with no stream running
     * and nothing ended (still connecting) leaves any record as it is.
     */
    static StopAction actionForStop(boolean connected, boolean endedByUser, boolean endedByError) {
        if (connected && !endedByUser) {
            return StopAction.REMEMBER;
        }
        if (connected || endedByUser || endedByError) {
            return StopAction.FORGET;
        }
        return StopAction.KEEP;
    }

    /**
     * One per stream activity: remembers how the stream ends and handles the record at the
     * first stop only, so a later stop of the same (already ended) activity, for example when
     * the screen comes back on, never clears a record that stop wrote.
     */
    public static final class StreamTracker {
        private boolean endedByUser;
        private boolean handled;

        /** The user ended the stream (Quit Session, Disconnect, Back or any finish() of ours). */
        public void markEndedByUser() {
            endedByUser = true;
        }

        public boolean isEndedByUser() {
            return endedByUser;
        }

        public void onStop(Context context, boolean connected, boolean endedByError,
                           @Nullable String pcUuid, int appId, @Nullable String appName) {
            if (handled) {
                return;
            }
            switch (actionForStop(connected, endedByUser, endedByError)) {
                case REMEMBER:
                    handled = true;
                    if (pcUuid != null && appId > 0) {
                        remember(context, pcUuid, appId, appName, System.currentTimeMillis());
                    }
                    else {
                        forget(context);
                    }
                    break;
                case FORGET:
                    handled = true;
                    forget(context);
                    break;
                default:
                    break;
            }
        }
    }

    static synchronized void remember(Context context, String pcUuid, int appId, @Nullable String appName, long time) {
        store(context).edit()
                .putString(PC_UUID, pcUuid)
                .putInt(APP_ID, appId)
                .putString(APP_NAME, appName != null ? appName : "")
                .putLong(TIME, time)
                .commit();
    }

    public static synchronized void forget(Context context) {
        SharedPreferences prefs = store(context);
        if (prefs.contains(PC_UUID) || prefs.contains(TIME)) {
            prefs.edit().remove(PC_UUID).remove(APP_ID).remove(APP_NAME).remove(TIME).commit();
        }
    }

    @Nullable
    public static synchronized Record pending(Context context) {
        SharedPreferences prefs = store(context);
        String pcUuid = prefs.getString(PC_UUID, null);
        if (pcUuid == null) {
            return null;
        }
        return new Record(pcUuid, prefs.getInt(APP_ID, 0), prefs.getString(APP_NAME, ""), prefs.getLong(TIME, 0));
    }

    /** The Return to stream setting, with the active profile applied. */
    public static boolean isEnabled(Context context) {
        return ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
                .getBoolean(PreferenceConfiguration.RETURN_TO_STREAM_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_RETURN_TO_STREAM);
    }

    /**
     * @param resumedAt when the screen asking came to the front: a record written after that
     *                  (a mini player closed while the screen was in front) waits for the next
     *                  return to the app
     */
    static Action decide(@Nullable Record record, boolean enabled, ComputerDetails details,
                         long now, long resumedAt) {
        if (record == null) {
            return Action.NONE;
        }
        if (!enabled || now - record.time >= MAX_AGE_MS || record.time > now) {
            return Action.FORGET;
        }
        if (record.time > resumedAt) {
            return Action.NONE;
        }
        if (details == null || details.uuid == null || !details.uuid.equalsIgnoreCase(record.pcUuid)) {
            return Action.NONE;
        }
        if (details.state != ComputerDetails.State.ONLINE ||
                details.pairState != PairingManager.PairState.PAIRED ||
                details.activeAddress == null) {
            // Not reachable yet: the record stays until it is too old
            return Action.NONE;
        }
        if (details.runningGameId != record.appId) {
            // The PC no longer runs that app
            return Action.FORGET;
        }
        return Action.LAUNCH;
    }

    /**
     * Called with each poll of a host while the PC list or app list is in front. Forgets a
     * record that can no longer be used. When the stream can be resumed, forgets the record
     * first (so a failed launch never repeats) and returns the app to start; null otherwise.
     */
    @Nullable
    public static NvApp take(Context context, ComputerDetails details, long resumedAt) {
        Record record;
        synchronized (ReturnToStream.class) {
            record = pending(context);
            if (record == null) {
                return null;
            }
            switch (decide(record, isEnabled(context), details, System.currentTimeMillis(), resumedAt)) {
                case FORGET:
                    forget(context);
                    return null;
                case LAUNCH:
                    forget(context);
                    break;
                default:
                    return null;
            }
        }
        return appFor(record, details);
    }

    /** The app as the PC lists it (for its HDR support), or one made from the record. */
    static NvApp appFor(Record record, ComputerDetails details) {
        if (details.rawAppList != null) {
            try {
                List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(details.rawAppList));
                for (NvApp app : apps) {
                    if (app.getAppId() == record.appId) {
                        return app;
                    }
                }
            } catch (Exception e) {
                // Use the record below
            }
        }
        return new NvApp(record.appName, null, record.appId, false);
    }

    /** Starts the stream as Quick Resume does, with a short notice. Call on the UI thread. */
    public static void launch(Activity activity, NvApp app, ComputerDetails details,
                              ComputerManagerService.ComputerManagerBinder managerBinder,
                              boolean withVDisplay) {
        Toast.makeText(activity, activity.getString(R.string.return_to_stream_toast, app.getAppName()),
                Toast.LENGTH_SHORT).show();
        ServerHelper.doStart(activity, app, details, managerBinder, withVDisplay);
    }
}

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

    /**
     * Whether a stream that stops went to the background: it was still connected (not ended
     * by an error or the host) and the activity was not finishing (a quit, disconnect or back
     * finishes it), or its mini player was closed.
     */
    static boolean wentToBackground(boolean connected, boolean finishing, boolean inPip) {
        return connected && (!finishing || inPip);
    }

    /**
     * Called by the stream when it stops: remembers it when the app only went to the
     * background, forgets any record otherwise (quit, disconnect, back, an error, the host
     * ending the stream).
     */
    public static void onStreamStopped(Context context, boolean connected, boolean finishing, boolean inPip,
                                       @Nullable String pcUuid, int appId, @Nullable String appName) {
        if (wentToBackground(connected, finishing, inPip) && pcUuid != null && appId > 0) {
            remember(context, pcUuid, appId, appName, System.currentTimeMillis());
        }
        else {
            forget(context);
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

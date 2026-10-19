package com.limelight.utils;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

/**
 * "Keep Wi-Fi awake while streaming": sends a tiny packet about once per frame from a
 * background thread, so the Wi-Fi radio does not doze and the main thread is not woken every
 * few milliseconds. The Wi-Fi transport is checked again once a second (a binder call), and
 * nothing is sent while the active network is not Wi-Fi. The thread ends itself once the
 * stream is no longer active.
 */
public final class WifiKeepalive {
    /** Sends one keepalive packet. */
    public interface Sender {
        void send();
    }

    /** Whether the stream is still running. */
    public interface ActiveCheck {
        boolean isActive();
    }

    static final long WIFI_RECHECK_MS = 1000;

    private final Sender sender;
    private final ActiveCheck activeCheck;

    private HandlerThread thread;
    private volatile Handler handler;
    private Context appContext;
    private int periodMs;
    private long lastWifiCheckMs;
    private boolean onWifi;

    public WifiKeepalive(Sender sender, ActiveCheck activeCheck) {
        this.sender = sender;
        this.activeCheck = activeCheck;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!tickOnce()) {
                return;
            }
            Handler h = handler;
            if (h != null) {
                h.postDelayed(this, periodMs);
            }
        }
    };

    /**
     * One keepalive tick: stops when the stream ended, re-checks the transport once a second
     * and sends on Wi-Fi only.
     *
     * @return false once stopped
     */
    boolean tickOnce() {
        if (!activeCheck.isActive()) {
            stop();
            return false;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastWifiCheckMs >= WIFI_RECHECK_MS) {
            lastWifiCheckMs = now;
            onWifi = NetHelper.isActiveNetworkWifi(appContext);
        }
        if (onWifi) {
            sender.send();
        }
        return true;
    }

    /**
     * Starts sending after {@code initialDelayMs}, every {@link NetHelper#keepalivePeriodMs}
     * for the stream frame rate. Does nothing if it is already running.
     */
    public synchronized void start(Context context, float fps, long initialDelayMs) {
        if (thread != null) {
            return;
        }
        appContext = context.getApplicationContext();
        periodMs = NetHelper.keepalivePeriodMs(fps);
        lastWifiCheckMs = SystemClock.uptimeMillis();
        onWifi = NetHelper.isActiveNetworkWifi(appContext);

        thread = new HandlerThread("Wi-Fi keepalive", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(tick, initialDelayMs);
    }

    /** Stops sending and ends the thread. Safe to call at any time. */
    public synchronized void stop() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    public synchronized boolean isRunning() {
        return thread != null;
    }

    /** The send period in use, in milliseconds. */
    public synchronized int getPeriodMs() {
        return periodMs;
    }
}

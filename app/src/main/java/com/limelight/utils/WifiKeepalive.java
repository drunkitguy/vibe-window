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
 * nothing is sent while the active network is not Wi-Fi.
 *
 * The owner must call {@link #stopAndWait()} before tearing down the connection: it returns
 * only once no packet is being sent, and no packet is sent after it returns. One instance
 * serves one stream: once stopped it is closed, and a later {@link #start} (for example a
 * queued "connection started" callback that runs after the stop) does nothing.
 */
public final class WifiKeepalive {
    /** Sends one keepalive packet. */
    public interface Sender {
        void send();
    }

    static final long WIFI_RECHECK_MS = 1000;
    static final long STOP_JOIN_TIMEOUT_MS = 500;

    private final Sender sender;
    // Held for a tick's check and send; stopAndWait() takes it to wait out a send in progress
    private final Object tickLock = new Object();

    private volatile boolean active;
    private boolean closed;
    private volatile Handler handler;
    private HandlerThread thread;
    private Context appContext;
    private int periodMs;
    private long lastWifiCheckMs;
    private boolean onWifi;

    public WifiKeepalive(Sender sender) {
        this.sender = sender;
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
     * One keepalive tick: re-checks the transport once a second and sends on Wi-Fi only.
     *
     * @return false once stopped
     */
    boolean tickOnce() {
        synchronized (tickLock) {
            if (!active) {
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
    }

    /**
     * Starts sending after {@code initialDelayMs}, every {@code periodMs} (see
     * {@link NetHelper#keepalivePeriodMs(float, boolean)}). Does nothing if it is already
     * running or was stopped.
     */
    public synchronized void start(Context context, int periodMs, long initialDelayMs) {
        if (thread != null || closed) {
            return;
        }
        appContext = context.getApplicationContext();
        synchronized (tickLock) {
            this.periodMs = Math.max(1, periodMs);
            lastWifiCheckMs = SystemClock.uptimeMillis();
            onWifi = NetHelper.isActiveNetworkWifi(appContext);
            active = true;
        }

        // Display priority: a late keepalive is a late wake-up of the Wi-Fi radio
        thread = new HandlerThread("Wi-Fi keepalive", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        Handler h = new Handler(thread.getLooper());
        handler = h;
        h.postDelayed(tick, initialDelayMs);
    }

    /**
     * Stops sending and ends the thread. When this returns, no send is in progress and none
     * will follow. Safe to call at any time and more than once.
     */
    public void stopAndWait() {
        HandlerThread oldThread;
        synchronized (this) {
            closed = true;
            active = false;
            Handler h = handler;
            handler = null;
            if (h != null) {
                h.removeCallbacksAndMessages(null);
            }
            oldThread = thread;
            thread = null;
        }

        // Wait for a tick that is sending right now; later ticks see active == false
        synchronized (tickLock) {
            // Nothing to do: taking the lock is the wait
        }

        if (oldThread != null) {
            oldThread.quitSafely();
            if (oldThread != Thread.currentThread()) {
                try {
                    oldThread.join(STOP_JOIN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public synchronized boolean isRunning() {
        return thread != null;
    }

    /** The send period in use, in milliseconds. */
    public int getPeriodMs() {
        synchronized (tickLock) {
            return periodMs;
        }
    }
}

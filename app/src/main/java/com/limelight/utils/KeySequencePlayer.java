package com.limelight.utils;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a key sequence (see KeyConfigHelper.Step) on the main thread: one key combination
 * at a time, KeyConfigHelper.DEFAULT_GAP_MS apart plus the waits in between. Each
 * combination goes to the sender as a whole; the sender presses it and releases every key
 * it pressed (Game.sendKeys releases after GameMenu.KEY_UP_DELAY), so cancelling or the
 * activity finishing between two combinations never leaves a key held. The sequence starts
 * once the stream window has the focus again (the menu dialog has closed) and stops when the
 * activity finishes or the sequence is cancelled.
 */
public final class KeySequencePlayer {
    /** How often the player checks for the stream window's focus before the first combination. */
    public static final long FOCUS_POLL_MS = 10;
    /** Gives up when the stream window does not get the focus back within this time. */
    public static final long FOCUS_TIMEOUT_MS = 3000;

    /** Presses a key combination and releases all its keys again. */
    public interface ChordSender {
        void send(short[] chord);
    }

    public interface Condition {
        boolean isTrue();
    }

    private final Handler handler;
    private final ChordSender sender;
    private final Condition alive;
    private final Condition focused;
    // Identifies the callbacks of the running sequence for cancel()
    private Object token;

    /**
     * @param handler a main-thread handler
     * @param alive   false once the activity is finishing; nothing is sent after that
     * @param focused whether the stream window has the focus
     */
    public KeySequencePlayer(Handler handler, ChordSender sender, Condition alive, Condition focused) {
        this.handler = handler;
        this.sender = sender;
        this.alive = alive;
        this.focused = focused;
    }

    /**
     * Whether a sequence may start: with the menu on the stream's own screen, once the stream
     * window has the focus again; with the menu on another screen (external display control),
     * once the menu dialog is no longer showing. Mirrors GameMenu.runWithGameFocus.
     */
    public static boolean streamReady(boolean menuOnStreamScreen, boolean streamWindowFocused, boolean menuShowing) {
        return menuOnStreamScreen ? streamWindowFocused : !menuShowing;
    }

    public boolean isPlaying() {
        return token != null;
    }

    /** Stops the running sequence; combinations already sent are released by the sender. */
    public void cancel() {
        if (token != null) {
            handler.removeCallbacksAndMessages(token);
            token = null;
        }
    }

    /** Cancels a running sequence and starts this one once the stream window has the focus. */
    public void play(List<KeyConfigHelper.Step> steps) {
        cancel();
        final Object run = new Object();
        token = run;
        final List<short[]> chords = new ArrayList<>();
        for (KeyConfigHelper.Step step : steps) {
            if (!step.isWait()) {
                chords.add(step.chord);
            }
        }
        final long[] times = KeyConfigHelper.sendTimesMs(steps);
        waitForFocus(run, chords, times, 0);
    }

    private void waitForFocus(final Object run, final List<short[]> chords, final long[] times, final long waited) {
        if (token != run) {
            return;
        }
        if (!alive.isTrue()) {
            token = null;
            return;
        }
        if (!focused.isTrue()) {
            if (waited >= FOCUS_TIMEOUT_MS) {
                token = null;
                return;
            }
            handler.postAtTime(new Runnable() {
                @Override
                public void run() {
                    waitForFocus(run, chords, times, waited + FOCUS_POLL_MS);
                }
            }, run, android.os.SystemClock.uptimeMillis() + FOCUS_POLL_MS);
            return;
        }
        long start = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < chords.size(); i++) {
            final short[] chord = chords.get(i);
            final boolean last = i == chords.size() - 1;
            Runnable send = new Runnable() {
                @Override
                public void run() {
                    if (token != run) {
                        return;
                    }
                    if (!alive.isTrue()) {
                        cancel();
                        return;
                    }
                    sender.send(chord);
                    if (last) {
                        token = null;
                    }
                }
            };
            if (times[i] == 0) {
                send.run();
            }
            else {
                handler.postAtTime(send, run, start + times[i]);
            }
        }
    }
}

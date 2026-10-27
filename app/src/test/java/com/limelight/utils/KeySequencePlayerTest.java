package com.limelight.utils;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KeySequencePlayerTest {

    private static final long KEY_UP_DELAY = 25;

    /** Records presses and releases the way Game.sendKeys does: press now, release 25 ms later. */
    private final List<String> events = new ArrayList<>();
    private final List<Long> sendTimes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean alive = true;
    private boolean focused = true;
    private long start;
    private KeySequencePlayer player;

    @Before
    public void setUp() {
        player = new KeySequencePlayer(handler, chord -> {
            sendTimes.add(SystemClock.uptimeMillis() - start);
            for (short key : chord) {
                events.add("down " + key);
            }
            handler.postDelayed(() -> {
                for (int i = chord.length - 1; i >= 0; i--) {
                    events.add("up " + chord[i]);
                }
            }, KEY_UP_DELAY);
        }, () -> alive, () -> focused);
        start = SystemClock.uptimeMillis();
    }

    private static List<KeyConfigHelper.Step> sample() {
        KeyConfigHelper.Shortcut sc = new KeyConfigHelper.Shortcut();
        sc.name = "Open";
        sc.steps = Arrays.asList("VK_LCONTROL+VK_OEM_COMMA", "wait:1000", "VK_LSHIFT+VK_TAB", "VK_LSHIFT+VK_TAB", "VK_END");
        return KeyConfigHelper.parseSteps(sc);
    }

    private static void advance(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void assertEveryKeyReleased() {
        int held = 0;
        for (String event : events) {
            held += event.startsWith("down") ? 1 : -1;
        }
        assertEquals("Every pressed key is released: " + events, 0, held);
    }

    @Test
    public void combinationsGoOutInOrderAtTheirTimes() {
        player.play(sample());
        advance(2000);
        assertEquals(Arrays.asList(0L, 1150L, 1300L, 1450L), sendTimes);
        assertEquals(Arrays.asList(
                "down " + 0xA2, "down " + 0xBC, "up " + 0xBC, "up " + 0xA2,
                "down " + 0xA0, "down " + 0x09, "up " + 0x09, "up " + 0xA0,
                "down " + 0xA0, "down " + 0x09, "up " + 0x09, "up " + 0xA0,
                "down " + 0x23, "up " + 0x23), events);
        assertFalse(player.isPlaying());
    }

    @Test
    public void cancelStopsTheRestAndReleasesWhatWasPressed() {
        player.play(sample());
        advance(1160);
        assertEquals(2, sendTimes.size());
        player.cancel();
        assertFalse(player.isPlaying());
        advance(2000);
        assertEquals(2, sendTimes.size());
        assertEveryKeyReleased();
    }

    @Test
    public void aFinishingActivityGetsNothingMoreAndNoKeyStaysHeld() {
        player.play(sample());
        advance(10);
        alive = false;
        advance(2000);
        assertEquals("Only the first combination went out", 1, sendTimes.size());
        assertEveryKeyReleased();
        assertFalse(player.isPlaying());
    }

    @Test
    public void theSequenceWaitsForTheStreamWindowFocus() {
        focused = false;
        player.play(sample());
        advance(500);
        assertTrue(sendTimes.isEmpty());
        assertTrue(player.isPlaying());
        focused = true;
        start = SystemClock.uptimeMillis();
        advance(2000);
        assertEquals(4, sendTimes.size());
        assertTrue("Starts within one focus check", sendTimes.get(0) <= KeySequencePlayer.FOCUS_POLL_MS);
        assertEveryKeyReleased();
    }

    @Test
    public void withoutTheFocusTheSequenceGivesUp() {
        focused = false;
        player.play(sample());
        advance(KeySequencePlayer.FOCUS_TIMEOUT_MS + 500);
        assertFalse(player.isPlaying());
        focused = true;
        advance(2000);
        assertTrue(sendTimes.isEmpty());
    }

    @Test
    public void playingAgainReplacesTheRunningSequence() {
        player.play(sample());
        advance(10);
        player.play(sample());
        start = SystemClock.uptimeMillis();
        advance(2000);
        assertEquals(1 + 4, sendTimes.size());
        assertEveryKeyReleased();
    }

    @Test
    public void readinessFollowsWhereTheMenuIsShown() {
        // Menu on the stream's own screen: wait for the stream window's focus
        assertTrue(KeySequencePlayer.streamReady(true, true, false));
        assertFalse(KeySequencePlayer.streamReady(true, false, false));
        // Menu on another screen (external display control): the stream window never gets
        // the focus back from it, so wait for the menu dialog to close
        assertTrue(KeySequencePlayer.streamReady(false, false, false));
        assertFalse(KeySequencePlayer.streamReady(false, false, true));
    }

    @Test
    public void onAnExternalDisplayTheSequenceStartsOnceTheMenuCloses() {
        final boolean[] menuShowing = {true};
        player = new KeySequencePlayer(handler, chord -> sendTimes.add(SystemClock.uptimeMillis() - start),
                () -> alive, () -> KeySequencePlayer.streamReady(false, false, menuShowing[0]));
        player.play(sample());
        advance(300);
        assertTrue(sendTimes.isEmpty());
        menuShowing[0] = false;
        advance(2000);
        assertEquals(4, sendTimes.size());
    }
}

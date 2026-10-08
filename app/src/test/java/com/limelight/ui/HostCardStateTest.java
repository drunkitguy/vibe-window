package com.limelight.ui;

import com.limelight.nvstream.http.ComputerDetails.State;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Every row of the host card state table. */
public class HostCardStateTest {

    @Test
    public void onlinePairedIdle() {
        HostCardState s = HostCardState.from(State.ONLINE, PairState.PAIRED, 0, true);
        assertEquals(HostCardState.Kind.ONLINE_IDLE, s.kind);
        assertEquals(HostCardState.Tile.ACCENT, s.tile);
        assertEquals(1.0f, s.glyphAlpha, 0.001f);
        assertTrue(s.glyphVisible);
        assertTrue(s.glow);
        assertFalse(s.spinner);
        assertEquals(HostCardState.Badge.NONE, s.badge);
        assertEquals(HostCardState.StatusIcon.DOT_SUCCESS, s.statusIcon);
        assertEquals(HostCardState.StatusText.ONLINE, s.statusText);
        assertEquals(HostCardState.PillStyle.FILLED, s.pillStyle);
        assertEquals(HostCardState.PillIcon.PLAY, s.pillIcon);
        assertEquals(HostCardState.PillText.OPEN, s.pillText);
        assertEquals(HostCardState.Action.APP_LIST, s.action);
    }

    @Test
    public void onlinePairedWithAppRunning() {
        HostCardState s = HostCardState.from(State.ONLINE, PairState.PAIRED, 42, false);
        assertEquals(HostCardState.Kind.ONLINE_RUNNING, s.kind);
        assertEquals(HostCardState.Tile.ACCENT, s.tile);
        assertTrue(s.glow);
        assertEquals(HostCardState.StatusIcon.DOT_SUCCESS, s.statusIcon);
        assertEquals(HostCardState.StatusText.STREAMING, s.statusText);
        assertEquals(HostCardState.PillStyle.FILLED, s.pillStyle);
        assertEquals(HostCardState.PillIcon.PLAY, s.pillIcon);
        assertEquals(HostCardState.PillText.RESUME, s.pillText);
        // Resume still opens the app list, as before
        assertEquals(HostCardState.Action.APP_LIST, s.action);
    }

    @Test
    public void onlineNotPaired() {
        for (PairState pairState : new PairState[] {PairState.NOT_PAIRED, PairState.FAILED, null}) {
            HostCardState s = HostCardState.from(State.ONLINE, pairState, 0, true);
            assertEquals(HostCardState.Kind.NOT_PAIRED, s.kind);
            assertEquals(HostCardState.Tile.TONAL, s.tile);
            assertEquals(0.63f, s.glyphAlpha, 0.001f);
            assertEquals(HostCardState.Badge.LOCK, s.badge);
            assertFalse(s.glow);
            assertEquals(HostCardState.StatusIcon.DOT_WARNING, s.statusIcon);
            assertEquals(HostCardState.StatusText.NOT_PAIRED, s.statusText);
            assertEquals(HostCardState.PillStyle.TONAL, s.pillStyle);
            assertEquals(HostCardState.PillIcon.LOCK_OPEN, s.pillIcon);
            assertEquals(HostCardState.PillText.PAIR, s.pillText);
            assertEquals(HostCardState.Action.PAIR, s.action);
        }
    }

    @Test
    public void offlineWithMacAddress() {
        HostCardState s = HostCardState.from(State.OFFLINE, PairState.PAIRED, 0, true);
        assertEquals(HostCardState.Kind.OFFLINE, s.kind);
        assertEquals(HostCardState.Tile.LOW, s.tile);
        assertEquals(0.40f, s.glyphAlpha, 0.001f);
        assertFalse(s.glow);
        assertEquals(HostCardState.StatusIcon.ICON_WARNING, s.statusIcon);
        assertEquals(HostCardState.StatusText.OFFLINE, s.statusText);
        assertEquals(HostCardState.PillStyle.TONAL, s.pillStyle);
        assertEquals(HostCardState.PillIcon.POWER, s.pillIcon);
        assertEquals(HostCardState.PillText.WAKE, s.pillText);
        assertEquals(HostCardState.Action.CONTEXT_MENU, s.action);
    }

    @Test
    public void offlineWithoutMacAddress() {
        HostCardState s = HostCardState.from(State.OFFLINE, PairState.PAIRED, 0, false);
        assertEquals(HostCardState.Kind.OFFLINE, s.kind);
        assertEquals(HostCardState.PillStyle.NEUTRAL, s.pillStyle);
        assertEquals(HostCardState.PillText.OPTIONS, s.pillText);
        assertEquals(HostCardState.Action.CONTEXT_MENU, s.action);
    }

    @Test
    public void unknownOrRefreshing() {
        for (State state : new State[] {State.UNKNOWN, null}) {
            HostCardState s = HostCardState.from(state, PairState.PAIRED, 0, true);
            assertEquals(HostCardState.Kind.UNKNOWN, s.kind);
            assertEquals(HostCardState.Tile.TONAL, s.tile);
            assertTrue(s.spinner);
            assertFalse(s.glyphVisible);
            assertFalse(s.glow);
            assertEquals(HostCardState.StatusIcon.ICON_SENSORS, s.statusIcon);
            assertEquals(HostCardState.StatusText.CHECKING, s.statusText);
            assertEquals(HostCardState.PillStyle.TONAL, s.pillStyle);
            assertEquals(HostCardState.PillText.OPTIONS, s.pillText);
            assertEquals(HostCardState.Action.CONTEXT_MENU, s.action);
        }
    }

    @Test
    public void runningGameIgnoredWhenNotOnline() {
        HostCardState s = HostCardState.from(State.OFFLINE, PairState.PAIRED, 7, true);
        assertEquals(HostCardState.Kind.OFFLINE, s.kind);
    }
}

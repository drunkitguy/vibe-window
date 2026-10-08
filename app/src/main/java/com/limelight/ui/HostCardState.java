package com.limelight.ui;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

/**
 * What a host card on the PC list shows for a given host state. Pure Java so the
 * state table can be unit tested; the adapter maps the values to resources.
 */
public final class HostCardState {

    /** The five rows of the host card state table. */
    public enum Kind { ONLINE_IDLE, ONLINE_RUNNING, NOT_PAIRED, OFFLINE, UNKNOWN }

    /** Fill of the icon tile behind the computer glyph. */
    public enum Tile { ACCENT, TONAL, LOW }

    /** Small badge drawn on the bottom right of the icon tile. */
    public enum Badge { NONE, LOCK, OFFLINE }

    /** Leading indicator of the status line. */
    public enum StatusIcon { DOT_SUCCESS, DOT_WARNING, ICON_WARNING, ICON_SENSORS }

    public enum StatusText { ONLINE, STREAMING, NOT_PAIRED, OFFLINE, CHECKING }

    public enum SecondaryText { LIBRARY, PAIR_TO_BROWSE, WAKE_AVAILABLE, MORE_OPTIONS, REFRESHING }

    /** Look of the action pill; it is a label for what A or a tap does, not a separate button. */
    public enum PillStyle { FILLED, TONAL, NEUTRAL }

    public enum PillIcon { PLAY, LOCK_OPEN, POWER, MORE }

    public enum PillText { OPEN, RESUME, PAIR, WAKE, OPTIONS }

    /** What A or a tap on the card does; matches the item click rules of the PC list. */
    public enum Action { APP_LIST, PAIR, CONTEXT_MENU }

    public static final float GLYPH_ALPHA_FULL = 1.0f;
    public static final float GLYPH_ALPHA_NOT_PAIRED = 0.63f;
    public static final float GLYPH_ALPHA_OFFLINE = 0.40f;

    public final Kind kind;
    public final Tile tile;
    public final float glyphAlpha;
    public final boolean glyphVisible;
    public final Badge badge;
    public final boolean spinner;
    public final boolean glow;
    public final StatusIcon statusIcon;
    public final StatusText statusText;
    public final SecondaryText secondaryText;
    public final PillStyle pillStyle;
    public final PillIcon pillIcon;
    public final PillText pillText;
    public final Action action;

    private HostCardState(Kind kind, Tile tile, float glyphAlpha, boolean glyphVisible, Badge badge,
                          boolean spinner, boolean glow, StatusIcon statusIcon, StatusText statusText,
                          SecondaryText secondaryText, PillStyle pillStyle, PillIcon pillIcon,
                          PillText pillText, Action action) {
        this.kind = kind;
        this.tile = tile;
        this.glyphAlpha = glyphAlpha;
        this.glyphVisible = glyphVisible;
        this.badge = badge;
        this.spinner = spinner;
        this.glow = glow;
        this.statusIcon = statusIcon;
        this.statusText = statusText;
        this.secondaryText = secondaryText;
        this.pillStyle = pillStyle;
        this.pillIcon = pillIcon;
        this.pillText = pillText;
        this.action = action;
    }

    public static HostCardState from(ComputerDetails.State state, PairingManager.PairState pairState,
                                     int runningGameId, boolean hasMacAddress) {
        if (state == ComputerDetails.State.OFFLINE) {
            return new HostCardState(Kind.OFFLINE, Tile.LOW, GLYPH_ALPHA_OFFLINE, true, Badge.OFFLINE,
                    false, false, StatusIcon.ICON_WARNING, StatusText.OFFLINE,
                    hasMacAddress ? SecondaryText.WAKE_AVAILABLE : SecondaryText.MORE_OPTIONS,
                    hasMacAddress ? PillStyle.TONAL : PillStyle.NEUTRAL,
                    hasMacAddress ? PillIcon.POWER : PillIcon.MORE,
                    hasMacAddress ? PillText.WAKE : PillText.OPTIONS,
                    Action.CONTEXT_MENU);
        }
        if (state != ComputerDetails.State.ONLINE) {
            // Unknown or refreshing
            return new HostCardState(Kind.UNKNOWN, Tile.TONAL, GLYPH_ALPHA_FULL, false, Badge.NONE,
                    true, false, StatusIcon.ICON_SENSORS, StatusText.CHECKING,
                    SecondaryText.REFRESHING, PillStyle.TONAL, PillIcon.MORE, PillText.OPTIONS,
                    Action.CONTEXT_MENU);
        }
        if (pairState != PairingManager.PairState.PAIRED) {
            return new HostCardState(Kind.NOT_PAIRED, Tile.TONAL, GLYPH_ALPHA_NOT_PAIRED, true, Badge.LOCK,
                    false, false, StatusIcon.DOT_WARNING, StatusText.NOT_PAIRED,
                    SecondaryText.PAIR_TO_BROWSE, PillStyle.TONAL, PillIcon.LOCK_OPEN, PillText.PAIR,
                    Action.PAIR);
        }
        if (runningGameId != 0) {
            return new HostCardState(Kind.ONLINE_RUNNING, Tile.ACCENT, GLYPH_ALPHA_FULL, true, Badge.NONE,
                    false, true, StatusIcon.DOT_SUCCESS, StatusText.STREAMING,
                    SecondaryText.LIBRARY, PillStyle.FILLED, PillIcon.PLAY, PillText.RESUME,
                    Action.APP_LIST);
        }
        return new HostCardState(Kind.ONLINE_IDLE, Tile.ACCENT, GLYPH_ALPHA_FULL, true, Badge.NONE,
                false, true, StatusIcon.DOT_SUCCESS, StatusText.ONLINE,
                SecondaryText.LIBRARY, PillStyle.FILLED, PillIcon.PLAY, PillText.OPEN,
                Action.APP_LIST);
    }
}

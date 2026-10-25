package com.limelight.ui;

import android.content.res.Configuration;

/**
 * When the PC list shows Settings beside it, and how wide the Settings panel is. Pure Java
 * so it can be unit tested.
 */
public final class SplitLayout {
    /** Narrowest window (in dp) that shows Settings beside the PC list. */
    public static final int MIN_WIDTH_DP = 720;
    public static final int PANEL_MIN_DP = 340;
    public static final int PANEL_MAX_DP = 460;
    public static final float PANEL_SHARE = 0.42f;

    private SplitLayout() {}

    public static boolean useSplit(int orientation, int screenWidthDp) {
        return orientation == Configuration.ORIENTATION_LANDSCAPE && screenWidthDp >= MIN_WIDTH_DP;
    }

    public static int panelWidthDp(int screenWidthDp) {
        int share = Math.round(screenWidthDp * PANEL_SHARE);
        return Math.max(PANEL_MIN_DP, Math.min(PANEL_MAX_DP, share));
    }
}

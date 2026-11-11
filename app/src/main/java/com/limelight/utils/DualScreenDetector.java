package com.limelight.utils;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Dual screen: decides from the decoded picture whether the app shows the two-window layout
 * (DUAL: DualScreenLayout's split over both displays) or anything else (SINGLE: the content
 * alone on the main display, the second display black). check(), pending(), reset() and giveUp()
 * run on the renderer's GL thread, check() a few times per second; view() is safe from any thread.
 *
 * shortcut: the stream stays the 1920x2160 composite for every app, so a window that fills the
 * virtual display is a 960x1080 picture on a 1920x1080 display. Upgrade when the stream mode can
 * follow the app (a host or protocol change).
 */
public final class DualScreenDetector {
    // One grid cell covers CELL x CELL video pixels, so window edges on multiples of CELL stay exact
    public static final int CELL = 4;
    public static final int GRID_W = DualScreenLayout.VIDEO_W / CELL;
    public static final int GRID_H = DualScreenLayout.VIDEO_H / CELL;
    // A cell has content when a colour channel is above this (0..1): the host paints pure black
    // around the windows and the codec adds noise to it.
    // shortcut: a dark window is black too. The TotK companion's canvas is 14/255 and its panel
    // fill 23/255 (packages/TearsOfTheKingdom/dualscreen/manifest.json "background", docs/PLAN.md
    // H3d), so only its frames, text and icons are content, and its "Companion unavailable" cover
    // (two labels, at most 420 px tall) never splits. Upgrade, to a threshold under 14/255, when
    // the Thor has shown what the encoder leaves on the host's black beside a bright window edge.
    public static final float BLACK_THRESHOLD = 24f / 255;
    public static final int CHECK_INTERVAL_MS = 250;

    // Debounce, in consecutive checks with content (CHECK_INTERVAL_MS apart)
    static final int DUAL_CHECKS = 3;
    static final int DEAD_ZONE_CHECKS = 3;
    static final int NO_BOTTOM_CHECKS = 8;
    static final int BOX_CHECKS = 3;
    // A crop holds a window when its content spans at least WINDOW_NUM/WINDOW_DEN of the crop's
    // width and of its height. More than 1240/1920 of the top crop's width, so no single rectangle
    // left of the dead zone passes, and more than 1/2 of the height, so a 16:9 picture across the
    // seam with a dark corner does not.
    static final int WINDOW_NUM = 2;
    static final int WINDOW_DEN = 3;
    // shortcut: the content box shrinks only to a box with at least 1/4 of its area, so a spinner
    // on a black loading screen is not blown up; a window that small stays small on the display,
    // also when it is the first thing shown (the box starts as the whole video). The box is the
    // lit pixels, not the window: a normal game whose scene is black along an edge, or lit only in
    // a region of at least 1/4 of the box (a pause menu on black), for BOX_CHECKS checks is
    // shifted or zoomed (up to 2x for a box of the display's shape) until the edge lights up again.
    // Upgrade when the host tells the client the window rect.
    static final int SHRINK_MIN_SHARE = 4;

    private static final int[] FULL = {0, 0, DualScreenLayout.VIDEO_W, DualScreenLayout.VIDEO_H};

    /** Immutable: what the main display shows and how its points map to the video. */
    public static final class View {
        public final boolean dual;
        /**
         * The video rect {x, y, width, height} on the main display: stretched over it when dual,
         * else scaled to fit with black bars. Never written.
         */
        public final int[] main;

        View(boolean dual, int[] main) {
            this.dual = dual;
            this.main = main;
        }

        /** Where the picture is on a viewW x viewH main view: {x, y, width, height}, y down. */
        public int[] rect(int viewW, int viewH) {
            return dual ? new int[] {0, 0, viewW, viewH} : DualScreenLayout.fitRect(main[2], main[3], viewW, viewH);
        }

        /** x/y normalized over the whole video for a point given as a fraction of the main view; a point on a bar lands on the content edge. */
        public float[] toVideo(float fx, float fy, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.toVideo(main, (fx * viewW - r[0]) / r[2], (fy * viewH - r[1]) / r[3]);
        }

        /** Scales a size given as a fraction of the main view to a fraction of the video. */
        public float[] sizeToVideo(float fw, float fh, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.sizeToVideo(main, fw * viewW / r[2], fh * viewH / r[3]);
        }

        /** Absolute mouse position {x, y} in video pixels for a point (pixels) on the main view. */
        public int[] mousePosition(float x, float y, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.mousePosition(main, x - r[0], y - r[1], r[2], r[3]);
        }
    }

    private final View dualView;
    // The only state other threads read: one immutable snapshot, replaced whole
    private volatile View view = new View(false, FULL);

    // GL thread only
    private boolean dual, gaveUp, pending;
    private int dualRun, deadZoneRun, noBottomRun, shrinkRun;
    // The drawn content box, the whole video until a smaller one has lasted, and the smaller one being counted
    private int[] box = FULL;
    private int[] smallerBox;

    /** @param mainTop the main display shows the top crop when dual (DualScreenLayout.isTopDisplay) */
    public DualScreenDetector(boolean mainTop) {
        dualView = new View(true, mainTop ? DualScreenLayout.TOP_CROP : DualScreenLayout.BOTTOM_CROP);
    }

    public View view() {
        return view;
    }

    /**
     * Start over in SINGLE with the whole video: a new decoder or GL context.
     * Returns whether that ended a split, for the log.
     */
    public boolean reset() {
        if (gaveUp) {
            return false;
        }
        boolean wasDual = dual;
        dual = false;
        dualRun = shrinkRun = 0;
        box = FULL;
        view = new View(false, FULL);
        return wasDual;
    }

    /** The picture cannot be analysed: split always, as before there was detection. */
    public void giveUp() {
        gaveUp = true;
        view = dualView;
    }

    /** Whether the last check left a debounce running, so the same picture checked again can change the answer. */
    public boolean pending() {
        return pending;
    }

    /**
     * One check. {@code cells} holds GRID_W * GRID_H bytes, rows from the top of the video,
     * non-zero where the cell has content. Returns what changed for the log, or null.
     *
     * shortcut: the layout is recognised by pixels alone, so a picture that is not two windows
     * is split when it looks like them for DUAL_CHECKS checks: black in the dead zone and lit
     * over WINDOW_NUM/WINDOW_DEN of both crops, such as a window filling the video whose
     * bottom-right 680x1080 is dark. Upgrade when the host can tell the client the window layout.
     */
    public String check(ByteBuffer cells) {
        pending = false;
        if (gaveUp) {
            return null;
        }
        // {first column, first row, last column, last row} of the cells with content
        int[] all = {GRID_W, GRID_H, -1, -1}, top = all.clone(), bottom = all.clone();
        boolean deadZone = false;
        for (int r = 0, i = 0; r < GRID_H; r++) {
            for (int c = 0; c < GRID_W; c++, i++) {
                if (cells.get(i) == 0) {
                    continue;
                }
                add(all, c, r);
                if (inside(DualScreenLayout.TOP_CROP, c, r)) {
                    add(top, c, r);
                } else if (inside(DualScreenLayout.BOTTOM_CROP, c, r)) {
                    add(bottom, c, r);
                } else {
                    deadZone = true;
                }
            }
        }
        if (all[2] < 0) {
            // All black (loading, a transition, no window yet) is no evidence either way
            return null;
        }

        boolean boxChanged = updateBox(new int[] {all[0] * CELL, all[1] * CELL, (all[2] - all[0] + 1) * CELL, (all[3] - all[1] + 1) * CELL});
        String change = null;
        if (!dual) {
            dualRun = !deadZone && spans(top, DualScreenLayout.TOP_CROP) && spans(bottom, DualScreenLayout.BOTTOM_CROP) ? dualRun + 1 : 0;
            if (dualRun >= DUAL_CHECKS) {
                dual = true;
                deadZoneRun = noBottomRun = 0;
                change = "two-window layout seen, splitting over both displays";
            }
        } else {
            deadZoneRun = deadZone ? deadZoneRun + 1 : 0;
            noBottomRun = bottom[2] < 0 ? noBottomRun + 1 : 0;
            if (deadZoneRun >= DEAD_ZONE_CHECKS) {
                change = "content outside the two windows, main display only";
            } else if (noBottomRun >= NO_BOTTOM_CHECKS) {
                change = "the second window is gone, main display only";
            }
            if (change != null) {
                dual = false;
                dualRun = 0;
            }
        }
        pending = shrinkRun > 0 || (dual ? deadZoneRun > 0 || noBottomRun > 0 : dualRun > 0);
        if (change == null && (dual || !boxChanged)) {
            return null;
        }
        view = dual ? dualView : new View(false, box);
        return change != null ? change : "content box " + Arrays.toString(box);
    }

    // The drawn box never shows less than the content: content outside it enlarges it at once.
    // It gets smaller only when the same smaller box, not under the floor, was measured on
    // BOX_CHECKS checks in a row, so a dark edge that flickers does not make it jitter. The check
    // that enlarges it is the first of those when the content is smaller than the new box.
    private boolean updateBox(int[] measured) {
        boolean grew = !contains(box, measured);
        if (grew) {
            // The measured box is not the one being counted, which lay inside the old box: the count restarts below
            box = union(box, measured);
        }
        if (Arrays.equals(measured, box) || (long) measured[2] * measured[3] * SHRINK_MIN_SHARE < (long) box[2] * box[3]) {
            shrinkRun = 0;
            return grew;
        }
        shrinkRun = Arrays.equals(measured, smallerBox) ? shrinkRun + 1 : 1;
        smallerBox = measured;
        if (shrinkRun < BOX_CHECKS) {
            return grew;
        }
        shrinkRun = 0;
        box = measured;
        return true;
    }

    private static void add(int[] extent, int c, int r) {
        extent[0] = Math.min(extent[0], c);
        extent[1] = Math.min(extent[1], r);
        extent[2] = Math.max(extent[2], c);
        extent[3] = Math.max(extent[3], r);
    }

    // Whether the content with this extent is a window in the crop; never for no content
    private static boolean spans(int[] extent, int[] crop) {
        return (extent[2] - extent[0] + 1) * CELL * WINDOW_DEN >= crop[2] * WINDOW_NUM
                && (extent[3] - extent[1] + 1) * CELL * WINDOW_DEN >= crop[3] * WINDOW_NUM;
    }

    // Whether the cell at column c, row r lies in a crop given in video pixels
    private static boolean inside(int[] crop, int c, int r) {
        int x = c * CELL, y = r * CELL;
        return x >= crop[0] && x < crop[0] + crop[2] && y >= crop[1] && y < crop[1] + crop[3];
    }

    private static boolean contains(int[] outer, int[] inner) {
        return inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[0] + inner[2] <= outer[0] + outer[2] && inner[1] + inner[3] <= outer[1] + outer[3];
    }

    private static int[] union(int[] a, int[] b) {
        int x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
        return new int[] {x, y, Math.max(a[0] + a[2], b[0] + b[2]) - x, Math.max(a[1] + a[3], b[1] + b[3]) - y};
    }
}

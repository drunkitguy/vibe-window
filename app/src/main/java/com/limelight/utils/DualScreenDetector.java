package com.limelight.utils;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Dual screen: decides from the decoded picture whether the app shows the two-window layout
 * (DUAL: DualScreenLayout's split over both displays) or anything else (SINGLE: the content
 * alone on the main display, the second display black). check() runs on the renderer's GL
 * thread a few times per second; view() is safe from any thread.
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
    // around the windows and the codec adds noise to it
    public static final float BLACK_THRESHOLD = 24f / 255;
    public static final int CHECK_INTERVAL_MS = 250;

    // Debounce, in consecutive checks with content (CHECK_INTERVAL_MS apart)
    static final int DUAL_CHECKS = 3;
    static final int DEAD_ZONE_CHECKS = 3;
    static final int NO_BOTTOM_CHECKS = 8;
    static final int BOX_CHECKS = 3;
    // shortcut: the content box shrinks only to a box with at least 1/4 of its area, so a spinner
    // on a black loading screen is not blown up; a window that small stays small on the display.
    // Upgrade when the host tells the client the window rect.
    static final int SHRINK_MIN_SHARE = 4;

    private static final int[] FULL = {0, 0, DualScreenLayout.VIDEO_W, DualScreenLayout.VIDEO_H};

    /** Immutable: what the main display shows and how its points map to the video. */
    public static final class View {
        public final boolean dual;
        /**
         * The video rect {x, y, width, height} on the main display: stretched over it when dual,
         * else scaled to fit with black bars. Null while no content was seen (black). Never written.
         */
        public final int[] main;

        View(boolean dual, int[] main) {
            this.dual = dual;
            this.main = main;
        }

        /** Where the picture is on a viewW x viewH main view: {x, y, width, height}, y down. */
        public int[] rect(int viewW, int viewH) {
            return dual || main == null ? new int[] {0, 0, viewW, viewH}
                    : DualScreenLayout.fitRect(main[2], main[3], viewW, viewH);
        }

        private int[] crop() {
            return main != null ? main : FULL;
        }

        /** x/y normalized over the whole video for a point given as a fraction of the main view; a point on a bar lands on the content edge. */
        public float[] toVideo(float fx, float fy, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.toVideo(crop(), (fx * viewW - r[0]) / r[2], (fy * viewH - r[1]) / r[3]);
        }

        /** Scales a size given as a fraction of the main view to a fraction of the video. */
        public float[] sizeToVideo(float fw, float fh, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.sizeToVideo(crop(), fw * viewW / r[2], fh * viewH / r[3]);
        }

        /** Absolute mouse position {x, y} in video pixels for a point (pixels) on the main view. */
        public int[] mousePosition(float x, float y, int viewW, int viewH) {
            int[] r = rect(viewW, viewH);
            return DualScreenLayout.mousePosition(crop(), x - r[0], y - r[1], r[2], r[3]);
        }
    }

    private final View dualView;
    // The only state other threads read: one immutable snapshot, replaced whole
    private volatile View view = new View(false, null);

    // GL thread only
    private boolean dual;
    private boolean gaveUp;
    private int dualRun, deadZoneRun, noBottomRun;
    private int[] box;
    private int[] pendingBox;
    private boolean pendingGrows;
    private int boxRun;

    /** @param mainTop the main display shows the top crop when dual (DualScreenLayout.isTopDisplay) */
    public DualScreenDetector(boolean mainTop) {
        dualView = new View(true, mainTop ? DualScreenLayout.TOP_CROP : DualScreenLayout.BOTTOM_CROP);
    }

    public View view() {
        return view;
    }

    /** Start over in SINGLE with nothing seen: a new decoder or GL context. */
    public void reset() {
        if (gaveUp) {
            return;
        }
        dual = false;
        dualRun = deadZoneRun = noBottomRun = boxRun = 0;
        box = pendingBox = null;
        view = new View(false, null);
    }

    /** The picture cannot be analysed: split always, as before there was detection. */
    public void giveUp() {
        gaveUp = dual = true;
        view = dualView;
    }

    /**
     * One check. {@code cells} holds GRID_W * GRID_H bytes, rows from the top of the video,
     * non-zero where the cell has content. Returns what changed for the log, or null.
     *
     * shortcut: the layout is recognised by pixels alone, so any picture that is black in the
     * dead zone and has content in both crops for DUAL_CHECKS checks is split. Upgrade when the
     * host can tell the client the window layout.
     */
    public String check(ByteBuffer cells) {
        if (gaveUp) {
            return null;
        }
        int minC = GRID_W, maxC = -1, minR = GRID_H, maxR = -1;
        boolean top = false, bottom = false, deadZone = false;
        for (int r = 0, i = 0; r < GRID_H; r++) {
            for (int c = 0; c < GRID_W; c++, i++) {
                if (cells.get(i) == 0) {
                    continue;
                }
                minC = Math.min(minC, c);
                maxC = Math.max(maxC, c);
                minR = Math.min(minR, r);
                maxR = r;
                if (inside(DualScreenLayout.TOP_CROP, c, r)) {
                    top = true;
                } else if (inside(DualScreenLayout.BOTTOM_CROP, c, r)) {
                    bottom = true;
                } else {
                    deadZone = true;
                }
            }
        }
        if (maxC < 0) {
            // All black (loading, a transition, no window yet) is no evidence either way
            return null;
        }

        boolean boxChanged = updateBox(new int[] {minC * CELL, minR * CELL, (maxC - minC + 1) * CELL, (maxR - minR + 1) * CELL});
        String change = null;
        if (!dual) {
            dualRun = top && bottom && !deadZone ? dualRun + 1 : 0;
            if (dualRun >= DUAL_CHECKS) {
                dual = true;
                deadZoneRun = noBottomRun = 0;
                change = "two-window layout seen, splitting over both displays";
            }
        } else {
            deadZoneRun = deadZone ? deadZoneRun + 1 : 0;
            noBottomRun = bottom ? 0 : noBottomRun + 1;
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
        if (change == null && (dual || !boxChanged)) {
            return null;
        }
        view = dual ? dualView : new View(false, box);
        return change != null ? change : "content box " + Arrays.toString(box);
    }

    // The drawn box follows the measured one only after BOX_CHECKS checks in a row, so it does not jitter:
    // content outside the box makes it the union of those checks (also the first box);
    // a smaller box must be the same on each of them (a dark edge that flickers is not a smaller window).
    private boolean updateBox(int[] measured) {
        if (Arrays.equals(measured, box)) {
            boxRun = 0;
            return false;
        }
        boolean grows = box == null || !contains(box, measured);
        if (boxRun > 0 && grows == pendingGrows && (grows || Arrays.equals(measured, pendingBox))) {
            if (grows) {
                pendingBox = union(pendingBox, measured);
            }
            boxRun++;
        } else {
            pendingBox = measured;
            pendingGrows = grows;
            boxRun = 1;
        }
        if (boxRun < BOX_CHECKS) {
            return false;
        }
        boxRun = 0;
        if (!grows && (long) pendingBox[2] * pendingBox[3] * SHRINK_MIN_SHARE < (long) box[2] * box[3]) {
            return false;
        }
        box = pendingBox;
        return true;
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

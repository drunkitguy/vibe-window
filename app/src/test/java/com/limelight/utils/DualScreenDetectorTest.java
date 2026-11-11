package com.limelight.utils;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DualScreenDetectorTest {
    private static final float EPS = 1e-6f;
    private static final int[] TOP = DualScreenLayout.TOP_CROP;
    private static final int[] BOTTOM = DualScreenLayout.BOTTOM_CROP;
    private static final int[] FULL = {0, 0, 1920, 2160};
    private static final int[] LETTERBOX = {0, 540, 1920, 1080};
    private static final ByteBuffer BLACK = grid();
    private static final ByteBuffer DUAL = grid(TOP, BOTTOM);

    // The grid the renderer reads back, with content in the given video rects {x, y, w, h}
    private static ByteBuffer grid(int[]... rects) {
        byte[] cells = new byte[DualScreenDetector.GRID_W * DualScreenDetector.GRID_H];
        for (int[] rect : rects) {
            for (int y = rect[1]; y < rect[1] + rect[3]; y += DualScreenDetector.CELL) {
                for (int x = rect[0]; x < rect[0] + rect[2]; x += DualScreenDetector.CELL) {
                    cells[y / DualScreenDetector.CELL * DualScreenDetector.GRID_W + x / DualScreenDetector.CELL] = (byte) 255;
                }
            }
        }
        return ByteBuffer.wrap(cells);
    }

    // The last check's answer
    private static String feed(DualScreenDetector d, ByteBuffer grid, int times) {
        String change = null;
        for (int i = 0; i < times; i++) {
            change = d.check(grid);
        }
        return change;
    }

    private static DualScreenDetector inDual() {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, DUAL, DualScreenDetector.DUAL_CHECKS);
        assertTrue(d.view().dual);
        return d;
    }

    private static DualScreenDetector showing(int[] box) {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, grid(box), DualScreenDetector.BOX_CHECKS);
        assertArrayEquals(box, d.view().main);
        return d;
    }

    @Test
    public void startsOnTheMainDisplayWithNothing() {
        DualScreenDetector.View v = new DualScreenDetector(true).view();
        assertFalse(v.dual);
        assertNull(v.main);
    }

    @Test
    public void theExactLayoutSplitsOnlyAfterTheDebounce() {
        DualScreenDetector d = new DualScreenDetector(true);
        assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
        assertFalse(d.view().dual);
        // Said once, with the reason
        assertEquals("two-window layout seen, splitting over both displays", d.check(DUAL));
        assertTrue(d.view().dual);
        assertSame(TOP, d.view().main);
        assertNull(feed(d, DUAL, 20));
        assertTrue(d.view().dual);
    }

    @Test
    public void whichCropTheMainDisplayGetsFollowsIsTopDisplay() {
        // The Thor's own way round, and the main display being the smaller one
        for (boolean mainTop : new boolean[] {
                DualScreenLayout.isTopDisplay(1920, 1080, 1240, 1080),
                DualScreenLayout.isTopDisplay(1240, 1080, 1920, 1080)}) {
            DualScreenDetector d = new DualScreenDetector(mainTop);
            feed(d, DUAL, DualScreenDetector.DUAL_CHECKS);
            assertSame(mainTop ? TOP : BOTTOM, d.view().main);
        }
    }

    @Test
    public void anAllBlackFrameIsNoEvidence() {
        DualScreenDetector d = new DualScreenDetector(true);
        assertNull(feed(d, BLACK, 50));
        assertFalse(d.view().dual);
        assertNull(d.view().main);

        // It neither counts for the debounce nor breaks it
        feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1);
        feed(d, BLACK, 50);
        assertFalse(d.view().dual);
        assertNotNull(d.check(DUAL));
        assertTrue(d.view().dual);

        // And it never ends the split
        assertNull(feed(d, BLACK, 50));
        assertTrue(d.view().dual);
    }

    @Test
    public void aLetterboxedGameGivesExactlyItsBox() {
        DualScreenDetector d = new DualScreenDetector(true);
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        assertNull(d.view().main);
        assertEquals("content box [0, 540, 1920, 1080]", d.check(grid(LETTERBOX)));
        assertNull(feed(d, grid(LETTERBOX), 20));
        assertFalse(d.view().dual);
        assertArrayEquals(LETTERBOX, d.view().main);
    }

    @Test
    public void aWindowFillingTheVideoStaysOnTheMainDisplay() {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, grid(FULL), 20);
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);
    }

    @Test
    public void contentOnlyInTheTopHalfStaysOnTheMainDisplay() {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, grid(TOP), 20);
        assertFalse(d.view().dual);
        assertArrayEquals(TOP, d.view().main);
        // Bottom-left alone is not the layout either
        d = new DualScreenDetector(true);
        feed(d, grid(BOTTOM), 20);
        assertFalse(d.view().dual);
        assertArrayEquals(BOTTOM, d.view().main);
    }

    @Test
    public void oneCellInTheDeadZoneIsNotTheLayout() {
        DualScreenDetector d = new DualScreenDetector(true);
        // The first cell right of the bottom crop, and the last one of the video
        for (int[] cell : new int[][] {{1240, 1080, 4, 4}, {1916, 2156, 4, 4}}) {
            feed(d, grid(TOP, BOTTOM, cell), 20);
            assertFalse(d.view().dual);
        }
        // The cells next to the dead zone belong to the crops
        feed(d, grid(TOP, BOTTOM, new int[] {1236, 1080, 4, 4}, new int[] {1240, 1076, 4, 4}), DualScreenDetector.DUAL_CHECKS);
        assertTrue(d.view().dual);
    }

    @Test
    public void deadZoneContentEndsTheSplitAfterTheDebounce() {
        DualScreenDetector d = inDual();
        ByteBuffer filled = grid(FULL);
        assertNull(feed(d, filled, DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        // Interrupted: the count starts again
        feed(d, DUAL, 1);
        assertNull(feed(d, filled, DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        assertTrue(d.view().dual);
        assertEquals("content outside the two windows, main display only", d.check(filled));
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);
    }

    @Test
    public void aMissingSecondWindowEndsTheSplitAfterTwoSeconds() {
        assertEquals(2000, DualScreenDetector.NO_BOTTOM_CHECKS * DualScreenDetector.CHECK_INTERVAL_MS);
        DualScreenDetector d = inDual();
        ByteBuffer topOnly = grid(TOP);
        assertNull(feed(d, topOnly, DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        feed(d, DUAL, 1);
        assertNull(feed(d, topOnly, DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        assertTrue(d.view().dual);
        assertEquals("the second window is gone, main display only", d.check(topOnly));
        assertFalse(d.view().dual);
        assertArrayEquals(TOP, d.view().main);

        // A dark game above a lit companion is still the layout
        d = inDual();
        feed(d, grid(BOTTOM), 50);
        assertTrue(d.view().dual);
    }

    @Test
    public void theBoxDoesNotJitter() {
        DualScreenDetector d = showing(FULL);
        DualScreenDetector.View shown = d.view();
        int[] darkTop = {0, 200, 1920, 1960};
        int[] darkLeft = {300, 0, 1620, 2160};

        // An edge that flickers dark: a different smaller box each time
        for (int i = 0; i < 20; i++) {
            assertNull(d.check(grid(i % 2 == 0 ? darkTop : darkLeft)));
        }
        // A smaller box that does not last
        feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1);
        feed(d, grid(FULL), 1);
        feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1);
        // The same snapshot object: nothing was published
        assertSame(shown, d.view());

        // It lasted
        assertNotNull(d.check(grid(LETTERBOX)));
        assertArrayEquals(LETTERBOX, d.view().main);
    }

    @Test
    public void aSpinnerOnBlackIsNotBlownUp() {
        DualScreenDetector d = showing(LETTERBOX);
        feed(d, grid(new int[] {1800, 1500, 64, 64}), 50);
        assertArrayEquals(LETTERBOX, d.view().main);
        // A quarter of the area is a window
        int[] quarter = {480, 808, 960, 540};
        feed(d, grid(quarter), DualScreenDetector.BOX_CHECKS);
        assertArrayEquals(quarter, d.view().main);
    }

    @Test
    public void contentOutsideTheBoxGrowsItToTheUnionAfterTheDebounce() {
        DualScreenDetector d = showing(LETTERBOX);
        int[] above = {0, 0, 1920, 1620};
        int[] below = {0, 540, 1920, 1620};
        assertNull(d.check(grid(above)));
        assertNull(d.check(grid(below)));
        assertArrayEquals(LETTERBOX, d.view().main);
        // Not the same box twice, but outside every time
        assertEquals("content box [0, 0, 1920, 2160]", d.check(grid(above)));
        assertArrayEquals(FULL, d.view().main);
    }

    @Test
    public void resetStartsOverAndGivingUpSplitsForGood() {
        DualScreenDetector d = inDual();
        d.reset();
        assertFalse(d.view().dual);
        assertNull(d.view().main);
        // The debounce starts over too, and the box it had is forgotten
        feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1);
        assertFalse(d.view().dual);
        assertNull(d.view().main);
        d.reset();
        feed(d, grid(FULL), DualScreenDetector.BOX_CHECKS);
        assertArrayEquals(FULL, d.view().main);

        d.giveUp();
        assertTrue(d.view().dual);
        assertSame(TOP, d.view().main);
        assertNull(feed(d, grid(FULL), 20));
        d.reset();
        assertTrue(d.view().dual);
    }

    @Test
    public void splitViewMapsAsBefore() {
        DualScreenDetector.View top = inDual().view();
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, top.rect(1920, 1080));
        assertArrayEquals(DualScreenLayout.toVideo(TOP, 0.25f, 0.75f), top.toVideo(0.25f, 0.75f, 1920, 1080), EPS);
        assertArrayEquals(DualScreenLayout.sizeToVideo(TOP, 0.1f, 0.2f), top.sizeToVideo(0.1f, 0.2f, 1920, 1080), EPS);
        assertArrayEquals(DualScreenLayout.mousePosition(TOP, 100.3f, 100.3f, 960, 540), top.mousePosition(100.3f, 100.3f, 960, 540));

        DualScreenDetector d = new DualScreenDetector(false);
        feed(d, DUAL, DualScreenDetector.DUAL_CHECKS);
        assertArrayEquals(new float[] {620f / 1920, 1620f / 2160}, d.view().toVideo(0.5f, 0.5f, 1240, 1080), EPS);
    }

    @Test
    public void fittedBoxFillingTheViewMapsOneToOne() {
        DualScreenDetector.View v = showing(LETTERBOX).view();
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, v.rect(1920, 1080));
        assertArrayEquals(new float[] {0f, 540f / 2160}, v.toVideo(0f, 0f, 1920, 1080), EPS);
        assertArrayEquals(new float[] {0.5f, 0.5f}, v.toVideo(0.5f, 0.5f, 1920, 1080), EPS);
        assertArrayEquals(new float[] {1f, 1620f / 2160}, v.toVideo(1f, 1f, 1920, 1080), EPS);
        assertArrayEquals(new int[] {480, 541}, v.mousePosition(479.6f, 0.7f, 1920, 1080));
    }

    @Test
    public void pointsOnTheBarsClampToTheContentEdge() {
        // The whole video on a 16:9 display: 960x1080 in the middle, bars left and right
        DualScreenDetector.View v = showing(FULL).view();
        assertArrayEquals(new int[] {480, 0, 960, 1080}, v.rect(1920, 1080));
        assertArrayEquals(new float[] {0f, 0.5f}, v.toVideo(0f, 0.5f, 1920, 1080), EPS);
        assertArrayEquals(new float[] {0f, 0.5f}, v.toVideo(0.25f, 0.5f, 1920, 1080), EPS);
        assertArrayEquals(new float[] {0.5f, 0.25f}, v.toVideo(0.5f, 0.25f, 1920, 1080), EPS);
        assertArrayEquals(new float[] {1f, 1f}, v.toVideo(1f, 1f, 1920, 1080), EPS);
        assertArrayEquals(new int[] {0, 1080}, v.mousePosition(100, 540, 1920, 1080));
        assertArrayEquals(new int[] {960, 1080}, v.mousePosition(960, 540, 1920, 1080));
        assertArrayEquals(new int[] {1920, 2160}, v.mousePosition(1900, 1080, 1920, 1080));
        // A tenth of the view's width is a fifth of the picture's
        assertArrayEquals(new float[] {0.2f, 0.1f}, v.sizeToVideo(0.1f, 0.1f, 1920, 1080), EPS);
    }

    @Test
    public void fittedBoxOnTheSmallerDisplayHasBarsAboveAndBelow() {
        // The main display is the 1240x1080 one and a 16:9 game is in the top half
        DualScreenDetector d = new DualScreenDetector(false);
        feed(d, grid(TOP), DualScreenDetector.BOX_CHECKS);
        DualScreenDetector.View v = d.view();
        assertFalse(v.dual);
        assertArrayEquals(new int[] {0, 191, 1240, 698}, v.rect(1240, 1080));
        assertArrayEquals(new float[] {0.5f, 0.25f}, v.toVideo(0.5f, 0.5f, 1240, 1080), EPS);
        // On the bars
        assertArrayEquals(new float[] {0f, 0f}, v.toVideo(0f, 0f, 1240, 1080), EPS);
        assertArrayEquals(new float[] {1f, 0.5f}, v.toVideo(1f, 1f, 1240, 1080), EPS);
        assertArrayEquals(new int[] {960, 0}, v.mousePosition(620, 100, 1240, 1080));
    }

    @Test
    public void beforeAnyContentTheViewStillMaps() {
        DualScreenDetector.View v = new DualScreenDetector(true).view();
        assertArrayEquals(new float[] {0.5f, 0.5f}, v.toVideo(0.5f, 0.5f, 1920, 1080), EPS);
        assertArrayEquals(new int[] {960, 1080}, v.mousePosition(960, 540, 1920, 1080));
    }
}

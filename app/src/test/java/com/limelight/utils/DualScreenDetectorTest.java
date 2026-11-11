package com.limelight.utils;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Random;

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

    // Every answer before the last must be null; returns the last
    private static String feed(DualScreenDetector d, ByteBuffer grid, int times) {
        for (int i = 1; i < times; i++) {
            assertNull(d.check(grid));
        }
        return d.check(grid);
    }

    private static DualScreenDetector inDual() {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, DUAL, DualScreenDetector.DUAL_CHECKS);
        assertTrue(d.view().dual);
        return d;
    }

    // SINGLE with the box drawn; the box must have at least 1/4 of the video's area
    private static DualScreenDetector showing(int[] box) {
        DualScreenDetector d = new DualScreenDetector(true);
        feed(d, grid(box), DualScreenDetector.BOX_CHECKS);
        assertArrayEquals(box, d.view().main);
        return d;
    }

    private static void assertNeverSplits(int[]... rects) {
        DualScreenDetector d = new DualScreenDetector(true);
        ByteBuffer grid = grid(rects);
        for (int i = 0; i < 20; i++) {
            d.check(grid);
            assertFalse(d.view().dual);
        }
    }

    @Test
    public void startsOnTheMainDisplayWithTheWholeVideo() {
        DualScreenDetector d = new DualScreenDetector(true);
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);
        assertFalse(d.pending());
    }

    @Test
    public void theExactLayoutSplitsOnlyAfterTheDebounce() {
        assertEquals(750, DualScreenDetector.DUAL_CHECKS * DualScreenDetector.CHECK_INTERVAL_MS);
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
    public void theSplitDebounceMustBeUninterrupted() {
        DualScreenDetector d = new DualScreenDetector(true);
        for (int i = 0; i < 5; i++) {
            assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
            assertNull(d.check(grid(FULL)));
        }
        assertFalse(d.view().dual);
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
        assertArrayEquals(FULL, d.view().main);

        // It neither counts for the debounce nor breaks it
        assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
        assertNull(feed(d, BLACK, 50));
        assertFalse(d.view().dual);
        assertNotNull(d.check(DUAL));
        assertTrue(d.view().dual);

        // And it never ends the split
        assertNull(feed(d, BLACK, 50));
        assertTrue(d.view().dual);
    }

    @Test
    public void anAllBlackFrameKeepsEveryCount() {
        // In the middle of a dead-zone run: the next check with it completes the run
        DualScreenDetector d = inDual();
        assertNull(feed(d, grid(FULL), DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        assertNull(feed(d, BLACK, 50));
        assertEquals("content outside the two windows, main display only", d.check(grid(FULL)));

        // Of a run without the second window
        d = inDual();
        assertNull(feed(d, grid(TOP), DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        assertNull(feed(d, BLACK, 50));
        assertEquals("the second window is gone, main display only", d.check(grid(TOP)));

        // Of a smaller box on its way
        d = new DualScreenDetector(true);
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        assertNull(feed(d, BLACK, 50));
        assertEquals("content box [0, 540, 1920, 1080]", d.check(grid(LETTERBOX)));
    }

    @Test
    public void aLetterboxedGameGivesExactlyItsBox() {
        DualScreenDetector d = new DualScreenDetector(true);
        // The whole video until the smaller box has lasted
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        assertArrayEquals(FULL, d.view().main);
        assertEquals("content box [0, 540, 1920, 1080]", d.check(grid(LETTERBOX)));
        assertNull(feed(d, grid(LETTERBOX), 20));
        assertFalse(d.view().dual);
        assertArrayEquals(LETTERBOX, d.view().main);
    }

    @Test
    public void aWindowFillingTheVideoStaysOnTheMainDisplay() {
        DualScreenDetector d = new DualScreenDetector(true);
        assertNull(feed(d, grid(FULL), 20));
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);
    }

    @Test
    public void contentOnlyInOneCropStaysOnTheMainDisplay() {
        // The top half alone, and bottom-left alone
        for (int[] crop : new int[][] {TOP, BOTTOM}) {
            DualScreenDetector d = showing(crop);
            assertNull(feed(d, grid(crop), 20));
            assertFalse(d.view().dual);
            assertArrayEquals(crop, d.view().main);
        }
    }

    @Test
    public void oneCellInTheDeadZoneIsNotTheLayout() {
        DualScreenDetector d = new DualScreenDetector(true);
        // The first cell right of the bottom crop, and the last one of the video
        for (int[] cell : new int[][] {{1240, 1080, 4, 4}, {1916, 2156, 4, 4}}) {
            assertNull(feed(d, grid(TOP, BOTTOM, cell), 20));
            assertFalse(d.view().dual);
        }
        // The cells next to the dead zone belong to the crops
        feed(d, grid(TOP, BOTTOM, new int[] {1236, 1080, 4, 4}, new int[] {1240, 1076, 4, 4}), DualScreenDetector.DUAL_CHECKS);
        assertTrue(d.view().dual);
    }

    @Test
    public void contentAcrossTheSeamThatIsNotTwoWindowsIsNotTheLayout() {
        // A spinner and a splash in the middle of the video
        assertNeverSplits(new int[] {928, 1048, 64, 64});
        assertNeverSplits(new int[] {720, 945, 480, 270});
        // A picture lit only on its left, and the widest rectangle left of the dead zone
        assertNeverSplits(new int[] {0, 0, 700, 2160});
        assertNeverSplits(new int[] {0, 0, 1240, 2160});
        // A letterboxed 16:9 game whose bottom-right corner is dark
        assertNeverSplits(new int[] {0, 540, 1920, 540}, new int[] {0, 1080, 1240, 540});
    }

    @Test
    public void eachWindowMustSpanTwoThirdsOfItsCrop() {
        // 2/3 of 1920x1080, and of 1240x1080 rounded up to whole cells
        int[] top = {0, 0, 1280, 720};
        int[] bottom = {0, 1080, 828, 720};
        DualScreenDetector d = new DualScreenDetector(true);
        d.check(grid(top, bottom));
        d.check(grid(top, bottom));
        d.check(grid(top, bottom));
        assertTrue(d.view().dual);
        // Dark inside is still a window: only the extent counts
        d = new DualScreenDetector(true);
        ByteBuffer corners = grid(new int[] {0, 0, 4, 4}, new int[] {1276, 716, 4, 4}, new int[] {0, 1080, 4, 4}, new int[] {824, 1796, 4, 4});
        feed(d, corners, DualScreenDetector.DUAL_CHECKS);
        assertTrue(d.view().dual);

        // One cell less in any direction is not
        assertNeverSplits(new int[] {0, 0, 1276, 720}, bottom);
        assertNeverSplits(new int[] {0, 0, 1280, 716}, bottom);
        assertNeverSplits(top, new int[] {0, 1080, 824, 720});
        assertNeverSplits(top, new int[] {0, 1080, 828, 716});
        // Nor when it reaches the crop's right or bottom edge: the span is the content's own
        assertNeverSplits(new int[] {644, 0, 1276, 1080}, BOTTOM);
        assertNeverSplits(new int[] {0, 364, 1920, 716}, BOTTOM);
        assertNeverSplits(TOP, new int[] {416, 1080, 824, 1080});
        assertNeverSplits(TOP, new int[] {0, 1444, 1240, 716});
    }

    @Test
    public void deadZoneContentEndsTheSplitAfterTheDebounce() {
        assertEquals(750, DualScreenDetector.DEAD_ZONE_CHECKS * DualScreenDetector.CHECK_INTERVAL_MS);
        DualScreenDetector d = inDual();
        ByteBuffer filled = grid(FULL);
        assertNull(feed(d, filled, DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        // Interrupted: the count starts again
        assertNull(d.check(DUAL));
        assertNull(feed(d, filled, DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        assertTrue(d.view().dual);
        assertEquals("content outside the two windows, main display only", d.check(filled));
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);

        // The way back takes the whole debounce again, and so does the next end
        assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
        assertFalse(d.view().dual);
        assertNotNull(d.check(DUAL));
        assertNull(feed(d, filled, DualScreenDetector.DEAD_ZONE_CHECKS - 1));
        assertTrue(d.view().dual);
    }

    @Test
    public void aMissingSecondWindowEndsTheSplitAfterTwoSeconds() {
        assertEquals(2000, DualScreenDetector.NO_BOTTOM_CHECKS * DualScreenDetector.CHECK_INTERVAL_MS);
        DualScreenDetector d = inDual();
        ByteBuffer topOnly = grid(TOP);
        assertNull(feed(d, topOnly, DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        assertNull(d.check(DUAL));
        assertNull(feed(d, topOnly, DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        assertTrue(d.view().dual);
        assertEquals("the second window is gone, main display only", d.check(topOnly));
        assertFalse(d.view().dual);
        assertArrayEquals(TOP, d.view().main);

        // Back in the layout (the second window is shown at once), the count starts again
        assertEquals("content box [0, 0, 1920, 2160]", d.check(DUAL));
        assertNull(d.check(DUAL));
        assertNotNull(d.check(DUAL));
        assertNull(feed(d, topOnly, DualScreenDetector.NO_BOTTOM_CHECKS - 1));
        assertTrue(d.view().dual);

        // A dark game above a lit companion is still the layout, and its box is not said
        d = inDual();
        assertNull(feed(d, grid(BOTTOM), 50));
        assertTrue(d.view().dual);
    }

    @Test
    public void theBoxDoesNotJitter() {
        assertEquals(750, DualScreenDetector.BOX_CHECKS * DualScreenDetector.CHECK_INTERVAL_MS);
        DualScreenDetector d = showing(FULL);
        DualScreenDetector.View shown = d.view();
        int[] darkTop = {0, 200, 1920, 1960};
        int[] darkLeft = {300, 0, 1620, 2160};

        // An edge that flickers dark: a different smaller box each time
        for (int i = 0; i < 20; i++) {
            assertNull(d.check(grid(i % 2 == 0 ? darkTop : darkLeft)));
        }
        // A smaller box that does not last
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        assertNull(d.check(grid(FULL)));
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        // The same snapshot object: nothing was published
        assertSame(shown, d.view());

        // It lasted
        assertNotNull(d.check(grid(LETTERBOX)));
        assertArrayEquals(LETTERBOX, d.view().main);
    }

    @Test
    public void aSpinnerOnBlackIsNotBlownUp() {
        // The first thing shown: the box is still the whole video
        DualScreenDetector d = new DualScreenDetector(true);
        assertNull(feed(d, grid(new int[] {1800, 1500, 64, 64}), 50));
        assertArrayEquals(FULL, d.view().main);

        // Inside the box
        d = showing(LETTERBOX);
        assertNull(feed(d, grid(new int[] {1800, 1500, 64, 64}), 50));
        // One cell column under a quarter of the area
        assertNull(feed(d, grid(new int[] {480, 808, 956, 540}), 50));
        assertArrayEquals(LETTERBOX, d.view().main);
        // A quarter of the area is a window
        int[] quarter = {480, 808, 960, 540};
        assertNotNull(feed(d, grid(quarter), DualScreenDetector.BOX_CHECKS));
        assertArrayEquals(quarter, d.view().main);

        // Partly outside the box: the box takes it in and never shrinks to it
        d = showing(LETTERBOX);
        ByteBuffer outside = grid(new int[] {1800, 1600, 64, 64});
        assertEquals("content box [0, 540, 1920, 1124]", d.check(outside));
        assertNull(feed(d, outside, 50));
        assertArrayEquals(new int[] {0, 540, 1920, 1124}, d.view().main);
    }

    @Test
    public void contentOutsideTheBoxIsShownAtOnce() {
        // Cutscene bars go away
        int[] bars = {0, 676, 1920, 808};
        DualScreenDetector d = showing(bars);
        assertEquals("content box [0, 540, 1920, 1080]", d.check(grid(LETTERBOX)));
        assertArrayEquals(LETTERBOX, d.view().main);
        // And come back only after the debounce
        assertNull(feed(d, grid(bars), DualScreenDetector.BOX_CHECKS - 1));
        assertArrayEquals(LETTERBOX, d.view().main);
        assertNotNull(d.check(grid(bars)));
        assertArrayEquals(bars, d.view().main);

        // To the left, to the right, then above: the union with what is drawn
        int[] quarter = {480, 808, 960, 540};
        d = showing(LETTERBOX);
        feed(d, grid(quarter), DualScreenDetector.BOX_CHECKS);
        assertEquals("content box [200, 808, 1240, 540]", d.check(grid(new int[] {200, 808, 1240, 540})));
        assertEquals("content box [200, 808, 1480, 540]", d.check(grid(new int[] {480, 808, 1200, 540})));
        assertEquals("content box [200, 600, 1480, 748]", d.check(grid(new int[] {480, 600, 960, 400})));
        assertArrayEquals(new int[] {200, 600, 1480, 748}, d.view().main);

        // A smaller box counted before the box grew starts its count again
        d = showing(LETTERBOX);
        assertNull(feed(d, grid(bars), DualScreenDetector.BOX_CHECKS - 1));
        assertNotNull(d.check(grid(FULL)));
        assertNull(feed(d, grid(bars), DualScreenDetector.BOX_CHECKS - 1));
        assertArrayEquals(FULL, d.view().main);
    }

    @Test
    public void aBoxEnlargedPastTheContentShrinksToIt() {
        // A window moved up by half its height: the box takes it in at once, and the same check
        // is the first of the way down to it
        DualScreenDetector d = showing(LETTERBOX);
        assertEquals("content box [0, 0, 1920, 1620]", d.check(grid(TOP)));
        assertTrue(d.pending());
        assertNull(feed(d, grid(TOP), DualScreenDetector.BOX_CHECKS - 2));
        assertTrue(d.pending());
        assertEquals("content box [0, 0, 1920, 1080]", d.check(grid(TOP)));
        assertFalse(d.pending());
        assertArrayEquals(TOP, d.view().main);
    }

    @Test
    public void contentThatBlinksOutsideTheBoxStaysShown() {
        DualScreenDetector d = showing(TOP);
        int[] text = {760, 1500, 400, 60};
        int[] withText = {0, 0, 1920, 1560};
        assertNotNull(d.check(grid(TOP, text)));
        // Lit on 2 checks, dark on 2, for 10 s
        for (int i = 1; i < 40; i++) {
            assertNull(d.check(i / 2 % 2 == 0 ? grid(TOP, text) : grid(TOP)));
            assertArrayEquals(withText, d.view().main);
        }
    }

    @Test
    public void theBoxIsTheBoundingBoxOfAnyShape() {
        // A bar on the top right, a block on the left, a block in the middle: last in reading
        // order is neither the leftmost nor the rightmost cell
        DualScreenDetector d = new DualScreenDetector(true);
        ByteBuffer shape = grid(new int[] {400, 540, 1520, 200}, new int[] {0, 740, 300, 440}, new int[] {800, 1180, 200, 440});
        assertEquals("content box [0, 540, 1920, 1080]", feed(d, shape, DualScreenDetector.BOX_CHECKS));
    }

    @Test
    public void checksAreWantedOnlyWhileADebounceRuns() {
        DualScreenDetector d = new DualScreenDetector(true);
        // Black is no evidence, a settled picture has nothing to wait for
        d.check(BLACK);
        assertFalse(d.pending());
        d.check(grid(FULL));
        assertFalse(d.pending());
        // A spinner under the floor never becomes the box
        d.check(grid(new int[] {1800, 1500, 64, 64}));
        assertFalse(d.pending());

        // A smaller box on its way
        for (int i = 1; i < DualScreenDetector.BOX_CHECKS; i++) {
            d.check(grid(LETTERBOX));
            assertTrue(d.pending());
        }
        d.check(grid(LETTERBOX));
        assertFalse(d.pending());
        // Black in the middle of a debounce stops the checks; the count is kept
        d.check(DUAL);
        assertTrue(d.pending());
        d.check(BLACK);
        assertFalse(d.pending());

        // The split on its way
        for (int i = 1; i < DualScreenDetector.DUAL_CHECKS - 1; i++) {
            d.check(DUAL);
            assertTrue(d.pending());
        }
        d.check(DUAL);
        assertTrue(d.view().dual);
        assertFalse(d.pending());
        // Its end on its way, by the dead zone and by the missing second window
        d.check(grid(FULL));
        assertTrue(d.pending());
        d.check(DUAL);
        assertFalse(d.pending());
        d.check(grid(new int[] {0, 0, 4, 4}));
        assertTrue(d.pending());

        // Counts from before leaving the split or giving up are not waited for
        d = inDual();
        feed(d, grid(FULL), DualScreenDetector.DEAD_ZONE_CHECKS);
        assertFalse(d.view().dual);
        assertFalse(d.pending());
        d = inDual();
        d.check(grid(FULL));
        d.giveUp();
        d.check(grid(FULL));
        assertFalse(d.pending());
    }

    @Test
    public void aStillPictureIsSettledWhenNoCheckIsWanted() {
        int[][] rects = {TOP, BOTTOM, FULL, LETTERBOX, {0, 676, 1920, 808}, {480, 808, 960, 540}, {928, 1048, 64, 64},
                {1240, 1080, 4, 4}, {0, 0, 1280, 720}, {0, 1080, 828, 720}, {300, 0, 1620, 2160}, {0, 0, 1240, 2160}};
        Random random = new Random(1);
        ByteBuffer[] grids = new ByteBuffer[24];
        grids[0] = BLACK;
        for (int i = 1; i < grids.length; i++) {
            grids[i] = grid(rects[random.nextInt(rects.length)], rects[random.nextInt(rects.length)]);
        }
        for (int run = 0; run < 400; run++) {
            DualScreenDetector d = new DualScreenDetector(run % 2 == 0);
            ByteBuffer still = BLACK;
            for (int i = random.nextInt(12); i >= 0; i--) {
                still = grids[random.nextInt(grids.length)];
                d.check(still);
            }
            // A running debounce ends
            for (int i = 0; d.pending(); i++) {
                assertTrue(i < DualScreenDetector.NO_BOTTOM_CHECKS);
                d.check(still);
            }
            // And then the same picture changes nothing
            DualScreenDetector.View settled = d.view();
            for (int i = 0; i < DualScreenDetector.NO_BOTTOM_CHECKS; i++) {
                assertNull(d.check(still));
                assertFalse(d.pending());
                assertSame(settled, d.view());
            }
        }
    }

    @Test
    public void resetStartsOverAndGivingUpSplitsForGood() {
        DualScreenDetector d = inDual();
        d.reset();
        assertFalse(d.view().dual);
        assertArrayEquals(FULL, d.view().main);
        // The layout is found again, after the whole debounce
        assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
        assertFalse(d.view().dual);
        // The debounce starts over too
        d.reset();
        assertNull(feed(d, DUAL, DualScreenDetector.DUAL_CHECKS - 1));
        assertFalse(d.view().dual);
        assertEquals("two-window layout seen, splitting over both displays", d.check(DUAL));

        // The box and its debounce are forgotten
        d = showing(LETTERBOX);
        d.reset();
        assertArrayEquals(FULL, d.view().main);
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        d.reset();
        assertNull(feed(d, grid(LETTERBOX), DualScreenDetector.BOX_CHECKS - 1));
        assertArrayEquals(FULL, d.view().main);
        assertNotNull(d.check(grid(LETTERBOX)));
        assertArrayEquals(LETTERBOX, d.view().main);

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
        // Stretched over the view, whatever its shape
        assertArrayEquals(new int[] {0, 0, 2400, 1080}, top.rect(2400, 1080));
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
        // The whole video on a 16:9 display, as before any smaller box: 960x1080 in the middle, bars left and right
        DualScreenDetector.View v = new DualScreenDetector(true).view();
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
        // Inside the picture, a quarter of the way down and across
        assertArrayEquals(new int[] {480, 270}, v.mousePosition(310, 365.5f, 1240, 1080));
        // A tenth of the view's height is more of the picture's
        assertArrayEquals(new float[] {0.1f, 0.05f * 1080 / 698}, v.sizeToVideo(0.1f, 0.1f, 1240, 1080), EPS);
        // On the bars
        assertArrayEquals(new float[] {0f, 0f}, v.toVideo(0f, 0f, 1240, 1080), EPS);
        assertArrayEquals(new float[] {1f, 0.5f}, v.toVideo(1f, 1f, 1240, 1080), EPS);
        assertArrayEquals(new int[] {960, 0}, v.mousePosition(620, 100, 1240, 1080));
    }
}

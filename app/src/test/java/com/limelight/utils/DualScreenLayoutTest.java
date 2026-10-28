package com.limelight.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DualScreenLayoutTest {
    private static final float EPS = 1e-6f;

    // A display-pixel touch on a 1:1 view of the crop, as the plan states the mapping
    private static void assertMaps(int[] crop, float tx, float ty, float expectX, float expectY) {
        float[] v = DualScreenLayout.toVideo(crop, tx / crop[2], ty / crop[3]);
        assertEquals("x for " + tx + "," + ty, expectX, v[0], EPS);
        assertEquals("y for " + tx + "," + ty, expectY, v[1], EPS);
    }

    @Test
    public void bottomDisplayCornersAndCentre() {
        int[] b = DualScreenLayout.BOTTOM_CROP;
        // x = tx / 1920, y = (1080 + ty) / 2160
        assertMaps(b, 0, 0, 0f, 1080f / 2160);
        assertMaps(b, 1240, 0, 1240f / 1920, 1080f / 2160);
        assertMaps(b, 0, 1080, 0f, 1f);
        assertMaps(b, 1240, 1080, 1240f / 1920, 1f);
        assertMaps(b, 620, 540, 620f / 1920, 1620f / 2160);
    }

    @Test
    public void topDisplayCornersAndCentre() {
        int[] t = DualScreenLayout.TOP_CROP;
        assertMaps(t, 0, 0, 0f, 0f);
        assertMaps(t, 1920, 0, 1f, 0f);
        assertMaps(t, 0, 1080, 0f, 0.5f);
        assertMaps(t, 1920, 1080, 1f, 0.5f);
        assertMaps(t, 960, 540, 0.5f, 0.25f);
    }

    @Test
    public void touchesOutsideTheViewClampToTheCrop() {
        float[] v = DualScreenLayout.toVideo(DualScreenLayout.TOP_CROP, -0.2f, 1.5f);
        assertEquals(0f, v[0], EPS);
        assertEquals(0.5f, v[1], EPS);
        v = DualScreenLayout.toVideo(DualScreenLayout.BOTTOM_CROP, 2f, -1f);
        assertEquals(1240f / 1920, v[0], EPS);
        assertEquals(0.5f, v[1], EPS);
    }

    @Test
    public void contactSizeScalesIntoTheCrop() {
        float[] s = DualScreenLayout.sizeToVideo(DualScreenLayout.TOP_CROP, 0.1f, 0.2f);
        assertEquals(0.1f, s[0], EPS);
        assertEquals(0.1f, s[1], EPS);
        s = DualScreenLayout.sizeToVideo(DualScreenLayout.BOTTOM_CROP, 1f, 1f);
        assertEquals(1240f / 1920, s[0], EPS);
        assertEquals(0.5f, s[1], EPS);
    }

    @Test
    public void mousePositionIsInVideoPixels() {
        assertArrayEquals(new int[] {960, 540},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 960, 540, 1920, 1080));
        assertArrayEquals(new int[] {1920, 1080},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 1920, 1080, 1920, 1080));
        // Non-integer results round to nearest (an int cast would give 479 and 0)
        assertArrayEquals(new int[] {480, 1},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 479.6f, 0.7f, 1920, 1080));
        // A view half the crop's size: 100.3 px -> 200.6 -> 201
        assertArrayEquals(new int[] {201, 201},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 100.3f, 100.3f, 960, 540));
    }

    @Test
    public void pointerIdsNeverCollideAndStayInHostSlots() {
        Set<Integer> top = new HashSet<>();
        Set<Integer> bottom = new HashSet<>();
        for (int id = 0; id <= 31; id++) {
            int t = DualScreenLayout.pointerId(false, id);
            int b = DualScreenLayout.pointerId(true, id);
            if (t >= 0) {
                assertTrue(t < DualScreenLayout.HOST_TOUCH_SLOTS);
                top.add(t);
            }
            if (b >= 0) {
                assertTrue(b < DualScreenLayout.HOST_TOUCH_SLOTS);
                bottom.add(b);
            }
        }
        assertEquals(5, top.size());
        assertEquals(5, bottom.size());
        for (int id : top) {
            assertFalse("collision on " + id, bottom.contains(id));
        }
        assertEquals(0, DualScreenLayout.pointerId(false, 0));
        assertEquals(5, DualScreenLayout.pointerId(true, 0));
        assertEquals(-1, DualScreenLayout.pointerId(false, 5));
        assertEquals(-1, DualScreenLayout.pointerId(true, 5));
        assertEquals(-1, DualScreenLayout.pointerId(true, -1));
    }

    @Test
    public void touchDispatchSelection() {
        // CANCEL_ALL becomes per-pointer CANCEL; everything else is unchanged
        assertEquals(0x04, DualScreenLayout.touchType((byte) 0x07));
        for (byte t : new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x06}) {
            assertEquals(t, DualScreenLayout.touchType(t));
        }
        // MOVE (2) and CANCEL (3) cover every pointer
        assertArrayEquals(new int[] {0, 3}, DualScreenLayout.pointerRange(2, 1, 3));
        assertArrayEquals(new int[] {0, 3}, DualScreenLayout.pointerRange(3, 2, 3));
        // DOWN (0), UP (1), POINTER_DOWN (5), POINTER_UP (6), HOVER_MOVE (7): the action index only
        for (int action : new int[] {0, 1, 5, 6, 7}) {
            assertArrayEquals(new int[] {2, 3}, DualScreenLayout.pointerRange(action, 2, 4));
        }
    }

    @Test
    public void modeOverridesOnlyWhenOn() {
        DualScreenLayout.Mode on = DualScreenLayout.mode(true, 1920, 1080, 120f);
        assertEquals(1920, on.width);
        assertEquals(2160, on.height);
        assertEquals(60f, on.fps, 0f);

        DualScreenLayout.Mode off = DualScreenLayout.mode(false, 2560, 1440, 119.88f);
        assertEquals(2560, off.width);
        assertEquals(1440, off.height);
        assertEquals(119.88f, off.fps, 0f);
    }

    @Test
    public void cropRects() {
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, DualScreenLayout.TOP_CROP);
        assertArrayEquals(new int[] {0, 1080, 1240, 1080}, DualScreenLayout.BOTTOM_CROP);
    }

    @Test
    public void largerDisplayIsTop() {
        assertTrue(DualScreenLayout.isTopDisplay(1920, 1080, 1240, 1080));
        assertFalse(DualScreenLayout.isTopDisplay(1240, 1080, 1920, 1080));
        // Orientation does not matter
        assertTrue(DualScreenLayout.isTopDisplay(1080, 1920, 1240, 1080));
        assertFalse(DualScreenLayout.isTopDisplay(1080, 1240, 1080, 1920));
    }

    @Test
    public void equalDisplaysKeepTheMainOneOnTop() {
        assertTrue(DualScreenLayout.isTopDisplay(1920, 1080, 1920, 1080));
        assertTrue(DualScreenLayout.isTopDisplay(1080, 1920, 1920, 1080));
    }

    @Test
    public void texCoordsSelectTheCropBeforeTheMatrix() {
        // Strip order BL, BR, TL, TR; v = 1 is the top of the upright video
        assertArrayEquals(new float[] {0f, 0.5f, 1f, 0.5f, 0f, 1f, 1f, 1f},
                DualScreenLayout.texCoords(DualScreenLayout.TOP_CROP), EPS);
        float u1 = 1240f / 1920;
        assertArrayEquals(new float[] {0f, 0f, u1, 0f, 0f, 0.5f, u1, 0.5f},
                DualScreenLayout.texCoords(DualScreenLayout.BOTTOM_CROP), EPS);
    }

    // SurfaceTexture's matrix for a buffer of bufH rows whose first 2160 rows hold the picture
    // (GLConsumer::computeTransformMatrix without the filtering shrink): crop, then flip V.
    // Column-major; result v' = 1 - (ty + sy * v), so buffer row = v' * bufH.
    private static float[] paddedMatrix(int bufH) {
        float sy = 2160f / bufH;
        float ty = (bufH - 2160f) / bufH;
        return new float[] {
                1, 0, 0, 0,
                0, -sy, 0, 0,
                0, 0, 1, 0,
                0, 1 - ty, 0, 1};
    }

    private static void assertCropRows(int bufH, int[] crop, float topRow, float bottomRow, float rightCol) {
        float[] m = paddedMatrix(bufH);
        float[] tc = DualScreenLayout.texCoords(crop);
        // BL, BR, TL, TR
        float[] bl = DualScreenLayout.applyTexMatrix(m, tc[0], tc[1]);
        float[] br = DualScreenLayout.applyTexMatrix(m, tc[2], tc[3]);
        float[] tl = DualScreenLayout.applyTexMatrix(m, tc[4], tc[5]);
        float[] tr = DualScreenLayout.applyTexMatrix(m, tc[6], tc[7]);
        assertEquals(bottomRow, bl[1] * bufH, 1e-3f);
        assertEquals(bottomRow, br[1] * bufH, 1e-3f);
        assertEquals(topRow, tl[1] * bufH, 1e-3f);
        assertEquals(topRow, tr[1] * bufH, 1e-3f);
        assertEquals(0f, bl[0] * 1920, 1e-3f);
        assertEquals(rightCol, tr[0] * 1920, 1e-3f);
    }

    @Test
    public void paddedBufferMapsCropsToTheirTexels() {
        // Qualcomm pads 2160 to 2176 rows; the padding (rows 2160..2175) is never sampled
        assertCropRows(2176, DualScreenLayout.TOP_CROP, 0f, 1080f, 1920f);
        assertCropRows(2176, DualScreenLayout.BOTTOM_CROP, 1080f, 2160f, 1240f);
        // Unpadded buffer: same rows
        assertCropRows(2160, DualScreenLayout.TOP_CROP, 0f, 1080f, 1920f);
        assertCropRows(2160, DualScreenLayout.BOTTOM_CROP, 1080f, 2160f, 1240f);
    }
}

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
    public void mousePositionIsInVideoPixels() {
        assertArrayEquals(new int[] {960, 540, 1920, 2160},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 960, 540, 1920, 1080));
        assertArrayEquals(new int[] {1920, 1080, 1920, 2160},
                DualScreenLayout.mousePosition(DualScreenLayout.TOP_CROP, 1920, 1080, 1920, 1080));
    }

    @Test
    public void pointerIdsNeverCollide() {
        Set<Integer> top = new HashSet<>();
        Set<Integer> bottom = new HashSet<>();
        for (int id = 0; id <= DualScreenLayout.MAX_ANDROID_POINTER_ID; id++) {
            top.add(DualScreenLayout.topPointerId(id));
            bottom.add(DualScreenLayout.bottomPointerId(id));
        }
        assertEquals(32, top.size());
        assertEquals(32, bottom.size());
        for (int id : top) {
            assertFalse("collision on " + id, bottom.contains(id));
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
    public void texCoordsSelectTheCrop() {
        // Strip order BL, BR, TL, TR; v = 0 is the top of the video
        assertArrayEquals(new float[] {0f, 0.5f, 1f, 0.5f, 0f, 0f, 1f, 0f},
                DualScreenLayout.texCoords(DualScreenLayout.TOP_CROP), EPS);
        float u1 = 1240f / 1920;
        assertArrayEquals(new float[] {0f, 1f, u1, 1f, 0f, 0.5f, u1, 0.5f},
                DualScreenLayout.texCoords(DualScreenLayout.BOTTOM_CROP), EPS);
    }
}

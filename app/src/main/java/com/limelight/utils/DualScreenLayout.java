package com.limelight.utils;

/**
 * Pure math for the "Dual screen" mode: one composite stream split over two displays.
 * Every size lives here. The side-by-side fallback (3160x1080) is VIDEO_W = 3160,
 * VIDEO_H = 1080, BOTTOM_X = 1920, BOTTOM_Y = 0; nothing else changes.
 */
public final class DualScreenLayout {
    // Composite stream requested from the host (stacked: game on top, companion below)
    public static final int VIDEO_W = 1920;
    public static final int VIDEO_H = 2160;
    public static final int VIDEO_FPS = 60;

    public static final int TOP_X = 0, TOP_Y = 0, TOP_W = 1920, TOP_H = 1080;
    public static final int BOTTOM_X = 0, BOTTOM_Y = 1080, BOTTOM_W = 1240, BOTTOM_H = 1080;

    /** Crop rects in video pixels: {x, y, width, height}. */
    public static final int[] TOP_CROP = {TOP_X, TOP_Y, TOP_W, TOP_H};
    public static final int[] BOTTOM_CROP = {BOTTOM_X, BOTTOM_Y, BOTTOM_W, BOTTOM_H};

    // Android pointer ids are 0..31 per display, so the bottom display's ids start past them
    public static final int MAX_ANDROID_POINTER_ID = 31;
    public static final int BOTTOM_POINTER_ID_BASE = MAX_ANDROID_POINTER_ID + 1;

    private DualScreenLayout() {
    }

    /** The stream mode to request. */
    public static final class Mode {
        public final int width;
        public final int height;
        public final float fps;

        Mode(int width, int height, float fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    /** The composite mode when on; the negotiated values, unchanged, when off. */
    public static Mode mode(boolean on, int width, int height, float fps) {
        return on ? new Mode(VIDEO_W, VIDEO_H, VIDEO_FPS) : new Mode(width, height, fps);
    }

    /** True if the display of size w x h shows the top crop: the larger display does. */
    public static boolean isTopDisplay(int w, int h, int otherW, int otherH) {
        return (long) w * h >= (long) otherW * otherH;
    }

    /**
     * Maps a point given as a fraction (0..1, clamped) of the view showing {@code crop}
     * to x/y normalized over the whole video.
     */
    public static float[] toVideo(int[] crop, float fx, float fy) {
        return new float[] {
                (crop[0] + clamp01(fx) * crop[2]) / VIDEO_W,
                (crop[1] + clamp01(fy) * crop[3]) / VIDEO_H
        };
    }

    /** Absolute mouse position {x, y, refWidth, refHeight} in video pixels for a view touch. */
    public static int[] mousePosition(int[] crop, float x, float y, int viewW, int viewH) {
        float[] v = toVideo(crop, x / viewW, y / viewH);
        return new int[] {Math.round(v[0] * VIDEO_W), Math.round(v[1] * VIDEO_H), VIDEO_W, VIDEO_H};
    }

    public static int topPointerId(int androidPointerId) {
        return androidPointerId;
    }

    public static int bottomPointerId(int androidPointerId) {
        return BOTTOM_POINTER_ID_BASE + androidPointerId;
    }

    /**
     * Texture coordinates of {@code crop} for a triangle strip over the quad
     * {-1,-1, 1,-1, -1,1, 1,1}, with v = 0 at the top of the video (Stereo3DRenderer's convention).
     */
    public static float[] texCoords(int[] crop) {
        float u0 = (float) crop[0] / VIDEO_W;
        float u1 = (float) (crop[0] + crop[2]) / VIDEO_W;
        float vTop = (float) crop[1] / VIDEO_H;
        float vBottom = (float) (crop[1] + crop[3]) / VIDEO_H;
        return new float[] {u0, vBottom, u1, vBottom, u0, vTop, u1, vTop};
    }

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }
}

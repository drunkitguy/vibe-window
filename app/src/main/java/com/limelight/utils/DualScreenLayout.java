package com.limelight.utils;

/**
 * Pure math for the "Dual screen" mode: one composite stream split over two displays.
 * Every size lives here, in VIDEO_* and the two crop rects. Fallbacks are constant changes:
 * side by side (3160x1080): VIDEO_W = 3160, VIDEO_H = 1080, BOTTOM_CROP = {1920, 0, 1240, 1080};
 * taskbar band (H2, 1920x2240): VIDEO_H = 2240, crops unchanged (the 80 px band is never shown).
 */
public final class DualScreenLayout {
    // Composite stream requested from the host (stacked: game on top, companion below)
    public static final int VIDEO_W = 1920;
    public static final int VIDEO_H = 2160;
    public static final int VIDEO_FPS = 60;

    /** Crop rects in video pixels, y down from the top of the video: {x, y, width, height}. */
    public static final int[] TOP_CROP = {0, 0, 1920, 1080};
    public static final int[] BOTTOM_CROP = {0, 1080, 1240, 1080};

    // apollo2-vibe has 10 touch slots per client (POINTER_TYPE_INFO touchInfo[10], also the
    // CreateSyntheticPointerDevice maxCount) and copies our id into POINTER_INFO.pointerId, so
    // all ids stay below 10: top display 0..4, bottom display 5..9. A 6th finger on one display is dropped.
    public static final int HOST_TOUCH_SLOTS = 10;
    public static final int POINTERS_PER_DISPLAY = HOST_TOUCH_SLOTS / 2;

    // LI_TOUCH_EVENT_* (moonlight-common-c, MoonBridge) and MotionEvent.ACTION_* values, copied so
    // this class stays plain Java
    static final byte LI_TOUCH_EVENT_CANCEL = 0x04;
    static final byte LI_TOUCH_EVENT_CANCEL_ALL = 0x07;
    static final int ACTION_MOVE = 2;
    static final int ACTION_CANCEL = 3;

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

    /** True if the display of size w x h shows the top crop: the larger display does; a tie keeps it. */
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

    /** Scales a size given as a fraction of the view showing {@code crop} to a fraction of the video. */
    public static float[] sizeToVideo(int[] crop, float fw, float fh) {
        return new float[] {fw * crop[2] / VIDEO_W, fh * crop[3] / VIDEO_H};
    }

    /** Absolute mouse position {x, y} in video pixels (reference size VIDEO_W x VIDEO_H) for a view touch. */
    public static int[] mousePosition(int[] crop, float x, float y, int viewW, int viewH) {
        float[] v = toVideo(crop, x / viewW, y / viewH);
        return new int[] {Math.round(v[0] * VIDEO_W), Math.round(v[1] * VIDEO_H)};
    }

    /** Host pointer id for an Android pointer id on one display, or -1 to drop it (over the slot share). */
    public static int pointerId(boolean bottom, int androidPointerId) {
        if (androidPointerId < 0 || androidPointerId >= POINTERS_PER_DISPLAY) {
            return -1;
        }
        return bottom ? POINTERS_PER_DISPLAY + androidPointerId : androidPointerId;
    }

    /** CANCEL_ALL would also drop the other display's touches, so a display cancels its pointers one by one. */
    public static byte touchType(byte liTouchType) {
        return liTouchType == LI_TOUCH_EVENT_CANCEL_ALL ? LI_TOUCH_EVENT_CANCEL : liTouchType;
    }

    /** Pointer indexes [first, end) to send: MOVE and CANCEL cover every pointer, other actions the action index. */
    public static int[] pointerRange(int actionMasked, int actionIndex, int pointerCount) {
        if (actionMasked == ACTION_MOVE || actionMasked == ACTION_CANCEL) {
            return new int[] {0, pointerCount};
        }
        return new int[] {actionIndex, actionIndex + 1};
    }

    /**
     * Texture coordinates of {@code crop} for a triangle strip over the quad
     * {-1,-1, 1,-1, -1,1, 1,1}, before the SurfaceTexture transform matrix: (0,0) is the
     * bottom-left of the upright video, (1,1) its top-right.
     */
    public static float[] texCoords(int[] crop) {
        float u0 = (float) crop[0] / VIDEO_W;
        float u1 = (float) (crop[0] + crop[2]) / VIDEO_W;
        float vTop = 1f - (float) crop[1] / VIDEO_H;
        float vBottom = 1f - (float) (crop[1] + crop[3]) / VIDEO_H;
        return new float[] {u0, vBottom, u1, vBottom, u0, vTop, u1, vTop};
    }

    /** The vertex shader's (m * vec4(u, v, 0, 1)).xy for a column-major 4x4 matrix m. */
    public static float[] applyTexMatrix(float[] m, float u, float v) {
        return new float[] {m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13]};
    }

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }
}

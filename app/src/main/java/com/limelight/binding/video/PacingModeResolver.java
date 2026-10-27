package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Resolves the frame pacing mode the user picked against the active display. Pure Java, so
 * it can be tested on the JVM.
 *
 * The rules are the cap-fps fallback that used to be written inline in Game.java, moved
 * unchanged: with "Balanced with FPS limit", a stream faster than the display by more than
 * 3 FPS uses Balanced (drop mode), a display of 49 Hz or less uses Balanced, and otherwise
 * the target frame rate becomes one below the display refresh rate. Every other mode passes
 * through untouched.
 */
public final class PacingModeResolver {

    /** The outcome of {@link #resolveForDisplay(int, float, int)}. */
    public static final class Result {
        /** The pacing mode to use, one of the PreferenceConfiguration.FRAME_PACING_* values. */
        public final int framePacing;
        /** The frame rate to request from the host (before any warp factor). */
        public final float frameRate;
        /** What changed and why, for the log, or null when nothing changed. */
        public final String logMessage;

        Result(int framePacing, float frameRate, String logMessage) {
            this.framePacing = framePacing;
            this.frameRate = frameRate;
            this.logMessage = logMessage;
        }
    }

    private PacingModeResolver() {
    }

    /**
     * @param userMode the mode from the preferences
     * @param fps the stream frame rate the user asked for
     * @param displayHz the active display refresh rate, rounded
     */
    public static Result resolveForDisplay(int userMode, float fps, int displayHz) {
        if (userMode == PreferenceConfiguration.FRAME_PACING_CAP_FPS && fps >= displayHz) {
            if (fps > displayHz + 3) {
                // Use frame drops when rendering above the screen frame rate
                return new Result(PreferenceConfiguration.FRAME_PACING_BALANCED, fps,
                        "Using drop mode for FPS > Hz");
            }
            else if (displayHz <= 49) {
                // Avoid clearly bogus refresh rates and fall back to legacy rendering
                return new Result(PreferenceConfiguration.FRAME_PACING_BALANCED, fps,
                        "Bogus refresh rate: " + displayHz);
            }
            else {
                float capped = displayHz - 1;
                return new Result(userMode, capped, "Adjusting FPS target for screen to " + capped);
            }
        }
        return new Result(userMode, fps, null);
    }

    /**
     * The newest-frame pacer follows the Choreographer, which ticks with the primary
     * display's vsync. On any other display the legacy immediate path ("latency") is used.
     */
    public static int forDisplay(int mode, boolean primaryDisplay) {
        if (mode == PreferenceConfiguration.FRAME_PACING_NEWEST && !primaryDisplay) {
            return PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        }
        return mode;
    }

    /**
     * Whether "Reduce refresh rate" applies in this mode. Smoothest and Cap FPS always allow a
     * lower refresh rate on their own; Balanced, newest and latency do when the user asks.
     */
    public static boolean honoursReduceRefreshRate(int mode) {
        return mode == PreferenceConfiguration.FRAME_PACING_BALANCED ||
                mode == PreferenceConfiguration.FRAME_PACING_NEWEST ||
                mode == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
    }

    /** The pacing mode only, see {@link #resolveForDisplay(int, float, int)}. */
    public static int resolve(int userMode, float fps, int displayHz) {
        return resolveForDisplay(userMode, fps, displayHz).framePacing;
    }

    /**
     * Whether the renderer may use its "latest only" fast path, which releases the newest
     * waiting frame at once without pacing. Smoothest and Cap FPS promise never to drop
     * frames and pace on their own, so they skip it. The newest-frame mode has its own
     * pacer and skips it too.
     */
    public static boolean usesLatestOnlyPath(int mode) {
        return mode != PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS &&
                mode != PreferenceConfiguration.FRAME_PACING_CAP_FPS &&
                mode != PreferenceConfiguration.FRAME_PACING_NEWEST;
    }

    /**
     * The mode name with the warp factor, for logs and the overlay: "latency+warp" for a
     * factor of 2 and "latency+warp2" for 4 (the warp entries use the latency path).
     */
    public static String modeName(int mode, int warpFactor) {
        String name = modeName(mode);
        if (warpFactor == 2) {
            return name + "+warp";
        }
        else if (warpFactor == 4) {
            return name + "+warp2";
        }
        else if (warpFactor > 0) {
            return name + "+warp(" + warpFactor + ")";
        }
        return name;
    }

    /** The stored preference value of a mode, for logs and the overlay. */
    public static String modeName(int mode) {
        switch (mode) {
            case PreferenceConfiguration.FRAME_PACING_NEWEST:
                return "newest";
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
                return "latency";
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                return "balanced";
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                return "cap-fps";
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return "smoothness";
            default:
                return "unknown(" + mode + ")";
        }
    }
}

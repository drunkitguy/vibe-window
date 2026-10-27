package com.limelight.preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Resolves "Auto (match display)" into a concrete stream size and frame rate.
 * Pure Java with no Android imports, so every rule is unit tested. The Android
 * side (AutoResolutionAndroid) only gathers the inputs.
 */
public final class AutoResolution {

    /** Policy limits that keep auto inside what hosts and NvConnection accept. */
    public static final int MAX_LONG_EDGE = 4096;
    public static final int MAX_SHORT_EDGE = 2160;
    // DCI 4K (4096x2160) is the largest size kept as is
    public static final long MAX_PIXELS = 4096L * 2160L;

    public static final int MIN_WIDTH = 640;
    public static final int MIN_HEIGHT = 360;

    public static final float MIN_FPS = 30f;
    public static final float MAX_FPS = 240f;
    /** The decoder clamp never lowers an auto frame rate below this. */
    public static final float MIN_DECODER_FPS = 60f;

    /** Short edges the decoder clamp steps down through, keeping the aspect ratio. */
    static final int[] SHORT_EDGE_LADDER = {2160, 1600, 1440, 1200, 1080};

    public enum Mode { EXPLICIT, ACTIVITY_DISPLAY, MIRRORED_EXTERNAL }

    public enum Clamp { NONE, POLICY, DECODER }

    private AutoResolution() {}

    /** One display mode, in physical pixels as the display reports it. */
    public static final class DisplayMode {
        public final int width;
        public final int height;
        public final float refreshRate;

        public DisplayMode(int width, int height, float refreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }
    }

    /** What the resolver needs to know about one display. */
    public static final class DisplayInfo {
        public final int id;
        public final boolean isDefault;
        /** Physical size and refresh rate of the current mode. */
        public final int width;
        public final int height;
        public final float refreshRate;
        public final List<DisplayMode> supportedModes;
        public final boolean presentation;
        public final boolean privateDisplay;
        public final boolean hasProductInfo;
        public final boolean on;

        public DisplayInfo(int id, boolean isDefault, int width, int height, float refreshRate,
                           List<DisplayMode> supportedModes, boolean presentation,
                           boolean privateDisplay, boolean hasProductInfo, boolean on) {
            this.id = id;
            this.isDefault = isDefault;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
            this.supportedModes = supportedModes != null ? supportedModes : Collections.<DisplayMode>emptyList();
            this.presentation = presentation;
            this.privateDisplay = privateDisplay;
            this.hasProductInfo = hasProductInfo;
            this.on = on;
        }

        long pixels() {
            return (long) width * height;
        }
    }

    /** What the video decoder reports it can do. */
    public interface DecoderCaps {
        /** False when the decoder does not even report 1280 wide support; its data is then ignored. */
        boolean trustworthy();

        boolean isSizeSupported(int width, int height);

        boolean isSizeAndRateSupported(int width, int height, float fps);
    }

    public static final class Request {
        public boolean autoResolution;
        public boolean autoFps;
        /** Explicit (or placeholder) values, used for whatever is not auto. */
        public int width;
        public int height;
        public float fps;
        public DisplayInfo activityDisplay;
        public List<DisplayInfo> otherDisplays = new ArrayList<>();
        public boolean preferExternalWhenMirrored = true;
        public DecoderCaps caps;
    }

    public static final class Result {
        public final int width;
        public final int height;
        public final float fps;
        public final int targetDisplayId;
        public final Mode mode;
        public final Clamp clamp;
        public final String reason;

        Result(int width, int height, float fps, int targetDisplayId, Mode mode, Clamp clamp, String reason) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.targetDisplayId = targetDisplayId;
            this.mode = mode;
            this.clamp = clamp;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return width + "x" + height + " at " + formatFps(fps) + " FPS (" + reason + ")";
        }
    }

    public static Result resolve(Request request) {
        int displayId = request.activityDisplay != null ? request.activityDisplay.id : -1;

        // 1. Explicit choices pass through untouched
        if (!request.autoResolution && !request.autoFps) {
            return new Result(request.width, request.height, request.fps, displayId,
                    Mode.EXPLICIT, Clamp.NONE, "explicit settings");
        }

        // 2. The display the activity is shown on
        DisplayInfo target = request.activityDisplay;
        if (target == null || target.width <= 0 || target.height <= 0) {
            return new Result(request.width, request.height, request.fps, displayId,
                    Mode.ACTIVITY_DISPLAY, Clamp.NONE, "no display information, using defaults");
        }

        // 3. The default display mirrored onto a larger external display
        Mode mode = Mode.ACTIVITY_DISPLAY;
        DisplayInfo chosen = target;
        if (target.isDefault && request.preferExternalWhenMirrored) {
            DisplayInfo external = findMirrorTarget(target, request.otherDisplays);
            if (external != null) {
                chosen = external;
                mode = Mode.MIRRORED_EXTERNAL;
            }
        }

        StringBuilder reason = new StringBuilder();
        reason.append(mode == Mode.MIRRORED_EXTERNAL ? "mirrored external display " : "display ")
                .append(chosen.id);

        int width = request.width;
        int height = request.height;
        float fps = request.fps;
        Clamp clamp = Clamp.NONE;

        // 4. Native size of the chosen display, landscape
        if (request.autoResolution) {
            int longEdge = Math.max(chosen.width, chosen.height);
            int shortEdge = Math.min(chosen.width, chosen.height);
            if (mode == Mode.MIRRORED_EXTERNAL) {
                // Android letterboxes the mirrored default display: fit its aspect inside the external one
                int[] fitted = fitAspect(Math.max(target.width, target.height),
                        Math.min(target.width, target.height), longEdge, shortEdge);
                longEdge = fitted[0];
                shortEdge = fitted[1];
            }
            width = longEdge;
            height = shortEdge;
            reason.append(", native ").append(width).append('x').append(height);
        }

        // 5. Highest refresh rate available at the current size
        List<Float> rates = refreshRatesAtCurrentSize(chosen);
        if (request.autoFps) {
            if (!rates.isEmpty()) {
                fps = normalizeFps(rates.get(0));
                reason.append(", ").append(formatFps(fps)).append(" Hz");
            }
            else {
                reason.append(", refresh rate unknown");
            }
        }

        if (request.autoResolution) {
            // 6. Policy clamp, keeping the aspect ratio
            int[] policy = clampToPolicy(width, height);
            if (policy[0] != width || policy[1] != height) {
                clamp = Clamp.POLICY;
                reason.append(", policy limit ").append(policy[0]).append('x').append(policy[1]);
            }

            // 7. Alignment
            int[] aligned = align(policy[0], policy[1]);
            width = aligned[0];
            height = aligned[1];
        }

        // 8. Decoder clamp, only above 1080p and only with trustworthy data
        DecoderCaps caps = request.caps;
        if (caps != null && caps.trustworthy() && isAbove1080p(width, height)) {
            int startWidth = width;
            int startHeight = height;
            float startFps = fps;
            double aspect = (double) width / height;

            if (request.autoResolution) {
                while (isAbove1080p(width, height) && !caps.isSizeSupported(width, height)) {
                    int[] next = stepDown(height, aspect);
                    if (next == null) {
                        break;
                    }
                    width = next[0];
                    height = next[1];
                }
            }

            while (isAbove1080p(width, height) && !caps.isSizeAndRateSupported(width, height, fps)) {
                Float lower = request.autoFps ? nextLowerRate(rates, fps) : null;
                if (lower != null) {
                    fps = lower;
                    continue;
                }
                int[] next = request.autoResolution ? stepDown(height, aspect) : null;
                if (next == null) {
                    break;
                }
                width = next[0];
                height = next[1];
            }

            if (width != startWidth || height != startHeight || fps != startFps) {
                clamp = Clamp.DECODER;
                reason.append(", decoder limit ").append(width).append('x').append(height)
                        .append(" at ").append(formatFps(fps));
            }
        }

        return new Result(width, height, fps, chosen.id, mode, clamp, reason.toString());
    }

    /** The external display a mirrored default display most likely shows on, or null. */
    static DisplayInfo findMirrorTarget(DisplayInfo defaultDisplay, List<DisplayInfo> others) {
        DisplayInfo best = null;
        if (others == null) {
            return null;
        }
        for (DisplayInfo candidate : others) {
            if (candidate == null || candidate.id == defaultDisplay.id || !candidate.on ||
                    candidate.privateDisplay || !(candidate.presentation || candidate.hasProductInfo)) {
                continue;
            }
            // Only clearly larger displays win; a small second panel never does
            if (candidate.pixels() <= defaultDisplay.pixels()) {
                continue;
            }
            if (best == null || candidate.pixels() > best.pixels() ||
                    (candidate.pixels() == best.pixels() && candidate.hasProductInfo && !best.hasProductInfo)) {
                best = candidate;
            }
        }
        return best;
    }

    /** Largest size with the source aspect ratio that fits inside the container. */
    static int[] fitAspect(int sourceLong, int sourceShort, int containerLong, int containerShort) {
        if (sourceLong <= 0 || sourceShort <= 0) {
            return new int[] {containerLong, containerShort};
        }
        // Compare sourceLong / sourceShort with containerLong / containerShort without rounding
        if ((long) containerLong * sourceShort > (long) sourceLong * containerShort) {
            // Container is wider: height limited
            return new int[] {(int) ((long) containerShort * sourceLong / sourceShort), containerShort};
        }
        return new int[] {containerLong, (int) ((long) containerLong * sourceShort / sourceLong)};
    }

    /** Distinct refresh rates of the modes with the current size, highest first. */
    static List<Float> refreshRatesAtCurrentSize(DisplayInfo display) {
        List<Float> rates = new ArrayList<>();
        int longEdge = Math.max(display.width, display.height);
        int shortEdge = Math.min(display.width, display.height);
        for (DisplayMode mode : display.supportedModes) {
            if (Math.max(mode.width, mode.height) == longEdge && Math.min(mode.width, mode.height) == shortEdge) {
                addRate(rates, mode.refreshRate);
            }
        }
        addRate(rates, display.refreshRate);
        Collections.sort(rates, Collections.<Float>reverseOrder());
        return rates;
    }

    private static void addRate(List<Float> rates, float rate) {
        if (rate <= 0 || Float.isNaN(rate) || Float.isInfinite(rate)) {
            return;
        }
        float normalized = normalizeFps(rate);
        for (Float existing : rates) {
            if (Math.abs(existing - normalized) < 0.01f) {
                return;
            }
        }
        rates.add(normalized);
    }

    /** Rounds to an integer when within 0.05 of one, and clamps to 30 to 240. */
    static float normalizeFps(float fps) {
        float rounded = Math.round(fps);
        if (Math.abs(fps - rounded) <= 0.05f) {
            fps = rounded;
        }
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    private static Float nextLowerRate(List<Float> rates, float fps) {
        for (Float rate : rates) {
            if (rate < fps - 0.01f && rate >= MIN_DECODER_FPS) {
                return rate;
            }
        }
        return null;
    }

    /** Uniform scale down so the long edge, short edge and pixel count stay inside the policy. */
    static int[] clampToPolicy(int width, int height) {
        int longEdge = Math.max(width, height);
        int shortEdge = Math.min(width, height);
        if (longEdge <= 0 || shortEdge <= 0) {
            return new int[] {width, height};
        }
        double scale = 1.0;
        scale = Math.min(scale, (double) MAX_LONG_EDGE / longEdge);
        scale = Math.min(scale, (double) MAX_SHORT_EDGE / shortEdge);
        scale = Math.min(scale, Math.sqrt((double) MAX_PIXELS / ((double) longEdge * shortEdge)));
        if (scale >= 1.0) {
            return new int[] {width, height};
        }
        // A tiny epsilon keeps exact ratios such as 0.5 from flooring one pixel short
        return new int[] {(int) Math.floor(width * scale + 1e-6), (int) Math.floor(height * scale + 1e-6)};
    }

    /** Width to a multiple of 8 and height to a multiple of 2, never below 640x360. */
    static int[] align(int width, int height) {
        int w = Math.max(MIN_WIDTH, width - (width % 8));
        int h = Math.max(MIN_HEIGHT, height - (height % 2));
        return new int[] {w, h};
    }

    private static boolean isAbove1080p(int width, int height) {
        return width > 1920 || height > 1080;
    }

    /** Next smaller size on the short edge ladder with the same aspect ratio, or null at 1080. */
    static int[] stepDown(int currentHeight, double aspect) {
        for (int shortEdge : SHORT_EDGE_LADDER) {
            if (shortEdge < currentHeight) {
                int width = (int) Math.round(shortEdge * aspect);
                return align(width, shortEdge);
            }
        }
        return null;
    }

    static String formatFps(float fps) {
        if (fps == Math.round(fps)) {
            return Integer.toString(Math.round(fps));
        }
        return String.format(Locale.US, "%.2f", fps);
    }
}

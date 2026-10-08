package com.limelight.preferences;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;

import com.limelight.LimeLog;
import com.limelight.binding.video.MediaCodecHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gathers display and decoder information for {@link AutoResolution} and writes the
 * result into a {@link PreferenceConfiguration}. Game calls {@link #resolveInto} once
 * at stream start. With explicit resolution and FPS it returns before touching any
 * display or codec API, so explicit choices behave exactly as before.
 */
public final class AutoResolutionAndroid {

    private static final int FALLBACK_WIDTH = 1920;
    private static final int FALLBACK_HEIGHT = 1080;
    private static final float FALLBACK_FPS = 60f;

    /** Source of display and decoder information; replaced in tests. */
    interface Probe {
        AutoResolution.DisplayInfo activityDisplay(Activity activity);

        List<AutoResolution.DisplayInfo> otherDisplays(Activity activity, int activityDisplayId);

        AutoResolution.DecoderCaps decoderCaps(Activity activity, PreferenceConfiguration prefConfig);
    }

    static Probe probe = new AndroidProbe();

    private AutoResolutionAndroid() {}

    /**
     * Replaces the auto placeholders in prefConfig with values for the display the
     * activity is shown on. Never throws; on any failure it falls back to the
     * display's current mode, else 1920x1080 at 60 FPS.
     */
    public static void resolveInto(Activity activity, PreferenceConfiguration prefConfig) {
        if (prefConfig == null || (!prefConfig.autoResolution && !prefConfig.autoFps)) {
            // Explicit resolution and FPS: no display or codec query at all
            return;
        }
        resolveInto(activity, prefConfig, probe);
    }

    static void resolveInto(Activity activity, PreferenceConfiguration prefConfig, Probe source) {
        if (prefConfig == null || (!prefConfig.autoResolution && !prefConfig.autoFps)) {
            return;
        }

        try {
            AutoResolution.Result result;
            try {
                result = AutoResolution.resolve(buildRequest(activity, prefConfig, source,
                        prefConfig.autoResolution, prefConfig.autoFps,
                        prefConfig.width, prefConfig.height, prefConfig.fps, true));
            } catch (Throwable t) {
                LimeLog.warning("Auto resolution failed, using the display's current mode: " + t);
                result = fallback(activity, prefConfig, source);
            }
            apply(prefConfig, result);
        } catch (Throwable t) {
            // Last resort: keep the stream startable
            LimeLog.warning("Auto resolution fallback failed: " + t);
            if (prefConfig.autoResolution) {
                prefConfig.width = FALLBACK_WIDTH;
                prefConfig.height = FALLBACK_HEIGHT;
            }
            if (prefConfig.autoFps) {
                prefConfig.fps = FALLBACK_FPS;
            }
        }
    }

    private static void apply(PreferenceConfiguration prefConfig, AutoResolution.Result result) {
        prefConfig.width = result.width;
        prefConfig.height = result.height;
        prefConfig.fps = result.fps;

        if (prefConfig.bitrateFollowsResolution) {
            int bitrate = Math.min(PreferenceConfiguration.MAX_BITRATE_KBPS,
                    PreferenceConfiguration.getDefaultBitrate(result.width, result.height, result.fps));
            prefConfig.bitrate = bitrate;
            if (prefConfig.meteredBitrateDerived) {
                prefConfig.meteredBitrate = bitrate / 4;
            }
        }

        LimeLog.info("Auto resolution: " + result.width + "x" + result.height + " at " +
                AutoResolution.formatFps(result.fps) + " FPS, bitrate " + prefConfig.bitrate +
                " Kbps (" + result.mode + ", " + result.reason + ")");
    }

    private static AutoResolution.Result fallback(Activity activity, PreferenceConfiguration prefConfig, Probe source) {
        int width = prefConfig.width;
        int height = prefConfig.height;
        float fps = prefConfig.fps;
        int displayId = Display.INVALID_DISPLAY;
        String reason;
        try {
            AutoResolution.DisplayInfo display = source.activityDisplay(activity);
            displayId = display.id;
            if (prefConfig.autoResolution) {
                int[] size = AutoResolution.clampToPolicy(Math.max(display.width, display.height),
                        Math.min(display.width, display.height));
                size = AutoResolution.align(size[0], size[1]);
                width = size[0];
                height = size[1];
            }
            if (prefConfig.autoFps && display.refreshRate > 0) {
                fps = AutoResolution.normalizeFps(display.refreshRate);
            }
            reason = "fallback to the current mode of display " + display.id;
        } catch (Throwable t) {
            if (prefConfig.autoResolution) {
                width = FALLBACK_WIDTH;
                height = FALLBACK_HEIGHT;
            }
            if (prefConfig.autoFps) {
                fps = FALLBACK_FPS;
            }
            reason = "fallback to 1920x1080 at 60 FPS";
        }
        if (prefConfig.autoFps && fps <= 0) {
            fps = FALLBACK_FPS;
        }
        return new AutoResolution.Result(width, height, fps, displayId,
                AutoResolution.Mode.ACTIVITY_DISPLAY, AutoResolution.Clamp.NONE, reason);
    }

    static AutoResolution.Request buildRequest(Activity activity, PreferenceConfiguration prefConfig, Probe source,
                                               boolean autoResolution, boolean autoFps,
                                               int width, int height, float fps, boolean withDecoder) {
        AutoResolution.Request request = new AutoResolution.Request();
        request.autoResolution = autoResolution;
        request.autoFps = autoFps;
        request.width = width;
        request.height = height;
        request.fps = fps;
        request.preferExternalWhenMirrored = prefConfig.autoResPreferExternal;
        request.activityDisplay = source.activityDisplay(activity);
        request.otherDisplays = source.otherDisplays(activity,
                request.activityDisplay != null ? request.activityDisplay.id : Display.INVALID_DISPLAY);
        if (withDecoder) {
            try {
                request.caps = source.decoderCaps(activity, prefConfig);
            } catch (Throwable t) {
                LimeLog.warning("Auto resolution: decoder capabilities unavailable: " + t);
                request.caps = null;
            }
        }
        return request;
    }

    /**
     * What auto resolves to on the display this activity is on, for the settings hint
     * and the debug info screen. Returns null on failure.
     */
    public static AutoResolution.Result preview(Activity activity, PreferenceConfiguration prefConfig,
                                                boolean autoResolution, boolean autoFps,
                                                int width, int height, float fps) {
        try {
            return AutoResolution.resolve(buildRequest(activity, prefConfig, probe,
                    autoResolution, autoFps, width, height, fps, true));
        } catch (Throwable t) {
            LimeLog.warning("Auto resolution preview failed: " + t);
            return null;
        }
    }

    /** The display the activity is shown on. */
    public static Display getActivityDisplay(Activity activity) {
        Display display = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                display = activity.getDisplay();
            } catch (UnsupportedOperationException e) {
                display = null;
            }
        }
        if (display == null) {
            display = activity.getWindowManager().getDefaultDisplay();
        }
        return display;
    }

    static AutoResolution.DisplayInfo toDisplayInfo(Display display) {
        int width;
        int height;
        float refreshRate;
        List<AutoResolution.DisplayMode> modes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode current = display.getMode();
            width = current.getPhysicalWidth();
            height = current.getPhysicalHeight();
            refreshRate = current.getRefreshRate();
            for (Display.Mode mode : display.getSupportedModes()) {
                modes.add(new AutoResolution.DisplayMode(mode.getPhysicalWidth(), mode.getPhysicalHeight(),
                        mode.getRefreshRate()));
            }
        }
        else {
            // No display modes before Android 6: real size and the placeholder frame rate
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            refreshRate = 0;
        }

        int flags = display.getFlags();
        boolean hasProductInfo = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasProductInfo = display.getDeviceProductInfo() != null;
        }
        int state = display.getState();
        boolean on = state == Display.STATE_ON || state == Display.STATE_VR ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && state == Display.STATE_ON_SUSPEND);

        return new AutoResolution.DisplayInfo(display.getDisplayId(),
                display.getDisplayId() == Display.DEFAULT_DISPLAY,
                width, height, refreshRate, modes,
                (flags & Display.FLAG_PRESENTATION) != 0,
                (flags & Display.FLAG_PRIVATE) != 0,
                hasProductInfo, on);
    }

    /** Text for the debug info screen: every display and the auto decision. */
    public static String describeDisplays(Activity activity, PreferenceConfiguration prefConfig) {
        StringBuilder sb = new StringBuilder();
        try {
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            Display activityDisplay = getActivityDisplay(activity);
            sb.append("Activity display: ").append(activityDisplay.getDisplayId()).append('\n');
            for (Display display : dm.getDisplays()) {
                sb.append('\n').append("Display ").append(display.getDisplayId())
                        .append(" \"").append(display.getName()).append('"').append('\n');
                sb.append("  state ").append(stateName(display.getState()));
                int flags = display.getFlags();
                sb.append(", flags");
                if ((flags & Display.FLAG_PRESENTATION) != 0) {
                    sb.append(" presentation");
                }
                if ((flags & Display.FLAG_PRIVATE) != 0) {
                    sb.append(" private");
                }
                if ((flags & Display.FLAG_SECURE) != 0) {
                    sb.append(" secure");
                }
                if ((flags & (Display.FLAG_PRESENTATION | Display.FLAG_PRIVATE | Display.FLAG_SECURE)) == 0) {
                    sb.append(" none");
                }
                sb.append('\n');
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Display.Mode current = display.getMode();
                    sb.append("  current mode ").append(modeText(current)).append('\n');
                    sb.append("  supported modes");
                    for (Display.Mode mode : display.getSupportedModes()) {
                        sb.append(' ').append(modeText(mode));
                    }
                    sb.append('\n');
                }
                else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    display.getRealMetrics(metrics);
                    sb.append("  size ").append(metrics.widthPixels).append('x').append(metrics.heightPixels)
                            .append(" at ").append(display.getRefreshRate()).append(" Hz\n");
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Display.HdrCapabilities hdr = display.getHdrCapabilities();
                    sb.append("  HDR types");
                    if (hdr == null || hdr.getSupportedHdrTypes().length == 0) {
                        sb.append(" none");
                    }
                    else {
                        for (int type : hdr.getSupportedHdrTypes()) {
                            sb.append(' ').append(hdrName(type));
                        }
                    }
                    sb.append('\n');
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sb.append("  product info ").append(display.getDeviceProductInfo() != null ? "yes" : "no").append('\n');
                }
            }

            sb.append('\n').append("Current settings: ");
            if (prefConfig.autoResolution || prefConfig.autoFps) {
                sb.append(prefConfig.autoResolution ? "Auto" : prefConfig.width + "x" + prefConfig.height)
                        .append(" at ")
                        .append(prefConfig.autoFps ? "Auto" : AutoResolution.formatFps(prefConfig.fps))
                        .append(" FPS\n");
            }
            else {
                sb.append(prefConfig.width).append('x').append(prefConfig.height).append(" at ")
                        .append(AutoResolution.formatFps(prefConfig.fps)).append(" FPS (explicit)\n");
            }
            sb.append("Mirror rule: ").append(prefConfig.autoResPreferExternal ? "on" : "off").append('\n');

            AutoResolution.Result result = preview(activity, prefConfig, true, true,
                    PreferenceConfiguration.AUTO_PLACEHOLDER_WIDTH, PreferenceConfiguration.AUTO_PLACEHOLDER_HEIGHT,
                    PreferenceConfiguration.AUTO_PLACEHOLDER_FPS);
            sb.append("Auto would choose: ");
            if (result != null) {
                sb.append(result.width).append('x').append(result.height).append(" at ")
                        .append(AutoResolution.formatFps(result.fps)).append(" FPS on display ")
                        .append(result.targetDisplayId).append(", ").append(result.mode)
                        .append(", clamp ").append(result.clamp).append('\n')
                        .append("Reason: ").append(result.reason).append('\n');
            }
            else {
                sb.append("unavailable\n");
            }
        } catch (Throwable t) {
            sb.append("\nDisplay information unavailable: ").append(t).append('\n');
        }
        return sb.toString();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static String modeText(Display.Mode mode) {
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + "@" +
                String.format(Locale.US, "%.2f", mode.getRefreshRate());
    }

    private static String stateName(int state) {
        switch (state) {
            case Display.STATE_ON:
                return "on";
            case Display.STATE_OFF:
                return "off";
            case Display.STATE_DOZE:
                return "doze";
            case Display.STATE_DOZE_SUSPEND:
                return "doze suspend";
            case Display.STATE_VR:
                return "vr";
            default:
                return "unknown (" + state + ")";
        }
    }

    private static String hdrName(int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                return "DolbyVision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10:
                return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG:
                return "HLG";
            default:
                return "type" + type;
        }
    }

    /** Reads displays and decoder capabilities from the platform. */
    static final class AndroidProbe implements Probe {
        @Override
        public AutoResolution.DisplayInfo activityDisplay(Activity activity) {
            return toDisplayInfo(getActivityDisplay(activity));
        }

        @Override
        public List<AutoResolution.DisplayInfo> otherDisplays(Activity activity, int activityDisplayId) {
            List<AutoResolution.DisplayInfo> displays = new ArrayList<>();
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return displays;
            }
            for (Display display : dm.getDisplays()) {
                if (display.getDisplayId() == activityDisplayId) {
                    continue;
                }
                try {
                    displays.add(toDisplayInfo(display));
                } catch (Throwable t) {
                    LimeLog.warning("Auto resolution: skipping display " + display.getDisplayId() + ": " + t);
                }
            }
            return displays;
        }

        @Override
        public AutoResolution.DecoderCaps decoderCaps(Activity activity, PreferenceConfiguration prefConfig) {
            // Idempotent: returns early when already initialized
            MediaCodecHelper.initialize(activity, GlPreferences.readPreferences(activity).glRenderer);

            String mimeType;
            MediaCodecInfo decoder;
            switch (prefConfig.videoFormat) {
                case FORCE_AV1:
                    mimeType = "video/av01";
                    break;
                case FORCE_H264:
                    mimeType = "video/avc";
                    break;
                default:
                    mimeType = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1) != null ?
                            "video/hevc" : "video/avc";
                    break;
            }
            decoder = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1);
            if (decoder == null && !"video/avc".equals(mimeType)) {
                mimeType = "video/avc";
                decoder = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1);
            }
            if (decoder == null) {
                return null;
            }

            final MediaCodecInfo.VideoCapabilities caps =
                    decoder.getCapabilitiesForType(mimeType).getVideoCapabilities();
            if (caps == null) {
                return null;
            }
            return new AutoResolution.DecoderCaps() {
                @Override
                public boolean trustworthy() {
                    // Same rule as the settings screen: ignore decoders that do not report 720p
                    Range<Integer> widths = caps.getSupportedWidths();
                    return widths != null && widths.contains(1280);
                }

                @Override
                public boolean isSizeSupported(int width, int height) {
                    return caps.isSizeSupported(width, height);
                }

                @Override
                public boolean isSizeAndRateSupported(int width, int height, float fps) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points =
                                caps.getSupportedPerformancePoints();
                        if (points != null) {
                            MediaCodecInfo.VideoCapabilities.PerformancePoint target =
                                    new MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, Math.round(fps));
                            for (MediaCodecInfo.VideoCapabilities.PerformancePoint point : points) {
                                if (point.covers(target)) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    }
                    return caps.areSizeAndRateSupported(width, height, fps);
                }
            };
        }
    }
}

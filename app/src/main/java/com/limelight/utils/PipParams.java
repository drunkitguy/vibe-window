package com.limelight.utils;

import android.util.Rational;

import androidx.annotation.Nullable;

/**
 * Picture-in-picture parameters the platform accepts. Android refuses (with an
 * IllegalArgumentException) an aspect ratio outside 1:2.39 to 2.39:1, or 0/0, so an
 * ultrawide or tall stream, or a view that is not laid out yet, must not reach it as is.
 */
public final class PipParams {
    // Just inside the platform limits (config_pictureInPictureMin/MaxAspectRatio, 0.41841
    // and 2.39), so a float rounding at the boundary can never be refused
    static final Rational MAX_RATIO = new Rational(2389, 1000);
    static final Rational MIN_RATIO = new Rational(1000, 2389);

    private PipParams() {}

    /** The ratio for a view of this size, clamped to the allowed range; null without a size. */
    @Nullable
    public static Rational aspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        Rational ratio = new Rational(width, height);
        if (ratio.compareTo(MAX_RATIO) > 0) {
            return MAX_RATIO;
        }
        if (ratio.compareTo(MIN_RATIO) < 0) {
            return MIN_RATIO;
        }
        return ratio;
    }

    /** Whether the view's own ratio is used, so its rectangle can serve as the source hint. */
    public static boolean isExact(int width, int height) {
        Rational ratio = aspectRatio(width, height);
        return ratio != null && ratio.equals(new Rational(width, height));
    }
}

package com.limelight.preferences;

/**
 * Decides whether the segments of a row fit side by side. Pure Java so it can be unit tested.
 */
public final class SegmentFit {
    private SegmentFit() {}

    /**
     * Segments share the width equally, so the widest label plus its padding on both sides
     * must fit in one share. Labels are never ellipsized: a row that does not fit becomes a
     * dropdown row.
     *
     * @param labelWidthsPx measured label widths, the More segment included
     * @param availablePx   width of the segment track inside its padding
     * @param minPaddingPx  smallest padding on each side of a label
     */
    public static boolean fits(float[] labelWidthsPx, float availablePx, float minPaddingPx) {
        if (labelWidthsPx.length == 0 || availablePx <= 0) {
            return true;
        }
        float share = availablePx / labelWidthsPx.length;
        for (float width : labelWidthsPx) {
            if (width + 2 * minPaddingPx > share) {
                return false;
            }
        }
        return true;
    }
}

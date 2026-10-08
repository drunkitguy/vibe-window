package com.limelight.ui;

/**
 * Column count and side padding that center a few host cards on the PC list.
 * Pure Java so the math can be unit tested. All values are in pixels.
 */
public final class HostGridLayout {
    private HostGridLayout() {}

    /** How many columns fit in the given width, never more than the number of hosts. */
    public static int columns(int width, int columnWidth, int spacing, int minPadding, int hostCount) {
        if (columnWidth <= 0) {
            return 1;
        }
        int available = width - 2 * minPadding;
        int fit = (available + spacing) / (columnWidth + spacing);
        fit = Math.max(1, fit);
        return Math.max(1, Math.min(fit, Math.max(1, hostCount)));
    }

    /** Equal left and right padding that centers the given number of columns. */
    public static int sidePadding(int width, int columnWidth, int spacing, int minPadding, int columns) {
        int used = columns * columnWidth + Math.max(0, columns - 1) * spacing;
        return Math.max(minPadding, (width - used) / 2);
    }
}

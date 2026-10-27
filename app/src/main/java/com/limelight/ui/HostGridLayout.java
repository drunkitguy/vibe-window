package com.limelight.ui;

/**
 * Column count, column width and side padding for the host cards on the PC list.
 * Columns are sized from the available width: as many columns as fit at the minimum
 * width (never more than there are hosts), each as wide as possible up to the maximum,
 * and the result is centered. A screen narrower than one minimum column gets one
 * column that shrinks to fit. Pure Java so the math can be unit tested; all values
 * are in pixels.
 */
public final class HostGridLayout {
    public final int columns;
    public final int columnWidth;
    public final int sidePadding;

    private HostGridLayout(int columns, int columnWidth, int sidePadding) {
        this.columns = columns;
        this.columnWidth = columnWidth;
        this.sidePadding = sidePadding;
    }

    public static HostGridLayout compute(int width, int minColumnWidth, int maxColumnWidth,
                                         int spacing, int minPadding, int hostCount) {
        int available = Math.max(0, width - 2 * minPadding);
        int fit = minColumnWidth > 0 ? (available + spacing) / (minColumnWidth + spacing) : 1;
        int columns = Math.max(1, Math.min(fit, Math.max(1, hostCount)));

        int columnWidth = (available - (columns - 1) * spacing) / columns;
        columnWidth = Math.min(maxColumnWidth, columnWidth);
        if (columnWidth <= 0) {
            columnWidth = Math.max(1, available);
        }

        int used = columns * columnWidth + (columns - 1) * spacing;
        int padding = Math.max(minPadding, (width - used) / 2);
        return new HostGridLayout(columns, columnWidth, padding);
    }
}

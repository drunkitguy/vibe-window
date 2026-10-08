package com.limelight.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HostGridLayoutTest {
    private static final int COLUMN = 336;
    private static final int SPACING = 16;
    private static final int PAD = 8;

    @Test
    public void oneHostIsCentered() {
        int columns = HostGridLayout.columns(1600, COLUMN, SPACING, PAD, 1);
        assertEquals(1, columns);
        assertEquals((1600 - 336) / 2, HostGridLayout.sidePadding(1600, COLUMN, SPACING, PAD, columns));
    }

    @Test
    public void threeHostsAreCentered() {
        int columns = HostGridLayout.columns(1600, COLUMN, SPACING, PAD, 3);
        assertEquals(3, columns);
        int used = 3 * 336 + 2 * 16;
        assertEquals((1600 - used) / 2, HostGridLayout.sidePadding(1600, COLUMN, SPACING, PAD, columns));
    }

    @Test
    public void manyHostsFillTheWidth() {
        // (1600 - 16 + 16) / 352 = 4 columns
        int columns = HostGridLayout.columns(1600, COLUMN, SPACING, PAD, 9);
        assertEquals(4, columns);
        int used = 4 * 336 + 3 * 16;
        assertEquals((1600 - used) / 2, HostGridLayout.sidePadding(1600, COLUMN, SPACING, PAD, columns));
    }

    @Test
    public void narrowScreenKeepsOneColumnAndMinimumPadding() {
        assertEquals(1, HostGridLayout.columns(300, COLUMN, SPACING, PAD, 3));
        assertEquals(PAD, HostGridLayout.sidePadding(300, COLUMN, SPACING, PAD, 1));
    }

    @Test
    public void emptyListUsesOneColumn() {
        assertEquals(1, HostGridLayout.columns(1600, COLUMN, SPACING, PAD, 0));
    }
}

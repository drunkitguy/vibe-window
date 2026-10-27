package com.limelight.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Values in dp at density 1, matching the dimens used by the PC list. */
public class HostGridLayoutTest {
    private static final int MIN_COLUMN = 252;
    private static final int MAX_COLUMN = 336;
    private static final int SPACING = 16;
    private static final int PAD = 8;

    private static HostGridLayout layout(int width, int hosts) {
        return HostGridLayout.compute(width, MIN_COLUMN, MAX_COLUMN, SPACING, PAD, hosts);
    }

    @Test
    public void oneHostIsCenteredAtFullCardSize() {
        HostGridLayout l = layout(1600, 1);
        assertEquals(1, l.columns);
        assertEquals(MAX_COLUMN, l.columnWidth);
        assertEquals((1600 - 336) / 2, l.sidePadding);
    }

    @Test
    public void threeHostsAreCentered() {
        HostGridLayout l = layout(1600, 3);
        assertEquals(3, l.columns);
        assertEquals(MAX_COLUMN, l.columnWidth);
        assertEquals((1600 - (3 * 336 + 2 * 16)) / 2, l.sidePadding);
    }

    @Test
    public void manyHostsFillTheWidth() {
        // (1584 + 16) / (252 + 16) = 5 columns of (1584 - 64) / 5 = 304
        HostGridLayout l = layout(1600, 9);
        assertEquals(5, l.columns);
        assertEquals(304, l.columnWidth);
    }

    @Test
    public void handheldLandscapeFitsTwoHosts() {
        // 731dp wide screen minus the 80dp rail and 24dp gutter
        int width = 731 - 104;
        HostGridLayout l = layout(width, 2);
        assertEquals(2, l.columns);
        assertTrue(l.columnWidth >= MIN_COLUMN);
        assertTrue(2 * l.columnWidth + SPACING + 2 * l.sidePadding <= width);
    }

    @Test
    public void smallLandscapeStillFitsTwoHosts() {
        int width = 640 - 104;
        HostGridLayout l = layout(width, 2);
        assertEquals(2, l.columns);
        assertTrue(l.columnWidth >= MIN_COLUMN);
        assertTrue(2 * l.columnWidth + SPACING + 2 * l.sidePadding <= width);
    }

    @Test
    public void narrowPortraitShrinksTheOnlyColumnToFit() {
        // 360dp portrait screen minus 2 x 16dp content padding: the card must not clip
        int width = 360 - 32;
        HostGridLayout l = layout(width, 3);
        assertEquals(1, l.columns);
        assertTrue(l.columnWidth <= width - 2 * PAD);
        assertEquals(PAD, l.sidePadding);

        HostGridLayout tiny = layout(200, 1);
        assertEquals(1, tiny.columns);
        assertEquals(200 - 2 * PAD, tiny.columnWidth);
    }

    @Test
    public void emptyListUsesOneColumn() {
        assertEquals(1, layout(1600, 0).columns);
    }
}

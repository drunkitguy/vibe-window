package com.limelight.preferences;

import org.junit.Test;

import static com.limelight.preferences.GroupedCardDecoration.POSITION_FIRST;
import static com.limelight.preferences.GroupedCardDecoration.POSITION_LAST;
import static com.limelight.preferences.GroupedCardDecoration.POSITION_MIDDLE;
import static com.limelight.preferences.GroupedCardDecoration.POSITION_NONE;
import static com.limelight.preferences.GroupedCardDecoration.POSITION_SINGLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupedCardDecorationTest {

    // A screen: header, row, row, expand row, header, row, header, header, row
    private static final boolean[] ROWS = {false, true, true, true, false, true, false, false, true};

    private static int positionAt(int index) {
        boolean previous = index > 0 && ROWS[index - 1];
        boolean next = index + 1 < ROWS.length && ROWS[index + 1];
        return GroupedCardDecoration.positionOf(previous, ROWS[index], next);
    }

    @Test
    public void headersAreNotPartOfACard() {
        assertEquals(POSITION_NONE, positionAt(0));
        assertEquals(POSITION_NONE, positionAt(4));
        assertEquals(POSITION_NONE, positionAt(6));
        assertEquals(POSITION_NONE, positionAt(7));
    }

    @Test
    public void runOfRowsHasFirstMiddleAndLast() {
        assertEquals(POSITION_FIRST, positionAt(1));
        assertEquals(POSITION_MIDDLE, positionAt(2));
        // The expand ("Show more") row closes the card of its category
        assertEquals(POSITION_LAST, positionAt(3));
    }

    @Test
    public void lonelyRowIsSingle() {
        assertEquals(POSITION_SINGLE, positionAt(5));
        // Last item of the list
        assertEquals(POSITION_SINGLE, positionAt(8));
    }

    @Test
    public void cornersAndDividers() {
        assertTrue(GroupedCardDecoration.roundsTop(POSITION_FIRST));
        assertFalse(GroupedCardDecoration.roundsBottom(POSITION_FIRST));
        assertFalse(GroupedCardDecoration.roundsTop(POSITION_MIDDLE));
        assertFalse(GroupedCardDecoration.roundsBottom(POSITION_MIDDLE));
        assertFalse(GroupedCardDecoration.roundsTop(POSITION_LAST));
        assertTrue(GroupedCardDecoration.roundsBottom(POSITION_LAST));
        assertTrue(GroupedCardDecoration.roundsTop(POSITION_SINGLE));
        assertTrue(GroupedCardDecoration.roundsBottom(POSITION_SINGLE));

        assertTrue(GroupedCardDecoration.hasDividerBelow(POSITION_FIRST));
        assertTrue(GroupedCardDecoration.hasDividerBelow(POSITION_MIDDLE));
        assertFalse(GroupedCardDecoration.hasDividerBelow(POSITION_LAST));
        assertFalse(GroupedCardDecoration.hasDividerBelow(POSITION_SINGLE));
        assertFalse(GroupedCardDecoration.hasDividerBelow(POSITION_NONE));
    }
}

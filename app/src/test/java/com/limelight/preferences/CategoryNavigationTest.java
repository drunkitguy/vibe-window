package com.limelight.preferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CategoryNavigationTest {
    private static final List<Integer> HEADERS = Arrays.asList(0, 5, 9, 12);

    @Test
    public void currentCategoryFromFocusedRow() {
        assertEquals(-1, CategoryNavigation.currentCategoryIndex(HEADERS, -1));
        assertEquals(0, CategoryNavigation.currentCategoryIndex(HEADERS, 0));
        assertEquals(0, CategoryNavigation.currentCategoryIndex(HEADERS, 4));
        assertEquals(1, CategoryNavigation.currentCategoryIndex(HEADERS, 5));
        assertEquals(2, CategoryNavigation.currentCategoryIndex(HEADERS, 11));
        assertEquals(3, CategoryNavigation.currentCategoryIndex(HEADERS, 40));
    }

    @Test
    public void nextAndPreviousWrap() {
        assertEquals(1, CategoryNavigation.nextCategoryIndex(0, 1, 4));
        assertEquals(0, CategoryNavigation.nextCategoryIndex(3, 1, 4));
        assertEquals(3, CategoryNavigation.nextCategoryIndex(0, -1, 4));
        assertEquals(1, CategoryNavigation.nextCategoryIndex(2, -1, 4));
    }

    @Test
    public void unknownPositionStartsAtAnEnd() {
        assertEquals(0, CategoryNavigation.nextCategoryIndex(-1, 1, 4));
        assertEquals(3, CategoryNavigation.nextCategoryIndex(-1, -1, 4));
        assertEquals(-1, CategoryNavigation.nextCategoryIndex(-1, 1, 0));
    }
}

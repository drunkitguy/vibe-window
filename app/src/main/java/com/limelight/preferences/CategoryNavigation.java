package com.limelight.preferences;

import java.util.List;

/**
 * L1 and R1 jumps between settings categories. Pure Java so it can be unit tested.
 */
public final class CategoryNavigation {
    private CategoryNavigation() {}

    /**
     * Index of the category that contains the given adapter position, or -1 when the
     * position is unknown or above the first category.
     *
     * @param categoryPositions adapter positions of the category headers, ascending
     */
    public static int currentCategoryIndex(List<Integer> categoryPositions, int focusedPosition) {
        if (focusedPosition < 0) {
            return -1;
        }
        int current = -1;
        for (int i = 0; i < categoryPositions.size(); i++) {
            if (categoryPositions.get(i) <= focusedPosition) {
                current = i;
            }
            else {
                break;
            }
        }
        return current;
    }

    /** Next (direction 1) or previous (direction -1) category, wrapping around. */
    public static int nextCategoryIndex(int current, int direction, int count) {
        if (count <= 0) {
            return -1;
        }
        if (current < 0) {
            return direction > 0 ? 0 : count - 1;
        }
        return ((current + direction) % count + count) % count;
    }
}

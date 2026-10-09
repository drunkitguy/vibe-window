package com.limelight.preferences;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the settings rows of each category as one rounded card, with hairlines
 * between rows. Category headers stay outside the cards. The expand ("Show more")
 * row belongs to the card of its category.
 */
public class GroupedCardDecoration extends RecyclerView.ItemDecoration {

    // Position of a row inside its card
    public static final int POSITION_NONE = 0;
    public static final int POSITION_FIRST = 1;
    public static final int POSITION_MIDDLE = 2;
    public static final int POSITION_LAST = 3;
    public static final int POSITION_SINGLE = 4;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final float radius;
    private final float dividerInset;
    private final Path path = new Path();
    private final RectF rect = new RectF();
    // Corner radii for Path.addRoundRect, indexed by roundsTop + 2 * roundsBottom
    private final float[][] radii = new float[4][];
    // View types of category headers, learned from the header views (vw_pref_category)
    private final Set<Integer> headerViewTypes = new HashSet<>();

    public GroupedCardDecoration(Context context) {
        cardPaint.setColor(ContextCompat.getColor(context, R.color.vw_surface));
        cardPaint.setStyle(Paint.Style.FILL);
        dividerPaint.setColor(ContextCompat.getColor(context, R.color.vw_separator));
        dividerPaint.setStyle(Paint.Style.FILL);
        radius = context.getResources().getDimension(R.dimen.vw_radius_lg);
        dividerInset = context.getResources().getDimension(R.dimen.vw_space_4);
        for (int i = 0; i < radii.length; i++) {
            float top = (i & 1) != 0 ? radius : 0;
            float bottom = (i & 2) != 0 ? radius : 0;
            radii[i] = new float[] {top, top, top, top, bottom, bottom, bottom, bottom};
        }
    }

    /**
     * Where a row sits in its card, from whether it and its neighbours are rows
     * (anything but a category header).
     */
    public static int positionOf(boolean previousIsRow, boolean isRow, boolean nextIsRow) {
        if (!isRow) {
            return POSITION_NONE;
        }
        if (previousIsRow && nextIsRow) {
            return POSITION_MIDDLE;
        }
        if (previousIsRow) {
            return POSITION_LAST;
        }
        if (nextIsRow) {
            return POSITION_FIRST;
        }
        return POSITION_SINGLE;
    }

    public static boolean roundsTop(int position) {
        return position == POSITION_FIRST || position == POSITION_SINGLE;
    }

    public static boolean roundsBottom(int position) {
        return position == POSITION_LAST || position == POSITION_SINGLE;
    }

    /** A hairline is drawn under every row that has another row below it in the same card. */
    public static boolean hasDividerBelow(int position) {
        return position == POSITION_FIRST || position == POSITION_MIDDLE;
    }

    /** Marks a view type as a category header, e.g. seeded from the adapter before the first draw. */
    public void addHeaderViewType(int viewType) {
        if (viewType != RecyclerView.INVALID_TYPE) {
            headerViewTypes.add(viewType);
        }
    }

    boolean isHeaderViewType(int viewType) {
        return headerViewTypes.contains(viewType);
    }

    private boolean isRow(RecyclerView.Adapter<?> adapter, int position) {
        if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
            return false;
        }
        return !headerViewTypes.contains(adapter.getItemViewType(position));
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int childCount = parent.getChildCount();

        // Category headers inflate vw_pref_category; remember their view type so headers
        // outside the visible area are recognized too
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            if (child.getId() == R.id.vw_pref_category) {
                RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
                if (holder != null && holder.getItemViewType() != RecyclerView.INVALID_TYPE) {
                    headerViewTypes.add(holder.getItemViewType());
                }
            }
        }

        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int adapterPosition = parent.getChildAdapterPosition(child);
            if (adapterPosition == RecyclerView.NO_POSITION) {
                continue;
            }

            int position = positionOf(isRow(adapter, adapterPosition - 1),
                    isRow(adapter, adapterPosition),
                    isRow(adapter, adapterPosition + 1));
            if (position == POSITION_NONE) {
                continue;
            }

            float left = child.getLeft() + child.getTranslationX();
            float right = child.getRight() + child.getTranslationX();
            float top = child.getTop() + child.getTranslationY();
            float bottom = child.getBottom() + child.getTranslationY();
            rect.set(left, top, right, bottom);
            path.reset();
            path.addRoundRect(rect, radii[(roundsTop(position) ? 1 : 0) + (roundsBottom(position) ? 2 : 0)],
                    Path.Direction.CW);
            c.drawPath(path, cardPaint);

            if (hasDividerBelow(position)) {
                float hairline = 1f;
                boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                c.drawRect(rtl ? left : left + dividerInset, bottom - hairline,
                        rtl ? right - dividerInset : right, bottom, dividerPaint);
            }
        }
    }
}

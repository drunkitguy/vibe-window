package com.limelight.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

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

    public GroupedCardDecoration(Context context) {
        cardPaint.setColor(ContextCompat.getColor(context, R.color.vw_surface));
        cardPaint.setStyle(Paint.Style.FILL);
        dividerPaint.setColor(ContextCompat.getColor(context, R.color.vw_separator));
        dividerPaint.setStyle(Paint.Style.FILL);
        radius = context.getResources().getDimension(R.dimen.vw_radius_lg);
        dividerInset = context.getResources().getDimension(R.dimen.vw_space_4);
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

    @SuppressLint("RestrictedApi")
    private static boolean isRow(RecyclerView.Adapter<?> adapter, int position) {
        if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
            return false;
        }
        if (adapter instanceof PreferenceGroupAdapter) {
            Preference preference = ((PreferenceGroupAdapter) adapter).getItem(position);
            return preference != null && !(preference instanceof PreferenceCategory);
        }
        return true;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int childCount = parent.getChildCount();
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
            float topRadius = roundsTop(position) ? radius : 0;
            float bottomRadius = roundsBottom(position) ? radius : 0;

            rect.set(left, top, right, bottom);
            path.reset();
            path.addRoundRect(rect, new float[] {
                    topRadius, topRadius, topRadius, topRadius,
                    bottomRadius, bottomRadius, bottomRadius, bottomRadius
            }, Path.Direction.CW);
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

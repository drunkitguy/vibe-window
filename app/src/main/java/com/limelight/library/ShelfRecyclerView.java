package com.limelight.library;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * Horizontal list of one shelf. When the D-pad moves focus up or down into
 * the shelf, focus lands on the card that was focused last in this shelf
 * instead of the card that happens to sit nearest geometrically.
 */
public class ShelfRecyclerView extends RecyclerView {
    public interface FocusMemory {
        /** Adapter position to focus when entering the shelf, or NO_POSITION. */
        int getRememberedPosition(ShelfRecyclerView shelf);
    }

    private FocusMemory focusMemory;

    public ShelfRecyclerView(@NonNull Context context) {
        super(context);
    }

    public ShelfRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ShelfRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setFocusMemory(FocusMemory focusMemory) {
        this.focusMemory = focusMemory;
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        // Entering from another shelf: offer the shelf itself as the target, so
        // onRequestFocusInDescendants can pick the remembered card
        if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
                && focusableMode == View.FOCUSABLES_ALL && isFocusable() && getChildCount() > 0
                && getVisibility() == View.VISIBLE) {
            views.add(this);
            return;
        }
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        int position = focusMemory != null ? focusMemory.getRememberedPosition(this) : NO_POSITION;
        if (position != NO_POSITION) {
            ViewHolder holder = findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView.requestFocus()) {
                return true;
            }
        }

        // Fall back to the first card on screen
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).requestFocus()) {
                return true;
            }
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }
}

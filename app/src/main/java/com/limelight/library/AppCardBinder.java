package com.limelight.library;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;

/**
 * Binds an app card (app_grid_item.xml or app_grid_item_small.xml): box art,
 * name, running overlay and hidden dimming.
 */
public final class AppCardBinder {
    private static final float FOCUSED_SCALE = 1.06f;
    private static final int FOCUS_ANIMATION_MS = 120;

    private AppCardBinder() {}

    public static void bind(View card, LibrarySnapshot.AppRow<AppView.AppObject> row, CachedAppAssetLoader loader) {
        card.setTag(R.id.tag_app_object, row.app);
        card.setContentDescription(row.name);

        ImageView imgView = card.findViewById(R.id.grid_image);
        TextView txtView = card.findViewById(R.id.grid_text);

        // Let the cached asset loader handle it
        loader.populateImageView(row.app.app, imgView, txtView);

        bindState(card, row);
    }

    /** Rebinds only the running overlay and hidden dimming, keeping the art. */
    public static void bindState(View card, LibrarySnapshot.AppRow<AppView.AppObject> row) {
        card.setTag(R.id.tag_app_object, row.app);

        ImageView overlayView = card.findViewById(R.id.grid_overlay);
        View gridMask = card.findViewById(R.id.grid_mask);

        if (overlayView != null && gridMask != null) {
            if (row.running) {
                // Show the play button overlay
                overlayView.setImageResource(R.drawable.ic_play);
                overlayView.setVisibility(View.VISIBLE);
                gridMask.setBackgroundColor(0x66000000);
            }
            else {
                overlayView.setVisibility(View.GONE);
                gridMask.setBackgroundColor(0x00000000);
            }
        }

        card.setAlpha(row.hidden ? 0.40f : 1.0f);
    }

    /** Scales a card up while it has D-pad focus. */
    public static void animateFocus(View card, boolean hasFocus) {
        float scale = hasFocus ? FOCUSED_SCALE : 1.0f;
        card.animate().cancel();
        card.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS).start();
    }

    /** Clears any focus scaling left on a recycled card. */
    public static void resetFocus(View card) {
        card.animate().cancel();
        card.setScaleX(1.0f);
        card.setScaleY(1.0f);
    }
}

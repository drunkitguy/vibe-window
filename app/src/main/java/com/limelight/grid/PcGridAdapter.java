package com.limelight.grid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.HostCardState;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        ComputerDetails details = obj.details;
        HostCardState card = HostCardState.from(details.state, details.pairState,
                details.runningGameId, details.macAddress != null);
        Context ctx = parentView.getContext();

        txtView.setText(details.name);

        imgView.setImageResource(R.drawable.ic_computer);
        imgView.setAlpha(card.glyphAlpha);
        imgView.setVisibility(card.glyphVisible ? View.VISIBLE : View.INVISIBLE);

        prgView.setVisibility(card.spinner ? View.VISIBLE : View.INVISIBLE);

        switch (card.badge) {
            case LOCK:
                overlayView.setImageResource(R.drawable.ic_badge_lock);
                overlayView.setAlpha(1.0f);
                overlayView.setVisibility(View.VISIBLE);
                break;
            case OFFLINE:
                overlayView.setImageResource(R.drawable.ic_pc_offline);
                overlayView.setAlpha(card.glyphAlpha);
                overlayView.setVisibility(View.VISIBLE);
                break;
            default:
                overlayView.setVisibility(View.GONE);
                break;
        }

        View tile = parentView.findViewById(R.id.host_icon_tile);
        if (tile != null) {
            bindTileColor(tile, details.uuid, ContextCompat.getColor(ctx, tileColorRes(card.tile)));
        }

        View glow = parentView.findViewById(R.id.host_glow);
        if (glow != null) {
            glow.setVisibility(card.glow ? View.VISIBLE : View.GONE);
        }

        String statusText = ctx.getString(statusTextRes(card.statusText));
        ImageView statusIcon = parentView.findViewById(R.id.host_status_dot);
        TextView statusView = parentView.findViewById(R.id.host_status_text);
        int statusColor = ContextCompat.getColor(ctx, statusColorRes(card.statusIcon));
        if (statusIcon != null) {
            statusIcon.setImageResource(statusIconRes(card.statusIcon));
            ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(statusColor));
        }
        if (statusView != null) {
            statusView.setText(statusText);
            statusView.setTextColor(statusColor);
        }

        TextView secondary = parentView.findViewById(R.id.host_secondary_label);
        if (secondary != null) {
            secondary.setText(secondaryTextRes(card.secondaryText));
        }

        View pill = parentView.findViewById(R.id.host_action_pill);
        ImageView pillIcon = parentView.findViewById(R.id.host_action_icon);
        TextView pillText = parentView.findViewById(R.id.host_action_text);
        if (pill != null && pillIcon != null && pillText != null) {
            int fill;
            int content;
            switch (card.pillStyle) {
                case FILLED:
                    fill = R.color.vw_accent_container;
                    content = R.color.vw_on_surface;
                    break;
                case TONAL:
                    fill = R.color.vw_accent_tonal;
                    content = R.color.vw_accent;
                    break;
                default:
                    fill = R.color.vw_surface_high;
                    content = R.color.vw_on_surface_variant;
                    break;
            }
            int contentColor = ContextCompat.getColor(ctx, content);
            ViewCompat.setBackgroundTintList(pill, ColorStateList.valueOf(ContextCompat.getColor(ctx, fill)));
            pillIcon.setImageResource(pillIconRes(card.pillIcon));
            ImageViewCompat.setImageTintList(pillIcon, ColorStateList.valueOf(contentColor));
            pillText.setText(pillTextRes(card.pillText));
            pillText.setTextColor(contentColor);
        }

        parentView.setContentDescription(details.name + ", " + statusText);
    }

    private static final class TileTag {
        final String uuid;
        final int color;
        final ValueAnimator animator;

        TileTag(String uuid, int color, ValueAnimator animator) {
            this.uuid = uuid;
            this.color = color;
            this.animator = animator;
        }
    }

    // Crossfade the tile when the same host changes state; bind directly otherwise
    private static void bindTileColor(final View tile, String uuid, int color) {
        Object tag = tile.getTag(R.id.tag_host_tile_color);
        TileTag previous = tag instanceof TileTag ? (TileTag) tag : null;
        if (previous != null && previous.uuid != null && previous.uuid.equals(uuid) && previous.color == color) {
            // Nothing changed; let a running crossfade finish
            return;
        }
        if (previous != null && previous.animator != null) {
            previous.animator.cancel();
        }

        ValueAnimator animator = null;
        if (previous != null && previous.uuid != null && previous.uuid.equals(uuid)) {
            animator = ValueAnimator.ofArgb(previous.color, color);
            animator.setDuration(tile.getResources().getInteger(R.integer.vw_motion_slow));
            animator.setInterpolator(AnimationUtils.loadInterpolator(tile.getContext(),
                    android.R.interpolator.fast_out_slow_in));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    ViewCompat.setBackgroundTintList(tile,
                            ColorStateList.valueOf((Integer) animation.getAnimatedValue()));
                }
            });
            animator.start();
        }
        else {
            ViewCompat.setBackgroundTintList(tile, ColorStateList.valueOf(color));
        }
        tile.setTag(R.id.tag_host_tile_color, new TileTag(uuid, color, animator));
    }

    private static int tileColorRes(HostCardState.Tile tile) {
        switch (tile) {
            case ACCENT:
                return R.color.vw_accent;
            case TONAL:
                return R.color.vw_accent_tonal;
            default:
                return R.color.vw_surface_low;
        }
    }

    private static int statusIconRes(HostCardState.StatusIcon icon) {
        switch (icon) {
            case ICON_WARNING:
                return R.drawable.ic_warning;
            case ICON_SENSORS:
                return R.drawable.ic_sensors;
            default:
                return R.drawable.host_status_dot;
        }
    }

    private static int statusColorRes(HostCardState.StatusIcon icon) {
        switch (icon) {
            case DOT_SUCCESS:
                return R.color.vw_success;
            case DOT_WARNING:
                return R.color.vw_warning;
            default:
                return R.color.vw_on_surface_variant;
        }
    }

    private static int statusTextRes(HostCardState.StatusText text) {
        switch (text) {
            case ONLINE:
                return R.string.host_status_online;
            case STREAMING:
                return R.string.host_status_streaming;
            case NOT_PAIRED:
                return R.string.host_status_not_paired;
            case OFFLINE:
                return R.string.host_status_offline;
            default:
                return R.string.host_status_checking;
        }
    }

    private static int secondaryTextRes(HostCardState.SecondaryText text) {
        switch (text) {
            case LIBRARY:
                return R.string.host_secondary_library;
            case PAIR_TO_BROWSE:
                return R.string.host_secondary_pair;
            case WAKE_AVAILABLE:
                return R.string.host_secondary_wake;
            case MORE_OPTIONS:
                return R.string.host_secondary_options;
            default:
                return R.string.host_secondary_refreshing;
        }
    }

    private static int pillIconRes(HostCardState.PillIcon icon) {
        switch (icon) {
            case PLAY:
                return R.drawable.ic_play_arrow;
            case LOCK_OPEN:
                return R.drawable.ic_lock_open;
            case POWER:
                return R.drawable.ic_power;
            default:
                return R.drawable.ic_more_horiz;
        }
    }

    private static int pillTextRes(HostCardState.PillText text) {
        switch (text) {
            case OPEN:
                return R.string.host_action_open;
            case RESUME:
                return R.string.host_action_resume;
            case PAIR:
                return R.string.host_action_pair;
            case WAKE:
                return R.string.host_action_wake;
            default:
                return R.string.host_action_options;
        }
    }
}

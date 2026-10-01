package com.limelight.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.R;

import java.util.List;

/**
 * Header and card rows for the grid (and the cards of each shelf). Uses stable
 * ids and DiffUtil so polling updates rebind cards in place without moving
 * D-pad focus.
 */
public class LibraryRowAdapter extends ListAdapter<LibrarySnapshot.Row, RecyclerView.ViewHolder> {
    /** Only the running state changed: keep the art, rebind the overlay. */
    public static final Object PAYLOAD_STATE = "state";
    /** Rebind everything but keep the view holder (and therefore focus). */
    public static final Object PAYLOAD_REBIND = "rebind";

    private static final DiffUtil.ItemCallback<LibrarySnapshot.Row> DIFF = new DiffUtil.ItemCallback<LibrarySnapshot.Row>() {
        @Override
        public boolean areItemsTheSame(@NonNull LibrarySnapshot.Row oldItem, @NonNull LibrarySnapshot.Row newItem) {
            return oldItem.getStableId() == newItem.getStableId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull LibrarySnapshot.Row oldItem, @NonNull LibrarySnapshot.Row newItem) {
            return oldItem.sameContent(newItem);
        }

        @Override
        public Object getChangePayload(@NonNull LibrarySnapshot.Row oldItem, @NonNull LibrarySnapshot.Row newItem) {
            if (newItem instanceof LibrarySnapshot.AppRow
                    && ((LibrarySnapshot.AppRow<?>) newItem).onlyRunningChanged(oldItem)) {
                return PAYLOAD_STATE;
            }
            // Always return a payload so the existing holder is reused
            return PAYLOAD_REBIND;
        }
    };

    public interface FocusListener {
        void onRowFocused(RecyclerView.ViewHolder holder, LibrarySnapshot.Row row);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final ImageView chevron;
        final TextView label;
        final TextView count;
        LibrarySnapshot.HeaderRow row;

        HeaderHolder(View itemView) {
            super(itemView);
            chevron = itemView.findViewById(R.id.header_chevron);
            label = itemView.findViewById(R.id.header_label);
            count = itemView.findViewById(R.id.header_count);
        }
    }

    static final class CardHolder extends RecyclerView.ViewHolder {
        LibrarySnapshot.AppRow<AppView.AppObject> row;

        CardHolder(View itemView) {
            super(itemView);
        }
    }

    private final LibraryLayoutController.Host host;
    private final int cardLayoutId;
    private final int cardWidthPx;
    private final FocusListener focusListener;

    /**
     * @param cardWidthPx a width that overrides the card layout's own width, or 0
     */
    public LibraryRowAdapter(LibraryLayoutController.Host host, int cardLayoutId, int cardWidthPx, FocusListener focusListener) {
        super(DIFF);
        this.host = host;
        this.cardLayoutId = cardLayoutId;
        this.cardWidthPx = cardWidthPx;
        this.focusListener = focusListener;
        setHasStableIds(true);
    }

    public static int getCardLayoutId(boolean smallIconMode) {
        return smallIconMode ? R.layout.app_grid_item_small : R.layout.app_grid_item;
    }

    public LibrarySnapshot.Row getRow(int position) {
        return getItem(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getStableId();
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == LibrarySnapshot.TYPE_HEADER) {
            final HeaderHolder holder = new HeaderHolder(inflater.inflate(R.layout.library_group_header, parent, false));
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder.row != null) {
                        host.onGroupHeaderClicked(holder.row.groupKey);
                    }
                }
            });
            holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && holder.row != null && focusListener != null) {
                        focusListener.onRowFocused(holder, holder.row);
                    }
                }
            });
            return holder;
        }

        View card = inflater.inflate(cardLayoutId, parent, false);
        if (cardWidthPx > 0) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.height = Math.round(lp.height * (cardWidthPx / (float) lp.width));
            lp.width = cardWidthPx;
            card.setLayoutParams(lp);
        }
        final CardHolder holder = new CardHolder(card);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder.row != null) {
                    host.onAppClicked(holder.row.app, v);
                }
            }
        });
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                AppCardBinder.animateFocus(v, hasFocus);
                if (hasFocus && holder.row != null) {
                    host.onAppFocused(holder.row.app);
                    if (focusListener != null) {
                        focusListener.onRowFocused(holder, holder.row);
                    }
                }
            }
        });
        host.registerContextView(card);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        bind(holder, getItem(position), false);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        boolean stateOnly = !payloads.isEmpty();
        for (Object payload : payloads) {
            if (payload != PAYLOAD_STATE) {
                stateOnly = false;
                break;
            }
        }
        bind(holder, getItem(position), stateOnly);
    }

    @SuppressWarnings("unchecked")
    private void bind(RecyclerView.ViewHolder holder, LibrarySnapshot.Row row, boolean stateOnly) {
        if (holder instanceof HeaderHolder) {
            HeaderHolder header = (HeaderHolder) holder;
            LibrarySnapshot.HeaderRow headerRow = (LibrarySnapshot.HeaderRow) row;
            header.row = headerRow;
            header.label.setText(headerRow.label);
            header.count.setText(String.valueOf(headerRow.count));
            header.chevron.setImageResource(headerRow.collapsed ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_down);
            header.itemView.setContentDescription(host.getContext().getString(
                    headerRow.collapsed ? R.string.library_group_header_collapsed_desc : R.string.library_group_header_desc,
                    headerRow.label, headerRow.count));
            // Only collapsed headers take D-pad focus; expanded ones would add a stop per group
            header.itemView.setFocusable(headerRow.collapsed);
            return;
        }

        CardHolder card = (CardHolder) holder;
        card.row = (LibrarySnapshot.AppRow<AppView.AppObject>) row;
        if (stateOnly) {
            AppCardBinder.bindState(card.itemView, card.row);
        }
        else {
            AppCardBinder.bind(card.itemView, card.row, host.getAppLibrary().getLoader());
        }
        if (!card.itemView.isFocused()) {
            AppCardBinder.resetFocus(card.itemView);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof CardHolder) {
            AppCardBinder.resetFocus(holder.itemView);
        }
    }
}

package com.limelight.library;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.R;

import java.util.List;

/**
 * Grid layout: group headers spanning the full width, followed by the cards of
 * each expanded group.
 */
public class GridLayoutController implements LibraryLayoutController {
    private final Host host;

    private RecyclerView recyclerView;
    private GridLayoutManager layoutManager;
    private LibraryRowAdapter adapter;
    private FocusKeeper focusKeeper;
    private boolean smallIconMode;
    private int lastWidth;

    public GridLayoutController(Host host) {
        this.host = host;
    }

    @Override
    public String getLayoutName() {
        return LibraryPrefs.LAYOUT_GRID;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container) {
        recyclerView = (RecyclerView) inflater.inflate(R.layout.library_grid, container, false);
        // RecyclerView makes itself focusable in touch mode; that would make the
        // bare list the default focus instead of a card
        recyclerView.setFocusableInTouchMode(false);

        layoutManager = new GridLayoutManager(host.getContext(), 2) {
            @Override
            public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                if (focusKeeper != null) {
                    focusKeeper.onLayoutCompleted();
                }
            }
        };
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter != null && position >= 0 && position < adapter.getItemCount()
                        && adapter.getItemViewType(position) == LibrarySnapshot.TYPE_HEADER) {
                    return layoutManager.getSpanCount();
                }
                return 1;
            }
        });
        recyclerView.setLayoutManager(layoutManager);

        createAdapter();

        recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                final int width = right - left;
                if (width != lastWidth) {
                    lastWidth = width;
                    // Changing the padding requests another layout, so do it outside this pass
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            updateSpans(width);
                        }
                    });
                }
            }
        });

        return recyclerView;
    }

    private void createAdapter() {
        smallIconMode = host.isSmallIconMode();
        LibrarySnapshot<AppView.AppObject> current = focusKeeper != null ? focusKeeper.getSnapshot() : null;
        adapter = new LibraryRowAdapter(host, LibraryRowAdapter.getCardLayoutId(smallIconMode), 0, null);
        recyclerView.setAdapter(adapter);
        focusKeeper = new FocusKeeper(recyclerView, adapter);
        if (current != null) {
            focusKeeper.submit(current);
        }
    }

    private int getCardWidthPx() {
        Context context = host.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round((smallIconMode ? 110 : 170) * density);
    }

    private void updateSpans(int width) {
        if (recyclerView == null || width <= 0) {
            return;
        }
        int cardWidth = getCardWidthPx();
        int spans = Math.max(2, width / cardWidth);
        int side = Math.max(0, (width - spans * cardWidth) / 2);
        if (spans != layoutManager.getSpanCount()) {
            layoutManager.setSpanCount(spans);
        }
        if (recyclerView.getPaddingLeft() != side || recyclerView.getPaddingRight() != side) {
            recyclerView.setPadding(side, recyclerView.getPaddingTop(), side, recyclerView.getPaddingBottom());
        }
    }

    @Override
    public void onDestroyView() {
        recyclerView = null;
        adapter = null;
        focusKeeper = null;
        layoutManager = null;
    }

    @Override
    public void submit(LibrarySnapshot<AppView.AppObject> snapshot) {
        if (focusKeeper != null) {
            focusKeeper.submit(snapshot);
        }
    }

    @Override
    public void onConfigurationChanged() {
        if (recyclerView == null) {
            return;
        }
        if (smallIconMode != host.isSmallIconMode()) {
            // The card layout changed, so every holder must be recreated
            String focused = focusKeeper.getFocusedAppKey();
            createAdapter();
            if (focused != null) {
                focusKeeper.focusApp(focused);
            }
        }
        else {
            // The art loader was replaced; rebind in place so focus stays put
            adapter.notifyItemRangeChanged(0, adapter.getItemCount(), LibraryRowAdapter.PAYLOAD_REBIND);
        }
        lastWidth = 0;
        recyclerView.requestLayout();
    }

    @Override
    public boolean focusApp(String appKey) {
        return focusKeeper != null && focusKeeper.focusApp(appKey);
    }

    @Override
    public boolean focusFirst() {
        return focusKeeper != null && focusKeeper.focusFirst();
    }

    @Override
    public boolean hasFocus() {
        return recyclerView != null && recyclerView.hasFocus();
    }

    @Override
    public AppView.AppObject getCurrentApp() {
        LibrarySnapshot.Row row = focusKeeper != null ? focusKeeper.getFocusedRow() : null;
        if (row instanceof LibrarySnapshot.AppRow) {
            return getApp(row);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AppView.AppObject getApp(LibrarySnapshot.Row row) {
        return ((LibrarySnapshot.AppRow<AppView.AppObject>) row).app;
    }

    @Override
    public View getCurrentAppView() {
        if (focusKeeper == null || !(focusKeeper.getFocusedRow() instanceof LibrarySnapshot.AppRow)) {
            return null;
        }
        return focusKeeper.getFocusedItemView();
    }

    @Override
    public boolean jumpToAdjacentGroup(int direction) {
        return focusKeeper != null && focusKeeper.jumpToAdjacentGroup(direction);
    }

    @Override
    public String getCurrentGroupKey() {
        LibrarySnapshot.Row row = focusKeeper != null ? focusKeeper.getFocusedRow() : null;
        return row != null ? row.groupKey : null;
    }

    @Override
    public boolean supportsCollapse() {
        return true;
    }

    /**
     * Keeps D-pad focus on the same app (or its group, or the nearest row)
     * across snapshot updates of a vertical list of {@link LibrarySnapshot} rows.
     * Shared by the grid and the cards of a shelf.
     */
    static final class FocusKeeper {
        private final RecyclerView recyclerView;
        private final LibraryRowAdapter adapter;
        private LibrarySnapshot<AppView.AppObject> committed;
        // The last snapshot handed to the adapter; differs from committed while it is diffed
        private LibrarySnapshot<AppView.AppObject> latest;
        private FocusRequest pending;
        // A focus request made before the latest snapshot was committed
        private FocusRequest deferred;

        private static final class FocusRequest {
            final String appKey;
            final String groupKey;
            final int position;
            final boolean preferHeader;
            final boolean first;
            int attempts;

            FocusRequest(String appKey, String groupKey, int position, boolean preferHeader) {
                this(appKey, groupKey, position, preferHeader, false);
            }

            FocusRequest(String appKey, String groupKey, int position, boolean preferHeader, boolean first) {
                this.appKey = appKey;
                this.groupKey = groupKey;
                this.position = position;
                this.preferHeader = preferHeader;
                this.first = first;
            }
        }

        FocusKeeper(RecyclerView recyclerView, LibraryRowAdapter adapter) {
            this.recyclerView = recyclerView;
            this.adapter = adapter;
        }

        LibrarySnapshot<AppView.AppObject> getSnapshot() {
            return committed;
        }

        View getFocusedItemView() {
            View focused = recyclerView.getFocusedChild();
            if (focused == null) {
                return null;
            }
            // The focused child is the item view itself or a view holding it
            RecyclerView.ViewHolder holder = recyclerView.findContainingViewHolder(focused);
            return holder != null ? holder.itemView : null;
        }

        LibrarySnapshot.Row getFocusedRow() {
            View focused = recyclerView.getFocusedChild();
            if (focused == null) {
                return null;
            }
            RecyclerView.ViewHolder holder = recyclerView.findContainingViewHolder(focused);
            if (holder == null) {
                return null;
            }
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) {
                return null;
            }
            return adapter.getRow(position);
        }

        String getFocusedAppKey() {
            LibrarySnapshot.Row row = getFocusedRow();
            if (row instanceof LibrarySnapshot.AppRow) {
                return ((LibrarySnapshot.AppRow<?>) row).appKey;
            }
            return null;
        }

        void submit(final LibrarySnapshot<AppView.AppObject> snapshot) {
            // Remember what has focus now, to restore it once the new rows are laid out
            FocusRequest restore = null;
            if (recyclerView.hasFocus()) {
                View focused = recyclerView.getFocusedChild();
                RecyclerView.ViewHolder holder = focused != null ? recyclerView.findContainingViewHolder(focused) : null;
                int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                if (position != RecyclerView.NO_POSITION && position < adapter.getItemCount()) {
                    LibrarySnapshot.Row row = adapter.getRow(position);
                    String appKey = row instanceof LibrarySnapshot.AppRow ? ((LibrarySnapshot.AppRow<?>) row).appKey : null;
                    restore = new FocusRequest(appKey, row.groupKey, position, appKey == null);
                }
            }
            final FocusRequest request = restore;
            latest = snapshot;

            adapter.submitList(snapshot.rows, new Runnable() {
                @Override
                public void run() {
                    committed = snapshot;
                    if (snapshot != latest) {
                        // A newer snapshot is being diffed; it applies the focus
                        return;
                    }
                    if (deferred != null) {
                        pending = deferred;
                        deferred = null;
                    }
                    else if (request != null && pending == null) {
                        pending = request;
                    }
                    recyclerView.requestLayout();
                }
            });
        }

        /** The snapshot positions refer to once every submitted list is committed. */
        private LibrarySnapshot<AppView.AppObject> target() {
            return latest != null ? latest : committed;
        }

        private void request(FocusRequest request) {
            if (latest != null && latest != committed) {
                // Apply once the rows this request refers to are in the adapter
                deferred = request;
            }
            else {
                pending = request;
                applyPending();
            }
        }

        boolean focusApp(String appKey) {
            LibrarySnapshot<AppView.AppObject> snapshot = target();
            if (appKey == null || snapshot == null || snapshot.findAppPosition(appKey) < 0) {
                return false;
            }
            request(new FocusRequest(appKey, null, RecyclerView.NO_POSITION, false));
            return true;
        }

        boolean focusFirst() {
            LibrarySnapshot<AppView.AppObject> snapshot = target();
            if (snapshot == null || snapshot.rows.isEmpty()) {
                return false;
            }
            request(new FocusRequest(null, null, RecyclerView.NO_POSITION, false, true));
            return true;
        }

        private static int firstFocusablePosition(LibrarySnapshot<AppView.AppObject> snapshot) {
            for (int i = 0; i < snapshot.rows.size(); i++) {
                LibrarySnapshot.Row row = snapshot.rows.get(i);
                if (row instanceof LibrarySnapshot.AppRow
                        || (row instanceof LibrarySnapshot.HeaderRow && ((LibrarySnapshot.HeaderRow) row).collapsed)) {
                    return i;
                }
            }
            return 0;
        }

        boolean jumpToAdjacentGroup(int direction) {
            if (committed == null || committed.groups.isEmpty()) {
                return false;
            }
            LibrarySnapshot.Row row = getFocusedRow();
            String target;
            if (row != null) {
                target = committed.adjacentGroupKey(row.groupKey, direction);
            }
            else {
                // Nothing focused yet: go to the group at the top of the screen
                target = null;
                View first = recyclerView.getChildCount() > 0 ? recyclerView.getChildAt(0) : null;
                RecyclerView.ViewHolder holder = first != null ? recyclerView.getChildViewHolder(first) : null;
                int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                if (position != RecyclerView.NO_POSITION && position < adapter.getItemCount()) {
                    target = adapter.getRow(position).groupKey;
                }
                if (target == null) {
                    target = committed.groups.get(0).key;
                }
            }
            if (target == null) {
                return false;
            }
            int header = committed.findHeaderPosition(target);
            if (header >= 0 && recyclerView.getLayoutManager() instanceof GridLayoutManager) {
                // Bring the header to the top so the whole group start is visible
                ((GridLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(header, 0);
            }
            pending = new FocusRequest(null, target, RecyclerView.NO_POSITION, false);
            recyclerView.requestLayout();
            return true;
        }

        void onLayoutCompleted() {
            if (pending != null) {
                // Focus changes are not allowed inside the layout pass
                recyclerView.post(new Runnable() {
                    @Override
                    public void run() {
                        applyPending();
                    }
                });
            }
        }

        private int resolve(FocusRequest request) {
            if (committed == null || committed.rows.isEmpty()) {
                return RecyclerView.NO_POSITION;
            }
            if (request.first) {
                return firstFocusablePosition(committed);
            }
            if (request.appKey != null) {
                int position = committed.findAppPosition(request.appKey);
                if (position >= 0) {
                    return position;
                }
            }
            if (request.groupKey != null) {
                int position = request.preferHeader ? committed.findHeaderPosition(request.groupKey) : -1;
                if (position >= 0 && committed.rows.get(position) instanceof LibrarySnapshot.HeaderRow
                        && ((LibrarySnapshot.HeaderRow) committed.rows.get(position)).collapsed) {
                    return position;
                }
                position = committed.findGroupEntryPosition(request.groupKey);
                if (position >= 0) {
                    return position;
                }
            }
            if (request.position != RecyclerView.NO_POSITION) {
                return Math.max(0, Math.min(request.position, committed.rows.size() - 1));
            }
            return RecyclerView.NO_POSITION;
        }

        private void applyPending() {
            final FocusRequest request = pending;
            if (request == null || recyclerView.isComputingLayout()) {
                return;
            }
            int position = resolve(request);
            if (position == RecyclerView.NO_POSITION) {
                pending = null;
                return;
            }

            // Skip rows that cannot take focus (expanded headers) by moving to the next row
            List<LibrarySnapshot.Row> rows = committed.rows;
            if (rows.get(position) instanceof LibrarySnapshot.HeaderRow
                    && !((LibrarySnapshot.HeaderRow) rows.get(position)).collapsed
                    && position + 1 < rows.size()) {
                position++;
            }

            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView.isFocusable() && !holder.itemView.isLayoutRequested()) {
                pending = null;
                if (!holder.itemView.hasFocus()) {
                    holder.itemView.requestFocus();
                }
                return;
            }

            if (request.attempts++ < 3) {
                // Not laid out yet: scroll there and try again after the next layout
                recyclerView.scrollToPosition(position);
                recyclerView.requestLayout();
            }
            else {
                pending = null;
            }
        }
    }
}

package com.limelight.library;

import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shelves layout: one row per group, a header over a horizontal list of
 * cards. Up and down move between shelves and return to the card that was
 * focused last in each shelf.
 */
public class ShelfLayoutController implements LibraryLayoutController {
    private static final int SHELF_CARD_WIDTH_DP = 150;
    private static final int MAX_FOCUS_ATTEMPTS = 6;

    private final Host host;

    private RecyclerView shelves;
    private LinearLayoutManager layoutManager;
    private ShelfAdapter adapter;
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();
    private LibrarySnapshot<AppView.AppObject> committed;
    private LibrarySnapshot<AppView.AppObject> latest;
    // Focus asked for before the latest snapshot was committed
    private String pendingFocusApp;
    private boolean pendingFocusFirst;
    // Group key to the app key focused last in that shelf
    private final Map<String, String> lastFocusedByGroup = new HashMap<>();
    private int focusGeneration;

    public ShelfLayoutController(Host host) {
        this.host = host;
        cardPool.setMaxRecycledViews(LibrarySnapshot.TYPE_APP, 40);
    }

    @Override
    public String getLayoutName() {
        return LibraryPrefs.LAYOUT_SHELVES;
    }

    private int dp(int value) {
        return Math.round(value * host.getContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container) {
        shelves = (RecyclerView) inflater.inflate(R.layout.library_shelves, container, false);
        layoutManager = new LinearLayoutManager(host.getContext()) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                // Keep the focused shelf near the top third of the screen
                int dy = child.getTop() - parent.getHeight() / 6;
                if (dy == 0) {
                    return false;
                }
                if (immediate) {
                    parent.scrollBy(0, dy);
                }
                else {
                    parent.smoothScrollBy(0, dy);
                }
                return true;
            }
        };
        shelves.setLayoutManager(layoutManager);
        adapter = new ShelfAdapter();
        shelves.setAdapter(adapter);
        if (committed != null) {
            adapter.submitList(committed.groups);
        }
        return shelves;
    }

    @Override
    public void onDestroyView() {
        shelves = null;
        adapter = null;
        layoutManager = null;
    }

    @Override
    public void submit(final LibrarySnapshot<AppView.AppObject> snapshot) {
        latest = snapshot;
        if (adapter == null) {
            committed = snapshot;
            return;
        }

        // Remember what has focus, to put it back after the update
        String focusedGroup = null;
        String focusedApp = null;
        boolean onHeader = false;
        if (shelves.hasFocus()) {
            View focused = shelves.findFocus();
            focusedGroup = getGroupKeyOf(focused);
            Object tag = focused != null ? focused.getTag(R.id.tag_app_object) : null;
            if (tag instanceof AppView.AppObject) {
                focusedApp = LibraryModel.appKey((AppView.AppObject) tag);
            }
            else {
                onHeader = true;
            }
        }
        final String group = focusedGroup;
        final String app = focusedApp;
        final boolean header = onHeader;

        adapter.submitList(snapshot.groups, new Runnable() {
            @Override
            public void run() {
                committed = snapshot;
                if (snapshot != latest) {
                    // A newer snapshot is on its way and will restore focus itself
                    return;
                }
                if (pendingFocusApp != null || pendingFocusFirst) {
                    String key = pendingFocusApp;
                    pendingFocusApp = null;
                    pendingFocusFirst = false;
                    if (key == null || !focusApp(key)) {
                        focusFirst();
                    }
                }
                else if (group != null) {
                    String targetGroup = group;
                    if (app != null && snapshot.findApp(app) != null) {
                        // The app may have moved to another group
                        targetGroup = snapshot.findApp(app).groupKey;
                    }
                    requestFocusIn(targetGroup, header ? null : app, false);
                }
            }
        });
    }

    private String getGroupKeyOf(View view) {
        if (view == null || shelves == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = shelves.findContainingViewHolder(view);
        return holder instanceof ShelfHolder ? ((ShelfHolder) holder).groupKey : null;
    }

    private LibrarySnapshot.Group<AppView.AppObject> findGroup(String key) {
        if (committed == null || key == null) {
            return null;
        }
        for (LibrarySnapshot.Group<AppView.AppObject> group : committed.groups) {
            if (group.key.equals(key)) {
                return group;
            }
        }
        return null;
    }

    private int indexOfGroup(String key) {
        if (committed == null) {
            return -1;
        }
        for (int i = 0; i < committed.groups.size(); i++) {
            if (committed.groups.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfApp(LibrarySnapshot.Group<AppView.AppObject> group, String appKey) {
        if (appKey == null) {
            return -1;
        }
        for (int i = 0; i < group.apps.size(); i++) {
            if (group.apps.get(i).appKey.equals(appKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Focuses an app in a shelf (or the shelf's remembered card, or its header
     * when collapsed), scrolling it into view first. Retries across layouts
     * while the nested lists commit their data.
     */
    private void requestFocusIn(final String groupKey, final String appKey, boolean scrollShelfToTop) {
        final int generation = ++focusGeneration;
        if (scrollShelfToTop) {
            int index = indexOfGroup(groupKey);
            if (index >= 0) {
                layoutManager.scrollToPositionWithOffset(index, shelves.getHeight() / 6);
            }
        }
        attemptFocus(groupKey, appKey, generation, 0);
    }

    private void attemptFocus(final String groupKey, final String appKey, final int generation, final int attempt) {
        if (shelves == null || generation != focusGeneration || attempt > MAX_FOCUS_ATTEMPTS) {
            return;
        }
        if (tryFocus(groupKey, appKey)) {
            return;
        }
        shelves.post(new Runnable() {
            @Override
            public void run() {
                attemptFocus(groupKey, appKey, generation, attempt + 1);
            }
        });
    }

    private boolean tryFocus(String groupKey, String appKey) {
        LibrarySnapshot.Group<AppView.AppObject> group = findGroup(groupKey);
        int index = indexOfGroup(groupKey);
        if (group == null || index < 0) {
            // Nothing to wait for
            return true;
        }

        RecyclerView.ViewHolder outer = shelves.findViewHolderForAdapterPosition(index);
        if (!(outer instanceof ShelfHolder)) {
            layoutManager.scrollToPosition(index);
            return false;
        }
        ShelfHolder shelf = (ShelfHolder) outer;

        if (group.collapsed || group.apps.isEmpty()) {
            return shelf.header.requestFocus();
        }

        int position = indexOfApp(group, appKey);
        if (position < 0) {
            position = Math.max(0, indexOfApp(group, lastFocusedByGroup.get(groupKey)));
        }
        if (shelf.cards.getItemCount() <= position) {
            // The nested list has not committed this group yet
            return false;
        }
        RecyclerView.ViewHolder card = shelf.list.findViewHolderForAdapterPosition(position);
        if (card == null) {
            shelf.list.scrollToPosition(position);
            return false;
        }
        return card.itemView.hasFocus() || card.itemView.requestFocus();
    }

    @Override
    public void onConfigurationChanged() {
        if (shelves == null) {
            return;
        }
        // The art loader was replaced; rebind every visible card in place
        for (int i = 0; i < shelves.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = shelves.getChildViewHolder(shelves.getChildAt(i));
            if (holder instanceof ShelfHolder) {
                LibraryRowAdapter cards = ((ShelfHolder) holder).cards;
                cards.notifyItemRangeChanged(0, cards.getItemCount(), LibraryRowAdapter.PAYLOAD_REBIND);
            }
        }
        cardPool.clear();
    }

    @Override
    public boolean focusApp(String appKey) {
        if (appKey == null || shelves == null) {
            return false;
        }
        if (latest != null && latest != committed) {
            // Apply once the latest snapshot is on screen
            if (latest.findApp(appKey) == null) {
                return false;
            }
            pendingFocusApp = appKey;
            return true;
        }
        if (committed == null) {
            return false;
        }
        LibrarySnapshot.AppRow<AppView.AppObject> row = committed.findApp(appKey);
        if (row == null || shelves == null) {
            return false;
        }
        requestFocusIn(row.groupKey, appKey, true);
        return true;
    }

    @Override
    public boolean focusFirst() {
        if (shelves == null) {
            return false;
        }
        if (latest != null && latest != committed) {
            if (latest.groups.isEmpty()) {
                return false;
            }
            pendingFocusFirst = true;
            return true;
        }
        if (committed == null || committed.groups.isEmpty()) {
            return false;
        }
        requestFocusIn(committed.groups.get(0).key, null, true);
        return true;
    }

    @Override
    public boolean hasFocus() {
        return shelves != null && shelves.hasFocus();
    }

    @Override
    public AppView.AppObject getCurrentApp() {
        View view = getCurrentAppView();
        return view != null ? (AppView.AppObject) view.getTag(R.id.tag_app_object) : null;
    }

    @Override
    public View getCurrentAppView() {
        if (shelves == null || !shelves.hasFocus()) {
            return null;
        }
        View focused = shelves.findFocus();
        if (focused != null && focused.getTag(R.id.tag_app_object) instanceof AppView.AppObject) {
            return focused;
        }
        return null;
    }

    @Override
    public boolean jumpToAdjacentGroup(int direction) {
        if (committed == null || committed.groups.isEmpty() || shelves == null) {
            return false;
        }
        String current = shelves.hasFocus() ? getGroupKeyOf(shelves.findFocus()) : null;
        String target;
        if (current != null) {
            target = committed.adjacentGroupKey(current, direction);
        }
        else {
            // Nothing focused yet: go to the shelf at the top of the screen
            int first = layoutManager.findFirstVisibleItemPosition();
            target = first >= 0 && first < committed.groups.size()
                    ? committed.groups.get(first).key : committed.groups.get(0).key;
        }
        requestFocusIn(target, lastFocusedByGroup.get(target), true);
        return true;
    }

    @Override
    public String getCurrentGroupKey() {
        return shelves != null && shelves.hasFocus() ? getGroupKeyOf(shelves.findFocus()) : null;
    }

    @Override
    public boolean supportsCollapse() {
        return true;
    }

    private static final DiffUtil.ItemCallback<LibrarySnapshot.Group<AppView.AppObject>> DIFF =
            new DiffUtil.ItemCallback<LibrarySnapshot.Group<AppView.AppObject>>() {
                @Override
                public boolean areItemsTheSame(@NonNull LibrarySnapshot.Group<AppView.AppObject> oldItem,
                                               @NonNull LibrarySnapshot.Group<AppView.AppObject> newItem) {
                    return oldItem.key.equals(newItem.key);
                }

                @Override
                public boolean areContentsTheSame(@NonNull LibrarySnapshot.Group<AppView.AppObject> oldItem,
                                                  @NonNull LibrarySnapshot.Group<AppView.AppObject> newItem) {
                    if (!oldItem.label.equals(newItem.label) || oldItem.collapsed != newItem.collapsed
                            || oldItem.apps.size() != newItem.apps.size()) {
                        return false;
                    }
                    for (int i = 0; i < oldItem.apps.size(); i++) {
                        if (!oldItem.apps.get(i).sameContent(newItem.apps.get(i))) {
                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public Object getChangePayload(@NonNull LibrarySnapshot.Group<AppView.AppObject> oldItem,
                                               @NonNull LibrarySnapshot.Group<AppView.AppObject> newItem) {
                    // Rebind the shelf in place so focus inside it survives
                    return LibraryRowAdapter.PAYLOAD_REBIND;
                }
            };

    private final class ShelfHolder extends RecyclerView.ViewHolder {
        final View header;
        final ImageView chevron;
        final TextView label;
        final TextView count;
        final ShelfRecyclerView list;
        final LibraryRowAdapter cards;
        String groupKey;

        ShelfHolder(View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.shelfHeader);
            chevron = header.findViewById(R.id.header_chevron);
            label = header.findViewById(R.id.header_label);
            count = header.findViewById(R.id.header_count);
            list = itemView.findViewById(R.id.shelfList);

            LinearLayoutManager shelfLayout = new LinearLayoutManager(host.getContext(), LinearLayoutManager.HORIZONTAL, false);
            shelfLayout.setInitialPrefetchItemCount(6);
            list.setLayoutManager(shelfLayout);
            list.setRecycledViewPool(cardPool);
            list.setFocusable(true);
            list.setFocusableInTouchMode(false);

            cards = new LibraryRowAdapter(host, R.layout.app_grid_item, dp(SHELF_CARD_WIDTH_DP),
                    new LibraryRowAdapter.FocusListener() {
                        @Override
                        public void onRowFocused(RecyclerView.ViewHolder holder, LibrarySnapshot.Row row) {
                            if (row instanceof LibrarySnapshot.AppRow && groupKey != null) {
                                lastFocusedByGroup.put(groupKey, ((LibrarySnapshot.AppRow<?>) row).appKey);
                            }
                        }
                    });
            list.setAdapter(cards);
            list.setFocusMemory(new ShelfRecyclerView.FocusMemory() {
                @Override
                public int getRememberedPosition(ShelfRecyclerView shelf) {
                    LibrarySnapshot.Group<AppView.AppObject> group = findGroup(groupKey);
                    if (group == null) {
                        return RecyclerView.NO_POSITION;
                    }
                    int position = indexOfApp(group, lastFocusedByGroup.get(groupKey));
                    return position >= 0 ? position : 0;
                }
            });

            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (groupKey != null) {
                        host.onGroupHeaderClicked(groupKey);
                    }
                }
            });
        }
    }

    private final class ShelfAdapter extends ListAdapter<LibrarySnapshot.Group<AppView.AppObject>, ShelfHolder> {
        ShelfAdapter() {
            super(DIFF);
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return LibrarySnapshot.headerId(getItem(position).key);
        }

        @NonNull
        @Override
        public ShelfHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ShelfHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.library_shelf_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ShelfHolder holder, int position) {
            LibrarySnapshot.Group<AppView.AppObject> group = getItem(position);
            boolean newGroup = !group.key.equals(holder.groupKey);
            holder.groupKey = group.key;

            holder.label.setText(group.label);
            holder.count.setText(String.valueOf(group.apps.size()));
            holder.chevron.setImageResource(group.collapsed ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_down);
            holder.header.setContentDescription(host.getContext().getString(
                    group.collapsed ? R.string.library_group_header_collapsed_desc : R.string.library_group_header_desc,
                    group.label, group.apps.size()));
            // A collapsed shelf is just its header, which then takes D-pad focus
            holder.header.setFocusable(group.collapsed);
            holder.list.setVisibility(group.collapsed ? View.GONE : View.VISIBLE);

            List<LibrarySnapshot.Row> rows = new ArrayList<LibrarySnapshot.Row>(group.apps);
            holder.cards.submitList(group.collapsed ? new ArrayList<LibrarySnapshot.Row>() : rows);
            if (newGroup) {
                holder.list.scrollToPosition(0);
            }
        }
    }
}

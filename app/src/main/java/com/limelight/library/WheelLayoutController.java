package com.limelight.library;

import android.content.Context;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wheel layout (ES-DE and HyperSpin style): one system at a time, a vertical
 * wheel of titles that curves away from the centered selection, and a large
 * hero panel for the selected app. The wheel itself holds focus; the selection
 * is the centered item, not Android focus.
 */
public class WheelLayoutController implements LibraryLayoutController {
    private static final float MIN_SCALE = 0.7f;
    private static final float SCALE_STEP = 0.12f;
    private static final float MIN_ALPHA = 0.35f;
    private static final float ALPHA_STEP = 0.22f;
    private static final float ARC_STEP = 0.28f;
    private static final int ARC_RADIUS_DP = 160;
    private static final int SCROLL_MS = 150;
    private static final int FAST_SCROLL_MS = 70;
    // A held direction moves about 12 items per second
    private static final int REPEAT_INTERVAL_MS = 83;

    private final Host host;

    private RecyclerView wheel;
    private LinearLayoutManager layoutManager;
    private WheelAdapter adapter;
    private LinearLayout systemStrip;
    private HorizontalScrollView systemScroll;
    private ImageView heroArt;
    private TextView heroArtText;
    private TextView heroTitle;
    private TextView heroSystem;
    private TextView heroRunning;

    private LibrarySnapshot<AppView.AppObject> snapshot;
    // The systems shown in the strip: the groups, or one "Results" group while searching
    private final List<String> systemKeys = new ArrayList<>();
    private final List<String> systemLabels = new ArrayList<>();
    private String currentSystem;
    private List<LibrarySnapshot.AppRow<AppView.AppObject>> currentApps = new ArrayList<>();
    private int selectedIndex = RecyclerView.NO_POSITION;
    private String selectedKey;
    private AppView.AppObject heroApp;
    private final Map<String, String> lastSelectedBySystem = new HashMap<>();
    private String pendingSelectKey;
    private boolean pendingFocus;
    private long lastRepeatMoveTime;
    private boolean confirmLongPressed;

    public WheelLayoutController(Host host) {
        this.host = host;
    }

    @Override
    public String getLayoutName() {
        return LibraryPrefs.LAYOUT_WHEEL;
    }

    private int dp(int value) {
        return Math.round(value * host.getContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container) {
        View root = inflater.inflate(R.layout.library_wheel, container, false);
        systemScroll = root.findViewById(R.id.wheelSystemScroll);
        systemStrip = root.findViewById(R.id.wheelSystems);
        heroArt = root.findViewById(R.id.wheelHeroArt);
        heroArtText = root.findViewById(R.id.wheelHeroArtText);
        heroTitle = root.findViewById(R.id.wheelHeroTitle);
        heroSystem = root.findViewById(R.id.wheelHeroSystem);
        heroRunning = root.findViewById(R.id.wheelHeroRunning);
        wheel = root.findViewById(R.id.wheelList);

        layoutManager = new LinearLayoutManager(host.getContext()) {
            @Override
            public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                applyTransforms();
            }
        };
        wheel.setLayoutManager(layoutManager);
        wheel.setItemAnimator(null);
        adapter = new WheelAdapter();
        wheel.setAdapter(adapter);
        new LinearSnapHelper().attachToRecyclerView(wheel);

        wheel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                applyTransforms();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // After a fling the centered item becomes the selection
                    int centered = findCenteredPosition();
                    if (centered != RecyclerView.NO_POSITION && centered != selectedIndex) {
                        select(centered, false);
                    }
                }
            }
        });

        wheel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (bottom - top != oldBottom - oldTop) {
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            updateWheelPadding();
                        }
                    });
                }
            }
        });

        wheel.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return handleWheelKey(keyCode, event);
            }
        });

        // The hero shows the selection; its art anchors the context menu
        heroArt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (heroApp != null) {
                    host.onAppClicked(heroApp, heroArt);
                }
            }
        });
        host.registerContextView(heroArt);

        return root;
    }

    private void updateWheelPadding() {
        if (wheel == null) {
            return;
        }
        // Let the first and last items reach the center
        int vertical = Math.max(0, wheel.getHeight() / 2 - dp(96) / 2);
        if (wheel.getPaddingTop() != vertical || wheel.getPaddingBottom() != vertical) {
            wheel.setPadding(wheel.getPaddingLeft(), vertical, wheel.getPaddingRight(), vertical);
            if (selectedIndex != RecyclerView.NO_POSITION) {
                layoutManager.scrollToPositionWithOffset(selectedIndex, 0);
            }
        }
    }

    @Override
    public void onDestroyView() {
        wheel = null;
        adapter = null;
        layoutManager = null;
        systemStrip = null;
        systemScroll = null;
        heroArt = null;
    }

    @Override
    public void submit(LibrarySnapshot<AppView.AppObject> snapshot) {
        this.snapshot = snapshot;
        rebuildSystems();

        String wanted = currentSystem;
        if (snapshot.queryActive) {
            wanted = PlatformCatalog.KEY_RESULTS;
        }
        else if (wanted == null || !systemKeys.contains(wanted)) {
            String saved = host.getLibraryPrefs().getWheelGroup();
            wanted = saved != null && systemKeys.contains(saved) ? saved : null;
            if (wanted == null && !systemKeys.isEmpty()) {
                wanted = systemKeys.get(0);
            }
        }
        showSystem(wanted, false);
    }

    private void rebuildSystems() {
        systemKeys.clear();
        systemLabels.clear();
        if (snapshot.queryActive) {
            if (snapshot.matchCount > 0) {
                systemKeys.add(PlatformCatalog.KEY_RESULTS);
                systemLabels.add(host.getContext().getString(R.string.library_results));
            }
        }
        else {
            for (LibrarySnapshot.Group<AppView.AppObject> group : snapshot.groups) {
                systemKeys.add(group.key);
                systemLabels.add(group.label);
            }
        }

        if (systemStrip == null) {
            return;
        }
        systemStrip.removeAllViews();
        Context context = host.getContext();
        for (int i = 0; i < systemKeys.size(); i++) {
            final String key = systemKeys.get(i);
            TextView chip = new TextView(context);
            chip.setText(systemLabels.get(i));
            chip.setTextSize(15);
            chip.setTextColor(ContextCompat.getColorStateList(context, R.color.library_chip_text));
            chip.setBackgroundResource(R.drawable.library_chip_background);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setSingleLine(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSystem(key, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            systemStrip.addView(chip, lp);
        }
    }

    private List<LibrarySnapshot.AppRow<AppView.AppObject>> appsOf(String systemKey) {
        if (snapshot == null || systemKey == null) {
            return new ArrayList<>();
        }
        if (PlatformCatalog.KEY_RESULTS.equals(systemKey)) {
            return snapshot.flatApps();
        }
        LibrarySnapshot.Group<AppView.AppObject> group = snapshot.findGroup(systemKey);
        // Collapse does not apply here: the wheel shows every app of the system
        return group != null ? new ArrayList<>(group.apps) : new ArrayList<LibrarySnapshot.AppRow<AppView.AppObject>>();
    }

    private void showSystem(String systemKey, boolean userChange) {
        boolean systemChanged = systemKey == null || !systemKey.equals(currentSystem);
        if (systemChanged && currentSystem != null && selectedKey != null) {
            lastSelectedBySystem.put(currentSystem, selectedKey);
        }
        currentSystem = systemKey;
        if (systemKey != null && !PlatformCatalog.KEY_RESULTS.equals(systemKey)) {
            host.getLibraryPrefs().setWheelGroup(systemKey);
        }

        // Highlight the current chip and bring it into view
        if (systemStrip != null) {
            for (int i = 0; i < systemStrip.getChildCount(); i++) {
                final View chip = systemStrip.getChildAt(i);
                boolean selected = i < systemKeys.size() && systemKeys.get(i).equals(systemKey);
                chip.setSelected(selected);
                if (selected) {
                    chip.post(new Runnable() {
                        @Override
                        public void run() {
                            if (systemScroll != null) {
                                systemScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(48)), 0);
                            }
                        }
                    });
                }
            }
        }

        // Which app to select in the new list
        String keep;
        if (pendingSelectKey != null) {
            keep = pendingSelectKey;
        }
        else if (systemChanged) {
            keep = lastSelectedBySystem.get(systemKey);
        }
        else {
            keep = selectedKey;
        }
        final int previousIndex = selectedIndex;
        final boolean changed = systemChanged;
        final String keepKey = keep;

        currentApps = appsOf(systemKey);
        if (adapter == null) {
            return;
        }
        adapter.submitList(currentApps, new Runnable() {
            @Override
            public void run() {
                int index = indexOf(keepKey);
                if (index < 0) {
                    index = changed ? 0 : Math.min(Math.max(previousIndex, 0), currentApps.size() - 1);
                }
                pendingSelectKey = null;
                if (index >= 0 && index < currentApps.size()) {
                    select(index, false);
                    // Polling updates keep the position; only jump when the selection moved
                    if (layoutManager != null && (changed || index != previousIndex)) {
                        layoutManager.scrollToPositionWithOffset(index, 0);
                    }
                }
                else {
                    selectedIndex = RecyclerView.NO_POSITION;
                    selectedKey = null;
                    updateHero(null);
                }
                if (pendingFocus && wheel != null) {
                    pendingFocus = false;
                    wheel.requestFocus();
                }
            }
        });
    }

    private int indexOf(String appKey) {
        if (appKey == null) {
            return -1;
        }
        for (int i = 0; i < currentApps.size(); i++) {
            if (currentApps.get(i).appKey.equals(appKey)) {
                return i;
            }
        }
        return -1;
    }

    private void select(int index, boolean animate) {
        if (index < 0 || index >= currentApps.size()) {
            return;
        }
        selectedIndex = index;
        LibrarySnapshot.AppRow<AppView.AppObject> row = currentApps.get(index);
        selectedKey = row.appKey;
        updateHero(row);
        host.onAppFocused(row.app);

        if (animate && wheel != null) {
            long now = SystemClock.uptimeMillis();
            final boolean fast = now - lastRepeatMoveTime < 3 * REPEAT_INTERVAL_MS;
            LinearSmoothScroller scroller = new LinearSmoothScroller(host.getContext()) {
                @Override
                public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                    // Center the target
                    return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
                }

                @Override
                protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                    float itemPx = 96 * displayMetrics.density;
                    return (fast ? FAST_SCROLL_MS : SCROLL_MS) / itemPx;
                }
            };
            scroller.setTargetPosition(index);
            layoutManager.startSmoothScroll(scroller);
        }
    }

    private void updateHero(LibrarySnapshot.AppRow<AppView.AppObject> row) {
        if (heroArt == null) {
            return;
        }
        if (row == null) {
            heroApp = null;
            heroArt.setTag(R.id.tag_app_object, null);
            heroArt.setImageDrawable(null);
            heroArtText.setText("");
            heroTitle.setText("");
            heroSystem.setText("");
            heroRunning.setVisibility(View.GONE);
            return;
        }

        boolean newApp = heroApp != row.app;
        heroApp = row.app;
        heroArt.setTag(R.id.tag_app_object, row.app);
        heroArt.setContentDescription(row.name);
        heroTitle.setText(row.name);
        heroSystem.setText(row.groupLabel);
        heroRunning.setVisibility(row.running ? View.VISIBLE : View.GONE);
        heroArt.setAlpha(row.hidden ? 0.4f : 1.0f);

        if (newApp) {
            host.getAppLibrary().getLoader().populateImageView(row.app.app, heroArt, heroArtText);
            // Crossfade to the new art
            View artFrame = (View) heroArt.getParent();
            artFrame.animate().cancel();
            artFrame.setAlpha(0.3f);
            artFrame.animate().alpha(1.0f).setDuration(180).start();
        }
    }

    private int findCenteredPosition() {
        if (wheel == null || wheel.getChildCount() == 0) {
            return RecyclerView.NO_POSITION;
        }
        float center = wheel.getHeight() / 2f;
        int best = RecyclerView.NO_POSITION;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < wheel.getChildCount(); i++) {
            View child = wheel.getChildAt(i);
            float childCenter = (child.getTop() + child.getBottom()) / 2f;
            float distance = Math.abs(childCenter - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = wheel.getChildAdapterPosition(child);
            }
        }
        return best;
    }

    private void applyTransforms() {
        if (wheel == null) {
            return;
        }
        float center = wheel.getHeight() / 2f;
        float itemHeight = dp(96);
        float arcRadius = dp(ARC_RADIUS_DP);
        for (int i = 0; i < wheel.getChildCount(); i++) {
            View child = wheel.getChildAt(i);
            float childCenter = (child.getTop() + child.getBottom()) / 2f;
            float d = Math.abs((childCenter - center) / itemHeight);
            float scale = Math.max(MIN_SCALE, 1 - SCALE_STEP * d);
            float alpha = Math.max(MIN_ALPHA, 1 - ALPHA_STEP * d);
            RecyclerView.ViewHolder holder = wheel.getChildViewHolder(child);
            if (holder instanceof WheelHolder && ((WheelHolder) holder).row != null && ((WheelHolder) holder).row.hidden) {
                alpha *= 0.4f;
            }
            child.setPivotX(0);
            child.setPivotY(child.getHeight() / 2f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);
            child.setTranslationX((float) (arcRadius * (1 - Math.cos(Math.min(d, 4f) * ARC_STEP))));
        }
    }

    private boolean handleWheelKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() > 0) {
                        long now = SystemClock.uptimeMillis();
                        if (now - lastRepeatMoveTime < REPEAT_INTERVAL_MS) {
                            return true;
                        }
                        lastRepeatMoveTime = now;
                    }
                    else {
                        lastRepeatMoveTime = 0;
                    }
                    int target = selectedIndex + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    // No wrap: the ends of the wheel stop the selection
                    if (target >= 0 && target < currentApps.size()) {
                        select(target, true);
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        confirmLongPressed = false;
                        event.startTracking();
                    }
                    else if (event.isLongPress() && !confirmLongPressed) {
                        // Long press A opens the context menu, as on cards
                        confirmLongPressed = true;
                        if (heroApp != null && wheel != null) {
                            heroArt.showContextMenu();
                        }
                    }
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (!confirmLongPressed && !event.isCanceled() && heroApp != null) {
                        host.onAppClicked(heroApp, heroArt);
                    }
                    confirmLongPressed = false;
                }
                return true;
            }
            default:
                return false;
        }
    }

    @Override
    public void onConfigurationChanged() {
        if (adapter != null) {
            // The art loader was replaced; rebind and refresh the hero
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
            heroApp = null;
            if (selectedIndex >= 0 && selectedIndex < currentApps.size()) {
                updateHero(currentApps.get(selectedIndex));
            }
        }
    }

    @Override
    public boolean focusApp(String appKey) {
        if (snapshot == null || appKey == null || snapshot.findApp(appKey) == null) {
            return false;
        }
        String system = PlatformCatalog.KEY_RESULTS;
        if (!snapshot.queryActive) {
            system = snapshot.findApp(appKey).groupKey;
        }
        pendingSelectKey = appKey;
        pendingFocus = true;
        showSystem(system, false);
        return true;
    }

    @Override
    public boolean focusFirst() {
        if (wheel == null || snapshot == null || systemKeys.isEmpty()) {
            return false;
        }
        if (currentApps.isEmpty()) {
            showSystem(systemKeys.get(0), false);
        }
        pendingFocus = true;
        wheel.requestFocus();
        return true;
    }

    @Override
    public boolean hasFocus() {
        return wheel != null && wheel.hasFocus();
    }

    @Override
    public AppView.AppObject getCurrentApp() {
        return heroApp;
    }

    @Override
    public View getCurrentAppView() {
        return heroApp != null ? heroArt : null;
    }

    @Override
    public boolean jumpToAdjacentGroup(int direction) {
        if (systemKeys.isEmpty()) {
            return false;
        }
        int index = systemKeys.indexOf(currentSystem);
        int size = systemKeys.size();
        int next = index < 0 ? 0 : ((index + (direction >= 0 ? 1 : -1)) % size + size) % size;
        showSystem(systemKeys.get(next), true);
        if (wheel != null && !wheel.hasFocus()) {
            wheel.requestFocus();
        }
        return true;
    }

    @Override
    public String getCurrentGroupKey() {
        return currentSystem;
    }

    @Override
    public boolean supportsCollapse() {
        return false;
    }

    private static final DiffUtil.ItemCallback<LibrarySnapshot.AppRow<AppView.AppObject>> DIFF =
            new DiffUtil.ItemCallback<LibrarySnapshot.AppRow<AppView.AppObject>>() {
                @Override
                public boolean areItemsTheSame(@NonNull LibrarySnapshot.AppRow<AppView.AppObject> oldItem,
                                               @NonNull LibrarySnapshot.AppRow<AppView.AppObject> newItem) {
                    return oldItem.getStableId() == newItem.getStableId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull LibrarySnapshot.AppRow<AppView.AppObject> oldItem,
                                                  @NonNull LibrarySnapshot.AppRow<AppView.AppObject> newItem) {
                    return oldItem.sameContent(newItem);
                }

                @Override
                public Object getChangePayload(@NonNull LibrarySnapshot.AppRow<AppView.AppObject> oldItem,
                                               @NonNull LibrarySnapshot.AppRow<AppView.AppObject> newItem) {
                    return newItem.onlyRunningChanged(oldItem) ? LibraryRowAdapter.PAYLOAD_STATE : LibraryRowAdapter.PAYLOAD_REBIND;
                }
            };

    private static final class WheelHolder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView artText;
        final TextView title;
        final ImageView running;
        LibrarySnapshot.AppRow<AppView.AppObject> row;

        WheelHolder(View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.grid_image);
            artText = itemView.findViewById(R.id.grid_text);
            title = itemView.findViewById(R.id.wheel_item_title);
            running = itemView.findViewById(R.id.wheel_item_running);
        }
    }

    private final class WheelAdapter extends ListAdapter<LibrarySnapshot.AppRow<AppView.AppObject>, WheelHolder> {
        WheelAdapter() {
            super(DIFF);
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).getStableId();
        }

        @NonNull
        @Override
        public WheelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final WheelHolder holder = new WheelHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.library_wheel_item, parent, false));
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int position = holder.getBindingAdapterPosition();
                    if (position == RecyclerView.NO_POSITION || holder.row == null) {
                        return;
                    }
                    if (position == selectedIndex) {
                        // Tapping the centered item launches it
                        host.onAppClicked(holder.row.app, v);
                    }
                    else {
                        // Tapping another item brings it to the center
                        select(position, true);
                    }
                }
            });
            host.registerContextView(holder.itemView);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull WheelHolder holder, int position) {
            bind(holder, getItem(position), false);
        }

        @Override
        public void onBindViewHolder(@NonNull WheelHolder holder, int position, @NonNull List<Object> payloads) {
            boolean stateOnly = !payloads.isEmpty();
            for (Object payload : payloads) {
                if (payload != LibraryRowAdapter.PAYLOAD_STATE) {
                    stateOnly = false;
                    break;
                }
            }
            bind(holder, getItem(position), stateOnly);
        }

        private void bind(WheelHolder holder, LibrarySnapshot.AppRow<AppView.AppObject> row, boolean stateOnly) {
            holder.row = row;
            holder.itemView.setTag(R.id.tag_app_object, row.app);
            holder.itemView.setContentDescription(row.name);
            holder.title.setText(row.name);
            holder.running.setVisibility(row.running ? View.VISIBLE : View.GONE);
            if (!stateOnly) {
                host.getAppLibrary().getLoader().populateImageView(row.app.app, holder.art, holder.artText);
            }
            if (row.appKey.equals(selectedKey)) {
                // Keep the hero in sync with polling updates (running state, name)
                updateHero(row);
            }
        }
    }
}

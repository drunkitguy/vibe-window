package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.library.AppLibrary;
import com.limelight.library.GridLayoutController;
import com.limelight.library.LibraryItem;
import com.limelight.library.LibraryLayoutController;
import com.limelight.library.LibraryModel;
import com.limelight.library.LibraryPrefs;
import com.limelight.library.LibrarySnapshot;
import com.limelight.library.LibraryText;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements LibraryLayoutController.Host {
    private AppLibrary appLibrary;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();
    // Apps hidden during this visit stay listed (dimmed) until the list is reopened,
    // so the focused card does not vanish under the user
    private final HashSet<Integer> hiddenThisVisit = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    // Library state
    private LibraryPrefs libraryPrefs;
    private String layoutName;
    private LibraryLayoutController controller;
    private LibrarySnapshot<AppObject> snapshot;
    private final HashSet<String> collapsedGroups = new HashSet<>();
    private Map<String, String> groupOverrides = new HashMap<>();
    private String query = "";
    private String lastFocusKey;
    private boolean initialFocusDone;

    private EditText searchField;
    private TextView filterCountView;
    private TextView emptyView;
    private FrameLayout libraryContainer;
    private MaterialButtonToggleGroup layoutToggle;
    private ImageButton collapseAllButton;
    private OnBackPressedCallback searchBackCallback;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable applySearchRunnable = new Runnable() {
        @Override
        public void run() {
            applySearch();
        }
    };
    private final HashSet<Integer> consumedKeyDowns = new HashSet<>();

    // The app and view the open context menu belongs to
    private AppObject contextApp;
    private View contextView;

    private final static int SEARCH_DEBOUNCE_MS = 150;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int CHANGE_GROUP_ID = 9;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appLibrary = new AppLibrary(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appLibrary.updateHiddenApps(hiddenAppIds);

                    // Now make the binder visible. We must do this after appLibrary
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appLibrary prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            showLayout(layoutName);
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appLibrary is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appLibrary != null) {
            // Recreate the art loader for the new density and card size
            appLibrary.updateLayoutWithPreferences(this, this.prefConfig);

            if (controller != null) {
                // Rebind the cards and recompute the columns without losing focus
                controller.onConfigurationChanged();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appLibrary != null) {
            appLibrary.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Restore the library state for this host
        libraryPrefs = new LibraryPrefs(this, uuidString);
        layoutName = libraryPrefs.getLayout();
        collapsedGroups.addAll(libraryPrefs.getCollapsedGroups());
        groupOverrides = libraryPrefs.getGroupOverrides();
        query = libraryPrefs.getQuery();
        lastFocusKey = libraryPrefs.getLastFocus();

        setupLibraryControls();

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void setupLibraryControls() {
        libraryContainer = findViewById(R.id.libraryContainer);
        emptyView = findViewById(R.id.libraryEmpty);
        filterCountView = findViewById(R.id.libraryFilterCount);
        searchField = findViewById(R.id.librarySearch);
        layoutToggle = findViewById(R.id.libraryLayoutToggle);
        collapseAllButton = findViewById(R.id.libraryCollapseAll);

        // A restored search is filled in, so the library never looks silently incomplete
        searchField.setText(query);
        updateSearchClearIcon();

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateSearchClearIcon();
                updateBackCallback();
                searchHandler.removeCallbacks(applySearchRunnable);
                searchHandler.postDelayed(applySearchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        searchField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    // Apply now and move to the results
                    searchHandler.removeCallbacks(applySearchRunnable);
                    applySearch();
                    hideKeyboard();
                    focusLibraryContent();
                    return true;
                }
                return false;
            }
        });
        searchField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateBackCallback();
            }
        });
        searchField.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // A tap on the clear icon at the end of the field clears the search
                if (event.getAction() == MotionEvent.ACTION_UP && searchField.length() > 0) {
                    Drawable[] drawables = searchField.getCompoundDrawablesRelative();
                    Drawable clear = drawables[2];
                    if (clear != null) {
                        int iconZone = clear.getBounds().width() + searchField.getPaddingEnd() + searchField.getCompoundDrawablePadding();
                        boolean rtl = searchField.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                        boolean onIcon = rtl ? event.getX() <= iconZone : event.getX() >= searchField.getWidth() - iconZone;
                        if (onIcon) {
                            clearSearch();
                            return true;
                        }
                    }
                }
                return false;
            }
        });

        searchBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                handleSearchBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, searchBackCallback);
        updateBackCallback();

        layoutToggle.check(getLayoutButtonId(layoutName));
        layoutToggle.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
                if (!isChecked) {
                    return;
                }
                String newLayout = getLayoutForButtonId(checkedId);
                if (newLayout != null && !newLayout.equals(layoutName)) {
                    switchLayout(newLayout);
                }
            }
        });
        updateLayoutButtons();

        collapseAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllCollapsed(!areAllGroupsCollapsed());
            }
        });
        updateCollapseAllButton();
    }

    // Layouts this version can show, in toggle order
    private static boolean isLayoutAvailable(String layout) {
        return LibraryPrefs.LAYOUT_GRID.equals(layout);
    }

    private static int getLayoutButtonId(String layout) {
        if (LibraryPrefs.LAYOUT_WHEEL.equals(layout)) {
            return R.id.layoutWheel;
        }
        else if (LibraryPrefs.LAYOUT_SHELVES.equals(layout)) {
            return R.id.layoutShelves;
        }
        return R.id.layoutGrid;
    }

    private static String getLayoutForButtonId(int id) {
        if (id == R.id.layoutGrid) {
            return LibraryPrefs.LAYOUT_GRID;
        }
        else if (id == R.id.layoutWheel) {
            return LibraryPrefs.LAYOUT_WHEEL;
        }
        else if (id == R.id.layoutShelves) {
            return LibraryPrefs.LAYOUT_SHELVES;
        }
        return null;
    }

    private void updateLayoutButtons() {
        findViewById(R.id.layoutGrid).setVisibility(isLayoutAvailable(LibraryPrefs.LAYOUT_GRID) ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutWheel).setVisibility(isLayoutAvailable(LibraryPrefs.LAYOUT_WHEEL) ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutShelves).setVisibility(isLayoutAvailable(LibraryPrefs.LAYOUT_SHELVES) ? View.VISIBLE : View.GONE);
    }

    private LibraryLayoutController createController(String layout) {
        return new GridLayoutController(this);
    }

    private void switchLayout(String layout) {
        if (!isLayoutAvailable(layout)) {
            layout = LibraryPrefs.LAYOUT_GRID;
        }
        layoutName = layout;
        libraryPrefs.setLayout(layout);
        if (layoutToggle.getCheckedButtonId() != getLayoutButtonId(layout)) {
            layoutToggle.check(getLayoutButtonId(layout));
        }
        if (appLibrary != null && controller != null) {
            // Keep the same app focused in the new layout
            AppObject current = controller.getCurrentApp();
            if (current != null) {
                lastFocusKey = LibraryModel.appKey(current);
            }
            boolean hadFocus = controller.hasFocus();
            showLayout(layout);
            if (hadFocus || lastFocusKey != null) {
                if (lastFocusKey == null || !controller.focusApp(lastFocusKey)) {
                    controller.focusFirst();
                }
            }
        }
    }

    private void showLayout(String layout) {
        if (!isLayoutAvailable(layout)) {
            layout = LibraryPrefs.LAYOUT_GRID;
        }
        layoutName = layout;

        if (controller != null) {
            View old = libraryContainer.findViewWithTag(controller);
            if (old != null) {
                libraryContainer.removeView(old);
            }
            controller.onDestroyView();
        }

        controller = createController(layout);
        View view = controller.onCreateView(LayoutInflater.from(this), libraryContainer);
        view.setTag(controller);
        // Keep the empty state text on top of the layout
        libraryContainer.addView(view, 0);

        updateCollapseAllButton();

        if (snapshot == null) {
            rebuildSnapshot();
        }
        else {
            controller.submit(snapshot);
            maybeApplyInitialFocus();
        }
    }

    private void maybeApplyInitialFocus() {
        if (initialFocusDone || controller == null || snapshot == null || snapshot.rows.isEmpty()) {
            return;
        }
        initialFocusDone = true;

        // The running app if any, else the last focused app, else the first card
        String key = null;
        if (lastRunningAppId != 0 && appLibrary != null) {
            AppObject running = appLibrary.findApp(lastRunningAppId);
            if (running != null) {
                key = LibraryModel.appKey(running);
            }
        }
        if (key == null || !controller.focusApp(key)) {
            if (lastFocusKey == null || !controller.focusApp(lastFocusKey)) {
                controller.focusFirst();
            }
        }
    }

    private void rebuildSnapshot() {
        if (appLibrary == null) {
            return;
        }

        snapshot = LibraryModel.build(new LibraryModel.Input<AppObject>()
                .apps(appLibrary.getAllApps())
                .hiddenIds(hiddenAppIds)
                .showHidden(showHiddenApps)
                .keepVisibleIds(hiddenThisVisit)
                .query(query)
                .collapsedKeys(collapsedGroups)
                .overrides(groupOverrides));

        updateFilterViews();
        updateCollapseAllButton();

        if (controller != null) {
            controller.submit(snapshot);
            maybeApplyInitialFocus();
        }
    }

    private void updateFilterViews() {
        if (snapshot != null && snapshot.queryActive) {
            filterCountView.setText(getString(R.string.library_filter_count, snapshot.matchCount, snapshot.totalCount));
            filterCountView.setVisibility(View.VISIBLE);
        }
        else {
            filterCountView.setVisibility(View.GONE);
        }

        if (snapshot != null && snapshot.queryActive && snapshot.matchCount == 0) {
            emptyView.setText(getString(R.string.library_no_results, query.trim()));
            emptyView.setVisibility(View.VISIBLE);
        }
        else {
            emptyView.setVisibility(View.GONE);
        }
    }

    // Search

    private void updateSearchClearIcon() {
        searchField.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0,
                searchField.length() > 0 ? R.drawable.ic_close : 0, 0);
    }

    private void applySearch() {
        String newQuery = searchField.getText() != null ? searchField.getText().toString() : "";
        if (newQuery.equals(query)) {
            return;
        }
        query = newQuery;
        rebuildSnapshot();
        updateBackCallback();
    }

    private void clearSearch() {
        searchHandler.removeCallbacks(applySearchRunnable);
        searchField.setText("");
        applySearch();
    }

    private void updateBackCallback() {
        if (searchBackCallback == null) {
            return;
        }
        boolean hasText = searchField.length() > 0;
        searchBackCallback.setEnabled(hasText || searchField.hasFocus());
    }

    /** Back with a search: clear a non-empty query, then leave the field, then leave the screen. */
    private void handleSearchBack() {
        if (searchField.length() > 0) {
            clearSearch();
        }
        else if (searchField.hasFocus()) {
            hideKeyboard();
            focusLibraryContent();
        }
        updateBackCallback();
    }

    private void focusSearch() {
        searchField.requestFocus();
        searchField.setSelection(searchField.length());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
    }

    private void focusLibraryContent() {
        if (controller != null) {
            if (lastFocusKey != null && controller.focusApp(lastFocusKey)) {
                return;
            }
            if (controller.focusFirst()) {
                return;
            }
        }
        // Nothing to focus in the library (no matches): move to the layout buttons
        View checked = findViewById(layoutToggle.getCheckedButtonId());
        if (checked != null) {
            checked.requestFocus();
        }
    }

    // Groups

    private void toggleGroup(String groupKey) {
        if (groupKey == null || snapshot == null || snapshot.queryActive) {
            // Collapse state is ignored (and kept) while searching
            return;
        }
        if (!collapsedGroups.remove(groupKey)) {
            collapsedGroups.add(groupKey);
        }
        libraryPrefs.setCollapsedGroups(collapsedGroups);
        rebuildSnapshot();
    }

    private boolean areAllGroupsCollapsed() {
        if (snapshot == null || snapshot.allGroups.isEmpty()) {
            return false;
        }
        for (LibrarySnapshot.Group<AppObject> group : snapshot.allGroups) {
            if (!collapsedGroups.contains(group.key)) {
                return false;
            }
        }
        return true;
    }

    private void setAllCollapsed(boolean collapse) {
        if (snapshot == null) {
            return;
        }
        if (collapse) {
            for (LibrarySnapshot.Group<AppObject> group : snapshot.allGroups) {
                collapsedGroups.add(group.key);
            }
        }
        else {
            collapsedGroups.clear();
        }
        libraryPrefs.setCollapsedGroups(collapsedGroups);
        rebuildSnapshot();
    }

    private void updateCollapseAllButton() {
        if (collapseAllButton == null) {
            return;
        }
        boolean supported = controller == null || controller.supportsCollapse();
        collapseAllButton.setVisibility(supported ? View.VISIBLE : View.GONE);
        boolean allCollapsed = areAllGroupsCollapsed();
        collapseAllButton.setImageResource(allCollapsed ? R.drawable.ic_unfold_more : R.drawable.ic_unfold_less);
        collapseAllButton.setContentDescription(getString(allCollapsed ? R.string.library_expand_all : R.string.library_collapse_all));
    }

    private void showViewMenu() {
        View anchor = layoutToggle;
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        final String[] layouts = {LibraryPrefs.LAYOUT_GRID, LibraryPrefs.LAYOUT_WHEEL, LibraryPrefs.LAYOUT_SHELVES};
        final int[] labels = {R.string.library_layout_grid, R.string.library_layout_wheel, R.string.library_layout_shelves};
        for (int i = 0; i < layouts.length; i++) {
            if (isLayoutAvailable(layouts[i])) {
                MenuItem item = menu.add(1, i, i, labels[i]);
                item.setCheckable(true);
                item.setChecked(layouts[i].equals(layoutName));
            }
        }
        menu.setGroupCheckable(1, true, true);
        final int collapseId = 100;
        final int expandId = 101;
        boolean collapseSupported = controller == null || controller.supportsCollapse();
        menu.add(2, collapseId, 10, R.string.library_collapse_all).setEnabled(collapseSupported);
        menu.add(2, expandId, 11, R.string.library_expand_all).setEnabled(collapseSupported);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == collapseId) {
                    setAllCollapsed(true);
                }
                else if (id == expandId) {
                    setAllCollapsed(false);
                }
                else if (id >= 0 && id < layouts.length) {
                    switchLayout(layouts[id]);
                }
                return true;
            }
        });
        popup.show();
    }

    // Gamepad

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (handleLibraryKey(event)) {
                consumedKeyDowns.add(keyCode);
                return true;
            }
        }
        else if (event.getAction() == KeyEvent.ACTION_UP && consumedKeyDowns.remove(keyCode)) {
            // Swallow the matching up so no fallback key (such as DEL or MENU) fires
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleLibraryKey(KeyEvent event) {
        // Keys typed into the search field are left alone (X falls back to DEL, Y to SPACE)
        if (getCurrentFocus() == searchField) {
            return false;
        }

        boolean repeat = event.getRepeatCount() > 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_X: {
                if (!repeat && controller != null) {
                    View view = controller.getCurrentAppView();
                    if (view != null) {
                        openContextMenu(view);
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_Y: {
                if (!repeat) {
                    focusSearch();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: {
                if (controller != null) {
                    controller.jumpToAdjacentGroup(event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_START: {
                // Always consumed: its DPAD_CENTER fallback would launch the focused app
                if (!repeat && controller != null && controller.supportsCollapse()) {
                    toggleGroup(controller.getCurrentGroupKey());
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_SELECT: {
                if (!repeat) {
                    showViewMenu();
                }
                return true;
            }
            default:
                return false;
        }
    }

    // LibraryLayoutController.Host

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public AppLibrary getAppLibrary() {
        return appLibrary;
    }

    @Override
    public boolean isSmallIconMode() {
        return prefConfig.smallIconMode;
    }

    @Override
    public LibraryPrefs getLibraryPrefs() {
        return libraryPrefs;
    }

    @Override
    public void onAppClicked(AppObject app, View view) {
        // Only open the context menu if something is running, otherwise start it
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            } else {
                openContextMenu(view);
            }
        } else {
            if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                        null
                );
            } else {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            }
        }
    }

    @Override
    public void onGroupHeaderClicked(String groupKey) {
        toggleGroup(groupKey);
    }

    @Override
    public void onAppFocused(AppObject app) {
        lastFocusKey = LibraryModel.appKey(app);
    }

    @Override
    public void registerContextView(View view) {
        registerForContextMenu(view);
    }

    private void updateHiddenApps() {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appLibrary.updateHiddenApps(hiddenAppIds);
        rebuildSnapshot();
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        searchHandler.removeCallbacks(applySearchRunnable);

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();

        // Persist the library state for the next visit
        if (libraryPrefs != null) {
            searchHandler.removeCallbacks(applySearchRunnable);
            libraryPrefs.setQuery(searchField.getText() != null ? searchField.getText().toString() : "");
            libraryPrefs.setCollapsedGroups(collapsedGroups);
            if (lastFocusKey != null) {
                libraryPrefs.setLastFocus(lastFocusKey);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /** The box art view of a card (or the view itself when it is an image, as in the wheel). */
    private static ImageView findArtView(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        return view.findViewById(R.id.grid_image);
    }

    /** The loaded box art of a card, or null while it is still loading. */
    private static Bitmap getLoadedArt(View view) {
        ImageView appImageView = findArtView(view);
        if (appImageView == null || !(appImageView.getDrawable() instanceof BitmapDrawable)) {
            return null;
        }
        return ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        Object tag = v.getTag(R.id.tag_app_object);
        if (!(tag instanceof AppObject)) {
            return;
        }
        AppObject selectedApp = (AppObject) tag;
        contextApp = selectedApp;
        contextView = v;

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            Bitmap art = getLoadedArt(v);
            if (art != null) {
                menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));

        menu.add(Menu.NONE, CHANGE_GROUP_ID, 7, getResources().getString(R.string.applist_menu_move_to_group));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final AppObject app = contextApp;
        if (app == null) {
            return super.onContextItemSelected(item);
        }
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden; it stays listed (dimmed) until the next visit
                    hiddenAppIds.add(app.app.getAppId());
                    hiddenThisVisit.add(app.app.getAppId());
                }
                updateHiddenApps();
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                Bitmap appBits = getLoadedArt(contextView);
                if (appBits == null || !shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            case CHANGE_GROUP_ID: {
                showMoveToGroupDialog(app);
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private void showMoveToGroupDialog(final AppObject app) {
        final String appKey = LibraryModel.appKey(app);
        final List<String> labels = new ArrayList<>();
        if (snapshot != null) {
            for (LibrarySnapshot.Group<AppObject> group : snapshot.allGroups) {
                labels.add(group.label);
            }
        }

        String override = groupOverrides.get(appKey);
        int checked = 0;
        if (override != null && !LibraryText.clean(override).isEmpty()) {
            checked = -1;
            for (int i = 0; i < labels.size(); i++) {
                if (LibraryText.normalize(labels.get(i)).equals(LibraryText.normalize(override))) {
                    checked = i + 1;
                    break;
                }
            }
        }

        // Automatic, the existing groups, then a new group
        String[] items = new String[labels.size() + 2];
        items[0] = getString(R.string.library_group_automatic);
        for (int i = 0; i < labels.size(); i++) {
            items[i + 1] = labels.get(i);
        }
        items[items.length - 1] = getString(R.string.library_group_new);

        new AlertDialog.Builder(this)
                .setTitle(R.string.library_move_title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            setGroupOverride(appKey, null);
                        }
                        else if (which == items.length - 1) {
                            showNewGroupDialog(appKey);
                        }
                        else {
                            setGroupOverride(appKey, labels.get(which - 1));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showNewGroupDialog(final String appKey) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.library_group_new_hint);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        FrameLayout frame = new FrameLayout(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.library_group_new)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String label = LibraryText.clean(input.getText().toString());
                        if (!label.isEmpty()) {
                            setGroupOverride(appKey, label);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setGroupOverride(String appKey, String label) {
        libraryPrefs.setGroupOverride(appKey, label);
        groupOverrides = libraryPrefs.getGroupOverrides();
        rebuildSnapshot();
        if (controller != null) {
            // Follow the app into its new group
            controller.focusApp(appKey);
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // Look through our current app list to tag the running app
                for (AppObject existingApp : appLibrary.getAllApps()) {
                    if (existingApp.app.getAppId() == details.runningGameId) {
                        if (!existingApp.isRunning) {
                            // This app wasn't running but now is
                            existingApp.isRunning = true;
                            updated = true;
                        }
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                }

                if (updated) {
                    rebuildSnapshot();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    AppObject existingApp = appLibrary.findApp(app.getAppId());

                    if (existingApp != null) {
                        // Found the app; update its properties
                        if (!existingApp.app.getAppName().equals(app.getAppName())) {
                            existingApp.app.setAppName(app.getAppName());
                            updated = true;
                        }
                        if (!existingApp.app.getPlatform().equals(app.getPlatform())) {
                            existingApp.app.setPlatform(app.getPlatform());
                            updated = true;
                        }
                        if (!existingApp.app.getPlatformId().equals(app.getPlatformId())) {
                            existingApp.app.setPlatformId(app.getPlatformId());
                            updated = true;
                        }
                    }
                    else {
                        // This app must be new
                        appLibrary.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                for (AppObject existingApp : new ArrayList<>(appLibrary.getAllApps())) {
                    boolean foundExistingApp = false;

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appLibrary.removeApp(existingApp);
                        updated = true;
                    }
                }

                if (updated || snapshot == null) {
                    rebuildSnapshot();
                }
            }
        });
    }

    public static class AppObject implements LibraryItem {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public int getAppId() {
            return app.getAppId();
        }

        @Override
        public String getAppUuid() {
            return app.getAppUUID() != null ? app.getAppUUID() : "";
        }

        @Override
        public String getAppName() {
            return app.getAppName();
        }

        @Override
        public String getPlatform() {
            return app.getPlatform();
        }

        @Override
        public String getPlatformId() {
            return app.getPlatformId();
        }

        @Override
        public boolean isRunning() {
            return isRunning;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}

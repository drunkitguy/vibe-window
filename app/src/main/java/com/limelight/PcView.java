package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.AutoResolutionAndroid;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SettingsHost;
import com.limelight.preferences.StreamSettings;
import com.limelight.preferences.UiState;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.ui.HostCardState;
import com.limelight.ui.HostGridLayout;
import com.limelight.ui.SplitLayout;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks, SettingsHost {
    private View noPcFoundLayout;
    private AbsListView pcListView;
    // Host selection to restore after returning to this screen, and whether the
    // user moved focus since then (so we never steal focus from them)
    private String lastSelectedUuid;
    private boolean pendingSelectionRestore = true;
    // The first-host fallback selection is applied at most once per visit
    private boolean fallbackSelectionApplied;
    private boolean userNavigated;
    // Controller shortcut whose key down we consumed, so its key up is consumed too
    private int consumedShortcutKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean centeringObserverRegistered;
    // Wide landscape: Settings beside the PC list
    private static final String SETTINGS_PANEL_TAG = "vw_settings_panel";
    private static final long SETTINGS_PANEL_ANIMATION_MS = 180;
    private boolean splitMode;
    private boolean settingsPanelRestoreFocus;
    private ValueAnimator settingsPanelAnimator;
    private int settingsPanelReturnPosition = -1;
    private String settingsPanelLanguage;
    // Display the panel's entries were built for (native resolution and rate entries)
    private int settingsPanelDisplayPixels = -1;
    private int settingsPanelDisplayId = android.view.Display.INVALID_DISPLAY;
    // Set when another screen (a stream) may have changed settings behind the panel
    private boolean settingsPanelStale;
    // Android 13+ sends Back as a callback, not a key: the panel takes it while it has the focus
    private final OnBackPressedCallback settingsPanelBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            StreamSettings.SettingsFragment fragment = settingsPanelFragment();
            if (fragment != null && fragment.dropPendingChoice()) {
                return;
            }
            focusHosts();
        }
    };
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

        refreshProfileButton();
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int PAIR_ID_OTP = 21;

    private void initializeViews() {
        // The panel fragment survives a layout change; remember where its focus was
        boolean panelHadFocus = settingsPanelHasFocus();
        if (panelHadFocus) {
            StreamSettings.SettingsFragment panelFragment = settingsPanelFragment();
            if (panelFragment != null) {
                settingsPanelReturnPosition = panelFragment.focusedRowPosition();
            }
        }
        settingsPanelRestoreFocus = panelHadFocus;
        Configuration config = getResources().getConfiguration();
        splitMode = SplitLayout.useSplit(config.orientation, config.screenWidthDp);
        setContentView(splitMode ? R.layout.activity_pc_view_split : R.layout.activity_pc_view);
        // The grid is attached again by AdapterFragment
        pcListView = null;

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        // Move installs still on old defaults to the current ones, once (base preferences only)
        PreferenceConfiguration.runOneTimeMigrations(this);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        View settingsButton = findViewById(R.id.settingsButton);
        View addComputerButton = findViewById(R.id.manuallyAddPc);
        View helpButton = findViewById(R.id.helpButton);
        View noPcAddButton = findViewById(R.id.noPcAddButton);
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (splitMode) {
                    // Beside the PC list the button shows or hides the settings panel
                    setSettingsPanelOpen(!UiState.settingsPanelOpen(PcView.this), true);
                }
                else {
                    startActivity(new Intent(PcView.this, StreamSettings.class));
                }
            }
        });
        OnClickListener addComputerListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        };
        addComputerButton.setOnClickListener(addComputerListener);
        if (noPcAddButton != null) {
            noPcAddButton.setOnClickListener(addComputerListener);
        }
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });
        profilesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, ProfilesActivity.class));
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        setupSettingsPanel();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
            // Give controller users something to focus while searching
            if (noPcAddButton != null) {
                noPcAddButton.requestFocus();
            }
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pendingSelectionRestore = true;
        fallbackSelectionApplied = false;
        userNavigated = false;
        updateHeader();
        pcGridAdapter.notifyDataSetChanged();
    }

    private void updateHeader() {
        TextView subtitle = findViewById(R.id.pcHeaderSubtitle);
        if (subtitle == null || pcGridAdapter == null) {
            return;
        }

        int total = pcGridAdapter.getCount();
        int online = 0;
        for (int i = 0; i < total; i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);
            if (computer.details.state == ComputerDetails.State.ONLINE) {
                online++;
            }
        }

        if (total == 0) {
            subtitle.setText(R.string.pcview_header_searching);
        }
        else {
            subtitle.setText(getString(R.string.pcview_header_counts, total, online));
        }
    }

    // Select the host the user had selected before, else the first online paired host,
    // else the first host until an online paired one shows up. Never overrides a
    // selection the user made.
    private void applyPendingSelection() {
        if (!pendingSelectionRestore || pcListView == null || pcGridAdapter == null) {
            return;
        }
        if (userNavigated) {
            pendingSelectionRestore = false;
            return;
        }

        int target = -1;
        int firstOnlinePaired = -1;
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerDetails details = ((ComputerObject) pcGridAdapter.getItem(i)).details;
            if (lastSelectedUuid != null && lastSelectedUuid.equals(details.uuid)) {
                target = i;
                break;
            }
            if (firstOnlinePaired < 0 && details.state == ComputerDetails.State.ONLINE &&
                    details.pairState == PairState.PAIRED) {
                firstOnlinePaired = i;
            }
        }
        if (target < 0) {
            target = firstOnlinePaired;
        }
        if (target >= 0) {
            pendingSelectionRestore = false;
        }
        else if (pcGridAdapter.getCount() > 0 && !fallbackSelectionApplied && !pcListView.isInTouchMode()) {
            // Nothing online and paired yet: start a controller user on the first host, once,
            // and keep looking for an online paired host
            fallbackSelectionApplied = true;
            target = 0;
        }
        else {
            return;
        }

        pcListView.setSelection(target);
        if (!pcListView.hasFocus()) {
            pcListView.requestFocus();
        }
    }

    private void rememberSelectedHost() {
        if (pcListView == null || pcGridAdapter == null) {
            return;
        }
        int pos = pcListView.getSelectedItemPosition();
        if (pos != AdapterView.INVALID_POSITION && pos < pcGridAdapter.getCount()) {
            lastSelectedUuid = ((ComputerObject) pcGridAdapter.getItem(pos)).details.uuid;
        }
    }

    // Center one to a few host cards; with more hosts the grid fills the width
    private void updateGridCentering() {
        if (!(pcListView instanceof GridView) || pcGridAdapter == null) {
            return;
        }
        GridView grid = (GridView) pcListView;
        int width = grid.getWidth();
        if (width <= 0) {
            return;
        }

        Resources res = getResources();
        HostGridLayout layout = HostGridLayout.compute(width,
                res.getDimensionPixelSize(R.dimen.vw_host_column_min_width),
                res.getDimensionPixelSize(R.dimen.vw_host_column_width),
                res.getDimensionPixelSize(R.dimen.vw_host_grid_spacing),
                res.getDimensionPixelSize(R.dimen.vw_host_grid_padding),
                pcGridAdapter.getCount());

        if (grid.getColumnWidth() != layout.columnWidth) {
            grid.setColumnWidth(layout.columnWidth);
        }
        if (grid.getNumColumns() != layout.columns) {
            grid.setNumColumns(layout.columns);
        }
        if (grid.getPaddingLeft() != layout.sidePadding || grid.getPaddingRight() != layout.sidePadding) {
            grid.setPadding(layout.sidePadding, grid.getPaddingTop(), layout.sidePadding, grid.getPaddingBottom());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Touching or scrolling the list is navigation too: never move the selection after it
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            userNavigated = true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // The key up of a consumed shortcut may go to another window (a menu or dialog)
            consumedShortcutKey = KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            userNavigated = true;
        }

        if (splitMode && completeOnCreateCalled) {
            Boolean handled = dispatchSplitKeyEvent(event);
            // Keys move the focus: keep the Back callback in step with it
            updateSettingsPanelBack();
            if (handled != null) {
                return handled;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0 && handleControllerShortcut(keyCode)) {
                    consumedShortcutKey = keyCode;
                    return true;
                }
                if (consumedShortcutKey == keyCode) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP && consumedShortcutKey == keyCode) {
                consumedShortcutKey = KeyEvent.KEYCODE_UNKNOWN;
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean handleControllerShortcut(int keyCode) {
        if (!completeOnCreateCalled) {
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_X:
                // Context menu of the selected host card
                if (pcListView == null || !pcListView.hasFocus()) {
                    return false;
                }
                View selected = pcListView.getSelectedView();
                if (selected == null) {
                    return false;
                }
                openContextMenu(selected);
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                startActivity(new Intent(PcView.this, ProfilesActivity.class));
                return true;

            case KeyEvent.KEYCODE_BUTTON_START:
                if (splitMode) {
                    // Start moves between the PC list and the settings panel
                    if (settingsPanelHasFocus()) {
                        focusHosts();
                    }
                    else {
                        if (!UiState.settingsPanelOpen(this)) {
                            setSettingsPanelOpen(true, true);
                        }
                        focusSettingsPanel();
                    }
                    return true;
                }
                startActivity(new Intent(PcView.this, StreamSettings.class));
                return true;

            default:
                return false;
        }
    }

    private StreamSettings.SettingsFragment settingsPanelFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(SETTINGS_PANEL_TAG);
        return fragment instanceof StreamSettings.SettingsFragment ? (StreamSettings.SettingsFragment) fragment : null;
    }

    /**
     * Adds the settings panel in split mode, removes it otherwise. After a layout change the
     * same fragment moves into the new layout; it is rebuilt only for another display.
     */
    private void setupSettingsPanel() {
        Fragment existing = getSupportFragmentManager().findFragmentByTag(SETTINGS_PANEL_TAG);
        View panel = findViewById(R.id.settings_panel);
        if (!splitMode || panel == null) {
            if (existing != null) {
                getSupportFragmentManager().beginTransaction().remove(existing).commitAllowingStateLoss();
            }
            return;
        }

        Configuration config = getResources().getConfiguration();
        ViewGroup.LayoutParams params = panel.getLayoutParams();
        params.width = Math.round(SplitLayout.panelWidthDp(config.screenWidthDp) *
                getResources().getDisplayMetrics().density);
        panel.setLayoutParams(params);
        panel.setVisibility(UiState.settingsPanelOpen(this) ? View.VISIBLE : View.GONE);

        com.google.android.material.button.MaterialButton collapse = findViewById(R.id.settingsBackButton);
        if (collapse != null) {
            collapse.setIconResource(R.drawable.ic_side_panel);
            collapse.setContentDescription(getString(R.string.settings_collapse));
            collapse.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setSettingsPanelOpen(false, true);
                }
            });
        }
        View more = findViewById(R.id.settingsMoreButton);
        if (more != null) {
            more.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    StreamSettings.SettingsFragment fragment = settingsPanelFragment();
                    if (fragment != null && fragment.getView() != null) {
                        fragment.showMoreMenu(v);
                    }
                }
            });
        }

        if (existing instanceof StreamSettings.SettingsFragment && !settingsPanelDisplayChanged()) {
            // Same display: keep the fragment (its rows and expanded sections) and only move
            // its view into the new layout
            // Two transactions: detach and attach in one are folded into nothing
            getSupportFragmentManager().beginTransaction().detach(existing).commitNowAllowingStateLoss();
            getSupportFragmentManager().beginTransaction().attach(existing).commitNowAllowingStateLoss();
        }
        else {
            attachSettingsPanelFragment();
        }
        if (settingsPanelRestoreFocus) {
            settingsPanelRestoreFocus = false;
            panel.post(new Runnable() {
                @Override
                public void run() {
                    focusSettingsPanel();
                }
            });
        }
    }

    /** The display's physical pixel count, as the settings screen compares it. */
    private int currentDisplayPixels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.view.Display.Mode mode = AutoResolutionAndroid.getActivityDisplay(this).getMode();
            return mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
        return -1;
    }

    /** Whether the panel's entries were built for another display (as StreamSettings checks). */
    private boolean settingsPanelDisplayChanged() {
        return currentDisplayPixels() != settingsPanelDisplayPixels ||
                AutoResolutionAndroid.getActivityDisplay(this).getDisplayId() != settingsPanelDisplayId;
    }

    /** Rebuilds the panel from the stored settings, keeping the focus where it was. */
    private void refreshSettingsPanel() {
        if (!splitMode || findViewById(R.id.settings_panel_list) == null) {
            return;
        }
        final boolean hadFocus = settingsPanelHasFocus();
        StreamSettings.SettingsFragment fragment = settingsPanelFragment();
        if (hadFocus && fragment != null) {
            settingsPanelReturnPosition = fragment.focusedRowPosition();
        }
        attachSettingsPanelFragment();
        if (hadFocus) {
            View panel = findViewById(R.id.settings_panel);
            panel.post(new Runnable() {
                @Override
                public void run() {
                    focusSettingsPanel();
                }
            });
        }
    }

    private void attachSettingsPanelFragment() {
        settingsPanelDisplayPixels = currentDisplayPixels();
        settingsPanelDisplayId = AutoResolutionAndroid.getActivityDisplay(this).getDisplayId();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        settingsPanelLanguage = prefs.language;
        StreamSettings.SettingsFragment fragment = new StreamSettings.SettingsFragment(prefs);
        Bundle args = new Bundle();
        args.putBoolean(StreamSettings.SettingsFragment.ARG_EMBEDDED, true);
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_panel_list, fragment, SETTINGS_PANEL_TAG)
                .commitAllowingStateLoss();
    }

    /** Opens or collapses the settings panel; the choice is kept for the next visit. */
    void setSettingsPanelOpen(final boolean open, boolean animate) {
        UiState.setSettingsPanelOpen(this, open);
        final View panel = findViewById(R.id.settings_panel);
        if (!splitMode || panel == null) {
            return;
        }
        if (!open && settingsPanelHasFocus()) {
            focusHosts();
        }
        if (settingsPanelAnimator != null) {
            settingsPanelAnimator.cancel();
            settingsPanelAnimator = null;
        }
        final int fullWidth = Math.round(SplitLayout.panelWidthDp(getResources().getConfiguration().screenWidthDp) *
                getResources().getDisplayMetrics().density);
        if (!animate) {
            panel.getLayoutParams().width = fullWidth;
            panel.setVisibility(open ? View.VISIBLE : View.GONE);
            panel.requestLayout();
            return;
        }
        int from = panel.getVisibility() == View.VISIBLE ? Math.max(0, panel.getWidth()) : 0;
        panel.setVisibility(View.VISIBLE);
        ValueAnimator animator = ValueAnimator.ofInt(from, open ? fullWidth : 0);
        animator.setDuration(SETTINGS_PANEL_ANIMATION_MS);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                panel.getLayoutParams().width = (Integer) animation.getAnimatedValue();
                panel.requestLayout();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) {
                    return;
                }
                panel.getLayoutParams().width = fullWidth;
                panel.setVisibility(open ? View.VISIBLE : View.GONE);
                panel.requestLayout();
                updateGridCentering();
            }
        });
        settingsPanelAnimator = animator;
        animator.start();
    }

    boolean settingsPanelHasFocus() {
        View panel = findViewById(R.id.settings_panel);
        return panel != null && panel.hasFocus();
    }

    private void updateSettingsPanelBack() {
        settingsPanelBack.setEnabled(splitMode && settingsPanelHasFocus());
    }

    private boolean settingsListHasFocus() {
        View list = findViewById(R.id.settings_panel_list);
        return list != null && list.hasFocus();
    }

    private void focusSettingsPanel() {
        final StreamSettings.SettingsFragment fragment = settingsPanelFragment();
        if (fragment == null || fragment.getView() == null) {
            return;
        }
        final int position = settingsPanelReturnPosition >= 0 ? settingsPanelReturnPosition : 0;
        boolean focused = fragment.focusRow(position);
        updateSettingsPanelBack();
        if (!focused) {
            // The list may not be laid out yet (the panel was hidden): try once more after it is
            fragment.getView().post(new Runnable() {
                @Override
                public void run() {
                    if (!settingsPanelHasFocus() && fragment.getView() != null) {
                        if (!fragment.focusRow(position) && fragment.getListView() != null) {
                            fragment.getListView().requestFocus();
                        }
                        updateSettingsPanelBack();
                    }
                }
            });
        }
    }

    private void focusHosts() {
        settingsPanelBack.setEnabled(false);
        StreamSettings.SettingsFragment fragment = settingsPanelFragment();
        if (fragment != null && settingsPanelHasFocus()) {
            settingsPanelReturnPosition = fragment.focusedRowPosition();
        }
        if (pcListView != null && pcListView.getVisibility() == View.VISIBLE && pcGridAdapter != null &&
                pcGridAdapter.getCount() > 0) {
            pcListView.requestFocus();
        }
        else {
            View add = findViewById(R.id.noPcAddButton);
            if (add != null && add.isShown()) {
                add.requestFocus();
            }
            else {
                View settings = findViewById(R.id.settingsButton);
                if (settings != null) {
                    settings.requestFocus();
                }
            }
        }
    }

    /**
     * Key rules while Settings sits beside the PC list. Returns null to fall through to the
     * normal handling, else whether the key was consumed.
     */
    private Boolean dispatchSplitKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (settingsPanelHasFocus()) {
            StreamSettings.SettingsFragment fragment = settingsPanelFragment();
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_L1:
                case KeyEvent.KEYCODE_BUTTON_R1:
                    if (down && fragment != null) {
                        fragment.jumpToCategory(keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT:
                    View more = findViewById(R.id.settingsMoreButton);
                    if (!down && fragment != null && more != null) {
                        fragment.showMoreMenu(more);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                case KeyEvent.KEYCODE_BUTTON_Y:
                    // No host shortcut in the panel: Y opens the focused row's info (the row
                    // handles it), never Profiles, and X does nothing
                    getWindow().superDispatchKeyEvent(event);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!settingsListHasFocus()) {
                        // Header buttons move as usual
                        return null;
                    }
                    // Segments and sliders take Right; other rows go back to the PC list
                    if (getWindow().superDispatchKeyEvent(event)) {
                        return true;
                    }
                    if (down) {
                        focusHosts();
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK:
                    // A segment highlight takes B first; otherwise B goes back to the PC list
                    if (getWindow().superDispatchKeyEvent(event)) {
                        return true;
                    }
                    if (!down) {
                        focusHosts();
                    }
                    return true;
                default:
                    return null;
            }
        }
        // Left from the first column of the PC list enters the open panel
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && UiState.settingsPanelOpen(this) &&
                pcListView instanceof GridView && pcListView.hasFocus()) {
            GridView grid = (GridView) pcListView;
            int selected = grid.getSelectedItemPosition();
            int columns = Math.max(1, grid.getNumColumns());
            if (selected == AdapterView.INVALID_POSITION || selected % columns == 0) {
                if (down) {
                    focusSettingsPanel();
                }
                return true;
            }
        }
        return null;
    }

    @Override
    public void reloadSettings() {
        if (splitMode && findViewById(R.id.settings_panel_list) != null) {
            attachSettingsPanelFragment();
        }
    }

    @Override
    public void onSettingsLanguageChanged() {
        PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
        if (settingsPanelLanguage == null || newPrefs.language.equals(settingsPanelLanguage)) {
            return;
        }
        // Same handling as closing the settings screen after a language change
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Intent intent = new Intent(this, PcView.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent, null);
        }
        else if (newPrefs.language == PreferenceConfiguration.DEFAULT_LANGUAGE) {
            Toast.makeText(this, "Language has been reset to default, please restart the app!", Toast.LENGTH_LONG).show();
            System.exit(0);
        }
        settingsPanelLanguage = newPrefs.language;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        StreamSettings.rememberDisplayCutout(this);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        updateSettingsPanelBack();
                    }
                });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A settings panel restored after process death has no container until the views
        // exist; drop it, setupSettingsPanel() adds a fresh one
        // The support fragment manager only holds the panel and the dialogs it opened (the
        // list dialog is tagged androidx.preference.PreferenceFragment.DIALOG); a restored
        // dialog without its panel would be orphaned, so every restored one goes
        if (!getSupportFragmentManager().getFragments().isEmpty()) {
            androidx.fragment.app.FragmentTransaction drop = getSupportFragmentManager().beginTransaction();
            for (Fragment restored : getSupportFragmentManager().getFragments()) {
                drop.remove(restored);
            }
            drop.commitNow();
        }
        getOnBackPressedDispatcher().addCallback(this, settingsPanelBack);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    private void refreshProfileButton() {
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
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        pendingSelectionRestore = true;
        fallbackSelectionApplied = false;
        userNavigated = false;

        inForeground = true;
        startComputerUpdates();

        // A stream (or the settings screen) may have changed settings behind the panel
        if (settingsPanelStale) {
            settingsPanelStale = false;
            refreshSettingsPanel();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        rememberSelectedHost();
        consumedShortcutKey = KeyEvent.KEYCODE_UNKNOWN;

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        settingsPanelStale = true;

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID_OTP, 1, getResources().getString(R.string.pcview_menu_pair_pc_otp));
            menu.add(Menu.NONE, PAIR_ID, 2, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                            getResources().getString(R.string.pair_pairing_help), false);
                        } else {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_otp_pairing_msg)+"\n\n"+
                                            getResources().getString(R.string.pair_otp_pairing_help), false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doOTPPair(final ComputerDetails computer) {
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details, null, null);
                return true;

            case PAIR_ID_OTP:
                doOTPPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false), computer.details, managerBinder, false);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", null, 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case OPEN_MANAGEMENT_PAGE_ID:
                String managementUrl = computer.guessManagementUrl();
                if (managementUrl == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                } else {
                    HelpLauncher.launchUrl(PcView.this, managementUrl);
                }

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                updateHeader();
                break;
            }
        }
    }

    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        boolean focusGrid = false;
        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view, moving controller focus to the grid
            focusGrid = noPcFoundLayout.hasFocus();
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();

        updateHeader();
        if (focusGrid && pcListView != null) {
            pcListView.requestFocus();
        }
        applyPendingSelection();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        pcListView = listView;
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                // The card's action pill names what happens here (HostCardState)
                HostCardState card = HostCardState.from(computer.details.state, computer.details.pairState,
                        computer.details.runningGameId, computer.details.macAddress != null);
                switch (card.action) {
                    case WAKE:
                        // Offline with a MAC address: wake it; X or a long press opens the menu
                        doWakeOnLan(computer.details);
                        break;
                    case CONTEXT_MENU:
                        // Offline without a MAC address, or refreshing
                        openContextMenu(arg1);
                        break;
                    case PAIR:
                        // Pair an unpaired machine by default
                        doPair(computer.details, null, null);
                        break;
                    default:
                        doAppList(computer.details, false, false);
                        break;
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);

        // Keep the cards centered when the size or the number of hosts changes
        listView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft) {
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            updateGridCentering();
                        }
                    });
                }
            }
        });
        if (!centeringObserverRegistered) {
            centeringObserverRegistered = true;
            pcGridAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    updateGridCentering();
                }
            });
        }
        updateGridCentering();
        applyPendingSelection();
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}

package com.limelight.library;

import android.content.Context;
import android.graphics.BitmapFactory;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The apps of one host and the box art loader that serves them. Only touched
 * from the UI thread once the library is shown.
 */
public class AppLibrary {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 110;
    private static final int LARGE_WIDTH_DP = 170;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private final Set<Integer> hiddenAppIds = new HashSet<>();
    private final ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    public AppLibrary(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    public static int getCardWidthDp(PreferenceConfiguration prefs) {
        return prefs.smallIconMode ? SMALL_WIDTH_DP : LARGE_WIDTH_DP;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp = getCardWidthDp(prefs);

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));
    }

    public CachedAppAssetLoader getLoader() {
        return loader;
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    public boolean isShowHiddenApps() {
        return showHiddenApps;
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds) {
        hiddenAppIds.clear();
        hiddenAppIds.addAll(newHiddenAppIds);

        for (AppView.AppObject app : allApps) {
            app.isHidden = hiddenAppIds.contains(app.app.getAppId());
        }
    }

    public Set<Integer> getHiddenAppIds() {
        return Collections.unmodifiableSet(hiddenAppIds);
    }

    /** The live list of apps, in host order. */
    public List<AppView.AppObject> getAllApps() {
        return Collections.unmodifiableList(allApps);
    }

    public AppView.AppObject findApp(int appId) {
        for (AppView.AppObject app : allApps) {
            if (app.app.getAppId() == appId) {
                return app;
            }
        }
        return null;
    }

    public void addApp(AppView.AppObject app) {
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());
        allApps.add(app);

        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);
        }
    }

    public void removeApp(AppView.AppObject app) {
        allApps.remove(app);
    }
}

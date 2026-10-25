package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings screen state that is not a setting: kept out of the default preferences, so it
 * never reaches profiles or the stream configuration.
 */
public final class UiState {
    public static final String FILE = "vw_ui_state";
    public static final String SHOW_DESCRIPTIONS = "show_descriptions";
    public static final String SETTINGS_PANEL_OPEN = "settings_panel_open";

    private UiState() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean showDescriptions(Context context) {
        return prefs(context).getBoolean(SHOW_DESCRIPTIONS, false);
    }

    public static void setShowDescriptions(Context context, boolean show) {
        prefs(context).edit().putBoolean(SHOW_DESCRIPTIONS, show).apply();
    }

    public static boolean settingsPanelOpen(Context context) {
        return prefs(context).getBoolean(SETTINGS_PANEL_OPEN, true);
    }

    public static void setSettingsPanelOpen(Context context, boolean open) {
        prefs(context).edit().putBoolean(SETTINGS_PANEL_OPEN, open).apply();
    }
}

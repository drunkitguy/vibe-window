package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;

/**
 * Statistics overlay as one Off / Lite / Full row. It stores nothing itself: it reads and
 * writes the two existing switches (checkbox_enable_perf_overlay and
 * checkbox_enable_perf_overlay_lite), whose rows stay in the Performance overlay section,
 * hidden. Off keeps the stored lite choice, so Lite or Full come back as they were.
 */
public class StatsOverlayPreference extends SegmentedListPreference {
    public static final String KEY = "quick_stats_overlay";
    static final String OVERLAY_KEY = "checkbox_enable_perf_overlay";
    static final String LITE_KEY = "checkbox_enable_perf_overlay_lite";

    public static final String OFF = "off";
    public static final String LITE = "lite";
    public static final String FULL = "full";

    public StatsOverlayPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    public StatsOverlayPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPersistent(false);
    }

    /** The composite value for the two switches. */
    static String valueFor(boolean overlay, boolean lite) {
        if (!overlay) {
            return OFF;
        }
        return lite ? LITE : FULL;
    }

    @Nullable
    private CheckBoxPreference backing(String key) {
        Preference preference = findPreferenceInHierarchy(key);
        return preference instanceof CheckBoxPreference ? (CheckBoxPreference) preference : null;
    }

    private boolean stored(String key) {
        CheckBoxPreference preference = backing(key);
        if (preference != null) {
            return preference.isChecked();
        }
        // Rows not attached yet: read the active store (base settings or the profile)
        PreferenceDataStore store = getPreferenceDataStore();
        if (store != null) {
            return store.getBoolean(key, false);
        }
        return getSharedPreferences() != null && getSharedPreferences().getBoolean(key, false);
    }

    @Override
    public String getValue() {
        if (getPreferenceManager() == null) {
            return OFF;
        }
        return valueFor(stored(OVERLAY_KEY), stored(LITE_KEY));
    }

    @Override
    public void setValue(String value) {
        // ListPreference sets the (absent) stored value while attaching; nothing to apply then
        if (value == null || getPreferenceManager() == null) {
            return;
        }
        CheckBoxPreference overlay = backing(OVERLAY_KEY);
        CheckBoxPreference lite = backing(LITE_KEY);
        if (overlay == null || lite == null) {
            return;
        }
        if (OFF.equals(value)) {
            overlay.setChecked(false);
        }
        else if (LITE.equals(value) || FULL.equals(value)) {
            // The overlay first, so the lite switch is enabled when it changes
            overlay.setChecked(true);
            lite.setChecked(LITE.equals(value));
        }
        notifyChanged();
    }

    @Override
    public CharSequence getEntry() {
        int index = findIndexOfValue(getValue());
        CharSequence[] entries = getEntries();
        return index >= 0 && entries != null ? entries[index] : null;
    }
}

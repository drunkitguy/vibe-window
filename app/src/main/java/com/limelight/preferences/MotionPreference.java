package com.limelight.preferences;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;

/**
 * Motion as one Off / On row. It stores nothing itself: it reads and writes the existing
 * switches checkbox_gamepad_motion_sensors and checkbox_gamepad_motion_fallback (both in
 * All settings, Gamepad Settings). On turns both on; Off turns only the fallback off, so a
 * controller with its own motion sensors keeps them. "Always use device's motion sensors"
 * is never changed here.
 */
public class MotionPreference extends SegmentedListPreference {
    public static final String KEY = "quick_motion";
    static final String SENSORS_KEY = "checkbox_gamepad_motion_sensors";
    static final String FALLBACK_KEY = "checkbox_gamepad_motion_fallback";

    public static final String OFF = "off";
    public static final String ON = "on";

    public MotionPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    public MotionPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPersistent(false);
    }

    /**
     * Android 12 keeps controller sensors off (a platform crash, see readPreferences), so
     * there the row only follows and sets the fallback.
     */
    static boolean controllerSensorsLocked(int sdk) {
        return sdk == Build.VERSION_CODES.S;
    }

    /** The row's value for the two switches. */
    static String valueFor(boolean sensors, boolean fallback, int sdk) {
        return fallback && (sensors || controllerSensorsLocked(sdk)) ? ON : OFF;
    }

    @Nullable
    private CheckBoxPreference backing(String key) {
        Preference preference = findPreferenceInHierarchy(key);
        return preference instanceof CheckBoxPreference ? (CheckBoxPreference) preference : null;
    }

    private boolean stored(String key, boolean defaultValue) {
        CheckBoxPreference preference = backing(key);
        if (preference != null) {
            return preference.isChecked();
        }
        // The row may be removed on this device: read the active store (base or profile)
        PreferenceDataStore store = getPreferenceDataStore();
        if (store != null) {
            return store.getBoolean(key, defaultValue);
        }
        return getSharedPreferences() != null && getSharedPreferences().getBoolean(key, defaultValue);
    }

    private void write(String key, boolean value) {
        CheckBoxPreference preference = backing(key);
        if (preference != null) {
            preference.setChecked(value);
            return;
        }
        PreferenceDataStore store = getPreferenceDataStore();
        if (store != null) {
            store.putBoolean(key, value);
        }
        else if (getSharedPreferences() != null) {
            getSharedPreferences().edit().putBoolean(key, value).apply();
        }
    }

    @Override
    public String getValue() {
        if (getPreferenceManager() == null) {
            return OFF;
        }
        return valueFor(stored(SENSORS_KEY, true), stored(FALLBACK_KEY, true), Build.VERSION.SDK_INT);
    }

    @Override
    public void setValue(String value) {
        // ListPreference sets the (absent) stored value while attaching; nothing to apply then
        if (value == null || getPreferenceManager() == null) {
            return;
        }
        if (ON.equals(value)) {
            if (!controllerSensorsLocked(Build.VERSION.SDK_INT)) {
                write(SENSORS_KEY, true);
            }
            write(FALLBACK_KEY, true);
        }
        else if (OFF.equals(value)) {
            // A controller's own sensors keep working
            write(FALLBACK_KEY, false);
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

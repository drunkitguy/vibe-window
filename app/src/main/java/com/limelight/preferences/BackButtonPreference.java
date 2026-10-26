package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;

/**
 * The Back button row in Touch and controls. It stores nothing itself: it reads and writes
 * list_back_button_mode, the Back button setting in All settings, Gamepad Settings.
 */
public class BackButtonPreference extends SegmentedListPreference {
    public static final String KEY = "quick_back_button";
    static final String BACKING_KEY = PreferenceConfiguration.LIST_BACK_BUTTON_MODE;

    public BackButtonPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    public BackButtonPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPersistent(false);
    }

    @Nullable
    private ListPreference backing() {
        Preference preference = findPreferenceInHierarchy(BACKING_KEY);
        return preference instanceof ListPreference ? (ListPreference) preference : null;
    }

    @Override
    public String getValue() {
        if (getPreferenceManager() == null) {
            return PreferenceConfiguration.DEFAULT_BACK_BUTTON_MODE;
        }
        String value = null;
        ListPreference preference = backing();
        if (preference != null) {
            value = preference.getValue();
        }
        else {
            // The row may be removed on this device: read the active store (base or profile)
            PreferenceDataStore store = getPreferenceDataStore();
            if (store != null) {
                value = store.getString(BACKING_KEY, null);
            }
            else if (getSharedPreferences() != null) {
                value = getSharedPreferences().getString(BACKING_KEY, null);
            }
        }
        // An unknown value works as the default (see PreferenceConfiguration.backButtonModeFor)
        return value != null && findIndexOfValue(value) >= 0 ? value : PreferenceConfiguration.DEFAULT_BACK_BUTTON_MODE;
    }

    @Override
    public void setValue(String value) {
        // ListPreference sets the (absent) stored value while attaching; nothing to apply then
        if (value == null || getPreferenceManager() == null || findIndexOfValue(value) < 0) {
            return;
        }
        ListPreference preference = backing();
        if (preference != null) {
            preference.setValue(value);
        }
        else {
            PreferenceDataStore store = getPreferenceDataStore();
            if (store != null) {
                store.putString(BACKING_KEY, value);
            }
            else if (getSharedPreferences() != null) {
                getSharedPreferences().edit().putString(BACKING_KEY, value).apply();
            }
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

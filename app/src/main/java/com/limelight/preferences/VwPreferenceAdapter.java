package com.limelight.preferences;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;

/**
 * The settings list adapter: binds every row as androidx does, then applies the shared
 * row binding (value on the title line, info button, row keys).
 */
class VwPreferenceAdapter extends PreferenceGroupAdapter {
    private final Context context;

    VwPreferenceAdapter(@NonNull PreferenceGroup group, @NonNull Context context) {
        super(group);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        Preference preference = getItem(position);
        if (preference != null) {
            PrefRowBinder.bind(holder, preference, UiState.showDescriptions(context));
        }
    }
}

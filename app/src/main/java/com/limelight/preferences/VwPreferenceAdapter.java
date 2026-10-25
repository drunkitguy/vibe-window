package com.limelight.preferences;

import android.content.Context;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

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
        if (preference == null) {
            return;
        }
        if (preference.getClass().getSimpleName().equals("ExpandButton")) {
            bindExpandButton(holder, position);
            return;
        }
        PrefRowBinder.bind(holder, preference, UiState.showDescriptions(context));
    }

    /** The collapsed section's row reads "Show N settings". */
    private void bindExpandButton(PreferenceViewHolder holder, int position) {
        PreferenceGroup group = null;
        for (int i = position - 1; i >= 0; i--) {
            Preference item = getItem(i);
            if (item instanceof PreferenceGroup) {
                group = (PreferenceGroup) item;
                break;
            }
        }
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (group == null || title == null) {
            return;
        }
        int hidden = hiddenCount(group);
        if (hidden > 0) {
            title.setText(context.getResources().getQuantityString(R.plurals.expand_settings_count, hidden, hidden));
        }
    }

    /** Visible rows of a collapsed group beyond the rows it shows before "Show more". */
    static int hiddenCount(PreferenceGroup group) {
        int visible = 0;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i).isVisible()) {
                visible++;
            }
        }
        int shown = group.getInitialExpandedChildrenCount();
        return shown == Integer.MAX_VALUE ? 0 : Math.max(0, visible - shown);
    }
}

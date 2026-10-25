package com.limelight.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limelight.R;

/**
 * Shared binding for every settings row: the value on the title line, the info button in
 * place of the summary (unless descriptions are shown), and the row key handling.
 */
final class PrefRowBinder {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PrefRowBinder() {}

    /** A row that shows its own value text on the title line. */
    interface ValueText {
        /** The value for the title line, or null for none. */
        @Nullable
        CharSequence getRowValueText();
    }

    /** A row that uses the D-pad itself (segments, sliders). */
    interface RowKeyHandler {
        /** Called for keys on the focused row; true consumes the key. */
        boolean onRowKey(View row, int keyCode, KeyEvent event);

        /** The row lost the focus: commit or drop what is pending. */
        void onRowFocusLost();
    }

    /** Rows that hold a value; everything else (actions, links) keeps its summary. */
    static boolean isValueRow(Preference preference) {
        return preference instanceof TwoStatePreference || preference instanceof ListPreference ||
                preference instanceof SeekBarPreference || preference instanceof EditTextPreference ||
                preference instanceof ValueText;
    }

    static boolean hasInfo(Preference preference) {
        return isValueRow(preference) && !TextUtils.isEmpty(preference.getSummary());
    }

    /** List rows that open the full list on A or a tap and show no segments. */
    static boolean isDropdown(Preference preference) {
        if (preference instanceof SegmentedListPreference) {
            return ((SegmentedListPreference) preference).isDropdownMode();
        }
        return preference instanceof ListPreference;
    }

    /** The value shown on the title line and in the info dialog, or null. */
    @Nullable
    static CharSequence valueText(Preference preference) {
        if (preference instanceof ValueText) {
            return ((ValueText) preference).getRowValueText();
        }
        if (preference instanceof ListPreference) {
            return ((ListPreference) preference).getEntry();
        }
        if (preference instanceof EditTextPreference) {
            return ((EditTextPreference) preference).getText();
        }
        return null;
    }

    static void bind(PreferenceViewHolder holder, final Preference preference, boolean showDescriptions) {
        View info = holder.itemView.findViewById(R.id.vw_pref_info);
        if (info == null) {
            // Category headers and the expand row
            return;
        }
        View summary = holder.findViewById(android.R.id.summary);
        boolean valueRow = isValueRow(preference);
        boolean hasSummary = !TextUtils.isEmpty(preference.getSummary());

        if (summary != null) {
            summary.setVisibility(hasSummary && (!valueRow || showDescriptions) ? View.VISIBLE : View.GONE);
        }
        boolean showInfo = valueRow && hasSummary && !showDescriptions;
        info.setVisibility(showInfo ? View.VISIBLE : View.GONE);
        // The info stays readable on rows that a dependency disabled
        info.setEnabled(true);
        info.setOnClickListener(showInfo ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInfo(v.getContext(), preference);
            }
        } : null);
        info.setClickable(showInfo);
        info.setFocusable(false);

        View chevron = holder.itemView.findViewById(R.id.vw_pref_chevron);
        if (chevron != null) {
            chevron.setVisibility(isDropdown(preference) ? View.VISIBLE : View.GONE);
        }

        TextView value = holder.itemView.findViewById(R.id.vw_pref_value);
        if (value != null) {
            CharSequence text = valueText(preference);
            value.setText(text);
            value.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }

        holder.itemView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return onRowKey(v, preference, keyCode, event);
            }
        });
        holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && preference instanceof RowKeyHandler) {
                    // The focus also leaves a row when the list recycles it during a layout or
                    // a scroll, where notifyChanged() must not run: settle the row right after
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            ((RowKeyHandler) preference).onRowFocusLost();
                        }
                    });
                }
            }
        });
    }

    static boolean onRowKey(View row, Preference preference, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (!hasInfo(preference)) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                showInfo(row.getContext(), preference);
            }
            return true;
        }
        if (preference instanceof RowKeyHandler && preference.isEnabled()) {
            return ((RowKeyHandler) preference).onRowKey(row, keyCode, event);
        }
        return false;
    }

    static String infoMessage(Context context, Preference preference) {
        StringBuilder message = new StringBuilder();
        if (!TextUtils.isEmpty(preference.getSummary())) {
            message.append(preference.getSummary());
        }
        if (preference instanceof ListPreference) {
            CharSequence entry = ((ListPreference) preference).getEntry();
            if (!TextUtils.isEmpty(entry)) {
                if (message.length() > 0) {
                    message.append("\n\n");
                }
                message.append(context.getString(R.string.pref_info_current, entry));
            }
        }
        return message.toString();
    }

    static void showInfo(Context context, Preference preference) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(preference.getTitle())
                .setMessage(infoMessage(context, preference))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}

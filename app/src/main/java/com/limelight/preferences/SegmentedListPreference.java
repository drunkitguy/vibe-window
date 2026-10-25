package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A list preference shown as a row of segments. Touching a segment commits it; with a
 * controller, Left and Right only move a highlight and A commits it, so passing through
 * never fires the change listeners. "More" and A without a highlight open the full list.
 * Stored type, values and listeners are those of ListPreference.
 */
public class SegmentedListPreference extends ListPreference
        implements PrefRowBinder.ValueText, PrefRowBinder.RowKeyHandler {

    /** One segment: an entry value, or null for More. */
    static final class Segment {
        final String value;
        final CharSequence label;

        Segment(String value, CharSequence label) {
            this.value = value;
            this.label = label;
        }

        boolean isMore() {
            return value == null;
        }
    }

    private CharSequence[] segmentValues = new CharSequence[0];
    private CharSequence[] segmentLabels = new CharSequence[0];
    private boolean showMoreSegment;
    private boolean alwaysShowValue;

    private int pendingIndex = -1;
    private boolean dropdownMode;
    private CharSequence autoValueText;

    public SegmentedListPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    public SegmentedListPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public SegmentedListPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public SegmentedListPreference(@NonNull Context context) {
        super(context);
        init(context, null);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SegmentedListPreference);
            CharSequence[] values = a.getTextArray(R.styleable.SegmentedListPreference_vwSegmentValues);
            CharSequence[] labels = a.getTextArray(R.styleable.SegmentedListPreference_vwSegmentLabels);
            showMoreSegment = a.getBoolean(R.styleable.SegmentedListPreference_vwShowMoreSegment, false);
            alwaysShowValue = a.getBoolean(R.styleable.SegmentedListPreference_vwAlwaysShowValue, false);
            a.recycle();
            setSegments(values, labels);
        }
        setLayoutResource(R.layout.vw_pref_segmented);
    }

    /** Sets the segment values and their short labels (same length). */
    public void setSegments(@Nullable CharSequence[] values, @Nullable CharSequence[] labels) {
        if (values == null || labels == null || values.length != labels.length) {
            segmentValues = new CharSequence[0];
            segmentLabels = new CharSequence[0];
        }
        else {
            segmentValues = values;
            segmentLabels = labels;
        }
        notifyChanged();
    }

    // The fragment adds and removes entries at runtime; the segments follow at once
    @Override
    public void setEntries(CharSequence[] entries) {
        super.setEntries(entries);
        notifyChanged();
    }

    @Override
    public void setEntryValues(CharSequence[] entryValues) {
        super.setEntryValues(entryValues);
        notifyChanged();
    }

    /** Short value for the title line while the value is Auto, e.g. what Auto resolves to. */
    public void setAutoValueText(@Nullable CharSequence text) {
        if (!TextUtils.equals(text, autoValueText)) {
            autoValueText = text;
            notifyChanged();
        }
    }

    boolean isDropdownMode() {
        return dropdownMode || segments().isEmpty();
    }

    int getPendingIndex() {
        return pendingIndex;
    }

    /** The segments to show now: configured values still in the entries, then More if needed. */
    List<Segment> segments() {
        List<Segment> result = new ArrayList<>();
        CharSequence[] entryValues = getEntryValues();
        if (entryValues == null) {
            return result;
        }
        int shown = 0;
        for (int i = 0; i < segmentValues.length; i++) {
            String value = segmentValues[i].toString();
            if (findIndexOfValue(value) >= 0) {
                result.add(new Segment(value, segmentLabels[i]));
                shown++;
            }
        }
        if (result.isEmpty()) {
            return result;
        }
        if (showMoreSegment && entryValues.length > shown) {
            result.add(new Segment(null, getContext().getString(R.string.seg_more)));
        }
        return result;
    }

    /** Index of the segment for the current value, More for other values, or -1. */
    static int checkedIndex(List<Segment> segments, @Nullable String value) {
        int more = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.isMore()) {
                more = i;
            }
            else if (segment.value.equals(value)) {
                return i;
            }
        }
        return more;
    }

    private CharSequence entryNameOf(@Nullable String value) {
        int index = value == null ? -1 : findIndexOfValue(value);
        CharSequence[] entries = getEntries();
        return index >= 0 && entries != null && index < entries.length ? entries[index] : null;
    }

    @Nullable
    @Override
    public CharSequence getRowValueText() {
        List<Segment> segments = segments();
        if (pendingIndex >= 0 && pendingIndex < segments.size() && isDropdownMode()) {
            Segment pending = segments.get(pendingIndex);
            return pending.isMore() ? pending.label : entryNameOf(pending.value);
        }
        CharSequence entry = getEntry();
        if (isDropdownMode() || alwaysShowValue) {
            return entry;
        }
        int checked = checkedIndex(segments, getValue());
        if (checked >= 0 && segments.get(checked).isMore()) {
            // Values without a segment (native, custom, 360p...) are named on the title line
            return entry;
        }
        if (autoValueText != null && (PreferenceConfiguration.RES_AUTO.equals(getValue()) ||
                PreferenceConfiguration.FPS_AUTO.equals(getValue()))) {
            return autoValueText;
        }
        return null;
    }

    /** The one commit path, the same as picking the value in the list dialog. */
    void commit(String value) {
        pendingIndex = -1;
        if (value.equals(getValue())) {
            // Choosing the current value again changes nothing (no bitrate reset)
            notifyChanged();
            return;
        }
        if (callChangeListener(value)) {
            setValue(value);
        }
        notifyChanged();
    }

    void onSegmentTapped(int index) {
        List<Segment> segments = segments();
        if (index < 0 || index >= segments.size()) {
            return;
        }
        Segment segment = segments.get(index);
        if (segment.isMore()) {
            pendingIndex = -1;
            notifyChanged();
            onClick();
        }
        else {
            commit(segment.value);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View row = holder.itemView;
        row.setTag(R.id.tag_pref_owner, this);
        LinearLayout group = row.findViewById(R.id.vw_pref_segments);
        if (group == null) {
            return;
        }
        List<Segment> segments = segments();
        int checked = checkedIndex(segments, getValue());
        LayoutInflater inflater = LayoutInflater.from(row.getContext());
        group.removeAllViews();
        CharSequence title = getTitle();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            MaterialButton button = (MaterialButton) inflater.inflate(R.layout.vw_pref_segment_button, group, false);
            button.setText(segment.label);
            button.setCheckable(true);
            button.setChecked(i == checked);
            button.setFocusable(false);
            button.setEnabled(isEnabled());
            CharSequence full = segment.isMore() ? segment.label : entryNameOf(segment.value);
            button.setContentDescription(row.getContext().getString(R.string.seg_content_description,
                    title == null ? "" : title, full == null ? segment.label : full));
            final int index = i;
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSegmentTapped(index);
                }
            });
            group.addView(button);
        }
        group.setVisibility(isDropdownMode() ? View.GONE : View.VISIBLE);
        group.setAlpha(isEnabled() ? 1f : 0.4f);
        applyHighlight(row);
        CharSequence entry = getEntry();
        if (entry != null) {
            ViewCompat.setStateDescription(row, entry);
        }
        row.post(new Runnable() {
            @Override
            public void run() {
                checkFit(row);
            }
        });
    }

    /** Switches between segments and a dropdown row when the labels stop (or start) fitting. */
    void checkFit(View row) {
        if (row.getTag(R.id.tag_pref_owner) != this || row.getWidth() <= 0) {
            return;
        }
        LinearLayout group = row.findViewById(R.id.vw_pref_segments);
        if (group == null || group.getChildCount() == 0) {
            return;
        }
        float[] widths = new float[group.getChildCount()];
        for (int i = 0; i < widths.length; i++) {
            TextView button = (TextView) group.getChildAt(i);
            widths[i] = button.getPaint().measureText(button.getText().toString());
        }
        float density = row.getResources().getDisplayMetrics().density;
        float available = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight()
                - group.getPaddingLeft() - group.getPaddingRight() - 4 * density;
        boolean fits = SegmentFit.fits(widths, available, 8 * density);
        if (fits == dropdownMode) {
            dropdownMode = !fits;
            notifyChanged();
        }
    }

    private void applyHighlight(View row) {
        LinearLayout group = row.findViewById(R.id.vw_pref_segments);
        if (group != null) {
            int stroke = Math.round(2 * row.getResources().getDisplayMetrics().density);
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof MaterialButton) {
                    ((MaterialButton) child).setStrokeWidth(i == pendingIndex ? stroke : 0);
                }
            }
        }
        if (isDropdownMode()) {
            TextView value = row.findViewById(R.id.vw_pref_value);
            CharSequence text = getRowValueText();
            if (value != null) {
                value.setText(text);
                value.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
            }
        }
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onRowKey(View row, int keyCode, KeyEvent event) {
        if (isConfirmKey(keyCode)) {
            if (pendingIndex < 0) {
                // No highlight: the row click opens the full list
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onSegmentTapped(pendingIndex);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
            if (pendingIndex < 0) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                pendingIndex = -1;
                applyHighlight(row);
            }
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }
        List<Segment> segments = segments();
        if (segments.isEmpty()) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            direction = -direction;
        }
        int from = pendingIndex >= 0 ? pendingIndex : checkedIndex(segments, getValue());
        int next = from < 0 ? (direction > 0 ? 0 : segments.size() - 1) : from + direction;
        if (next < 0 || next >= segments.size()) {
            // No wrap and no escape at the ends
            return true;
        }
        pendingIndex = next;
        applyHighlight(row);
        return true;
    }

    @Override
    public void onRowFocusLost() {
        if (pendingIndex >= 0) {
            pendingIndex = -1;
            notifyChanged();
        }
    }
}

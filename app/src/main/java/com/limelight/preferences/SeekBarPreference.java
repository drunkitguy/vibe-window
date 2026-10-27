package com.limelight.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends Preference
        implements PrefRowBinder.ValueText, PrefRowBinder.RowKeyHandler
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    /** Key presses on an inline slider are committed this long after the last one. */
    static final long KEY_COMMIT_DELAY_MS = 600;
    /** Held keys step this many times faster after this many repeats. */
    static final int FAST_REPEAT_COUNT = 8;
    static final int FAST_STEP_FACTOR = 5;

    private AlertDialog dialog;
    private SeekBar seekBar;
    private TextView valueText;
    private final Context context;

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private final boolean inline;
    private int currentValue;

    private final int seekbarMax;

    // Inline slider: value shown while keys are pressed, committed after a pause
    private Integer pendingValue;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable commitPending = new Runnable() {
        @Override
        public void run() {
            commitPendingValue();
        }
    };

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        // Read the message from XML
        int dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        if (dialogMessageId == 0) {
            dialogMessage = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage");
        }
        else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        inline = attrs.getAttributeBooleanValue(SEEKBAR_SCHEMA_URL, "inline", true);
        seekbarMax = maxValue - minValue;

        if (inline) {
            setLayoutResource(R.layout.vw_pref_slider);
        }

        // androidx never calls onSetInitialValue for a row without a stored value (the XML
        // default is not handed over), so start from the XML default as the dialog does
        currentValue = defaultValue;
    }

    /** The text shown for a value: the divisor gives one decimal, a suffix longer than one character gets a space. */
    static String formatValue(int value, int divisor, String suffix) {
        String t;
        if (divisor != 1) {
            float floatValue = value / (float)divisor;
            t = String.format((Locale)null, "%.1f", floatValue);
        }
        else {
            t = String.valueOf(value);
        }
        return suffix == null ? t : t.concat(suffix.length() > 1 ? " "+suffix : suffix);
    }

    /** Rounds to the step grid and keeps the value in range, as the dialog does. */
    static int snap(int value, int min, int max, int step) {
        int rounded = step > 0 ? Math.round((float)value / step) * step : value;
        return Math.max(min, Math.min(max, rounded));
    }

    /**
     * The one commit path for the dialog and the inline slider. Same order and semantics as
     * 1.3.5: the value is stored first, then the change listener runs (the bitrate listener
     * reads the stored value).
     */
    void commitValue(int value) {
        if (shouldPersist()) {
            currentValue = value;
            persistInt(currentValue);
            callChangeListener(currentValue);
        }
    }

    protected AlertDialog getDialog() {
        if (dialog != null) {
            return dialog;
        }

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0%");
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueText, params);

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                value += minValue;
                if (value < minValue) {
                    seekBar.setProgress(0);
                    return;
                }

                int roundedValue = Math.round((float)value / stepSize) * stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue - minValue);
                    return;
                }

                valueText.setText(formatValue(roundedValue, divisor, suffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        seekBar.setMax(seekbarMax);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue - minValue);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(getTitle());
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton("OK", (dialog, which) -> {
            if (shouldPersist()) {
                commitValue(seekBar.getProgress() + minValue);
            }

            dialog.dismiss();
        });
        dialogBuilder.setNegativeButton(context.getString(R.string.cancel), (dialog, which) -> dialog.dismiss());

        dialog = dialogBuilder.create();
        return dialog;
    }

    protected void updateSeekbar() {
        seekBar.setMax(seekbarMax);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue - minValue);
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue)
    {
        super.onSetInitialValue(restore, defaultValue);
        if (restore) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = (Integer) defaultValue;
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            seekBar.setProgress(progress - minValue);
        }
        notifyChanged();
    }

    // NB: returns currentValue + minValue although currentValue is already the absolute
    // value. Nothing outside this class calls it; kept as in 1.3.5 on purpose.
    public int getProgress() {
        return currentValue + minValue;
    }

    /** Re-reads the stored value (for example after the bitrate was reset) and redraws the row. */
    public void refreshFromStore() {
        if (pendingValue == null && shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }
        notifyChanged();
    }

    public void showDialog() {
        AlertDialog dialog = getDialog();
        updateSeekbar();
        dialog.show();
    }

    @Override
    protected void onClick() {
        // A on the row (or a tap outside the slider) opens the dialog for precise values
        commitPendingValue();
        super.onClick();
        showDialog();
    }

    boolean isInline() {
        return inline;
    }

    int getDisplayedValue() {
        return pendingValue != null ? pendingValue : currentValue;
    }

    /** True while the bitrate follows the resolution (only the bitrate row shows this). */
    private boolean bitrateFollowsResolution() {
        if (!PreferenceConfiguration.BITRATE_PREF_STRING.equals(getKey())) {
            return false;
        }
        PreferenceDataStore store = getPreferenceDataStore();
        if (store != null) {
            return store.getBoolean(PreferenceConfiguration.BITRATE_FOLLOWS_RESOLUTION_PREF_STRING, true);
        }
        return getSharedPreferences() != null &&
                getSharedPreferences().getBoolean(PreferenceConfiguration.BITRATE_FOLLOWS_RESOLUTION_PREF_STRING, true);
    }

    @Nullable
    @Override
    public CharSequence getRowValueText() {
        if (!inline) {
            return null;
        }
        String value = formatValue(getDisplayedValue(), divisor, suffix);
        if (pendingValue == null && bitrateFollowsResolution()) {
            SpannableStringBuilder text = new SpannableStringBuilder(value);
            int start = text.length() + 1;
            text.append(' ').append(context.getString(R.string.pref_value_auto));
            text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.vw_on_surface_variant)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return text;
        }
        return value;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (!inline) {
            return;
        }
        if (pendingValue == null && shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }
        final View row = holder.itemView;
        final SeekBar slider = row.findViewById(R.id.vw_pref_seekbar);
        if (slider == null) {
            return;
        }
        slider.setOnSeekBarChangeListener(null);
        slider.setMax(seekbarMax);
        slider.setProgress(getDisplayedValue() - minValue);
        slider.setFocusable(false);
        slider.setEnabled(isEnabled());
        slider.setAlpha(isEnabled() ? 1f : 0.4f);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    showValue(row, snap(progress + minValue, minValue, maxValue, stepSize));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                handler.removeCallbacks(commitPending);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // One drag is one change
                int value = snap(bar.getProgress() + minValue, minValue, maxValue, stepSize);
                pendingValue = null;
                if (value != currentValue) {
                    commitValue(value);
                }
                notifyChanged();
            }
        });
        ViewCompat.setStateDescription(row, formatValue(getDisplayedValue(), divisor, suffix));
    }

    private void showValue(View row, int value) {
        TextView text = row.findViewById(R.id.vw_pref_value);
        String formatted = formatValue(value, divisor, suffix);
        if (text != null) {
            text.setText(formatted);
            text.setVisibility(View.VISIBLE);
        }
        ViewCompat.setStateDescription(row, formatted);
    }

    @Override
    public boolean onRowKey(View row, int keyCode, KeyEvent event) {
        if (!inline || (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        int step = keyStepSize != 0 ? keyStepSize : stepSize;
        if (event.getRepeatCount() > FAST_REPEAT_COUNT) {
            step *= FAST_STEP_FACTOR;
        }
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            direction = -direction;
        }
        int from = getDisplayedValue();
        int next = snap(from + direction * step, minValue, maxValue, stepSize);
        if (next == from) {
            // At the end of the range: consumed, the focus stays on the row
            return true;
        }
        pendingValue = next;
        showValue(row, next);
        SeekBar slider = row.findViewById(R.id.vw_pref_seekbar);
        if (slider != null) {
            slider.setProgress(next - minValue);
        }
        handler.removeCallbacks(commitPending);
        handler.postDelayed(commitPending, KEY_COMMIT_DELAY_MS);
        return true;
    }

    @Override
    public void onRowFocusLost() {
        commitPendingValue();
    }

    private void commitPendingValue() {
        handler.removeCallbacks(commitPending);
        if (pendingValue == null) {
            return;
        }
        int value = pendingValue;
        pendingValue = null;
        if (value != currentValue) {
            commitValue(value);
        }
        notifyChanged();
    }
}

package com.limelight;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class LayoutInflationTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void allLayoutsInflateSuccessfully() throws IllegalAccessException {
        Context base = ApplicationProvider.getApplicationContext();
        // Inflate with the themes the app really uses: Material components need them
        Context appContext = new androidx.appcompat.view.ContextThemeWrapper(base, R.style.AppTheme);
        Context settingsContext = new androidx.appcompat.view.ContextThemeWrapper(base, R.style.SettingsTheme);
        Resources res = base.getResources();
        for (int layoutId : getAllLayoutResourceIds()) {
            String name = res.getResourceEntryName(layoutId);
            Context context = name.startsWith("vw_pref_") || name.equals("expand_button") ||
                    name.equals("activity_stream_settings") || name.equals("activity_edit_profile") ?
                    settingsContext : appContext;
            try {
                LayoutInflater.from(context).inflate(layoutId, null);
            } catch (android.view.InflateException e) {
                // Retry with a dummy FrameLayout for <merge> root layouts
                android.widget.FrameLayout dummyRoot = new android.widget.FrameLayout(context);
                try {
                    LayoutInflater.from(context).inflate(layoutId, dummyRoot, true);
                } catch (RuntimeException retry) {
                    throw new AssertionError("Layout " + name + " failed to inflate", e);
                }
            }
        }
    }

    private static int[] getAllLayoutResourceIds() throws IllegalAccessException {
        Field[] fields = com.limelight.R.layout.class.getFields();
        int[] ids = new int[fields.length];
        int idx = 0;
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                ids[idx++] = f.getInt(null);
            }
        }
        // Trim array if necessary
        if (idx != ids.length) {
            int[] trimmed = new int[idx];
            System.arraycopy(ids, 0, trimmed, 0, idx);
            return trimmed;
        }
        return ids;
    }
}
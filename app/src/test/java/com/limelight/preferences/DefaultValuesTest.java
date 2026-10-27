package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;

/**
 * A clean install writes exactly the 1.3.5 default map: no new stored key and no changed
 * default. The golden map was taken from de0b1ec.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class DefaultValuesTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        context.getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE).edit().clear().commit();
    }

    static Map<String, String> golden() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        for (String line : PreferenceKeyInventoryTest.readResource("preference_defaults_v135.txt")) {
            String[] parts = line.split("\t", -1);
            expected.put(parts[0], parts[1] + ":" + parts[2]);
        }
        return expected;
    }

    static Map<String, String> describe(Map<String, ?> values) {
        Map<String, String> actual = new TreeMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            actual.put(entry.getKey(), (value == null ? "null" : value.getClass().getSimpleName()) + ":" + value);
        }
        return actual;
    }

    @Test
    public void cleanInstallWritesThe135DefaultMap() throws Exception {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertEquals(golden().toString(), describe(prefs.getAll()).toString());
    }

    @Test
    public void streamingDefaultsStayAsIn135() {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertEquals("balanced", prefs.getString("frame_pacing", null));
        assertEquals(Boolean.FALSE, prefs.getAll().get("checkbox_vendor_decoder_keys"));
        assertEquals(Boolean.FALSE, prefs.getAll().get("checkbox_prevent_packet_loss"));
        assertEquals(Boolean.FALSE, prefs.getAll().get("checkbox_keepalive_per_frame"));
        assertEquals("auto", prefs.getString("list_resolution", null));
        assertEquals("auto", prefs.getString("list_fps", null));
        assertEquals(Boolean.TRUE, prefs.getAll().get("checkbox_window_only"));
        assertEquals(Boolean.TRUE, prefs.getAll().get("checkbox_use_virtual_display"));
    }
}

package com.limelight.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.GameMenu;
import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Settings, Import Custom Keys refuses files the menu or the on-screen keyboard cannot use. */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ImportCustomKeysTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        SettingsTestUtil.resetEnvironment();
        ApplicationProvider.getApplicationContext().getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private static void importFile(StreamSettings.SettingsFragment fragment, String json) throws Exception {
        File file = File.createTempFile("keys", ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Intent data = new Intent();
        data.setData(Uri.fromFile(file));
        fragment.onActivityResult(fragment.READ_REQUEST_SPECIAL_CODE, Activity.RESULT_OK, data);
    }

    private static String stored() {
        return ApplicationProvider.getApplicationContext().getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE)
                .getString(GameMenu.KEY_NAME, null);
    }

    @Test
    public void aStepsEntryWithoutKeysIsRefused() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        importFile(fragment, "{\"data\":[{\"name\":\"Seq\",\"steps\":[\"VK_ESCAPE\",\"VK_RETURN\"]}]}");
        assertEquals(null, stored());
        String toast = ShadowToast.getTextOfLatestToast();
        assertTrue(toast, toast.startsWith(fragment.getString(R.string.pref_import_refused, "").trim()));
        assertTrue(toast, toast.contains("Seq") && toast.contains("no keys"));
    }

    @Test
    public void aValidFileWithStepsIsImported() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        String json = "{\"data\":[{\"name\":\"Seq\",\"keys\":[\"VK_ESCAPE\"],\"steps\":[\"VK_ESCAPE\",\"wait:500\",\"VK_RETURN\"]}]}";
        importFile(fragment, json);
        assertEquals(json, stored());
        assertEquals(fragment.getString(R.string.pref_import_success), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aFileWithABadKeyIsRefused() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        importFile(fragment, "{\"data\":[{\"name\":\"B\",\"keys\":[\"VK_NOPE\"]}]}");
        assertFalse(stored() != null);
    }

    @Test
    public void anEmptyListClearsTheCustomKeys() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        importFile(fragment, "{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]}]}");
        assertTrue(stored().contains("Esc"));

        importFile(fragment, "{\"data\":[]}");
        assertEquals("{\"data\":[]}", stored());
        assertEquals(fragment.getString(R.string.pref_import_cleared), ShadowToast.getTextOfLatestToast());
        assertTrue(com.limelight.utils.KeyConfigHelper.parseEntries(
                com.limelight.utils.KeyConfigHelper.parseShortcutFile(stored())).entries.isEmpty());
    }

    @Test
    public void aNullDataListIsRefused() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        importFile(fragment, "{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]}]}");
        importFile(fragment, "{\"data\":null}");
        assertTrue("The previous keys stay", stored().contains("Esc"));
        String toast = ShadowToast.getTextOfLatestToast();
        assertTrue(toast, toast.startsWith(fragment.getString(R.string.pref_import_refused, "").trim()));
    }

    @Test
    public void aKeyboardLayoutExportIsRefusedAndTheKeysStay() throws Exception {
        StreamSettings.SettingsFragment fragment = SettingsTestUtil.launch();
        importFile(fragment, "{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]}]}");
        // Shaped like Settings, Export keyboard layout: one string per button, no data list
        importFile(fragment, "{\"VK_ESCAPE\":\"{\\\"x\\\":10,\\\"y\\\":20}\",\"layout\":\"OSC_Keyboard\"}");
        assertTrue("The previous keys stay", stored().contains("Esc"));
        String toast = ShadowToast.getTextOfLatestToast();
        assertTrue(toast, toast.startsWith(fragment.getString(R.string.pref_import_refused, "").trim()));

        importFile(fragment, "{}");
        assertTrue("{} does not clear the keys", stored().contains("Esc"));
    }
}

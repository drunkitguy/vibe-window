package com.limelight.preferences;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every preference key of 1.3.5 (de0b1ec) stays in res/xml/preferences.xml with the same
 * class family, default value, entry values, dependency and seek bar range. Profiles and the
 * one-time migrations depend on them. The golden file was generated from de0b1ec.
 */
public class PreferenceKeyInventoryTest {

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    static final String SEEKBAR_NS = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    /** Element name to class family. A new preference class must be listed here on purpose. */
    static final Map<String, String> FAMILIES = new HashMap<>();
    static {
        FAMILIES.put("ListPreference", "List");
        FAMILIES.put("com.limelight.preferences.LanguagePreference", "List");
        FAMILIES.put("com.limelight.preferences.SegmentedListPreference", "List");
        FAMILIES.put("CheckBoxPreference", "CheckBox");
        FAMILIES.put("com.limelight.preferences.SmallIconCheckboxPreference", "CheckBox");
        FAMILIES.put("com.limelight.preferences.VwSwitchPreference", "CheckBox");
        FAMILIES.put("com.limelight.preferences.SeekBarPreference", "SeekBar");
        FAMILIES.put("EditTextPreference", "EditText");
        FAMILIES.put("com.limelight.preferences.ConfirmDeleteOscPreference", "Dialog");
        FAMILIES.put("com.limelight.preferences.ConfirmDeleteKeyboardPreference", "Dialog");
        FAMILIES.put("Preference", "Plain");
        FAMILIES.put("com.limelight.preferences.WebLauncherPreference", "Plain");
        FAMILIES.put("com.limelight.preferences.StatsOverlayPreference", "Composite");
        FAMILIES.put("PreferenceCategory", "Category");
        FAMILIES.put("com.limelight.preferences.CaptionCategory", "Category");
    }

    /** The only keys 1.4.0 may add. None of them stores a value. */
    static final Set<String> ALLOWED_NEW_KEYS = new HashSet<>(Arrays.asList(
            "category_quick_stream", "category_quick_controls", "category_quick_audio",
            "category_quick_app", "category_all_settings", "quick_stats_overlay"));

    static File preferencesXml() {
        for (String path : new String[] {"src/main/res/xml/preferences.xml", "app/src/main/res/xml/preferences.xml"}) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        throw new AssertionError("res/xml/preferences.xml not found from " + new File(".").getAbsolutePath());
    }

    static List<Element> keyedElements() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        NodeList all = builder.parse(preferencesXml()).getElementsByTagName("*");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.hasAttributeNS(ANDROID_NS, "key")) {
                result.add(element);
            }
        }
        return result;
    }

    private static String attr(Element element, String ns, String name) {
        return element.hasAttributeNS(ns, name) ? element.getAttributeNS(ns, name) : null;
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    static String row(Element element) {
        String family = FAMILIES.get(element.getTagName());
        if (family == null) {
            fail("Unknown preference class " + element.getTagName() + "; add it to FAMILIES on purpose");
        }
        String extra = "-";
        if (family.equals("SeekBar")) {
            StringBuilder sb = new StringBuilder();
            for (String name : new String[] {"min", "max", "step", "divisor", "keyStep"}) {
                String value = name.equals("max") ? attr(element, ANDROID_NS, "max") : attr(element, SEEKBAR_NS, name);
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(name).append('=').append(value == null ? "" : value);
            }
            extra = sb.toString();
        }
        return String.join("\t", attr(element, ANDROID_NS, "key"), family,
                orDash(attr(element, ANDROID_NS, "defaultValue")),
                orDash(attr(element, ANDROID_NS, "entryValues")),
                orDash(attr(element, ANDROID_NS, "dependency")), extra);
    }

    static List<String> readResource(String name) throws Exception {
        InputStream in = PreferenceKeyInventoryTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("Missing test resource " + name, in);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    @Test
    public void everyKeyOf135IsKeptUnchanged() throws Exception {
        Map<String, String> current = new LinkedHashMap<>();
        for (Element element : keyedElements()) {
            String key = attr(element, ANDROID_NS, "key");
            assertFalse("Key appears twice: " + key, current.containsKey(key));
            current.put(key, row(element));
        }

        List<String> golden = readResource("preference_keys_v135.txt");
        assertTrue(golden.size() > 100);
        StringBuilder problems = new StringBuilder();
        for (String line : golden) {
            String key = line.substring(0, line.indexOf('\t'));
            String now = current.get(key);
            if (now == null) {
                problems.append("missing: ").append(key).append('\n');
            }
            else if (!now.equals(line)) {
                problems.append("changed: ").append(line).append("  ->  ").append(now).append('\n');
            }
        }
        assertEquals(problems.toString(), "", problems.toString());
    }

    @Test
    public void onlyTheAllowedKeysAreNewAndNoneOfThemStoresAValue() throws Exception {
        Set<String> goldenKeys = new HashSet<>();
        for (String line : readResource("preference_keys_v135.txt")) {
            goldenKeys.add(line.substring(0, line.indexOf('\t')));
        }
        for (Element element : keyedElements()) {
            String key = attr(element, ANDROID_NS, "key");
            if (goldenKeys.contains(key)) {
                continue;
            }
            assertTrue("Key not allowed in 1.4.0: " + key, ALLOWED_NEW_KEYS.contains(key));
            assertEquals("New keys have no default value: " + key, null, attr(element, ANDROID_NS, "defaultValue"));
            String family = FAMILIES.get(element.getTagName());
            if (!"Category".equals(family)) {
                assertEquals("New rows must not persist: " + key, "false", attr(element, ANDROID_NS, "persistent"));
            }
        }
    }
}

package com.limelight.utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KeyConfigHelperTest {

    private static KeyConfigHelper.Shortcut entry(String json) {
        return KeyConfigHelper.parseShortcutFile("{\"data\":[" + json + "]}").data.get(0);
    }

    private static void assertRejected(String json) {
        try {
            KeyConfigHelper.parseSteps(entry(json));
            fail("Should be rejected: " + json);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void theOldKeysFormatIsOneCombination() {
        KeyConfigHelper.Shortcut sc = entry("{\"id\":\"x\",\"name\":\"Ctrl+,\",\"keys\":[\"VK_LCONTROL\",\"0xBC\"]}");
        assertNull(sc.steps);
        List<KeyConfigHelper.Step> steps = KeyConfigHelper.parseSteps(sc);
        assertEquals(1, steps.size());
        assertArrayEquals(new short[] {0xA2, 0xBC}, steps.get(0).chord);
        assertFalse(KeyConfigHelper.isSequence(steps));
    }

    @Test
    public void stepsAreCombinationsAndWaitsInOrder() {
        KeyConfigHelper.Shortcut sc = entry("{\"id\":\"o\",\"name\":\"Open\",\"keys\":[\"VK_LCONTROL\",\"VK_OEM_COMMA\"]," +
                "\"steps\":[\"VK_LCONTROL+VK_OEM_COMMA\",\"wait:1000\",\"VK_LSHIFT + VK_TAB\",\"VK_LSHIFT+VK_TAB\",\"VK_END\"]}");
        List<KeyConfigHelper.Step> steps = KeyConfigHelper.parseSteps(sc);
        assertEquals(5, steps.size());
        assertArrayEquals(new short[] {0xA2, 0xBC}, steps.get(0).chord);
        assertTrue(steps.get(1).isWait());
        assertEquals(1000, steps.get(1).waitMs);
        assertArrayEquals(new short[] {0xA0, 0x09}, steps.get(2).chord);
        assertArrayEquals(new short[] {0x23}, steps.get(4).chord);
        assertTrue(KeyConfigHelper.isSequence(steps));
        // 150 ms apart, plus the wait
        assertArrayEquals(new long[] {0, 1150, 1300, 1450}, KeyConfigHelper.sendTimesMs(steps));
    }

    @Test
    public void stepsWithoutKeysStillParse() {
        List<KeyConfigHelper.Step> steps = KeyConfigHelper.parseSteps(
                entry("{\"id\":\"s\",\"name\":\"S\",\"steps\":[\"VK_ESCAPE\",\"VK_RETURN\"]}"));
        assertEquals(2, steps.size());
    }

    @Test
    public void badInputIsRejected() {
        assertRejected("{\"name\":\"a\",\"keys\":[\"VK_NOT_A_KEY\"]}");
        assertRejected("{\"name\":\"a\",\"keys\":[\"ESCAPE\"]}");
        assertRejected("{\"name\":\"a\",\"keys\":[]}");
        assertRejected("{\"name\":\"a\",\"keys\":[\"0xZZ\"]}");
        assertRejected("{\"name\":\"a\",\"steps\":[\"VK_LSHIFT+\"]}");
        assertRejected("{\"name\":\"a\",\"steps\":[\"\"]}");
        assertRejected("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:abc\"]}");
        assertRejected("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:0\"]}");
        assertRejected("{\"name\":\"a\",\"steps\":[\"wait:100\"]}");
    }

    @Test
    public void limitsAreEnforced() {
        // A wait of 5 s is allowed, longer is not
        KeyConfigHelper.parseSteps(entry("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:5000\",\"VK_ESCAPE\"]}"));
        assertRejected("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:5001\",\"VK_ESCAPE\"]}");

        // 32 steps are allowed, 33 are not
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            steps.add("\"VK_ESCAPE\"");
        }
        String many = String.join(",", steps);
        assertEquals(32, KeyConfigHelper.parseSteps(entry("{\"name\":\"a\",\"steps\":[" + many + "]}")).size());
        assertRejected("{\"name\":\"a\",\"steps\":[" + many + ",\"VK_ESCAPE\"]}");

        // At most 10 s in total
        assertRejected("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:5000\",\"wait:5000\",\"VK_ESCAPE\"]}");
        KeyConfigHelper.parseSteps(entry("{\"name\":\"a\",\"steps\":[\"VK_ESCAPE\",\"wait:5000\",\"wait:4850\",\"VK_ESCAPE\"]}"));
    }

    @Test
    public void theExampleFileParses() throws Exception {
        File file = null;
        for (String path : new String[] {"../docs/key-sequence-example.json", "docs/key-sequence-example.json"}) {
            if (new File(path).isFile()) {
                file = new File(path);
            }
        }
        assertTrue("docs/key-sequence-example.json not found", file != null);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        KeyConfigHelper.ShortcutFile parsed = KeyConfigHelper.parseShortcutFile(json);
        assertEquals(Arrays.asList("Open settings, Controls page", "Close dialog (Esc)"),
                Arrays.asList(parsed.data.get(0).name, parsed.data.get(1).name));
        assertTrue(KeyConfigHelper.isSequence(KeyConfigHelper.parseSteps(parsed.data.get(0))));
        assertFalse(KeyConfigHelper.isSequence(KeyConfigHelper.parseSteps(parsed.data.get(1))));
    }

    @Test
    public void anInvalidEntrySkipsOnlyItself() {
        KeyConfigHelper.ShortcutFile file = KeyConfigHelper.parseShortcutFile("{\"data\":[" +
                "{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]}," +
                "{\"name\":\"Broken\",\"keys\":[\"VK_NOT_A_KEY\"]}," +
                "{\"name\":\"Nothing\",\"keys\":[]}," +
                "{\"name\":\"Seq\",\"keys\":[\"VK_ESCAPE\"],\"steps\":[\"VK_ESCAPE\",\"VK_RETURN\"]}]}");
        KeyConfigHelper.Entries parsed = KeyConfigHelper.parseEntries(file);
        assertEquals(1, parsed.rejected);
        assertEquals(3, parsed.entries.size());
        assertEquals("Esc", parsed.entries.get(0).name);
        assertEquals("Nothing", parsed.entries.get(1).name);
        assertEquals("Seq", parsed.entries.get(2).name);
        // Empty keys without steps stays a do-nothing entry, as up to 1.4.3
        List<KeyConfigHelper.Step> nothing = parsed.entries.get(1).steps;
        assertFalse(KeyConfigHelper.isSequence(nothing));
        assertEquals(0, nothing.get(0).chord.length);
        assertTrue(KeyConfigHelper.isSequence(parsed.entries.get(2).steps));
    }

    @Test
    public void importValidation() {
        assertNull(KeyConfigHelper.validateForImport(
                "{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]},{\"name\":\"Nothing\",\"keys\":[]}]}"));
        String stepsOnly = KeyConfigHelper.validateForImport(
                "{\"data\":[{\"name\":\"Seq\",\"steps\":[\"VK_ESCAPE\",\"VK_RETURN\"]}]}");
        assertTrue(stepsOnly, stepsOnly != null && stepsOnly.contains("\"Seq\"") && stepsOnly.contains("no keys"));
        String badKey = KeyConfigHelper.validateForImport("{\"data\":[{\"name\":\"B\",\"keys\":[\"VK_NOPE\"]}]}");
        assertTrue(badKey, badKey != null && badKey.contains("VK_NOPE"));
        String badWait = KeyConfigHelper.validateForImport(
                "{\"data\":[{\"name\":\"W\",\"keys\":[\"VK_ESCAPE\"],\"steps\":[\"VK_ESCAPE\",\"wait:9000\"]}]}");
        assertTrue(badWait != null);
        assertTrue(KeyConfigHelper.validateForImport("not json at all {") != null);
        // An empty list is valid and clears the keys; an object without a data list is not a key file
        assertNull(KeyConfigHelper.validateForImport("{\"data\":[]}"));
        String noData = KeyConfigHelper.validateForImport("{}");
        assertTrue(noData, noData != null && noData.contains("\"data\""));
        assertTrue(KeyConfigHelper.validateForImport("{\"data\":{}}") != null);
        assertTrue(KeyConfigHelper.validateForImport("[]") != null);
        assertFalse(KeyConfigHelper.clearsCustomKeys("{}"));
        assertTrue(KeyConfigHelper.clearsCustomKeys("{\"data\":[]}"));
        assertFalse(KeyConfigHelper.clearsCustomKeys("{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]}]}"));
        // A JSON null list is refused
        String nullData = KeyConfigHelper.validateForImport("{\"data\":null}");
        assertTrue(nullData, nullData != null && nullData.contains("data"));
    }

    @Test
    public void theExampleFileCanBeImported() throws Exception {
        for (String path : new String[] {"../docs/key-sequence-example.json", "docs/key-sequence-example.json"}) {
            File file = new File(path);
            if (file.isFile()) {
                assertNull(KeyConfigHelper.validateForImport(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
                return;
            }
        }
        fail("docs/key-sequence-example.json not found");
    }
}

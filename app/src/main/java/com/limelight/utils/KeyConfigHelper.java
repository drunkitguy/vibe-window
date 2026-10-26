package com.limelight.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KeyConfigHelper {
    /** Pause between two key combinations of a sequence. */
    public static final long DEFAULT_GAP_MS = 150;
    public static final int MAX_STEPS = 32;
    public static final long MAX_WAIT_MS = 5000;
    public static final long MAX_TOTAL_MS = 10000;
    static final String WAIT_PREFIX = "wait:";

    public static class ShortcutFile {
        public List<Shortcut> data;
        
        // Default constructor for Gson
        public ShortcutFile() {
            this.data = new ArrayList<>();
        }
        public ShortcutFile(List<Shortcut> data) {
            this.data = data;
        }
    }

    public static class Shortcut {
        public String id;
        public String name;
        public boolean sticky = false;  // Default to false
        public List<String> keys;
        /**
         * Optional ordered steps: key combinations such as "VK_LSHIFT+VK_TAB" and waits such
         * as "wait:800" (milliseconds). Without steps, keys is sent as one combination.
         */
        public List<String> steps;

        // Default constructor for Gson
        public Shortcut() {
            this.keys = new ArrayList<>();
        }
        
        public Shortcut(String id, String name, boolean sticky, List<String> keys) {
            this.id = id;
            this.name = name;
            this.sticky = sticky;
            this.keys = keys;
        }
    }

    /** One step of a key sequence: a key combination, or a wait. */
    public static final class Step {
        /** Windows virtual key codes pressed together, or null for a wait. */
        public final short[] chord;
        public final long waitMs;

        private Step(short[] chord, long waitMs) {
            this.chord = chord;
            this.waitMs = waitMs;
        }

        public static Step chord(short[] keys) {
            return new Step(keys, 0);
        }

        public static Step waitFor(long ms) {
            return new Step(null, ms);
        }

        public boolean isWait() {
            return chord == null;
        }
    }

    public static ShortcutFile parseShortcutFile(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, ShortcutFile.class);
    }

    /** A key as "0x.." (hex) or a VK_ name from KeyMapper. Unknown keys are rejected. */
    public static short parseKeyCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Missing key code");
        }
        String trimmed = code.trim();
        int keycode;
        if (trimmed.startsWith("0x")) {
            keycode = Integer.parseInt(trimmed.substring(2), 16);
        } else if (trimmed.startsWith("VK_")) {
            try {
                Field field = KeyMapper.class.getDeclaredField(trimmed);
                keycode = field.getInt(null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalArgumentException("Unknown key code: " + code);
            }
        } else {
            throw new IllegalArgumentException("Unknown key code: " + code);
        }
        if (keycode <= 0 || keycode > 0xFFFF) {
            throw new IllegalArgumentException("Key code out of range: " + code);
        }
        return (short) keycode;
    }

    /** A list of keys pressed together. Rejects an empty combination. */
    public static short[] parseChord(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Empty key combination");
        }
        short[] chord = new short[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            chord[i] = parseKeyCode(keys.get(i));
        }
        return chord;
    }

    /** "VK_LSHIFT+VK_TAB" as a key combination. */
    public static short[] parseChord(String combo) {
        if (combo == null || combo.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty key combination");
        }
        List<String> keys = new ArrayList<>();
        for (String part : combo.split("\\+", -1)) {
            if (part.trim().isEmpty()) {
                throw new IllegalArgumentException("Empty key in " + combo);
            }
            keys.add(part.trim());
        }
        return parseChord(keys);
    }

    /**
     * The steps of an entry: its steps when it has any, otherwise its keys as one combination
     * (the format before steps existed). Limits: at most MAX_STEPS steps, each wait at most
     * MAX_WAIT_MS, and at most MAX_TOTAL_MS from the first to the last combination.
     */
    public static List<Step> parseSteps(Shortcut shortcut) {
        if (shortcut == null) {
            throw new IllegalArgumentException("Missing entry");
        }
        if (shortcut.steps == null || shortcut.steps.isEmpty()) {
            return Collections.singletonList(Step.chord(parseChord(shortcut.keys)));
        }
        if (shortcut.steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("More than " + MAX_STEPS + " steps");
        }
        List<Step> steps = new ArrayList<>();
        boolean hasChord = false;
        for (String item : shortcut.steps) {
            if (item == null) {
                throw new IllegalArgumentException("Empty step");
            }
            String trimmed = item.trim();
            if (trimmed.startsWith(WAIT_PREFIX)) {
                long ms;
                try {
                    ms = Long.parseLong(trimmed.substring(WAIT_PREFIX.length()).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad wait: " + item);
                }
                if (ms <= 0 || ms > MAX_WAIT_MS) {
                    throw new IllegalArgumentException("Wait out of range (1 to " + MAX_WAIT_MS + " ms): " + item);
                }
                steps.add(Step.waitFor(ms));
            } else {
                steps.add(Step.chord(parseChord(trimmed)));
                hasChord = true;
            }
        }
        if (!hasChord) {
            throw new IllegalArgumentException("No key combination in the steps");
        }
        if (totalDurationMs(steps) > MAX_TOTAL_MS) {
            throw new IllegalArgumentException("Sequence longer than " + MAX_TOTAL_MS + " ms");
        }
        return steps;
    }

    /** Whether the steps are more than one plain key combination. */
    public static boolean isSequence(List<Step> steps) {
        return steps.size() != 1 || steps.get(0).isWait();
    }

    /**
     * When each combination is sent, from the start of the sequence: the first at its leading
     * waits, every later one DEFAULT_GAP_MS after the previous one plus the waits in between.
     */
    public static long[] sendTimesMs(List<Step> steps) {
        List<Long> times = new ArrayList<>();
        long at = 0;
        boolean first = true;
        for (Step step : steps) {
            if (step.isWait()) {
                at += step.waitMs;
            } else {
                if (!first) {
                    at += DEFAULT_GAP_MS;
                }
                times.add(at);
                first = false;
            }
        }
        long[] result = new long[times.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = times.get(i);
        }
        return result;
    }

    /** Time from the start to the last combination. */
    public static long totalDurationMs(List<Step> steps) {
        long[] times = sendTimesMs(steps);
        return times.length == 0 ? 0 : times[times.length - 1];
    }

    /** One menu entry of a custom key file. */
    public static final class Entry {
        public final String name;
        public final List<Step> steps;

        Entry(String name, List<Step> steps) {
            this.name = name;
            this.steps = steps;
        }
    }

    /** The usable entries of a file, in order, and how many were skipped as invalid. */
    public static final class Entries {
        public final List<Entry> entries;
        public final int rejected;

        Entries(List<Entry> entries, int rejected) {
            this.entries = entries;
            this.rejected = rejected;
        }
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    /**
     * Every entry of a file on its own: an invalid entry is skipped and counted, the others
     * stay. An entry without keys and without steps is kept and sends nothing, as up to 1.4.3.
     */
    public static Entries parseEntries(ShortcutFile file) {
        List<Entry> entries = new ArrayList<>();
        int rejected = 0;
        if (file == null || file.data == null) {
            return new Entries(entries, 0);
        }
        for (Shortcut sc : file.data) {
            if (sc == null) {
                rejected++;
                continue;
            }
            if (isEmpty(sc.steps) && isEmpty(sc.keys)) {
                entries.add(new Entry(sc.name, Collections.singletonList(Step.chord(new short[0]))));
                continue;
            }
            try {
                entries.add(new Entry(sc.name, parseSteps(sc)));
            } catch (IllegalArgumentException e) {
                rejected++;
            }
        }
        return new Entries(entries, rejected);
    }

    /**
     * Whether the text is a JSON object with a "data" key holding an array. Anything else (for
     * example {} or an on-screen keyboard layout export) is not a custom key file.
     */
    static boolean hasDataArray(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject object = root.getAsJsonObject();
            return object.has("data") && object.get("data").isJsonArray();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a valid file has no entries, so importing it clears the custom keys. */
    public static boolean clearsCustomKeys(String json) {
        if (json == null || !hasDataArray(json)) {
            return false;
        }
        try {
            ShortcutFile file = parseShortcutFile(json);
            return file != null && file.data != null && file.data.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks a file before it is imported. Returns null when it can be imported, otherwise
     * why not. An entry with steps must also have keys: the on-screen keyboard only reads keys.
     */
    public static String validateForImport(String json) {
        if (json == null || !hasDataArray(json)) {
            return "no \"data\" list";
        }
        ShortcutFile file;
        try {
            file = parseShortcutFile(json);
        } catch (RuntimeException e) {
            return "not a custom key file (" + e.getMessage() + ")";
        }
        if (file == null || file.data == null) {
            // An empty list is fine: importing it clears the custom keys
            return "no \"data\" list";
        }
        for (int i = 0; i < file.data.size(); i++) {
            Shortcut sc = file.data.get(i);
            String label = sc == null || sc.name == null ? "entry " + (i + 1) : "\"" + sc.name + "\"";
            if (sc == null) {
                return label + " is empty";
            }
            if (!isEmpty(sc.steps) && isEmpty(sc.keys)) {
                return label + " has steps but no keys; add keys with its first key combination for the on-screen keyboard";
            }
            if (isEmpty(sc.steps) && isEmpty(sc.keys)) {
                continue;
            }
            try {
                parseSteps(sc);
                if (!isEmpty(sc.steps)) {
                    // The on-screen keyboard sends keys; they must be valid keys too
                    parseChord(sc.keys);
                }
            } catch (IllegalArgumentException e) {
                return label + ": " + e.getMessage();
            }
        }
        return null;
    }
}

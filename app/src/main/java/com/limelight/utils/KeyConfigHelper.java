package com.limelight.utils;

import com.google.gson.Gson;

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
}

package com.limelight.library;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recognizes host utilities (desktop, launchers, host tools) that belong in
 * the "Apps" group when the host does not say so itself.
 */
public final class UtilityDetector {
    public static final String VIRTUAL_DISPLAY_UUID = "8902CB19-674A-403D-A587-41B092E900BA";
    public static final String DESKTOP_UUID = "EAAC6159-089A-46A9-9E24-6436885F6610";
    public static final String REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4";
    public static final String TERMINATE_UUID = "E16CBE1B-295D-4632-9A76-EC4180C857D3";

    private static final Set<String> BUILT_IN_UUIDS = new HashSet<>(Arrays.asList(
            VIRTUAL_DISPLAY_UUID, DESKTOP_UUID, REMOTE_INPUT_UUID, TERMINATE_UUID));

    private static final Set<String> EXACT_NAMES = new HashSet<>(Arrays.asList(
            "desktop", "steam big picture", "playnite", "playnite fullscreen",
            "virtual display", "terminate"));

    private static final String[] CONTAINED_NAMES = {
            "lossless scaling",
    };

    private static final String[] NAME_PREFIXES = {
            "remote ",
    };

    private UtilityDetector() {}

    public static boolean isBuiltInUuid(String uuid) {
        return uuid != null && BUILT_IN_UUIDS.contains(uuid.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isUtility(String uuid, String name) {
        if (isBuiltInUuid(uuid)) {
            return true;
        }

        String normalized = LibraryText.normalize(name);
        if (normalized.isEmpty()) {
            return false;
        }
        if (EXACT_NAMES.contains(normalized)) {
            return true;
        }
        for (String contained : CONTAINED_NAMES) {
            if (normalized.contains(contained)) {
                return true;
            }
        }
        for (String prefix : NAME_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

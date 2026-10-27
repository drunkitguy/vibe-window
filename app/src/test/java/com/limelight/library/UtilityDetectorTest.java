package com.limelight.library;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UtilityDetectorTest {
    @Test
    public void builtInUuids() {
        assertTrue(UtilityDetector.isUtility("8902CB19-674A-403D-A587-41B092E900BA", "Anything"));
        assertTrue(UtilityDetector.isUtility("EAAC6159-089A-46A9-9E24-6436885F6610", "Anything"));
        assertTrue(UtilityDetector.isUtility("8CB5C136-DA67-4F99-B4A1-F9CD35005CF4", "Anything"));
        assertTrue(UtilityDetector.isUtility("e16cbe1b-295d-4632-9a76-ec4180c857d3", "Anything"));
    }

    @Test
    public void utilityNames() {
        assertTrue(UtilityDetector.isUtility("", "Desktop"));
        assertTrue(UtilityDetector.isUtility("", "desktop"));
        assertTrue(UtilityDetector.isUtility("", "Steam Big Picture"));
        assertTrue(UtilityDetector.isUtility("", "Playnite"));
        assertTrue(UtilityDetector.isUtility("", "Virtual Display"));
        assertTrue(UtilityDetector.isUtility("", "Terminate"));
        assertTrue(UtilityDetector.isUtility("", "Remote Input"));
        assertTrue(UtilityDetector.isUtility("", "Remote Example Tool"));
        assertTrue(UtilityDetector.isUtility("", "Lossless Scaling"));
        assertTrue(UtilityDetector.isUtility("", "Example Lossless Scaling Profile"));
        // Zero-width characters and spacing do not hide a utility
        assertTrue(UtilityDetector.isUtility(null, "​Desktop "));
        assertTrue(UtilityDetector.isUtility(null, "Steam  Big   Picture"));
    }

    @Test
    public void gamesAreNotUtilities() {
        assertFalse(UtilityDetector.isUtility("", "Example Game"));
        assertFalse(UtilityDetector.isUtility("11111111-2222-3333-4444-555555555555", "Example Game"));
        assertFalse(UtilityDetector.isUtility("", "Desktop Example Game"));
        assertFalse(UtilityDetector.isUtility("", "Example Remote"));
        assertFalse(UtilityDetector.isUtility(null, null));
        assertFalse(UtilityDetector.isUtility("", ""));
    }
}

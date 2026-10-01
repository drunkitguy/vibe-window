package com.limelight.library;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlatformCatalogTest {
    @Test
    public void pcAliasesShareOneGroup() {
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve("pc_windows", "PC (Windows)"));
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve("pc_dos", ""));
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve("pc_linux", null));
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve("", "PC (Windows)"));
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve(null, "PC"));
        assertSame(PlatformCatalog.PC, PlatformCatalog.resolve("PC_WINDOWS", ""));
        assertEquals("PC", PlatformCatalog.PC.label);
        assertEquals(PlatformCatalog.RANK_FIRST, PlatformCatalog.PC.rank);
    }

    @Test
    public void knownSpecIdsGetShortLabels() {
        assertEquals("Nintendo Switch", PlatformCatalog.resolve("nintendo_switch", "Nintendo Switch").label);
        assertEquals("Nintendo 3DS", PlatformCatalog.resolve("nintendo_3ds", "").label);
        assertEquals("GameCube", PlatformCatalog.resolve("nintendo_gamecube", "Nintendo GameCube").label);
        assertEquals("Wii", PlatformCatalog.resolve("nintendo_wii", "").label);
        assertEquals("Wii U", PlatformCatalog.resolve("nintendo_wiiu", "").label);
        assertEquals("PlayStation 2", PlatformCatalog.resolve("sony_playstation2", "Sony PlayStation 2").label);
        assertEquals("nintendo_switch", PlatformCatalog.resolve("nintendo_switch", "").key);
        assertEquals(PlatformCatalog.RANK_SYSTEM, PlatformCatalog.resolve("nintendo_switch", "").rank);
    }

    @Test
    public void knownNamesResolveWithoutId() {
        assertEquals("nintendo_gamecube", PlatformCatalog.resolve("", "Nintendo GameCube").key);
        assertEquals("nintendo_gamecube", PlatformCatalog.resolve("", "gamecube").key);
        assertEquals("sony_playstation2", PlatformCatalog.resolve(null, "  Sony PlayStation 2 ").key);
    }

    @Test
    public void idWinsOverName() {
        assertEquals("nintendo_3ds", PlatformCatalog.resolve("nintendo_3ds", "Something Else").key);
    }

    @Test
    public void unknownValuesKeepHostText() {
        PlatformCatalog.Platform platform = PlatformCatalog.resolve("", "Example Console");
        assertEquals("example console", platform.key);
        assertEquals("Example Console", platform.label);
        assertEquals(PlatformCatalog.RANK_OTHER, platform.rank);

        PlatformCatalog.Platform byId = PlatformCatalog.resolve("example_console", "");
        assertEquals("example_console", byId.key);
        assertEquals("example_console", byId.label);

        PlatformCatalog.Platform unknownIdWithName = PlatformCatalog.resolve("example_console", "Example Console");
        assertEquals("example console", unknownIdWithName.key);
        assertEquals("Example Console", unknownIdWithName.label);
    }

    @Test
    public void emulatedIsAnOrdinaryUnknownGroup() {
        PlatformCatalog.Platform platform = PlatformCatalog.resolve("", "Emulated");
        assertEquals("emulated", platform.key);
        assertEquals(PlatformCatalog.RANK_OTHER, platform.rank);
    }

    @Test
    public void reservedKeys() {
        assertSame(PlatformCatalog.APPS, PlatformCatalog.resolve("apps", "Apps"));
        assertSame(PlatformCatalog.APPS, PlatformCatalog.resolveLabel("apps"));
        assertEquals(PlatformCatalog.RANK_LAST, PlatformCatalog.APPS.rank);
        assertSame(PlatformCatalog.GAMES, PlatformCatalog.resolveLabel("Games"));
        assertTrue(PlatformCatalog.isReservedKey("apps"));
        assertTrue(PlatformCatalog.isReservedKey("results"));
        // A user group called "Results" must not collide with the wheel's search pseudo group
        assertEquals("other:results", PlatformCatalog.resolveLabel("Results").key);
    }

    @Test
    public void emptyValuesResolveToNothing() {
        assertNull(PlatformCatalog.resolve(null, null));
        assertNull(PlatformCatalog.resolve("", "  "));
        assertNull(PlatformCatalog.resolve("​", ""));
    }
}

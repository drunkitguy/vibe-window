package com.limelight.library;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LibraryModelTest {
    private static LibraryModel.Input<TestApp> input(TestApp... apps) {
        return new LibraryModel.Input<TestApp>().apps(Arrays.asList(apps));
    }

    private static List<String> groupKeys(LibrarySnapshot<TestApp> snapshot) {
        List<String> keys = new ArrayList<>();
        for (LibrarySnapshot.Group<TestApp> group : snapshot.groups) {
            keys.add(group.key);
        }
        return keys;
    }

    private static List<String> names(LibrarySnapshot.Group<TestApp> group) {
        List<String> names = new ArrayList<>();
        for (LibrarySnapshot.AppRow<TestApp> row : group.apps) {
            names.add(row.app.name);
        }
        return names;
    }

    private static List<String> rowSummary(LibrarySnapshot<TestApp> snapshot) {
        List<String> rows = new ArrayList<>();
        for (LibrarySnapshot.Row row : snapshot.rows) {
            if (row instanceof LibrarySnapshot.HeaderRow) {
                LibrarySnapshot.HeaderRow header = (LibrarySnapshot.HeaderRow) row;
                rows.add("# " + header.label + " " + header.count + (header.collapsed ? " collapsed" : ""));
            } else {
                rows.add(((LibrarySnapshot.AppRow<?>) row).name);
            }
        }
        return rows;
    }

    private static TestApp[] newHostLibrary() {
        return new TestApp[] {
                TestApp.onPlatform(1, "Example Game B", "PC (Windows)", "pc_windows"),
                TestApp.onPlatform(2, "Example Game A", "PC (Windows)", "pc_windows"),
                TestApp.onPlatform(3, "Sample Kart Racer", "Nintendo Switch", "nintendo_switch"),
                TestApp.onPlatform(4, "Sample Puzzle", "Nintendo 3DS", "nintendo_3ds"),
                TestApp.onPlatform(5, "Sample Kart Classic", "Nintendo GameCube", "nintendo_gamecube"),
                TestApp.onPlatform(6, "Desktop", "Apps", "apps"),
                TestApp.onPlatform(7, "Sample Odd One", "Example Console", ""),
                TestApp.onPlatform(8, "Sample Emulated", "Emulated", ""),
                TestApp.onPlatform(9, "Example Tool", "", ""),
                TestApp.onPlatform(10, "Lossless Scaling", "", ""),
        };
    }

    @Test
    public void groupOrderPcFirstSystemsThenOthersThenApps() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()));
        assertEquals(Arrays.asList("pc", "nintendo_gamecube", "nintendo_3ds", "nintendo_switch",
                "emulated", "example console", "apps"), groupKeys(snapshot));
        assertTrue(snapshot.hostHasPlatforms);
    }

    @Test
    public void untaggedAppsGoToPcOnNewHostsAndUtilitiesToApps() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()));
        assertEquals(Arrays.asList("Example Game A", "Example Game B", "Example Tool"),
                names(snapshot.findGroup("pc")));
        assertEquals(Arrays.asList("Desktop", "Lossless Scaling"), names(snapshot.findGroup("apps")));
    }

    @Test
    public void oldHostFallsBackToGamesAndApps() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                new TestApp(1, "Example Game"),
                new TestApp(2, "Desktop"),
                new TestApp(3, UtilityDetector.REMOTE_INPUT_UUID, "Input Helper", "", ""),
                new TestApp(4, "Another Example Game"),
                new TestApp(5, "Steam Big Picture")));
        assertFalse(snapshot.hostHasPlatforms);
        assertEquals(Arrays.asList("games", "apps"), groupKeys(snapshot));
        assertEquals("Games", snapshot.groups.get(0).label);
        assertEquals(Arrays.asList("Another Example Game", "Example Game"), names(snapshot.groups.get(0)));
        assertEquals(Arrays.asList("Desktop", "Input Helper", "Steam Big Picture"), names(snapshot.groups.get(1)));
    }

    @Test
    public void appsSortByNameIgnoringCaseAccentsAndZeroWidth() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                new TestApp(1, "​​zeta Example"),
                new TestApp(2, "Alpha Example"),
                new TestApp(3, "Écho Example"),
                new TestApp(4, "beta Example"),
                new TestApp(5, "Delta Example")));
        assertEquals(Arrays.asList("Alpha Example", "beta Example", "Delta Example",
                "Écho Example", "​​zeta Example"), names(snapshot.groups.get(0)));
    }

    @Test
    public void spellingsOfOneSystemShareAGroup() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                TestApp.onPlatform(1, "Sample One", "Nintendo GameCube", "nintendo_gamecube"),
                TestApp.onPlatform(2, "Sample Two", "Nintendo GameCube", ""),
                TestApp.onPlatform(3, "Sample Three", "", "nintendo_gamecube")));
        assertEquals(1, snapshot.groups.size());
        assertEquals("GameCube", snapshot.groups.get(0).label);
        assertEquals(3, snapshot.groups.get(0).apps.size());
    }

    @Test
    public void hiddenAppsExcludedUnlessShown() {
        TestApp[] apps = newHostLibrary();
        LibrarySnapshot<TestApp> hidden = LibraryModel.build(input(apps)
                .hiddenIds(Arrays.asList(1, 2, 9, 4)));
        assertNull("PC group is empty and omitted", hidden.findGroup("pc"));
        assertNull("3DS group is empty and omitted", hidden.findGroup("nintendo_3ds"));
        assertEquals(6, hidden.totalCount);

        LibrarySnapshot<TestApp> shown = LibraryModel.build(input(apps)
                .hiddenIds(Arrays.asList(1, 2, 9, 4)).showHidden(true));
        LibrarySnapshot.Group<TestApp> pc = shown.findGroup("pc");
        assertEquals(3, pc.apps.size());
        assertTrue(pc.apps.get(0).hidden);
        assertTrue(pc.apps.get(1).hidden);
        assertTrue(pc.apps.get(2).hidden);
        assertFalse(shown.findGroup("apps").apps.get(0).hidden);
        assertEquals(10, shown.totalCount);
    }

    @Test
    public void appsHiddenThisVisitStayListedAndDimmed() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary())
                .hiddenIds(Arrays.asList(1, 2)).keepVisibleIds(Collections.singletonList(1)));
        LibrarySnapshot.Group<TestApp> pc = snapshot.findGroup("pc");
        assertEquals(Arrays.asList("Example Game B", "Example Tool"), names(pc));
        assertTrue(pc.apps.get(0).hidden);
        assertFalse(pc.apps.get(1).hidden);
    }

    @Test
    public void hiddenAppsStillCountTowardPlatformSupport() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                TestApp.onPlatform(1, "Sample Hidden", "Nintendo Switch", "nintendo_switch"),
                new TestApp(2, "Example Game"))
                .hiddenIds(Collections.singletonList(1)));
        assertTrue(snapshot.hostHasPlatforms);
        assertEquals(Collections.singletonList("pc"), groupKeys(snapshot));
    }

    @Test
    public void collapsedGroupsHaveHeaderOnly() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary())
                .collapsedKeys(new HashSet<>(Arrays.asList("pc", "apps", "not_a_group"))));
        assertEquals(Arrays.asList(
                "# PC 3 collapsed",
                "# GameCube 1", "Sample Kart Classic",
                "# Nintendo 3DS 1", "Sample Puzzle",
                "# Nintendo Switch 1", "Sample Kart Racer",
                "# Emulated 1", "Sample Emulated",
                "# Example Console 1", "Sample Odd One",
                "# Apps 2 collapsed"), rowSummary(snapshot));
        assertTrue(snapshot.findGroup("pc").collapsed);
        // Collapse does not hide apps from the group itself (the wheel uses them)
        assertEquals(3, snapshot.findGroup("pc").apps.size());
    }

    @Test
    public void searchMatchesEveryTokenAcrossGroups() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()).query("kart"));
        assertTrue(snapshot.queryActive);
        assertEquals(Arrays.asList("nintendo_gamecube", "nintendo_switch"), groupKeys(snapshot));
        assertEquals(2, snapshot.matchCount);
        assertEquals(10, snapshot.totalCount);

        LibrarySnapshot<TestApp> twoTokens = LibraryModel.build(input(newHostLibrary()).query("  KART   racer "));
        assertEquals(Collections.singletonList("nintendo_switch"), groupKeys(twoTokens));

        LibrarySnapshot<TestApp> none = LibraryModel.build(input(newHostLibrary()).query("kart missing"));
        assertTrue(none.groups.isEmpty());
        assertTrue(none.rows.isEmpty());
        assertEquals(0, none.matchCount);
    }

    @Test
    public void searchMatchesGroupLabel() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()).query("switch"));
        assertEquals(Collections.singletonList("nintendo_switch"), groupKeys(snapshot));

        LibrarySnapshot<TestApp> apps = LibraryModel.build(input(newHostLibrary()).query("apps"));
        assertEquals(Collections.singletonList("apps"), groupKeys(apps));
        assertEquals(2, apps.groups.get(0).apps.size());
    }

    @Test
    public void searchIgnoresAccentsCaseAndZeroWidth() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                new TestApp(1, "Café Example"),
                new TestApp(2, "Ex​ample Other"),
                new TestApp(3, "Unrelated")).query("CAFE"));
        assertEquals(1, snapshot.matchCount);
        assertEquals("Café Example", snapshot.groups.get(0).apps.get(0).name);

        LibrarySnapshot<TestApp> accentQuery = LibraryModel.build(input(
                new TestApp(1, "Cafe Example"),
                new TestApp(2, "Ex​ample Other")).query("café"));
        assertEquals(1, accentQuery.matchCount);

        LibrarySnapshot<TestApp> zeroWidth = LibraryModel.build(input(
                new TestApp(1, "Cafe Example"),
                new TestApp(2, "Ex​ample Other")).query("example other"));
        assertEquals(1, zeroWidth.matchCount);
        assertEquals(2, zeroWidth.groups.get(0).apps.get(0).app.id);
    }

    @Test
    public void searchIgnoresButKeepsCollapseState() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary())
                .collapsedKeys(Collections.singletonList("nintendo_switch"))
                .query("sample kart"));
        LibrarySnapshot.Group<TestApp> sw = snapshot.findGroup("nintendo_switch");
        assertFalse(sw.collapsed);
        assertEquals(Arrays.asList("# GameCube 1", "Sample Kart Classic",
                "# Nintendo Switch 1", "Sample Kart Racer"), rowSummary(snapshot));
        // allGroups keeps the saved state and every group regardless of the query
        assertEquals(7, snapshot.allGroups.size());
    }

    @Test
    public void blankQueryIsInactive() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary())
                .query(" ​ ").collapsedKeys(Collections.singletonList("pc")));
        assertFalse(snapshot.queryActive);
        assertTrue(snapshot.findGroup("pc").collapsed);
    }

    @Test
    public void overrideWinsOverHostAndHeuristics() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("id:1", "Nintendo Switch");  // host says PC
        overrides.put("id:6", "My Favorites");     // host says Apps
        overrides.put("id:10", "PC");              // heuristic says Apps
        overrides.put("id:999", "Ignored");        // no such app
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()).overrides(overrides));

        assertEquals(Arrays.asList("Example Game A", "Example Tool", "Lossless Scaling"),
                names(snapshot.findGroup("pc")));
        assertEquals(Arrays.asList("Example Game B", "Sample Kart Racer"),
                names(snapshot.findGroup("nintendo_switch")));
        LibrarySnapshot.Group<TestApp> custom = snapshot.findGroup("my favorites");
        assertEquals("My Favorites", custom.label);
        assertEquals(Collections.singletonList("Desktop"), names(custom));
        assertNull("Apps is empty now", snapshot.findGroup("apps"));
    }

    @Test
    public void overrideKeysUseUuidWhenPresent() {
        TestApp withUuid = new TestApp(5, "ABCD-1234", "Example Game", "", "");
        assertEquals("ABCD-1234", LibraryModel.appKey(withUuid));
        assertEquals("id:7", LibraryModel.appKey(new TestApp(7, "Example Game")));

        Map<String, String> overrides = new HashMap<>();
        overrides.put("ABCD-1234", "Example Collection");
        overrides.put("id:5", "Ignored Because The UUID Wins");
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(withUuid).overrides(overrides));
        assertEquals("Example Collection", snapshot.groups.get(0).label);
    }

    @Test
    public void blankOverrideMeansAutomatic() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("id:1", "  ");
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()).overrides(overrides));
        assertEquals(3, snapshot.findGroup("pc").apps.size());
    }

    @Test
    public void overrideOnOldHostDoesNotEnablePlatforms() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("id:1", "Nintendo Switch");
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(
                new TestApp(1, "Example Game"), new TestApp(2, "Another Example Game")).overrides(overrides));
        assertFalse(snapshot.hostHasPlatforms);
        assertEquals(Arrays.asList("games", "nintendo_switch"),
                Arrays.asList(snapshot.groups.get(0).key, snapshot.groups.get(1).key));
    }

    @Test
    public void rowsHaveStableDistinctIds() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary()));
        HashSet<Long> ids = new HashSet<>();
        for (LibrarySnapshot.Row row : snapshot.rows) {
            assertTrue("duplicate id", ids.add(row.getStableId()));
        }
        LibrarySnapshot<TestApp> again = LibraryModel.build(input(newHostLibrary()).query("sample"));
        LibrarySnapshot.AppRow<TestApp> before = snapshot.findApp("id:3");
        LibrarySnapshot.AppRow<TestApp> after = again.findApp("id:3");
        assertEquals(before.getStableId(), after.getStableId());
        assertTrue(before.sameContent(after));
        assertNotEquals(LibrarySnapshot.headerId("pc"), LibrarySnapshot.appId(0));
    }

    @Test
    public void runningChangeIsDetected() {
        TestApp[] apps = newHostLibrary();
        LibrarySnapshot<TestApp> before = LibraryModel.build(input(apps));
        apps[2].running = true;
        LibrarySnapshot<TestApp> after = LibraryModel.build(input(apps));
        LibrarySnapshot.AppRow<TestApp> oldRow = before.findApp("id:3");
        LibrarySnapshot.AppRow<TestApp> newRow = after.findApp("id:3");
        assertFalse(oldRow.sameContent(newRow));
        assertTrue(newRow.onlyRunningChanged(oldRow));
    }

    @Test
    public void navigationHelpers() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(input(newHostLibrary())
                .collapsedKeys(Collections.singletonList("nintendo_3ds")));
        // Rows: #PC, A, B, Tool, #GC, Classic, #3DS(collapsed), #Switch, Racer, ...
        assertEquals(1, snapshot.findGroupEntryPosition("pc"));
        assertEquals(6, snapshot.findGroupEntryPosition("nintendo_3ds"));
        assertEquals(-1, snapshot.findGroupEntryPosition("missing"));
        assertEquals(2, snapshot.findAppPosition("id:1"));
        assertEquals(-1, snapshot.findAppPosition("id:4"));
        assertEquals("nintendo_gamecube", snapshot.adjacentGroupKey("pc", 1));
        assertEquals("apps", snapshot.adjacentGroupKey("pc", -1));
        assertEquals("pc", snapshot.adjacentGroupKey("apps", 1));
        assertEquals("pc", snapshot.adjacentGroupKey("missing", 1));
        assertEquals(10, snapshot.flatApps().size());
    }

    @Test
    public void emptyLibrary() {
        LibrarySnapshot<TestApp> snapshot = LibraryModel.build(new LibraryModel.Input<TestApp>());
        assertTrue(snapshot.groups.isEmpty());
        assertTrue(snapshot.rows.isEmpty());
        assertNull(snapshot.adjacentGroupKey("pc", 1));
    }
}

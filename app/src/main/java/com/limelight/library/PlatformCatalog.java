package com.limelight.library;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a host platform id (a Playnite specification id) or platform name to a
 * library group: a stable key, a short display label and a sort rank.
 */
public final class PlatformCatalog {
    public static final String KEY_PC = "pc";
    public static final String KEY_APPS = "apps";
    public static final String KEY_GAMES = "games";
    public static final String KEY_RESULTS = "results";

    public static final String LABEL_PC = "PC";
    public static final String LABEL_APPS = "Apps";
    public static final String LABEL_GAMES = "Games";

    /** PC (and the "Games" group of old hosts) sort first. */
    public static final int RANK_FIRST = 0;
    /** Known systems, alphabetical by label. */
    public static final int RANK_SYSTEM = 1;
    /** Unknown platforms, "Emulated" and user made groups, alphabetical by label. */
    public static final int RANK_OTHER = 2;
    /** Apps sort last. */
    public static final int RANK_LAST = 3;

    public static final class Platform {
        public final String key;
        public final String label;
        public final int rank;

        // Normalized label plus the full and alternative names of the platform, for search
        private String searchText;

        Platform(String key, String label, int rank) {
            this.key = key;
            this.label = label;
            this.rank = rank;
            this.searchText = LibraryText.normalize(label);
        }

        void addSearchName(String name) {
            String normalized = LibraryText.normalize(name);
            if (!normalized.isEmpty() && !(" " + searchText + " ").contains(" " + normalized + " ")) {
                searchText = searchText.isEmpty() ? normalized : searchText + " " + normalized;
            }
        }

        /** Normalized text a search query is matched against, such as "gamecube nintendo gamecube". */
        public String getSearchText() {
            return searchText;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Platform)) {
                return false;
            }
            Platform other = (Platform) o;
            return key.equals(other.key) && label.equals(other.label) && rank == other.rank;
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + label.hashCode();
        }

        @Override
        public String toString() {
            return key + " (" + label + ")";
        }
    }

    public static final Platform PC = new Platform(KEY_PC, LABEL_PC, RANK_FIRST);
    public static final Platform APPS = new Platform(KEY_APPS, LABEL_APPS, RANK_LAST);
    public static final Platform GAMES = new Platform(KEY_GAMES, LABEL_GAMES, RANK_FIRST);

    // Specification id to known platform
    private static final Map<String, Platform> BY_ID = new HashMap<>();
    // Normalized name (full Playnite name, short label or alias) to known platform
    private static final Map<String, Platform> BY_NAME = new HashMap<>();

    private static void system(String specId, String label, String... names) {
        Platform platform = new Platform(specId, label, RANK_SYSTEM);
        BY_ID.put(specId, platform);
        BY_NAME.put(LibraryText.normalize(label), platform);
        for (String name : names) {
            BY_NAME.put(LibraryText.normalize(name), platform);
            platform.addSearchName(name);
        }
    }

    private static void alias(Platform platform, String... idsOrNames) {
        for (String value : idsOrNames) {
            BY_ID.put(value.toLowerCase(Locale.ROOT), platform);
            BY_NAME.put(LibraryText.normalize(value), platform);
            if (value.indexOf('_') < 0) {
                platform.addSearchName(value);
            }
        }
    }

    static {
        alias(PC, "pc", "pc_windows", "pc_dos", "pc_linux", "PC (Windows)", "PC (DOS)", "PC (Linux)",
                "Windows", "PC Windows");
        alias(APPS, "apps", "Applications", "Utilities", "Tools");
        alias(GAMES, "games");

        system("nintendo_switch", "Nintendo Switch", "Switch");
        system("nintendo_3ds", "Nintendo 3DS", "3DS");
        system("nintendo_ds", "Nintendo DS");
        system("nintendo_gamecube", "GameCube", "Nintendo GameCube");
        system("nintendo_wii", "Wii", "Nintendo Wii");
        system("nintendo_wiiu", "Wii U", "Nintendo Wii U");
        system("nintendo_64", "Nintendo 64", "N64");
        system("nintendo_nes", "NES", "Nintendo Entertainment System", "Famicom");
        system("nintendo_famicom_disk", "Famicom Disk System", "Nintendo Famicom Disk System");
        system("nintendo_super_nes", "SNES", "Super Nintendo Entertainment System", "Super Nintendo", "Super Famicom");
        system("nintendo_gameboy", "Game Boy", "Nintendo Game Boy");
        system("nintendo_gameboycolor", "Game Boy Color", "Nintendo Game Boy Color");
        system("nintendo_gameboyadvance", "Game Boy Advance", "Nintendo Game Boy Advance", "GBA");
        system("nintendo_virtualboy", "Virtual Boy", "Nintendo Virtual Boy");
        system("sony_playstation", "PlayStation", "Sony PlayStation", "PS1", "PSX");
        system("sony_playstation2", "PlayStation 2", "Sony PlayStation 2", "PS2");
        system("sony_playstation3", "PlayStation 3", "Sony PlayStation 3", "PS3");
        system("sony_playstation4", "PlayStation 4", "Sony PlayStation 4", "PS4");
        system("sony_playstation5", "PlayStation 5", "Sony PlayStation 5", "PS5");
        system("sony_psp", "PSP", "Sony PSP", "Sony PlayStation Portable", "PlayStation Portable");
        system("sony_vita", "PS Vita", "Sony PlayStation Vita", "PlayStation Vita");
        system("xbox", "Xbox", "Microsoft Xbox");
        system("xbox360", "Xbox 360", "Microsoft Xbox 360");
        system("xbox_one", "Xbox One", "Microsoft Xbox One");
        system("xbox_series", "Xbox Series", "Microsoft Xbox Series");
        system("sega_mastersystem", "Master System", "Sega Master System");
        system("sega_genesis", "Genesis", "Sega Genesis", "Sega Mega Drive", "Mega Drive", "Sega Genesis/Mega Drive");
        system("sega_cd", "Sega CD", "Sega Mega-CD");
        system("sega_32x", "32X", "Sega 32X");
        system("sega_saturn", "Saturn", "Sega Saturn");
        system("sega_dreamcast", "Dreamcast", "Sega Dreamcast");
        system("sega_gamegear", "Game Gear", "Sega Game Gear");
        system("nec_turbografx_16", "TurboGrafx-16", "NEC TurboGrafx 16", "PC Engine");
        system("nec_turbografx_cd", "TurboGrafx-CD", "NEC TurboGrafx-CD", "PC Engine CD");
        system("snk_neogeopocket", "Neo Geo Pocket", "SNK Neo Geo Pocket");
        system("snk_neogeopocket_color", "Neo Geo Pocket Color", "SNK Neo Geo Pocket Color");
        system("atari_2600", "Atari 2600");
        system("atari_7800", "Atari 7800");
        system("atari_jaguar", "Atari Jaguar");
        system("atari_lynx", "Atari Lynx");
        system("bandai_wonderswan", "WonderSwan", "Bandai WonderSwan");
        system("bandai_wonderswan_color", "WonderSwan Color", "Bandai WonderSwan Color");
        system("commodore_64", "Commodore 64", "C64");
        system("commodore_amiga", "Amiga", "Commodore Amiga");
        system("3do", "3DO", "3DO Interactive Multiplayer");
        system("arcade", "Arcade", "MAME");
    }

    private PlatformCatalog() {}

    /**
     * Resolves a host platform. The id wins when it is known; otherwise the
     * name is looked up; unknown values keep the host text as the label and use
     * its normalized text as the key. Returns null when both are empty.
     */
    public static Platform resolve(String platformId, String platformName) {
        String id = LibraryText.clean(platformId).toLowerCase(Locale.ROOT);
        String name = LibraryText.clean(platformName);

        if (!id.isEmpty()) {
            Platform known = BY_ID.get(id);
            if (known != null) {
                return known;
            }
        }

        if (!name.isEmpty()) {
            Platform known = BY_NAME.get(LibraryText.normalize(name));
            if (known != null) {
                return known;
            }
            return new Platform(otherKey(LibraryText.normalize(name)), name, RANK_OTHER);
        }

        if (!id.isEmpty()) {
            // An id we do not know and no name: show the id rather than nothing
            return new Platform(otherKey(id), LibraryText.clean(platformId), RANK_OTHER);
        }

        return null;
    }

    private static String otherKey(String key) {
        // Never let an unknown platform take over a reserved key
        return KEY_RESULTS.equals(key) ? "other:" + key : key;
    }

    /** Resolves a group label, as typed by the user or chosen from the group list. */
    public static Platform resolveLabel(String label) {
        return resolve(null, label);
    }

    /** True when the key is one of the reserved, non-system keys. */
    public static boolean isReservedKey(String key) {
        return KEY_APPS.equals(key) || KEY_GAMES.equals(key) || KEY_RESULTS.equals(key);
    }
}

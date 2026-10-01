package com.limelight.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure model of the app library: grouping by platform, search, sorting,
 * hidden apps and collapse. No Android dependencies.
 */
public final class LibraryModel {
    private LibraryModel() {}

    public static final class Input<T extends LibraryItem> {
        final List<T> apps = new ArrayList<>();
        final Set<Integer> hiddenIds = new HashSet<>();
        boolean showHidden;
        final Set<Integer> keepVisibleIds = new HashSet<>();
        String query = "";
        final Set<String> collapsedKeys = new HashSet<>();
        final Map<String, String> overrides = new HashMap<>();

        public Input<T> apps(Collection<? extends T> apps) {
            this.apps.clear();
            if (apps != null) {
                this.apps.addAll(apps);
            }
            return this;
        }

        public Input<T> hiddenIds(Collection<Integer> hiddenIds) {
            this.hiddenIds.clear();
            if (hiddenIds != null) {
                this.hiddenIds.addAll(hiddenIds);
            }
            return this;
        }

        public Input<T> showHidden(boolean showHidden) {
            this.showHidden = showHidden;
            return this;
        }

        /**
         * Hidden apps that stay listed (dimmed) anyway, such as apps hidden
         * during the current visit, so the focused card does not vanish.
         */
        public Input<T> keepVisibleIds(Collection<Integer> keepVisibleIds) {
            this.keepVisibleIds.clear();
            if (keepVisibleIds != null) {
                this.keepVisibleIds.addAll(keepVisibleIds);
            }
            return this;
        }

        public Input<T> query(String query) {
            this.query = query != null ? query : "";
            return this;
        }

        public Input<T> collapsedKeys(Collection<String> collapsedKeys) {
            this.collapsedKeys.clear();
            if (collapsedKeys != null) {
                this.collapsedKeys.addAll(collapsedKeys);
            }
            return this;
        }

        /** Per-device overrides: app key (see {@link #appKey}) to group label. */
        public Input<T> overrides(Map<String, String> overrides) {
            this.overrides.clear();
            if (overrides != null) {
                this.overrides.putAll(overrides);
            }
            return this;
        }
    }

    /** Device local identity of an app: its UUID when the host sends one, else its id. */
    public static String appKey(LibraryItem app) {
        String uuid = app.getAppUuid();
        if (uuid != null && !uuid.trim().isEmpty()) {
            return uuid.trim();
        }
        return "id:" + app.getAppId();
    }

    /** True when the host tags at least one app with a platform (host 1.1.0 or later). */
    public static boolean hostHasPlatforms(Collection<? extends LibraryItem> apps) {
        for (LibraryItem app : apps) {
            if (!LibraryText.clean(app.getPlatform()).isEmpty()
                    || !LibraryText.clean(app.getPlatformId()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The effective group of an app, highest priority first: device override,
     * host platform, utility heuristic, then "PC" on hosts that send platforms
     * or "Games" on hosts that do not.
     */
    public static PlatformCatalog.Platform groupOf(LibraryItem app, Map<String, String> overrides,
                                                   boolean hostHasPlatforms) {
        if (overrides != null) {
            String override = overrides.get(appKey(app));
            PlatformCatalog.Platform platform = PlatformCatalog.resolveLabel(override);
            if (platform != null) {
                return platform;
            }
        }

        PlatformCatalog.Platform hostPlatform = PlatformCatalog.resolve(app.getPlatformId(), app.getPlatform());
        if (hostPlatform != null) {
            return hostPlatform;
        }

        if (UtilityDetector.isUtility(app.getAppUuid(), app.getAppName())) {
            return PlatformCatalog.APPS;
        }

        return hostHasPlatforms ? PlatformCatalog.PC : PlatformCatalog.GAMES;
    }

    /** True when every query token appears in the app name or its group label. */
    static boolean matches(List<String> tokens, String name, String groupLabel) {
        if (tokens.isEmpty()) {
            return true;
        }
        String haystack = LibraryText.normalize(name) + " " + LibraryText.normalize(groupLabel);
        for (String token : tokens) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static final class Bucket<T extends LibraryItem> {
        final PlatformCatalog.Platform platform;
        final List<Entry<T>> entries = new ArrayList<>();

        Bucket(PlatformCatalog.Platform platform) {
            this.platform = platform;
        }
    }

    private static final class Entry<T extends LibraryItem> {
        final T app;
        final String sortKey;
        final boolean hidden;

        Entry(T app, boolean hidden) {
            this.app = app;
            this.sortKey = LibraryText.normalize(app.getAppName());
            this.hidden = hidden;
        }
    }

    private static final Comparator<Bucket<?>> GROUP_ORDER = new Comparator<Bucket<?>>() {
        @Override
        public int compare(Bucket<?> lhs, Bucket<?> rhs) {
            if (lhs.platform.rank != rhs.platform.rank) {
                return lhs.platform.rank - rhs.platform.rank;
            }
            int byLabel = LibraryText.normalize(lhs.platform.label).compareTo(LibraryText.normalize(rhs.platform.label));
            if (byLabel != 0) {
                return byLabel;
            }
            return lhs.platform.key.compareTo(rhs.platform.key);
        }
    };

    private static final Comparator<Entry<?>> APP_ORDER = new Comparator<Entry<?>>() {
        @Override
        public int compare(Entry<?> lhs, Entry<?> rhs) {
            int byKey = lhs.sortKey.compareTo(rhs.sortKey);
            if (byKey != 0) {
                return byKey;
            }
            String lName = String.valueOf(lhs.app.getAppName());
            String rName = String.valueOf(rhs.app.getAppName());
            int byName = lName.compareTo(rName);
            if (byName != 0) {
                return byName;
            }
            return Integer.compare(lhs.app.getAppId(), rhs.app.getAppId());
        }
    };

    public static <T extends LibraryItem> LibrarySnapshot<T> build(Input<T> input) {
        boolean hostHasPlatforms = hostHasPlatforms(input.apps);
        List<String> tokens = LibraryText.tokens(input.query);
        boolean queryActive = !tokens.isEmpty();

        // Bucket the apps by effective group. The first platform seen for a key
        // provides the label, so two spellings of one system share a group.
        Map<String, Bucket<T>> buckets = new LinkedHashMap<>();
        for (T app : input.apps) {
            boolean hidden = input.hiddenIds.contains(app.getAppId());
            if (hidden && !input.showHidden && !input.keepVisibleIds.contains(app.getAppId())) {
                continue;
            }

            PlatformCatalog.Platform platform = groupOf(app, input.overrides, hostHasPlatforms);
            Bucket<T> bucket = buckets.get(platform.key);
            if (bucket == null) {
                bucket = new Bucket<>(platform);
                buckets.put(platform.key, bucket);
            }
            bucket.entries.add(new Entry<>(app, hidden));
        }

        List<Bucket<T>> ordered = new ArrayList<>(buckets.values());
        Collections.sort(ordered, GROUP_ORDER);

        List<LibrarySnapshot.Group<T>> groups = new ArrayList<>();
        List<LibrarySnapshot.Group<T>> allGroups = new ArrayList<>();
        int totalCount = 0;
        int matchCount = 0;
        for (Bucket<T> bucket : ordered) {
            Collections.sort(bucket.entries, APP_ORDER);

            PlatformCatalog.Platform platform = bucket.platform;
            List<LibrarySnapshot.AppRow<T>> all = new ArrayList<>();
            List<LibrarySnapshot.AppRow<T>> matching = new ArrayList<>();
            for (Entry<T> entry : bucket.entries) {
                LibrarySnapshot.AppRow<T> row = new LibrarySnapshot.AppRow<>(entry.app, platform.key, platform.label, entry.hidden);
                all.add(row);
                if (matches(tokens, entry.app.getAppName(), platform.label)) {
                    matching.add(row);
                }
            }

            boolean collapsed = input.collapsedKeys.contains(platform.key);
            totalCount += all.size();
            matchCount += matching.size();

            allGroups.add(new LibrarySnapshot.Group<>(platform.key, platform.label, platform.rank,
                    all.size(), all, collapsed));
            if (!matching.isEmpty()) {
                // A query shows every matching group expanded without touching the saved state
                groups.add(new LibrarySnapshot.Group<>(platform.key, platform.label, platform.rank,
                        all.size(), matching, collapsed && !queryActive));
            }
        }

        return new LibrarySnapshot<>(input.query, queryActive, hostHasPlatforms,
                groups, allGroups, totalCount, matchCount);
    }
}

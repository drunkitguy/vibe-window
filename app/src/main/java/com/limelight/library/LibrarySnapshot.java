package com.limelight.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of {@link LibraryModel#build}: the ordered groups and the
 * flat row list (headers and apps) that sectioned layouts render.
 */
public final class LibrarySnapshot<T extends LibraryItem> {
    public static final int TYPE_HEADER = 0;
    public static final int TYPE_APP = 1;

    // Stable ids: apps use their 32 bit host id, headers set bit 32
    private static final long HEADER_ID_FLAG = 1L << 32;

    public static final class Group<T extends LibraryItem> {
        public final String key;
        public final String label;
        public final int rank;
        /** Apps in the group after hidden filtering, ignoring the query. */
        public final int totalCount;
        /** Apps in the group that match the query (all of them without a query), ignoring collapse. */
        public final List<AppRow<T>> apps;
        /** Effective collapse state: always false while a query is active. */
        public final boolean collapsed;

        Group(String key, String label, int rank, int totalCount, List<AppRow<T>> apps, boolean collapsed) {
            this.key = key;
            this.label = label;
            this.rank = rank;
            this.totalCount = totalCount;
            this.apps = Collections.unmodifiableList(apps);
            this.collapsed = collapsed;
        }
    }

    public abstract static class Row {
        public final String groupKey;

        Row(String groupKey) {
            this.groupKey = groupKey;
        }

        public abstract int getType();

        public abstract long getStableId();

        /** True when both rows show the same thing, used for change detection. */
        public abstract boolean sameContent(Row other);
    }

    public static final class HeaderRow extends Row {
        public final String label;
        public final int count;
        public final boolean collapsed;

        HeaderRow(String groupKey, String label, int count, boolean collapsed) {
            super(groupKey);
            this.label = label;
            this.count = count;
            this.collapsed = collapsed;
        }

        @Override
        public int getType() {
            return TYPE_HEADER;
        }

        @Override
        public long getStableId() {
            return headerId(groupKey);
        }

        @Override
        public boolean sameContent(Row other) {
            if (!(other instanceof HeaderRow)) {
                return false;
            }
            HeaderRow o = (HeaderRow) other;
            return groupKey.equals(o.groupKey) && label.equals(o.label)
                    && count == o.count && collapsed == o.collapsed;
        }
    }

    public static final class AppRow<T extends LibraryItem> extends Row {
        public final T app;
        public final String appKey;
        public final String groupLabel;
        // Captured at build time so changes are detectable even though apps are mutable
        public final String name;
        public final boolean hidden;
        public final boolean running;

        AppRow(T app, String groupKey, String groupLabel, boolean hidden) {
            super(groupKey);
            this.app = app;
            this.appKey = LibraryModel.appKey(app);
            this.groupLabel = groupLabel;
            this.name = app.getAppName();
            this.hidden = hidden;
            this.running = app.isRunning();
        }

        @Override
        public int getType() {
            return TYPE_APP;
        }

        @Override
        public long getStableId() {
            return appId(app.getAppId());
        }

        @Override
        public boolean sameContent(Row other) {
            if (!(other instanceof AppRow)) {
                return false;
            }
            AppRow<?> o = (AppRow<?>) other;
            return appKey.equals(o.appKey) && groupKey.equals(o.groupKey)
                    && String.valueOf(name).equals(String.valueOf(o.name))
                    && hidden == o.hidden && running == o.running;
        }

        /** True when only the running state differs from the other row. */
        public boolean onlyRunningChanged(Row other) {
            if (!(other instanceof AppRow)) {
                return false;
            }
            AppRow<?> o = (AppRow<?>) other;
            return appKey.equals(o.appKey) && groupKey.equals(o.groupKey)
                    && String.valueOf(name).equals(String.valueOf(o.name))
                    && hidden == o.hidden && running != o.running;
        }
    }

    public static long headerId(String groupKey) {
        return HEADER_ID_FLAG | (groupKey.hashCode() & 0xFFFFFFFFL);
    }

    public static long appId(int appId) {
        return appId & 0xFFFFFFFFL;
    }

    public final String query;
    public final boolean queryActive;
    public final boolean hostHasPlatforms;
    /** Groups with at least one app to show (after hidden filtering and the query). */
    public final List<Group<T>> groups;
    /** Groups after hidden filtering, ignoring the query. Used for "Move to group". */
    public final List<Group<T>> allGroups;
    public final List<Row> rows;
    /** Number of apps shown after hidden filtering, ignoring the query. */
    public final int totalCount;
    /** Number of apps that match the query (equal to totalCount without a query). */
    public final int matchCount;

    LibrarySnapshot(String query, boolean queryActive, boolean hostHasPlatforms,
                    List<Group<T>> groups, List<Group<T>> allGroups,
                    int totalCount, int matchCount) {
        this.query = query;
        this.queryActive = queryActive;
        this.hostHasPlatforms = hostHasPlatforms;
        this.groups = Collections.unmodifiableList(groups);
        this.allGroups = Collections.unmodifiableList(allGroups);
        this.totalCount = totalCount;
        this.matchCount = matchCount;

        List<Row> rows = new ArrayList<>();
        for (Group<T> group : groups) {
            rows.add(new HeaderRow(group.key, group.label, group.apps.size(), group.collapsed));
            if (!group.collapsed) {
                rows.addAll(group.apps);
            }
        }
        this.rows = Collections.unmodifiableList(rows);
    }

    public Group<T> findGroup(String key) {
        if (key == null) {
            return null;
        }
        for (Group<T> group : groups) {
            if (group.key.equals(key)) {
                return group;
            }
        }
        return null;
    }

    public int indexOfGroup(String key) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Row position of the app with the given key, or -1 when it is not shown. */
    public int findAppPosition(String appKey) {
        if (appKey == null) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row instanceof AppRow && ((AppRow<?>) row).appKey.equals(appKey)) {
                return i;
            }
        }
        return -1;
    }

    /** Row position of the header of a group, or -1. */
    public int findHeaderPosition(String groupKey) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row instanceof HeaderRow && row.groupKey.equals(groupKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The row to land on when jumping to a group: its first app when the group
     * is expanded, else its header. Returns -1 for an unknown group.
     */
    public int findGroupEntryPosition(String groupKey) {
        int header = findHeaderPosition(groupKey);
        if (header < 0) {
            return -1;
        }
        if (header + 1 < rows.size() && rows.get(header + 1) instanceof AppRow) {
            return header + 1;
        }
        return header;
    }

    /** The group key of the group before or after the given one, wrapping around. */
    public String adjacentGroupKey(String groupKey, int direction) {
        if (groups.isEmpty()) {
            return null;
        }
        int index = indexOfGroup(groupKey);
        if (index < 0) {
            return groups.get(direction >= 0 ? 0 : groups.size() - 1).key;
        }
        int size = groups.size();
        int next = ((index + (direction >= 0 ? 1 : -1)) % size + size) % size;
        return groups.get(next).key;
    }

    /** All matching apps, in group order. */
    public List<AppRow<T>> flatApps() {
        List<AppRow<T>> apps = new ArrayList<>();
        for (Group<T> group : groups) {
            apps.addAll(group.apps);
        }
        return apps;
    }

    /** The shown row for an app, or null. */
    public AppRow<T> findApp(String appKey) {
        for (Group<T> group : groups) {
            for (AppRow<T> row : group.apps) {
                if (row.appKey.equals(appKey)) {
                    return row;
                }
            }
        }
        return null;
    }
}

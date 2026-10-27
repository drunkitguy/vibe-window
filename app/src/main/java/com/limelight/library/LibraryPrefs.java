package com.limelight.library;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Library state kept on the device: the layout, and per host the collapsed
 * groups, the last search, the wheel system, the last focused app and the
 * "Move to group" overrides.
 */
public class LibraryPrefs {
    public static final String PREF_FILENAME = "LibraryPrefs";
    public static final String OVERRIDES_PREF_FILENAME = "AppGroupOverrides";

    public static final String LAYOUT_GRID = "grid";
    public static final String LAYOUT_WHEEL = "wheel";
    public static final String LAYOUT_SHELVES = "shelves";

    private static final String KEY_LAYOUT = "layout";
    private static final String KEY_COLLAPSED = "collapsed:";
    private static final String KEY_QUERY = "query:";
    private static final String KEY_WHEEL_GROUP = "wheelGroup:";
    private static final String KEY_LAST_FOCUS = "lastFocus:";

    private final SharedPreferences prefs;
    private final SharedPreferences overrides;
    private final String hostUuid;

    public LibraryPrefs(Context context, String hostUuid) {
        this.prefs = context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE);
        this.overrides = context.getSharedPreferences(OVERRIDES_PREF_FILENAME, Context.MODE_PRIVATE);
        this.hostUuid = hostUuid != null ? hostUuid : "";
    }

    public static String normalizeLayout(String layout) {
        if (LAYOUT_WHEEL.equals(layout) || LAYOUT_SHELVES.equals(layout)) {
            return layout;
        }
        return LAYOUT_GRID;
    }

    public String getLayout() {
        return normalizeLayout(prefs.getString(KEY_LAYOUT, LAYOUT_GRID));
    }

    public void setLayout(String layout) {
        prefs.edit().putString(KEY_LAYOUT, normalizeLayout(layout)).apply();
    }

    public Set<String> getCollapsedGroups() {
        // Never hand out or modify the set owned by SharedPreferences
        return new HashSet<>(prefs.getStringSet(KEY_COLLAPSED + hostUuid, new HashSet<String>()));
    }

    public void setCollapsedGroups(Set<String> keys) {
        prefs.edit().putStringSet(KEY_COLLAPSED + hostUuid, new HashSet<>(keys)).apply();
    }

    public String getQuery() {
        return prefs.getString(KEY_QUERY + hostUuid, "");
    }

    public void setQuery(String query) {
        prefs.edit().putString(KEY_QUERY + hostUuid, query != null ? query : "").apply();
    }

    public String getWheelGroup() {
        return prefs.getString(KEY_WHEEL_GROUP + hostUuid, null);
    }

    public void setWheelGroup(String groupKey) {
        prefs.edit().putString(KEY_WHEEL_GROUP + hostUuid, groupKey).apply();
    }

    public String getLastFocus() {
        return prefs.getString(KEY_LAST_FOCUS + hostUuid, null);
    }

    public void setLastFocus(String appKey) {
        prefs.edit().putString(KEY_LAST_FOCUS + hostUuid, appKey).apply();
    }

    /** App key to group label, for this host. */
    public Map<String, String> getGroupOverrides() {
        Map<String, String> result = new HashMap<>();
        String prefix = hostUuid + "|";
        for (Map.Entry<String, ?> entry : overrides.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String) {
                result.put(entry.getKey().substring(prefix.length()), (String) entry.getValue());
            }
        }
        return result;
    }

    /** Sets the group label of an app on this device, or clears it when the label is empty. */
    public void setGroupOverride(String appKey, String label) {
        String key = hostUuid + "|" + appKey;
        String cleaned = LibraryText.clean(label);
        if (cleaned.isEmpty()) {
            overrides.edit().remove(key).apply();
        } else {
            overrides.edit().putString(key, cleaned).apply();
        }
    }
}

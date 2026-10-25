package com.limelight.preferences;

/**
 * An activity that shows the settings fragment: the settings screen, or the PC list with
 * Settings beside it.
 */
public interface SettingsHost {
    /** Rebuilds the settings fragment, e.g. after entries that depend on other settings changed. */
    void reloadSettings();

    /** The language setting was changed (called after the new value is stored). */
    void onSettingsLanguageChanged();
}

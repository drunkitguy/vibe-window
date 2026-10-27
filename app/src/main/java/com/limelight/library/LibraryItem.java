package com.limelight.library;

/**
 * The view of an app that the library model needs. Implemented by
 * {@link com.limelight.AppView.AppObject}; kept free of Android types so the
 * model can be unit tested on the JVM.
 */
public interface LibraryItem {
    int getAppId();

    /** Host UUID of the app, or an empty string for hosts that do not send one. */
    String getAppUuid();

    String getAppName();

    /** Host provided platform display name, or an empty string. */
    String getPlatform();

    /** Host provided platform id (a Playnite specification id), or an empty string. */
    String getPlatformId();

    boolean isRunning();
}

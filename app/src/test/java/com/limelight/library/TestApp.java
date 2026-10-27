package com.limelight.library;

/** A plain {@link LibraryItem} for model tests. */
final class TestApp implements LibraryItem {
    final int id;
    final String uuid;
    final String name;
    final String platform;
    final String platformId;
    boolean running;

    TestApp(int id, String name) {
        this(id, "", name, "", "");
    }

    TestApp(int id, String uuid, String name, String platform, String platformId) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.platform = platform;
        this.platformId = platformId;
    }

    static TestApp onPlatform(int id, String name, String platform, String platformId) {
        return new TestApp(id, "", name, platform, platformId);
    }

    @Override
    public int getAppId() {
        return id;
    }

    @Override
    public String getAppUuid() {
        return uuid;
    }

    @Override
    public String getAppName() {
        return name;
    }

    @Override
    public String getPlatform() {
        return platform;
    }

    @Override
    public String getPlatformId() {
        return platformId;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

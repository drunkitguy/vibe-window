package com.limelight.binding.video;

/**
 * Frame pacing for the "Lowest latency (newest frame)" mode. Pure Java, no Android
 * dependencies, so it can be tested on the JVM. Every time is CLOCK_MONOTONIC nanoseconds
 * (System.nanoTime()), passed in by the caller.
 *
 * It runs in one of two regimes:
 * <ul>
 * <li><b>Frame timelines</b> (Android 13 and later, {@link #onFrameTimelines}): each vsync
 * reports every upcoming presentation the app can still target, as pairs of a queueing
 * deadline and an expected presentation time. A decoded frame is released at once for the
 * earliest presentation whose deadline is still at least {@link #DEADLINE_MARGIN_NS} away.
 * (The "preferred" timeline is not used: it budgets for an app that renders, so on a 120 Hz
 * panel it lies two to four refreshes ahead, which a decoded frame does not need.) When a
 * newer frame targets the same presentation as the previous one, the display shows only
 * the newer one and the older one is counted as dropped by pacing. When no timeline is
 * usable any more (the vsync callback is late), the frame is released for "now".</li>
 * <li><b>Vsync only</b> (older Android, {@link #onVsync}): the first frame decoded in a
 * refresh interval is released at once; later ones in the same interval wait in a single
 * slot (replacing older ones) and go out at the next vsync. Releasing the waiting frame
 * does not count as presenting in the new interval, so a single late frame never pushes
 * the following frames into the waiting slot.</li>
 * </ul>
 *
 * {@link #onDecodedFrame} is called on the renderer thread and the vsync methods on the
 * Choreographer thread. All state is guarded by one lock, and the {@link Releaser} is called
 * while it is held, so each buffer index is released exactly once and rendered frames are
 * released in decode order.
 */
public final class NewestFramePacer {

    /** A timeline is usable while its deadline is at least this far away. */
    public static final long DEADLINE_MARGIN_NS = 1_000_000L;

    /** Returned by {@link #getLastTimelineIndex()} for a frame released for "now". */
    public static final int NO_TIMELINE = -1;

    /** Hands a decoded output buffer back to the codec. */
    public interface Releaser {
        /**
         * @param index the output buffer index
         * @param render true to render it, false to drop it
         * @param presentTimeNs when rendering, the desired presentation time
         *                      (CLOCK_MONOTONIC); unused when dropping
         */
        void release(int index, boolean render, long presentTimeNs);
    }

    private final Object lock = new Object();
    private final Releaser releaser;

    private int pendingIndex = -1;
    private long pendingPtsUs;

    // Vsync-only regime
    private boolean presentedThisVsync;

    // Frame timeline regime, used once the first timelines arrive
    private boolean timelinesKnown;
    private long[] deadlinesNs = new long[0];
    private long[] presentsNs = new long[0];
    private long lastTimelinePresentNs = Long.MIN_VALUE;
    private int lastTimelineIndex = NO_TIMELINE;
    private long lastLeadNs;

    private long presented;
    private long droppedByPacing;
    private long releasedImmediately;
    private long releasedAtVsync;
    private long releasedWithoutTimeline;

    public NewestFramePacer(Releaser releaser) {
        this.releaser = releaser;
    }

    /** A new decoded output buffer is ready at {@code nowNs}. */
    public void onDecodedFrame(int index, long ptsUs, long nowNs) {
        synchronized (lock) {
            // A waiting frame is always older than this one
            dropPendingLocked();

            if (timelinesKnown) {
                releaseForTimelineLocked(index, nowNs);
                return;
            }

            if (!presentedThisVsync) {
                presentedThisVsync = true;
                releasedImmediately++;
                presented++;
                releaser.release(index, true, nowNs);
            }
            else {
                pendingIndex = index;
                pendingPtsUs = ptsUs;
            }
        }
    }

    private void releaseForTimelineLocked(int index, long nowNs) {
        int chosen = NO_TIMELINE;
        for (int i = 0; i < deadlinesNs.length; i++) {
            if (deadlinesNs[i] - nowNs >= DEADLINE_MARGIN_NS &&
                    (chosen == NO_TIMELINE || deadlinesNs[i] < deadlinesNs[chosen])) {
                chosen = i;
            }
        }

        long presentNs;
        if (chosen == NO_TIMELINE) {
            presentNs = nowNs;
            releasedWithoutTimeline++;
        }
        else {
            presentNs = presentsNs[chosen];
            if (presentNs == lastTimelinePresentNs) {
                // The previous frame was released for the same presentation; the display keeps
                // only the newest buffer due at that time, so the previous one is dropped
                droppedByPacing++;
                presented--;
            }
            lastTimelinePresentNs = presentNs;
        }

        lastTimelineIndex = chosen;
        lastLeadNs = presentNs - nowNs;
        releasedImmediately++;
        presented++;
        releaser.release(index, true, presentNs);
    }

    /**
     * A vsync without frame timeline data (before Android 13). Releases the waiting frame,
     * if any, and arms the immediate path for the new interval.
     */
    public void onVsync(long nowNs) {
        synchronized (lock) {
            releasePendingAtVsyncLocked(nowNs);
            presentedThisVsync = false;
        }
    }

    /**
     * A vsync with frame timelines (Android 13 and later): the queueing deadline and expected
     * presentation time of every presentation the app can target, in any order. The arrays
     * are copied.
     */
    public void onFrameTimelines(long[] deadlinesNs, long[] expectedPresentsNs, long nowNs) {
        if (deadlinesNs.length != expectedPresentsNs.length) {
            throw new IllegalArgumentException("Timeline arrays differ in length");
        }
        synchronized (lock) {
            timelinesKnown = true;
            this.deadlinesNs = deadlinesNs.clone();
            this.presentsNs = expectedPresentsNs.clone();
            // Only a frame held before the first timelines arrived can be waiting here
            releasePendingAtVsyncLocked(nowNs);
        }
    }

    /**
     * Forgets the waiting frame without releasing it, for a codec flush, restart or stop
     * (all of which invalidate its index), and arms the immediate path.
     */
    public void clear() {
        synchronized (lock) {
            pendingIndex = -1;
            pendingPtsUs = 0;
            presentedThisVsync = false;
            lastTimelinePresentNs = Long.MIN_VALUE;
        }
    }

    private void releasePendingAtVsyncLocked(long presentNs) {
        if (pendingIndex >= 0) {
            int index = pendingIndex;
            pendingIndex = -1;
            releasedAtVsync++;
            presented++;
            releaser.release(index, true, presentNs);
        }
    }

    private void dropPendingLocked() {
        if (pendingIndex >= 0) {
            int index = pendingIndex;
            pendingIndex = -1;
            droppedByPacing++;
            releaser.release(index, false, 0);
        }
    }

    /** Frames dropped by pacing as a percentage of the frames received, 0 when none were. */
    public static float dropPercent(long dropped, long received) {
        return received > 0 ? (float) dropped / received * 100 : 0f;
    }

    public boolean hasPending() {
        synchronized (lock) {
            return pendingIndex >= 0;
        }
    }

    /** The presentation time of the waiting frame, or -1 when none is waiting. */
    public long getPendingPtsUs() {
        synchronized (lock) {
            return pendingIndex >= 0 ? pendingPtsUs : -1;
        }
    }

    /** Whether the frame timeline regime is in use. */
    public boolean usesFrameTimeline() {
        synchronized (lock) {
            return timelinesKnown;
        }
    }

    /** The timeline index chosen for the last frame, or {@link #NO_TIMELINE}. */
    public int getLastTimelineIndex() {
        synchronized (lock) {
            return lastTimelineIndex;
        }
    }

    /** Requested presentation time minus release time of the last timeline-regime frame. */
    public long getLastLeadNs() {
        synchronized (lock) {
            return lastLeadNs;
        }
    }

    /** Frames rendered, minus those the display drops for a newer one at the same time. */
    public long getPresented() {
        synchronized (lock) {
            return presented;
        }
    }

    public long getDroppedByPacing() {
        synchronized (lock) {
            return droppedByPacing;
        }
    }

    public long getReleasedImmediately() {
        synchronized (lock) {
            return releasedImmediately;
        }
    }

    public long getReleasedAtVsync() {
        synchronized (lock) {
            return releasedAtVsync;
        }
    }

    /** Timeline-regime frames released for "now" because no timeline was usable. */
    public long getReleasedWithoutTimeline() {
        synchronized (lock) {
            return releasedWithoutTimeline;
        }
    }
}

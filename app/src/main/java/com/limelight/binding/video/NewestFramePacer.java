package com.limelight.binding.video;

/**
 * Frame pacing for the "Lowest latency (newest frame)" mode. Pure Java, no Android
 * dependencies, so it can be tested on the JVM. Every time is CLOCK_MONOTONIC nanoseconds
 * (System.nanoTime()), passed in by the caller.
 *
 * It runs in one of two regimes:
 * <ul>
 * <li><b>Frame timeline</b> (Android 13 and later, {@link #onFrameTimeline}): each vsync
 * reports the deadline for the next presentation and its expected presentation time. A
 * frame decoded before that deadline is released at once for that presentation time. A
 * frame decoded after it can no longer make that presentation, so it waits for the next
 * vsync; a newer frame replaces it (the older one is dropped and counted).</li>
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

    // Frame timeline regime, used once the first timeline arrives
    private boolean timelineKnown;
    private long deadlineNs;
    private long expectedPresentNs;

    private long presented;
    private long droppedByPacing;
    private long releasedImmediately;
    private long releasedAtVsync;

    public NewestFramePacer(Releaser releaser) {
        this.releaser = releaser;
    }

    /** A new decoded output buffer is ready at {@code nowNs}. */
    public void onDecodedFrame(int index, long ptsUs, long nowNs) {
        synchronized (lock) {
            boolean immediate;
            long presentNs;
            if (timelineKnown) {
                immediate = nowNs < deadlineNs;
                presentNs = expectedPresentNs;
            }
            else {
                immediate = !presentedThisVsync;
                presentNs = nowNs;
            }

            // A waiting frame is always older than this one
            dropPendingLocked();

            if (immediate) {
                presentedThisVsync = true;
                releasedImmediately++;
                presented++;
                releaser.release(index, true, presentNs);
            }
            else {
                pendingIndex = index;
                pendingPtsUs = ptsUs;
            }
        }
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
     * A vsync with the preferred frame timeline (Android 13 and later): frames decoded
     * before {@code deadlineNs} are released for {@code expectedPresentNs}. The waiting
     * frame, if any, is released now for that presentation.
     */
    public void onFrameTimeline(long deadlineNs, long expectedPresentNs, long nowNs) {
        synchronized (lock) {
            timelineKnown = true;
            this.deadlineNs = deadlineNs;
            this.expectedPresentNs = expectedPresentNs;
            releasePendingAtVsyncLocked(expectedPresentNs);
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
            return timelineKnown;
        }
    }

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
}

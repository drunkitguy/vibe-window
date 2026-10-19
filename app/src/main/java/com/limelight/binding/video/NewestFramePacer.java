package com.limelight.binding.video;

/**
 * Frame pacing for the "Lowest latency (newest frame)" mode. Pure Java, no Android
 * dependencies, so it can be tested on the JVM.
 *
 * At most one decoded frame is presented per display refresh, and it is always the newest
 * one:
 * <ul>
 * <li>A frame that arrives while nothing has been presented since the last vsync is released
 * for rendering at once, so it makes the next latch.</li>
 * <li>Otherwise it waits in a single pending slot. A frame already waiting there is released
 * without rendering (dropped and counted), so the slot always holds the newest frame.</li>
 * <li>At each vsync the pending frame, if any, is released for rendering. If there is none,
 * the immediate path is armed for the coming interval.</li>
 * </ul>
 *
 * {@link #onDecodedFrame} is called on the renderer thread and {@link #onVsync} on the
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
         */
        void release(int index, boolean render);
    }

    private final Object lock = new Object();
    private final Releaser releaser;

    private int pendingIndex = -1;
    private long pendingPtsUs;
    private boolean presentedThisVsync;

    private long presented;
    private long droppedByPacing;
    private long releasedImmediately;
    private long releasedAtVsync;

    public NewestFramePacer(Releaser releaser) {
        this.releaser = releaser;
    }

    /** A new decoded output buffer is ready. */
    public void onDecodedFrame(int index, long ptsUs) {
        synchronized (lock) {
            if (!presentedThisVsync) {
                // Nothing pending can exist here (a pending frame implies a presented one),
                // but never leak a buffer if that ever changes.
                dropPendingLocked();
                presentedThisVsync = true;
                releasedImmediately++;
                presented++;
                releaser.release(index, true);
                return;
            }

            dropPendingLocked();
            pendingIndex = index;
            pendingPtsUs = ptsUs;
        }
    }

    /** A display refresh started. */
    public void onVsync(long frameTimeNanos) {
        synchronized (lock) {
            if (pendingIndex >= 0) {
                int index = pendingIndex;
                pendingIndex = -1;
                presentedThisVsync = true;
                releasedAtVsync++;
                presented++;
                releaser.release(index, true);
            }
            else {
                presentedThisVsync = false;
            }
        }
    }

    /**
     * Forgets the pending frame without releasing it, for a codec flush, restart or stop
     * (all of which invalidate its index), and arms the immediate path.
     */
    public void clear() {
        synchronized (lock) {
            pendingIndex = -1;
            pendingPtsUs = 0;
            presentedThisVsync = false;
        }
    }

    private void dropPendingLocked() {
        if (pendingIndex >= 0) {
            int index = pendingIndex;
            pendingIndex = -1;
            droppedByPacing++;
            releaser.release(index, false);
        }
    }

    public boolean hasPending() {
        synchronized (lock) {
            return pendingIndex >= 0;
        }
    }

    /** The presentation time of the pending frame, or -1 when none is pending. */
    public long getPendingPtsUs() {
        synchronized (lock) {
            return pendingIndex >= 0 ? pendingPtsUs : -1;
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

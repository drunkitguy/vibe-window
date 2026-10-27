package com.limelight.binding.video;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures how often a frame scheduled for a frame timeline was actually shown later than
 * the presentation it asked for, so the newest-frame pacer's deadline margin can be tuned on
 * a device. Pure Java and thread-safe: requests are recorded on the renderer or Choreographer
 * thread, render times arrive on the codec's frame-rendered thread.
 */
public final class PresentLatenessTracker {
    /**
     * A frame counts as late when it was shown more than this after the requested
     * presentation, which means it missed the refresh it targeted (a quarter of a 120 Hz
     * refresh absorbs timestamp jitter).
     */
    public static final long LATE_TOLERANCE_NS = 2_000_000L;

    // Frames the display skips for a newer one never report a render time; keep the table small
    static final int MAX_PENDING = 64;

    private final Map<Long, Long> requestedByPts = new LinkedHashMap<Long, Long>(MAX_PENDING * 2, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_PENDING;
        }
    };

    private long measured;
    private long late;

    /** A frame with this presentation timestamp was released for {@code presentTimeNs}. */
    public synchronized void onScheduled(long ptsUs, long presentTimeNs) {
        requestedByPts.put(ptsUs, presentTimeNs);
    }

    /** The codec reports that the frame with this timestamp was shown at {@code renderTimeNs}. */
    public synchronized void onRendered(long ptsUs, long renderTimeNs) {
        Long requested = requestedByPts.remove(ptsUs);
        if (requested == null) {
            return;
        }
        measured++;
        if (renderTimeNs - requested > LATE_TOLERANCE_NS) {
            late++;
        }
    }

    public synchronized void clear() {
        requestedByPts.clear();
    }

    /** Frames whose render time could be compared with their requested presentation. */
    public synchronized long getMeasured() {
        return measured;
    }

    public synchronized long getLate() {
        return late;
    }

    /** Late frames as a percentage of measured ones, 0 when none were measured. */
    public synchronized float getLatePercent() {
        return measured > 0 ? (float) late / measured * 100 : 0f;
    }
}

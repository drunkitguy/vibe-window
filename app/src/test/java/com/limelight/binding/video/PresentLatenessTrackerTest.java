package com.limelight.binding.video;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PresentLatenessTrackerTest {

    @Test
    public void countsFramesShownAfterTheRequestedPresentation() {
        PresentLatenessTracker tracker = new PresentLatenessTracker();
        tracker.onScheduled(1, 100_000_000L);
        tracker.onScheduled(2, 108_333_333L);
        tracker.onScheduled(3, 116_666_666L);

        // On time, within the tolerance, and one refresh late
        tracker.onRendered(1, 100_100_000L);
        tracker.onRendered(2, 108_333_333L + PresentLatenessTracker.LATE_TOLERANCE_NS);
        tracker.onRendered(3, 125_000_000L);

        assertEquals(3, tracker.getMeasured());
        assertEquals(1, tracker.getLate());
        assertEquals(100f / 3, tracker.getLatePercent(), 1e-4f);
    }

    @Test
    public void ignoresFramesItDidNotSchedule() {
        PresentLatenessTracker tracker = new PresentLatenessTracker();
        tracker.onRendered(7, 1_000_000_000L);
        tracker.onScheduled(8, 100L);
        tracker.onRendered(8, 100L);
        tracker.onRendered(8, 999_999_999L); // reported twice: counted once
        assertEquals(1, tracker.getMeasured());
        assertEquals(0, tracker.getLate());
        assertEquals(0f, new PresentLatenessTracker().getLatePercent(), 0f);
    }

    @Test
    public void forgetsOldRequestsThatNeverRendered() {
        PresentLatenessTracker tracker = new PresentLatenessTracker();
        for (int i = 0; i <= PresentLatenessTracker.MAX_PENDING; i++) {
            tracker.onScheduled(i, 0);
        }
        tracker.onRendered(0, 0); // evicted
        tracker.onRendered(PresentLatenessTracker.MAX_PENDING, 0);
        assertEquals(1, tracker.getMeasured());

        tracker.onScheduled(1000, 0);
        tracker.clear();
        tracker.onRendered(1000, 0);
        assertEquals(1, tracker.getMeasured());
    }

    @Test
    public void pacerReportsOnlyTimelineScheduledFrames() {
        final List<String> scheduled = new ArrayList<>();
        NewestFramePacer pacer = new NewestFramePacer((index, render, presentNs) -> { });
        pacer.setScheduleListener((ptsUs, presentNs) -> scheduled.add(ptsUs + "@" + presentNs));

        // Vsync-only regime: nothing is scheduled for a timeline
        pacer.onVsync(0);
        pacer.onDecodedFrame(1, 11, 100);
        assertEquals(0, scheduled.size());

        pacer.onFrameTimelines(new long[] {5_000_000L}, new long[] {12_000_000L}, 0);
        pacer.onDecodedFrame(2, 22, 1_000_000L);
        // No usable timeline: released for now, not reported
        pacer.onDecodedFrame(3, 33, 4_500_000L);
        assertEquals(1, scheduled.size());
        assertEquals("22@12000000", scheduled.get(0));
    }
}

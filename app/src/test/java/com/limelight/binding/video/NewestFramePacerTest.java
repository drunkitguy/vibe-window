package com.limelight.binding.video;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NewestFramePacerTest {

    private static final long PERIOD_NS = 8_333_333L; // 120 Hz

    /** Records every release in order, with the requested presentation time. */
    private static final class RecordingReleaser implements NewestFramePacer.Releaser {
        final List<String> calls = new ArrayList<>();
        final List<Long> presentTimes = new ArrayList<>();

        @Override
        public void release(int index, boolean render, long presentTimeNs) {
            calls.add((render ? "render " : "drop ") + index);
            presentTimes.add(render ? presentTimeNs : -1L);
        }

        String last() {
            return calls.get(calls.size() - 1);
        }

        long lastPresent() {
            return presentTimes.get(presentTimes.size() - 1);
        }
    }

    private RecordingReleaser releaser;
    private NewestFramePacer pacer;
    private long now;

    @Before
    public void setUp() {
        releaser = new RecordingReleaser();
        pacer = new NewestFramePacer(releaser);
        now = 1_000_000_000L;
    }

    private void vsync() {
        now += PERIOD_NS;
        pacer.onVsync(now);
    }

    private void frame(int index) {
        now += 100_000L;
        pacer.onDecodedFrame(index, index, now);
    }

    private static List<String> calls(String... expected) {
        return Arrays.asList(expected);
    }

    // ---- Vsync-only regime (before Android 13) ----

    @Test
    public void oneFramePerIntervalIsAlwaysReleasedImmediately() {
        vsync();
        for (int i = 0; i < 100; i++) {
            frame(i);
            assertEquals("render " + i, releaser.last());
            assertEquals(now, releaser.lastPresent());
            vsync();
        }
        assertEquals(100, pacer.getReleasedImmediately());
        assertEquals(0, pacer.getReleasedAtVsync());
        assertEquals(0, pacer.getDroppedByPacing());
        assertEquals(100, pacer.getPresented());
        assertFalse(pacer.hasPending());
        assertFalse(pacer.usesFrameTimeline());
    }

    @Test
    public void secondFrameInAnIntervalIsHeldUntilVsync() {
        vsync();
        frame(1);
        frame(2);
        assertEquals(calls("render 1"), releaser.calls);
        assertTrue(pacer.hasPending());

        vsync();
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertEquals(now, releaser.lastPresent());
        assertEquals(1, pacer.getReleasedImmediately());
        assertEquals(1, pacer.getReleasedAtVsync());
        assertFalse(pacer.hasPending());
    }

    @Test
    public void middleOfThreeFramesInAnIntervalIsDropped() {
        vsync();
        frame(1);
        frame(2);
        frame(3);
        assertEquals(calls("render 1", "drop 2"), releaser.calls);
        assertEquals(1, pacer.getDroppedByPacing());

        vsync();
        assertEquals(calls("render 1", "drop 2", "render 3"), releaser.calls);
        assertEquals(2, pacer.getPresented());
    }

    @Test
    public void aVsyncReleaseDoesNotHoldTheNextFrame() {
        vsync();
        frame(1);
        frame(2);
        // The held frame goes out at this vsync, and the interval stays armed
        vsync();
        frame(3);
        assertEquals(calls("render 1", "render 2", "render 3"), releaser.calls);
        assertFalse(pacer.hasPending());
        assertEquals(2, pacer.getReleasedImmediately());
        assertEquals(1, pacer.getReleasedAtVsync());
    }

    @Test
    public void framesBeforeTheFirstVsyncAreHandledLikeAnyInterval() {
        frame(1);
        frame(2);
        assertEquals(calls("render 1"), releaser.calls);
        vsync();
        assertEquals(calls("render 1", "render 2"), releaser.calls);
    }

    @Test
    public void clearForgetsThePendingFrameWithoutReleasingIt() {
        vsync();
        frame(1);
        frame(2);
        pacer.clear();
        assertFalse(pacer.hasPending());

        // Nothing stale comes out at the next vsync
        vsync();
        assertEquals(calls("render 1"), releaser.calls);

        // And the next frame after a clear is released at once, even mid-interval
        frame(10);
        pacer.clear();
        frame(11);
        assertEquals(calls("render 1", "render 10", "render 11"), releaser.calls);
    }

    @Test
    public void pendingSlotAlwaysHoldsTheNewestFrame() {
        vsync();
        for (int i = 0; i < 10; i++) {
            frame(i);
        }
        vsync();
        assertEquals("render 9", releaser.last());
        assertEquals(8, pacer.getDroppedByPacing());
        assertEquals(10, releaser.calls.size());
    }

    /**
     * The stream runs at the panel rate with a slow clock drift. One interval gets two frames
     * because of jitter. Only that frame may wait: every later frame must go out at once,
     * instead of the stream settling into a hold-every-frame state.
     */
    @Test
    public void driftAfterAHeldFrameStaysOnTheImmediatePath() {
        final double framePeriodNs = PERIOD_NS * (1 - 50e-6); // host clock 50 ppm fast
        long vsyncAt = 0;
        double nextFrameAt = PERIOD_NS * 0.5;
        int index = 0;
        boolean jittered = false;
        while (index < 5000) {
            if (nextFrameAt < vsyncAt + PERIOD_NS) {
                long t = (long) nextFrameAt;
                pacer.onDecodedFrame(index++, 0, t);
                nextFrameAt += framePeriodNs;
                if (!jittered && index == 100) {
                    // One late frame followed by one on time: both land in one interval
                    jittered = true;
                    pacer.onDecodedFrame(index++, 0, t + 1000);
                }
            }
            else {
                vsyncAt += PERIOD_NS;
                pacer.onVsync(vsyncAt);
            }
        }
        pacer.onVsync(vsyncAt + PERIOD_NS);
        assertEquals(1, pacer.getReleasedAtVsync());
        assertEquals(index - 1, pacer.getReleasedImmediately());
        assertEquals(0, pacer.getDroppedByPacing());
    }

    // ---- Frame timeline regime (Android 13 and later) ----

    // As on a 120 Hz panel: the platform budgets an app work duration longer than one refresh,
    // so its preferred timeline (index 1 here) has its deadline 10 ms after the vsync, while
    // timeline 0 can still be made right after the vsync.
    private static final long APP_DURATION_NS = 10_000_000L;
    private static final long SF_DURATION_NS = 6_000_000L;
    private static final int TIMELINES = 5;
    private static final int PREFERRED = 1;

    private static long deadline(long vsyncNs, int k) {
        return vsyncNs + APP_DURATION_NS + (k - PREFERRED) * PERIOD_NS;
    }

    private static long present(long vsyncNs, int k) {
        return deadline(vsyncNs, k) + SF_DURATION_NS;
    }

    private void timelines(long vsyncNs) {
        long[] deadlines = new long[TIMELINES];
        long[] presents = new long[TIMELINES];
        // Reverse order: the pacer must not depend on the array order
        for (int k = 0; k < TIMELINES; k++) {
            deadlines[TIMELINES - 1 - k] = deadline(vsyncNs, k);
            presents[TIMELINES - 1 - k] = present(vsyncNs, k);
        }
        pacer.onFrameTimelines(deadlines, presents, vsyncNs);
    }

    /** The earliest timeline k whose deadline is at least the margin after t, or -1. */
    private static int earliestValid(long vsyncNs, long t) {
        for (int k = 0; k < TIMELINES; k++) {
            if (deadline(vsyncNs, k) - t >= NewestFramePacer.DEADLINE_MARGIN_NS) {
                return k;
            }
        }
        return -1;
    }

    @Test
    public void earliestUsableTimelineIsChosenNotThePreferredOne() {
        timelines(0);
        assertTrue(pacer.usesFrameTimeline());
        assertTrue(deadline(0, PREFERRED) > PERIOD_NS);

        // Timeline 0's deadline (1.67 ms after the vsync) is still 1.47 ms away
        pacer.onDecodedFrame(1, 0, 200_000L);
        assertEquals("render 1", releaser.last());
        assertEquals(present(0, 0), releaser.lastPresent());
        assertEquals(TIMELINES - 1, pacer.getLastTimelineIndex()); // array is reversed
        assertEquals(present(0, 0) - 200_000L, pacer.getLastLeadNs());

        // Less than the margin before timeline 0's deadline: the next timeline
        pacer.onDecodedFrame(2, 0, 1_000_000L);
        assertEquals("render 2", releaser.last());
        assertEquals(present(0, 1), releaser.lastPresent());
        assertFalse(pacer.hasPending());
        assertEquals(0, pacer.getDroppedByPacing());
    }

    @Test
    public void aNewerFrameForTheSamePresentationDropsTheOlderOne() {
        timelines(0);
        pacer.onDecodedFrame(1, 0, 2_000_000L);
        pacer.onDecodedFrame(2, 0, 3_000_000L);
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertEquals(present(0, 1), releaser.presentTimes.get(0).longValue());
        assertEquals(present(0, 1), releaser.lastPresent());
        assertEquals(1, pacer.getDroppedByPacing());
        assertEquals(1, pacer.getPresented());
        assertEquals(2, pacer.getReleasedImmediately());

        // After a clear (codec flush) the same presentation is not counted again
        pacer.clear();
        pacer.onDecodedFrame(3, 0, 4_000_000L);
        assertEquals(1, pacer.getDroppedByPacing());
    }

    @Test
    public void noUsableTimelineReleasesForNow() {
        timelines(0);
        long late = deadline(0, TIMELINES - 1);
        pacer.onDecodedFrame(1, 0, late);
        assertEquals("render 1", releaser.last());
        assertEquals(late, releaser.lastPresent());
        assertEquals(NewestFramePacer.NO_TIMELINE, pacer.getLastTimelineIndex());
        assertEquals(1, pacer.getReleasedWithoutTimeline());
        assertFalse(pacer.hasPending());
    }

    @Test
    public void aFrameHeldBeforeTheFirstTimelinesGoesOutWithThem() {
        vsync();
        frame(1);
        frame(2);
        assertTrue(pacer.hasPending());
        timelines(now);
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertFalse(pacer.hasPending());
    }

    /**
     * Frames at the panel rate with clock drift sweep through every phase of the refresh
     * interval. Every frame goes out at once for the earliest presentation it can still make,
     * never for the preferred one, nothing waits and nothing is dropped.
     */
    @Test
    public void timelineDriftAlwaysTargetsTheEarliestPresentation() {
        final double framePeriodNs = PERIOD_NS * (1 + 200e-6); // host clock 200 ppm slow
        long vsyncAt = 0;
        timelines(vsyncAt);
        double nextFrameAt = PERIOD_NS * 0.1;
        int frames = 0;
        long maxLeadNs = 0;
        int earlierThanPreferred = 0;
        while (frames < 20000) {
            if (nextFrameAt < vsyncAt + PERIOD_NS) {
                long t = (long) nextFrameAt;
                int k = earliestValid(vsyncAt, t);
                pacer.onDecodedFrame(frames, 0, t);
                assertEquals("render " + frames, releaser.last());
                assertEquals(present(vsyncAt, k), releaser.lastPresent());
                assertFalse(pacer.hasPending());
                maxLeadNs = Math.max(maxLeadNs, releaser.lastPresent() - t);
                // Never later than the platform's preferred timeline, often earlier
                assertTrue(releaser.lastPresent() <= present(vsyncAt, PREFERRED));
                if (releaser.lastPresent() < present(vsyncAt, PREFERRED)) {
                    earlierThanPreferred++;
                }
                frames++;
                nextFrameAt += framePeriodNs;
            }
            else {
                vsyncAt += PERIOD_NS;
                timelines(vsyncAt);
            }
        }
        assertEquals(0, pacer.getDroppedByPacing());
        assertEquals(0, pacer.getReleasedAtVsync());
        assertEquals(0, pacer.getReleasedWithoutTimeline());
        assertEquals(frames, pacer.getReleasedImmediately());
        // At most one refresh plus the compositor's share, far below the preferred timeline
        assertTrue("max lead " + maxLeadNs,
                maxLeadNs <= PERIOD_NS + SF_DURATION_NS + NewestFramePacer.DEADLINE_MARGIN_NS);
        assertTrue("earlier " + earlierThanPreferred, earlierThanPreferred > frames / 20);
    }

    @Test
    public void dropPercentIsRelativeToReceivedFrames() {
        assertEquals(0f, NewestFramePacer.dropPercent(0, 0), 0f);
        assertEquals(0f, NewestFramePacer.dropPercent(5, 0), 0f);
        assertEquals(0.5f, NewestFramePacer.dropPercent(1, 200), 1e-6f);
        assertEquals(100f, NewestFramePacer.dropPercent(240, 240), 1e-6f);
    }

    // ---- Concurrency ----

    @Test
    public void everyIndexIsReleasedExactlyOnceUnderTwoThreads() throws Exception {
        for (boolean useTimeline : new boolean[] {false, true}) {
            runTwoThreads(useTimeline);
        }
    }

    private static void runTwoThreads(final boolean useTimeline) throws Exception {
        final int frames = 10_000;
        final AtomicIntegerArray releases = new AtomicIntegerArray(frames);
        final AtomicReference<String> orderError = new AtomicReference<>();
        final int[] lastRendered = {-1};
        final int[] renderedCount = {0};

        final NewestFramePacer concurrent = new NewestFramePacer((index, render, presentNs) -> {
            releases.incrementAndGet(index);
            if (render) {
                // The pacer calls the releaser under its lock, so rendered frames never go backwards
                if (index <= lastRendered[0] && orderError.get() == null) {
                    orderError.set("rendered " + index + " after " + lastRendered[0]);
                }
                lastRendered[0] = index;
                renderedCount[0]++;
            }
        });

        final AtomicBoolean producerDone = new AtomicBoolean(false);
        final CountDownLatch start = new CountDownLatch(1);

        Thread vsyncThread = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!producerDone.get()) {
                long t = System.nanoTime();
                if (useTimeline) {
                    concurrent.onFrameTimelines(new long[] {t + 6_000_000L, t + 14_000_000L},
                            new long[] {t + 16_000_000L, t + 24_000_000L}, t);
                }
                else {
                    concurrent.onVsync(t);
                }
                Thread.yield();
            }
        });
        Thread producer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            for (int i = 0; i < frames; i++) {
                // Alternate between beating and missing the deadline
                long t = System.nanoTime() + ((i & 1) == 0 ? 0 : 20_000_000L);
                concurrent.onDecodedFrame(i, i, t);
                if ((i & 3) == 0) {
                    Thread.yield();
                }
            }
            producerDone.set(true);
        });

        vsyncThread.start();
        producer.start();
        start.countDown();
        producer.join(30_000);
        vsyncThread.join(30_000);
        assertFalse(producer.isAlive());
        assertFalse(vsyncThread.isAlive());

        // One more vsync flushes the frame that may still be waiting
        long t = System.nanoTime();
        if (useTimeline) {
            concurrent.onFrameTimelines(new long[0], new long[0], t);
        }
        else {
            concurrent.onVsync(t);
        }
        assertFalse(concurrent.hasPending());

        for (int i = 0; i < frames; i++) {
            assertEquals("index " + i, 1, releases.get(i));
        }
        assertNull(orderError.get());
        assertEquals(frames - 1, lastRendered[0]);
        assertEquals(frames, concurrent.getPresented() + concurrent.getDroppedByPacing());
        assertEquals(renderedCount[0],
                concurrent.getReleasedImmediately() + concurrent.getReleasedAtVsync());
    }

    /**
     * A fake codec with a small pool of reused output buffer indexes. Releasing an index the
     * app does not own (never dequeued, already released, or reclaimed by a flush) fails.
     */
    private static final class FakeCodec {
        private final boolean[] owned;
        private final ArrayDeque<Integer> free = new ArrayDeque<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicLong released = new AtomicLong();

        FakeCodec(int poolSize) {
            owned = new boolean[poolSize];
            for (int i = 0; i < poolSize; i++) {
                free.add(i);
            }
        }

        synchronized int dequeue() {
            Integer index = free.poll();
            if (index == null) {
                return -1;
            }
            owned[index] = true;
            return index;
        }

        synchronized void release(int index) {
            if (!owned[index]) {
                error.compareAndSet(null, "released index " + index + " that the app does not own");
                return;
            }
            owned[index] = false;
            free.add(index);
            released.incrementAndGet();
        }

        /** A flush returns every buffer the app holds to the codec. */
        synchronized void flush() {
            for (int i = 0; i < owned.length; i++) {
                if (owned[i]) {
                    owned[i] = false;
                    free.add(i);
                }
            }
        }
    }

    @Test
    public void reusedIndexesAreNeverReleasedTwiceEvenAcrossFlushes() throws Exception {
        for (boolean useTimeline : new boolean[] {false, true}) {
            runFakeCodec(useTimeline);
        }
    }

    private static void runFakeCodec(final boolean useTimeline) throws Exception {
        final FakeCodec codec = new FakeCodec(4);
        final NewestFramePacer fakePacer = new NewestFramePacer((index, render, presentNs) -> codec.release(index));
        // Codec recovery quiesces the renderer and Choreographer threads before the flush and
        // clear(); this lock plays that role.
        final Object quiesce = new Object();
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicLong decoded = new AtomicLong();
        final AtomicLong flushes = new AtomicLong();
        final CountDownLatch start = new CountDownLatch(1);

        Thread renderer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!done.get()) {
                synchronized (quiesce) {
                    int index = codec.dequeue();
                    if (index >= 0) {
                        long t = System.nanoTime() + ((decoded.get() % 3 == 0) ? 20_000_000L : 0);
                        fakePacer.onDecodedFrame(index, 0, t);
                        decoded.incrementAndGet();
                    }
                }
                Thread.yield();
            }
        });
        Thread vsync = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!done.get()) {
                synchronized (quiesce) {
                    long t = System.nanoTime();
                    if (useTimeline) {
                        fakePacer.onFrameTimelines(new long[] {t + 6_000_000L, t + 14_000_000L},
                                new long[] {t + 16_000_000L, t + 24_000_000L}, t);
                    }
                    else {
                        fakePacer.onVsync(t);
                    }
                }
                Thread.yield();
            }
        });
        Thread recovery = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!done.get()) {
                synchronized (quiesce) {
                    codec.flush();
                    fakePacer.clear();
                    flushes.incrementAndGet();
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        renderer.start();
        vsync.start();
        recovery.start();
        start.countDown();
        long until = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < until && decoded.get() < 50_000 && codec.error.get() == null) {
            Thread.sleep(10);
        }
        done.set(true);
        renderer.join(10_000);
        vsync.join(10_000);
        recovery.join(10_000);

        assertNull(codec.error.get());
        assertTrue("decoded " + decoded.get(), decoded.get() > 1000);
        assertTrue("flushes " + flushes.get(), flushes.get() > 10);
        assertTrue(codec.released.get() > 0);
        // The pool never runs dry for good: the pacer holds at most one buffer
        synchronized (quiesce) {
            codec.flush();
            fakePacer.clear();
        }
        assertTrue(codec.dequeue() >= 0);
    }
}

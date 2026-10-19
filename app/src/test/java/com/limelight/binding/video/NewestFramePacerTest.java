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

    private static final long DEADLINE_OFFSET_NS = PERIOD_NS - 2_000_000L;
    private static final long PRESENT_OFFSET_NS = 2 * PERIOD_NS;

    private void timeline(long vsyncNs) {
        pacer.onFrameTimeline(vsyncNs + DEADLINE_OFFSET_NS, vsyncNs + PRESENT_OFFSET_NS, vsyncNs);
    }

    @Test
    public void timelineReleasesFramesBeforeTheDeadlineForItsPresentTime() {
        timeline(0);
        assertTrue(pacer.usesFrameTimeline());
        pacer.onDecodedFrame(1, 0, 1_000_000L);
        assertEquals(calls("render 1"), releaser.calls);
        assertEquals(PRESENT_OFFSET_NS, releaser.lastPresent());

        // A second frame before the same deadline also goes out; the display keeps the newer
        pacer.onDecodedFrame(2, 0, 2_000_000L);
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertEquals(2, pacer.getReleasedImmediately());
        assertFalse(pacer.hasPending());
    }

    @Test
    public void timelineHoldsFramesAfterTheDeadlineAndKeepsTheNewest() {
        timeline(0);
        pacer.onDecodedFrame(1, 0, DEADLINE_OFFSET_NS + 1);
        pacer.onDecodedFrame(2, 0, DEADLINE_OFFSET_NS + 2);
        assertEquals(calls("drop 1"), releaser.calls);
        assertTrue(pacer.hasPending());

        timeline(PERIOD_NS);
        assertEquals(calls("drop 1", "render 2"), releaser.calls);
        assertEquals(PERIOD_NS + PRESENT_OFFSET_NS, releaser.lastPresent());
        assertEquals(1, pacer.getReleasedAtVsync());
        assertEquals(1, pacer.getDroppedByPacing());

        // The next frame before the new deadline goes out at once
        pacer.onDecodedFrame(3, 0, PERIOD_NS + 1_000_000L);
        assertEquals("render 3", releaser.last());
        assertEquals(1, pacer.getReleasedImmediately());
    }

    /**
     * Frames at the panel rate with clock drift sweep through every phase of the refresh
     * interval. Each frame must go out at once when it beats the deadline, otherwise at the
     * very next vsync, never later, and nothing is dropped at one frame per interval.
     */
    @Test
    public void timelineDriftOnlyHoldsFramesThatMissedTheDeadline() {
        final double framePeriodNs = PERIOD_NS * (1 + 200e-6); // host clock 200 ppm slow
        long vsyncAt = 0;
        timeline(vsyncAt);
        double nextFrameAt = PERIOD_NS * 0.1;
        int frames = 0;
        long expectedHeld = 0;
        while (frames < 20000) {
            if (nextFrameAt < vsyncAt + PERIOD_NS) {
                long t = (long) nextFrameAt;
                boolean beforeDeadline = t < vsyncAt + DEADLINE_OFFSET_NS;
                int before = releaser.calls.size();
                pacer.onDecodedFrame(frames, 0, t);
                if (beforeDeadline) {
                    assertEquals(before + 1, releaser.calls.size());
                    assertEquals("render " + frames, releaser.last());
                    assertEquals(vsyncAt + PRESENT_OFFSET_NS, releaser.lastPresent());
                }
                else {
                    expectedHeld++;
                    assertTrue(pacer.hasPending());
                }
                frames++;
                nextFrameAt += framePeriodNs;
            }
            else {
                boolean hadPending = pacer.hasPending();
                vsyncAt += PERIOD_NS;
                timeline(vsyncAt);
                if (hadPending) {
                    assertEquals("render " + (frames - 1), releaser.last());
                    assertEquals(vsyncAt + PRESENT_OFFSET_NS, releaser.lastPresent());
                }
                assertFalse(pacer.hasPending());
            }
        }
        assertEquals(0, pacer.getDroppedByPacing());
        assertEquals(expectedHeld, pacer.getReleasedAtVsync() + (pacer.hasPending() ? 1 : 0));
        // About a quarter of the interval lies past the deadline, so about a quarter is held
        double heldShare = (double) expectedHeld / frames;
        assertTrue("held share " + heldShare, heldShare > 0.15 && heldShare < 0.35);
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

        final NewestFramePacer concurrent = new NewestFramePacer((index, render, presentNs) -> {
            releases.incrementAndGet(index);
            if (render) {
                // The pacer calls the releaser under its lock, so rendered frames never go backwards
                if (index <= lastRendered[0] && orderError.get() == null) {
                    orderError.set("rendered " + index + " after " + lastRendered[0]);
                }
                lastRendered[0] = index;
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
                    concurrent.onFrameTimeline(t + 6_000_000L, t + 16_000_000L, t);
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
            concurrent.onFrameTimeline(t, t, t);
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
        assertEquals(concurrent.getPresented(),
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
                        fakePacer.onFrameTimeline(t + 6_000_000L, t + 16_000_000L, t);
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

package com.limelight.binding.video;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NewestFramePacerTest {

    /** Records every release in order. */
    private static final class RecordingReleaser implements NewestFramePacer.Releaser {
        final List<String> calls = new ArrayList<>();

        @Override
        public void release(int index, boolean render) {
            calls.add((render ? "render " : "drop ") + index);
        }
    }

    private RecordingReleaser releaser;
    private NewestFramePacer pacer;
    private long vsyncNanos;

    @Before
    public void setUp() {
        releaser = new RecordingReleaser();
        pacer = new NewestFramePacer(releaser);
        vsyncNanos = 0;
    }

    private void vsync() {
        vsyncNanos += 8_333_333L;
        pacer.onVsync(vsyncNanos);
    }

    private List<String> calls(String... expected) {
        List<String> list = new ArrayList<>();
        for (String s : expected) {
            list.add(s);
        }
        return list;
    }

    @Test
    public void oneFramePerIntervalIsAlwaysReleasedImmediately() {
        vsync();
        for (int i = 0; i < 100; i++) {
            pacer.onDecodedFrame(i, i * 8333L);
            assertEquals("render " + i, releaser.calls.get(releaser.calls.size() - 1));
            vsync();
        }
        assertEquals(100, releaser.calls.size());
        assertEquals(100, pacer.getReleasedImmediately());
        assertEquals(0, pacer.getReleasedAtVsync());
        assertEquals(0, pacer.getDroppedByPacing());
        assertEquals(100, pacer.getPresented());
        assertFalse(pacer.hasPending());
    }

    @Test
    public void secondFrameInAnIntervalIsHeldUntilVsync() {
        vsync();
        pacer.onDecodedFrame(1, 0);
        pacer.onDecodedFrame(2, 1);
        assertEquals(calls("render 1"), releaser.calls);
        assertTrue(pacer.hasPending());

        vsync();
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertEquals(1, pacer.getReleasedImmediately());
        assertEquals(1, pacer.getReleasedAtVsync());
        assertEquals(0, pacer.getDroppedByPacing());
        assertFalse(pacer.hasPending());
    }

    @Test
    public void middleOfThreeFramesInAnIntervalIsDropped() {
        vsync();
        pacer.onDecodedFrame(1, 0);
        pacer.onDecodedFrame(2, 1);
        pacer.onDecodedFrame(3, 2);
        assertEquals(calls("render 1", "drop 2"), releaser.calls);
        assertEquals(1, pacer.getDroppedByPacing());

        vsync();
        assertEquals(calls("render 1", "drop 2", "render 3"), releaser.calls);
        assertEquals(2, pacer.getPresented());
    }

    @Test
    public void vsyncWithNothingPendingArmsTheImmediatePath() {
        vsync();
        pacer.onDecodedFrame(1, 0);
        pacer.onDecodedFrame(2, 1);
        // The pending frame goes out at this vsync, so the interval after it has presented
        vsync();
        pacer.onDecodedFrame(3, 2);
        assertEquals(calls("render 1", "render 2"), releaser.calls);
        assertTrue(pacer.hasPending());

        vsync();
        assertEquals(calls("render 1", "render 2", "render 3"), releaser.calls);
        // Nothing pending at this vsync: the next frame goes out at once again
        vsync();
        pacer.onDecodedFrame(4, 3);
        assertEquals(calls("render 1", "render 2", "render 3", "render 4"), releaser.calls);
        assertFalse(pacer.hasPending());
    }

    @Test
    public void framesBeforeTheFirstVsyncAreHandledLikeAnyInterval() {
        pacer.onDecodedFrame(1, 0);
        pacer.onDecodedFrame(2, 1);
        assertEquals(calls("render 1"), releaser.calls);
        vsync();
        assertEquals(calls("render 1", "render 2"), releaser.calls);
    }

    @Test
    public void clearForgetsThePendingFrameWithoutReleasingIt() {
        vsync();
        pacer.onDecodedFrame(1, 0);
        pacer.onDecodedFrame(2, 1);
        pacer.clear();
        assertFalse(pacer.hasPending());

        // Nothing stale comes out at the next vsync
        vsync();
        assertEquals(calls("render 1"), releaser.calls);

        // And the next frame after a clear is released at once, even mid-interval
        pacer.onDecodedFrame(10, 2);
        pacer.clear();
        pacer.onDecodedFrame(11, 3);
        assertEquals(calls("render 1", "render 10", "render 11"), releaser.calls);
    }

    @Test
    public void pendingSlotAlwaysHoldsTheNewestFrame() {
        vsync();
        for (int i = 0; i < 10; i++) {
            pacer.onDecodedFrame(i, i);
        }
        vsync();
        assertEquals("render 9", releaser.calls.get(releaser.calls.size() - 1));
        assertEquals(8, pacer.getDroppedByPacing());
        assertEquals(10, releaser.calls.size());
    }

    @Test
    public void everyIndexIsReleasedExactlyOnceUnderTwoThreads() throws Exception {
        final int frames = 10_000;
        final AtomicIntegerArray releases = new AtomicIntegerArray(frames);
        final AtomicIntegerArray renders = new AtomicIntegerArray(frames);
        final AtomicReference<String> orderError = new AtomicReference<>();
        final int[] lastRendered = {-1};

        NewestFramePacer concurrent = new NewestFramePacer((index, render) -> {
            releases.incrementAndGet(index);
            if (render) {
                renders.incrementAndGet(index);
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
            long t = 0;
            while (!producerDone.get()) {
                concurrent.onVsync(t += 8_333_333L);
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
                concurrent.onDecodedFrame(i, i * 8333L);
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

        // One more vsync flushes the frame that may still be pending
        concurrent.onVsync(Long.MAX_VALUE);
        assertFalse(concurrent.hasPending());

        for (int i = 0; i < frames; i++) {
            assertEquals("index " + i, 1, releases.get(i));
        }
        assertNull(orderError.get());
        assertEquals(frames - 1, lastRendered[0]);
        assertEquals(1, renders.get(frames - 1));
        assertEquals(frames, concurrent.getPresented() + concurrent.getDroppedByPacing());
        assertEquals(concurrent.getPresented(),
                concurrent.getReleasedImmediately() + concurrent.getReleasedAtVsync());
    }
}

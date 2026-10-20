package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class WifiKeepaliveTest {
    private Context context;
    private ConnectivityManager connMgr;
    private final AtomicInteger sent = new AtomicInteger();
    private WifiKeepalive keepalive;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        keepalive = new WifiKeepalive(sent::incrementAndGet);
    }

    private void setActiveTransport(int transport) {
        Network network = connMgr.getActiveNetwork();
        assertNotNull(network);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        shadowOf(caps).addTransportType(transport);
        shadowOf(connMgr).setNetworkCapabilities(network, caps);
    }

    @Test
    public void sendsOnWifiAndNeverAfterStop() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        // A long first delay keeps the background thread idle; ticks are driven directly
        keepalive.start(context, 120, 60_000);
        assertTrue(keepalive.isRunning());
        assertEquals(8, keepalive.getPeriodMs());

        assertTrue(keepalive.tickOnce());
        assertTrue(keepalive.tickOnce());
        assertEquals(2, sent.get());

        keepalive.stopAndWait();
        assertFalse(keepalive.isRunning());
        assertFalse(keepalive.tickOnce());
        assertEquals(2, sent.get());
    }

    @Test
    public void stopWaitsForASendInProgress() throws Exception {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        final CountDownLatch sending = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger blockingSends = new AtomicInteger();
        final WifiKeepalive blocking = new WifiKeepalive(() -> {
            blockingSends.incrementAndGet();
            sending.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        blocking.start(context, 120, 60_000);

        Thread ticker = new Thread(blocking::tickOnce);
        ticker.start();
        assertTrue(sending.await(5, TimeUnit.SECONDS));

        final CountDownLatch stopped = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            blocking.stopAndWait();
            stopped.countDown();
        });
        stopper.start();

        // The send is still in progress, so stopAndWait() must not return yet
        assertFalse(stopped.await(300, TimeUnit.MILLISECONDS));

        release.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        ticker.join(5000);
        stopper.join(5000);

        // No tick sends after the stop
        assertFalse(blocking.tickOnce());
        assertEquals(1, blockingSends.get());
        assertFalse(blocking.isRunning());
    }

    @Test
    public void rechecksTheTransportOnceASecond() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        keepalive.start(context, 60, 60_000);
        assertEquals(16, keepalive.getPeriodMs());

        // Moving to Ethernet is noticed at the next once-a-second check
        setActiveTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        assertTrue(keepalive.tickOnce());
        assertEquals(1, sent.get());

        ShadowSystemClock.advanceBy(Duration.ofMillis(WifiKeepalive.WIFI_RECHECK_MS + 1));
        assertTrue(keepalive.tickOnce());
        assertTrue(keepalive.tickOnce());
        assertEquals(1, sent.get());

        // And back to Wi-Fi
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ShadowSystemClock.advanceBy(Duration.ofMillis(WifiKeepalive.WIFI_RECHECK_MS + 1));
        assertTrue(keepalive.tickOnce());
        assertEquals(2, sent.get());
        keepalive.stopAndWait();
        assertFalse(keepalive.isRunning());
    }

    @Test
    public void startingTwiceKeepsOneThreadAndStopIsRepeatable() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        keepalive.start(context, 120, 60_000);
        keepalive.start(context, 30, 60_000);
        assertEquals(8, keepalive.getPeriodMs());
        keepalive.stopAndWait();
        keepalive.stopAndWait();
        assertFalse(keepalive.isRunning());

        // Stopping a keepalive that never started is harmless
        new WifiKeepalive(sent::incrementAndGet).stopAndWait();
    }
}

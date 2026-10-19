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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean active = new AtomicBoolean(true);
    private WifiKeepalive keepalive;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        keepalive = new WifiKeepalive(sent::incrementAndGet, active::get);
    }

    private void setActiveTransport(int transport) {
        Network network = connMgr.getActiveNetwork();
        assertNotNull(network);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        shadowOf(caps).addTransportType(transport);
        shadowOf(connMgr).setNetworkCapabilities(network, caps);
    }

    @Test
    public void sendsOnWifiAndStopsWhenTheStreamEnds() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        // A long first delay keeps the background thread idle; ticks are driven directly
        keepalive.start(context, 120, 60_000);
        assertTrue(keepalive.isRunning());
        assertEquals(8, keepalive.getPeriodMs());

        assertTrue(keepalive.tickOnce());
        assertTrue(keepalive.tickOnce());
        assertEquals(2, sent.get());

        active.set(false);
        assertFalse(keepalive.tickOnce());
        assertFalse(keepalive.isRunning());
        assertEquals(2, sent.get());
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
        keepalive.stop();
        assertFalse(keepalive.isRunning());
    }

    @Test
    public void startingTwiceKeepsOneThread() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        keepalive.start(context, 120, 60_000);
        keepalive.start(context, 30, 60_000);
        assertEquals(8, keepalive.getPeriodMs());
        keepalive.stop();
        keepalive.stop();
        assertFalse(keepalive.isRunning());
    }
}

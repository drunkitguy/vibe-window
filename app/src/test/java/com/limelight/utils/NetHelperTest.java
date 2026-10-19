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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class NetHelperTest {
    private Context context;
    private ConnectivityManager connMgr;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void setActiveTransport(int transport) {
        Network active = connMgr.getActiveNetwork();
        assertNotNull(active);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        shadowOf(caps).addTransportType(transport);
        shadowOf(connMgr).setNetworkCapabilities(active, caps);
    }

    @Test
    public void wifiIsWifi() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI);
        assertTrue(NetHelper.isActiveNetworkWifi(context));
    }

    @Test
    public void ethernetAndCellularAreNotWifi() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        assertFalse(NetHelper.isActiveNetworkWifi(context));

        setActiveTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        assertFalse(NetHelper.isActiveNetworkWifi(context));
    }

    @Test
    public void keepalivePeriodFollowsTheFrameRate() {
        assertEquals(8, NetHelper.keepalivePeriodMs(120));
        assertEquals(16, NetHelper.keepalivePeriodMs(60));
        assertEquals(20, NetHelper.keepalivePeriodMs(30));
        assertEquals(8, NetHelper.keepalivePeriodMs(144));
        assertEquals(8, NetHelper.keepalivePeriodMs(240));
        assertEquals(11, NetHelper.keepalivePeriodMs(90));
        assertEquals(16, NetHelper.keepalivePeriodMs(59.94f));
        assertEquals(20, NetHelper.keepalivePeriodMs(0));
        assertEquals(20, NetHelper.keepalivePeriodMs(Float.NaN));
    }
}

package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

public class NetHelper {
    // Wi-Fi keepalive period bounds: about one packet per frame, at least 8 ms apart, at most 20 ms
    static final int KEEPALIVE_MIN_PERIOD_MS = 8;
    static final int KEEPALIVE_MAX_PERIOD_MS = 20;

    /**
     * The Wi-Fi keepalive period for a stream frame rate: one frame time rounded down,
     * between 8 and 20 ms. 120 FPS gives 8 ms, 60 FPS gives 16 ms, 30 FPS gives 20 ms.
     */
    public static int keepalivePeriodMs(float fps) {
        if (!(fps > 0)) {
            return KEEPALIVE_MAX_PERIOD_MS;
        }
        int frameMs = (int) (1000 / fps);
        return Math.max(KEEPALIVE_MIN_PERIOD_MS, Math.min(KEEPALIVE_MAX_PERIOD_MS, frameMs));
    }

    /** Whether the active network uses Wi-Fi. Always false before Android 6.0. */
    public static boolean isActiveNetworkWifi(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connMgr == null) {
                return false;
            }
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(activeNetwork);
                if (netCaps != null) {
                    return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }
            }
        }
        return false;
    }

    public static boolean isActiveNetworkVpn(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(activeNetwork);
                if (netCaps != null) {
                    return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                            !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
                }
            }
        }
        else {
            NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            if (activeNetworkInfo != null) {
                return activeNetworkInfo.getType() == ConnectivityManager.TYPE_VPN;
            }
        }

        return false;
    }
}

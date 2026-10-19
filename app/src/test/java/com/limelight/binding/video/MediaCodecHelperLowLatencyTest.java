package com.limelight.binding.video;

import android.media.MediaFormat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class MediaCodecHelperLowLatencyTest {
    private static final String QTI = "c2.qti.hevc.decoder";
    private static final String GENERIC = "c2.android.hevc.decoder";

    private static final String QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable";
    private static final String QTI_SW_FENCE = "vendor.qti-ext-output-sw-fence-enable.value";

    @Before
    public void setUp() throws Exception {
        // initialize() needs a GL renderer string; the decoder lists are static, so marking the
        // helper initialized is enough here. isAdreno620 keeps its default of false, which
        // allows KEY_OPERATING_RATE on Qualcomm decoders (the Adreno 740 case).
        Field initialized = MediaCodecHelper.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, true);

        Field adreno620 = MediaCodecHelper.class.getDeclaredField("isAdreno620");
        adreno620.setAccessible(true);
        assertFalse(adreno620.getBoolean(null));
    }

    private static MediaFormat format() {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
    }

    private static MediaFormat apply(String decoder, boolean feature, boolean ultra, boolean vendorKeys, int tryNumber, boolean[] result) {
        MediaFormat f = format();
        result[0] = MediaCodecHelper.applyLowLatencyOptions(f, decoder, feature, ultra, vendorKeys, tryNumber);
        return f;
    }

    private static void assertOnlyLowLatency(MediaFormat f) {
        assertTrue(f.containsKey("low-latency"));
        assertEquals(1, f.getInteger("low-latency"));
        assertFalse(f.containsKey("vdec-lowlatency"));
        assertFalse(f.containsKey(MediaFormat.KEY_OPERATING_RATE));
        assertFalse(f.containsKey(MediaFormat.KEY_PRIORITY));
        assertFalse(f.containsKey(QTI_LOW_LATENCY));
        assertFalse(f.containsKey(QTI_SW_FENCE));
        assertFalse(f.containsKey("vendor.qti-ext-dec-picture-order.enable"));
    }

    @Test
    public void qualcommWithFeatureGetsVendorKeysAtTryZero() {
        boolean[] r = new boolean[1];
        MediaFormat f = apply(QTI, true, false, true, 0, r);
        assertTrue(r[0]);
        assertEquals(1, f.getInteger("low-latency"));
        assertEquals(1, f.getInteger(QTI_LOW_LATENCY));
        assertEquals(1, f.getInteger(QTI_SW_FENCE));
        assertEquals(1, f.getInteger("vendor.qti-ext-output-fence.enable"));
        assertEquals(1, f.getInteger("vendor.qti-ext-output-fence.fence_type"));
        assertEquals(1, f.getInteger("vendor.qti-ext-dec-picture-order.enable"));
        assertEquals(Short.MAX_VALUE, f.getInteger(MediaFormat.KEY_OPERATING_RATE));
        assertEquals("qti-full", MediaCodecHelper.describeLowLatencyPath(f, r[0], 0));
    }

    @Test
    public void vendorKeysOffKeepsTheOldTryZero() {
        boolean[] r = new boolean[1];
        MediaFormat f = apply(QTI, true, false, false, 0, r);
        assertTrue(r[0]);
        assertOnlyLowLatency(f);
        assertEquals("android-feature-only", MediaCodecHelper.describeLowLatencyPath(f, r[0], 0));
    }

    @Test
    public void nonQualcommWithFeatureStillReturnsEarly() {
        for (boolean vendorKeys : new boolean[] {true, false}) {
            boolean[] r = new boolean[1];
            MediaFormat f = apply(GENERIC, true, false, vendorKeys, 0, r);
            assertTrue(r[0]);
            assertOnlyLowLatency(f);
        }
    }

    @Test
    public void safeTryIsTodaysConfigurationAndEndsTheLadder() {
        boolean[] r = new boolean[1];
        MediaFormat f = apply(QTI, true, false, true, MediaCodecHelper.QUALCOMM_SAFE_TRY, r);
        assertEquals(5, MediaCodecHelper.QUALCOMM_SAFE_TRY);
        assertTrue(r[0]);
        assertOnlyLowLatency(f);
        assertEquals("android-feature-only", MediaCodecHelper.describeLowLatencyPath(f, r[0], 5));

        f = apply(QTI, true, false, true, 6, r);
        assertFalse(r[0]);
        assertFalse(f.containsKey("low-latency"));
        assertEquals("none", MediaCodecHelper.describeLowLatencyPath(f, r[0], 6));
    }

    @Test
    public void vendorLadderDropsOptionsTryByTry() {
        boolean[] r = new boolean[1];
        for (int t = 1; t <= 4; t++) {
            MediaFormat on = apply(QTI, true, false, true, t, r);
            assertTrue(r[0]);
            assertTrue(on.containsKey(QTI_LOW_LATENCY));
            assertFalse(on.containsKey("low-latency"));
            assertEquals("qti-ladder-" + t, MediaCodecHelper.describeLowLatencyPath(on, r[0], t));

            // Tries 1 to 4 are the same as before 1.3.0
            MediaFormat off = apply(QTI, true, false, false, t, r);
            assertTrue(r[0]);
            assertEquals(off.toString(), on.toString());
        }
        // The old ladder ended after try 4
        apply(QTI, true, false, false, 5, r);
        assertFalse(r[0]);
    }

    @Test
    public void ultraLowLatencyIsUnchangedBySwitch() {
        for (int t = 0; t <= 6; t++) {
            boolean[] on = new boolean[1];
            boolean[] off = new boolean[1];
            MediaFormat fOn = apply(QTI, true, true, true, t, on);
            MediaFormat fOff = apply(QTI, true, true, false, t, off);
            assertEquals("try " + t, off[0], on[0]);
            assertEquals("try " + t, fOff.toString(), fOn.toString());
        }
    }

    @Test
    public void qualcommWithoutFeatureIsUnchangedBySwitch() {
        for (int t = 0; t <= 6; t++) {
            boolean[] on = new boolean[1];
            boolean[] off = new boolean[1];
            MediaFormat fOn = apply(QTI, false, false, true, t, on);
            MediaFormat fOff = apply(QTI, false, false, false, t, off);
            assertEquals("try " + t, off[0], on[0]);
            assertEquals("try " + t, fOff.toString(), fOn.toString());
        }
    }
}

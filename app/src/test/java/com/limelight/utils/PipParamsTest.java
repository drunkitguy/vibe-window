package com.limelight.utils;

import android.app.PictureInPictureParams;
import android.util.Rational;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Android 12+ refuses a picture-in-picture ratio outside 1:2.39 to 2.39:1, or 0/0. */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PipParamsTest {
    // The platform's limits (config_pictureInPictureMin/MaxAspectRatio)
    private static final float MIN = 0.41841f;
    private static final float MAX = 2.39f;

    private static void assertAccepted(Rational ratio) {
        assertNotNull(ratio);
        float value = ratio.floatValue();
        assertTrue(value + " is at least " + MIN, value >= MIN);
        assertTrue(value + " is at most " + MAX, value <= MAX);
        // Building the parameters must not throw either
        assertNotNull(new PictureInPictureParams.Builder().setAspectRatio(ratio).build());
    }

    @Test
    public void ultrawideIsClamped() {
        // A 32:9 stream filling a 3840x1080 view
        Rational ratio = PipParams.aspectRatio(3840, 1080);
        assertAccepted(ratio);
        assertEquals(PipParams.MAX_RATIO, ratio);
        assertFalse("Its rectangle is no source hint then", PipParams.isExact(3840, 1080));
    }

    @Test
    public void tallIsClamped() {
        // A portrait phone stream, 1080x2640
        Rational ratio = PipParams.aspectRatio(1080, 2640);
        assertAccepted(ratio);
        assertEquals(PipParams.MIN_RATIO, ratio);
        assertFalse(PipParams.isExact(1080, 2640));
    }

    @Test
    public void noSizeGivesNoRatio() {
        assertNull(PipParams.aspectRatio(0, 0));
        assertNull(PipParams.aspectRatio(1920, 0));
        assertNull(PipParams.aspectRatio(0, 1080));
        assertFalse(PipParams.isExact(0, 0));
        // Parameters without a ratio are fine
        assertNotNull(new PictureInPictureParams.Builder().build());
    }

    @Test
    public void ordinaryRatiosAreKept() {
        assertEquals(new Rational(16, 9), PipParams.aspectRatio(1920, 1080));
        assertTrue(PipParams.isExact(1920, 1080));
        assertEquals(new Rational(4, 3), PipParams.aspectRatio(1024, 768));
        // 21:9 (2.33) is still inside the range
        assertEquals(new Rational(2560, 1080), PipParams.aspectRatio(2560, 1080));
        assertAccepted(PipParams.aspectRatio(2560, 1080));
    }

    @Test
    public void theLimitsSitJustInsideThePlatformRange() {
        assertAccepted(PipParams.MAX_RATIO);
        assertAccepted(PipParams.MIN_RATIO);
    }
}

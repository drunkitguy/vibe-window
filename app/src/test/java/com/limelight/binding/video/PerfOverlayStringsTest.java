package com.limelight.binding.video;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PerfOverlayStringsTest {

    @Test
    public void liteModeShowsRenderedOverIncomingFps() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("R/I 119.8/120.0",
                context.getString(R.string.perf_overlay_lite_rendered_incoming, 119.8f, 120.0f));
        assertEquals("R/I 59.9/60.0",
                context.getString(R.string.perf_overlay_lite_rendered_incoming, 59.94f, 60f));
    }
}

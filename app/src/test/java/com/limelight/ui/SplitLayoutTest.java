package com.limelight.ui;

import android.content.res.Configuration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SplitLayoutTest {

    @Test
    public void wideLandscapeWindowsSplit() {
        assertTrue(SplitLayout.useSplit(Configuration.ORIENTATION_LANDSCAPE, 720));
        assertTrue(SplitLayout.useSplit(Configuration.ORIENTATION_LANDSCAPE, 960));
        assertFalse(SplitLayout.useSplit(Configuration.ORIENTATION_LANDSCAPE, 719));
        assertFalse(SplitLayout.useSplit(Configuration.ORIENTATION_LANDSCAPE, 640));
    }

    @Test
    public void portraitNeverSplits() {
        assertFalse(SplitLayout.useSplit(Configuration.ORIENTATION_PORTRAIT, 900));
        assertFalse(SplitLayout.useSplit(Configuration.ORIENTATION_UNDEFINED, 900));
    }

    @Test
    public void panelWidthIsClamped() {
        assertEquals(340, SplitLayout.panelWidthDp(731));
        assertEquals(403, SplitLayout.panelWidthDp(960));
        assertEquals(460, SplitLayout.panelWidthDp(1280));
    }
}

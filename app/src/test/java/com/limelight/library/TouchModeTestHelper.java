package com.limelight.library;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/** Switches a Robolectric window in and out of touch mode, as touch or D-pad input would. */
final class TouchModeTestHelper {
    private TouchModeTestHelper() {}

    static void setTouchMode(Activity activity, final boolean inTouchMode) {
        View decor = activity.getWindow().getDecorView();
        final boolean[] notified = {false};
        ViewTreeObserver.OnTouchModeChangeListener probe = new ViewTreeObserver.OnTouchModeChangeListener() {
            @Override
            public void onTouchModeChanged(boolean isInTouchMode) {
                if (isInTouchMode == inTouchMode) {
                    notified[0] = true;
                }
            }
        };
        decor.getViewTreeObserver().addOnTouchModeChangeListener(probe);
        try {
            // The public way first
            try {
                InstrumentationRegistry.getInstrumentation().setInTouchMode(inTouchMode);
            } catch (Throwable ignored) {
                // Not supported here; handled below
            }

            if (!notified[0]) {
                // Robolectric may change the flag without telling the window's listeners,
                // which is what the app reacts to. This is the only reflection in the tests:
                // ViewRootImpl.ensureTouchModeLocally() applies the mode and notifies the
                // listeners, as the platform does on real input. Flip to the other mode
                // first when the flag already reads as the target.
                try {
                    Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(decor);
                    Method ensure = viewRoot.getClass().getDeclaredMethod("ensureTouchModeLocally", boolean.class);
                    ensure.setAccessible(true);
                    if (decor.isInTouchMode() == inTouchMode) {
                        ensure.invoke(viewRoot, !inTouchMode);
                    }
                    ensure.invoke(viewRoot, inTouchMode);
                } catch (Exception e) {
                    throw new AssertionError("Could not change touch mode", e);
                }
            }
        } finally {
            decor.getViewTreeObserver().removeOnTouchModeChangeListener(probe);
        }
        assertEquals("touch mode", inTouchMode, decor.isInTouchMode());
    }
}

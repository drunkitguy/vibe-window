package com.limelight.library;

import android.app.Activity;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/** Switches a Robolectric window in and out of touch mode, as touch or D-pad input would. */
final class TouchModeTestHelper {
    private TouchModeTestHelper() {}

    static void setTouchMode(Activity activity, boolean inTouchMode) {
        View decor = activity.getWindow().getDecorView();
        if (decor.isInTouchMode() == inTouchMode) {
            return;
        }

        // The public API: under Robolectric it only records the mode for the window
        // session (so new windows start in it) and leaves this window unchanged
        InstrumentationRegistry.getInstrumentation().setInTouchMode(inTouchMode);

        if (decor.isInTouchMode() != inTouchMode) {
            // The only reflection in the tests: ViewRootImpl.ensureTouchModeLocally()
            // applies the mode to this window and notifies its touch mode listeners,
            // as the platform does on real input
            try {
                Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(decor);
                Method ensure = viewRoot.getClass().getDeclaredMethod("ensureTouchModeLocally", boolean.class);
                ensure.setAccessible(true);
                ensure.invoke(viewRoot, inTouchMode);
            } catch (Exception e) {
                throw new AssertionError("Could not change touch mode", e);
            }
        }
        assertEquals("touch mode", inTouchMode, decor.isInTouchMode());
    }
}

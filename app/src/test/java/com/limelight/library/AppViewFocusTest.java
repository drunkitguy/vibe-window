package com.limelight.library;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.utils.CacheHelper;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.OutputStream;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Focus behavior of the real AppView: the search field only takes focus when
 * asked, the library gets the initial focus, and the first D-pad press after
 * touch input lands on the last app.
 */
@Config(sdk = {33}, qualifiers = "w960dp-h540dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class AppViewFocusTest {
    private static final String HOST_UUID = "test-focus-host";

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static AppView launch() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), AppView.class);
        intent.putExtra(AppView.NAME_EXTRA, "Example PC");
        intent.putExtra(AppView.UUID_EXTRA, HOST_UUID);
        return Robolectric.buildActivity(AppView.class, intent).setup().get();
    }

    /** Serves a host with six cached apps through a stub computer manager. */
    private static void provideHostWithApps() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">");
        for (int id = 101; id <= 106; id++) {
            xml.append("<App><AppTitle>Example Game ").append(id).append("</AppTitle><ID>").append(id)
                    .append("</ID><Platform>PC (Windows)</Platform><PlatformId>pc_windows</PlatformId></App>");
        }
        xml.append("</root>");
        try (OutputStream out = CacheHelper.openCacheFileForOutput(context.getCacheDir(), "applist", HOST_UUID)) {
            CacheHelper.writeStringToOutputStream(out, xml.toString());
        }

        ComputerDetails computer = new ComputerDetails();
        computer.uuid = HOST_UUID;
        computer.name = "Example PC";

        ComputerManagerService.ComputerManagerBinder binder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.getComputer(HOST_UUID)).thenReturn(computer);
        when(binder.getUniqueId()).thenReturn("0123456789ABCDEF");
        when(binder.createAppListPoller(any(ComputerDetails.class)))
                .thenReturn(mock(ComputerManagerService.ApplistPoller.class));
        Shadows.shadowOf((Application) context).setComponentNameAndServiceForBindService(
                new ComponentName(context, ComputerManagerService.class), binder);
    }

    private static String focusedAppKey(AppView activity) {
        View focused = activity.findViewById(android.R.id.content).findFocus();
        Object tag = focused != null ? focused.getTag(R.id.tag_app_object) : null;
        return tag instanceof AppView.AppObject ? LibraryModel.appKey((AppView.AppObject) tag) : null;
    }

    /** Runs the main looper (and the service thread's posts) until an app card has focus. */
    private static String waitForFocusedApp(AppView activity) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            String key = focusedAppKey(activity);
            if (key != null) {
                return key;
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            Thread.sleep(2);
        }
    }

    private static boolean pressKey(AppView activity, int keyCode) {
        long now = SystemClock.uptimeMillis();
        boolean handled = activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        return handled;
    }

    @Test
    public void searchTakesFocusOnlyWhenAsked() throws Exception {
        AppView activity = launch();
        TouchModeTestHelper.setTouchMode(activity, false);
        EditText search = activity.findViewById(R.id.librarySearch);
        assertFalse("not a default focus or D-pad stop", search.isFocusable());

        // TalkBack or Switch Access double tap
        assertTrue(search.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(search.isFocused());

        // Leaving the field makes it non-focusable again
        View gridButton = activity.findViewById(R.id.layoutGrid);
        assertTrue(gridButton.requestFocus());
        assertFalse(search.isFocused());
        assertFalse(search.isFocusable());

        // The accessibility focus action works too
        assertTrue(search.performAccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, null));
        assertTrue(search.isFocused());
    }

    @Test
    public void cancelledTouchLeavesSearchUnfocusable() throws Exception {
        AppView activity = launch();
        EditText search = activity.findViewById(R.id.librarySearch);
        long now = SystemClock.uptimeMillis();
        search.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0));
        search.dispatchTouchEvent(MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_CANCEL, 10, 10, 0));
        assertFalse(search.isFocused());
        assertFalse(search.isFocusable());
    }

    @Test
    public void libraryGetsInitialFocusAndKeepsItAfterTouch() throws Exception {
        provideHostWithApps();
        new LibraryPrefs(ApplicationProvider.getApplicationContext(), HOST_UUID).setLastFocus("id:103");

        AppView activity = launch();
        TouchModeTestHelper.setTouchMode(activity, false);

        // Initial focus goes to the last focused app, through the real AppView and grid
        String initial = waitForFocusedApp(activity);
        if (!"id:103".equals(initial)) {
            fail("initial focus was " + initial + ", search focused="
                    + activity.findViewById(R.id.librarySearch).isFocused());
        }

        // Touch input: the card loses focus
        TouchModeTestHelper.setTouchMode(activity, true);
        View focused = activity.findViewById(android.R.id.content).findFocus();
        if (focused != null) {
            focused.clearFocus();
        }
        settle();
        assertEquals(null, focusedAppKey(activity));

        // The D-pad press that ends touch mode restores the last app...
        TouchModeTestHelper.setTouchMode(activity, false);
        assertEquals("id:103", focusedAppKey(activity));
        // ...and that same press does not move past it
        assertTrue("the key that ended touch mode is swallowed", pressKey(activity, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("id:103", focusedAppKey(activity));
        // Later presses are left to normal focus navigation
        assertFalse(pressKey(activity, KeyEvent.KEYCODE_DPAD_DOWN));

        // Touch mode can also end without a key, for example when the window gets
        // focus back after a dialog or a stream: the next press must still count
        TouchModeTestHelper.setTouchMode(activity, true);
        View again = activity.findViewById(android.R.id.content).findFocus();
        if (again != null) {
            again.clearFocus();
        }
        settle();
        TouchModeTestHelper.setTouchMode(activity, false);
        assertEquals("id:103", focusedAppKey(activity));
        // The user presses a little later
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        assertFalse("a key pressed after the restore is not swallowed", pressKey(activity, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("id:103", focusedAppKey(activity));
    }

    @Test
    public void yAndXAreNotLibraryKeysInsideTheSearchField() throws Exception {
        AppView activity = launch();
        TouchModeTestHelper.setTouchMode(activity, false);
        EditText search = activity.findViewById(R.id.librarySearch);

        // Y outside the field focuses it
        assertTrue(pressKey(activity, KeyEvent.KEYCODE_BUTTON_Y));
        assertTrue(search.isFocused());
        // Inside the field the library keys are left alone: X does not open a menu
        // and the field keeps focus
        pressKey(activity, KeyEvent.KEYCODE_BUTTON_X);
        pressKey(activity, KeyEvent.KEYCODE_BUTTON_Y);
        assertTrue(search.isFocused());
    }
}

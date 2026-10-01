package com.limelight.library;

import android.app.Activity;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Drives the grid and shelves controllers with a stub host, checking that
 * D-pad focus lands where it should across asynchronous list updates.
 */
@Config(sdk = {33}, qualifiers = "w960dp-h540dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class LibraryControllersTest {
    private AppCompatActivity activity;
    private FrameLayout container;
    private StubHost host;

    private static final class StubHost implements LibraryLayoutController.Host {
        final AppCompatActivity activity;
        final AppLibrary library;
        final LibraryPrefs prefs;
        String lastFocused;

        StubHost(AppCompatActivity activity) {
            this.activity = activity;
            ComputerDetails computer = new ComputerDetails();
            computer.uuid = "test-host";
            this.library = new AppLibrary(activity, PreferenceConfiguration.readPreferences(activity),
                    computer, "test-client", false);
            this.prefs = new LibraryPrefs(activity, "test-host");
        }

        @Override
        public android.content.Context getContext() {
            return activity;
        }

        @Override
        public AppLibrary getAppLibrary() {
            return library;
        }

        @Override
        public boolean isSmallIconMode() {
            return false;
        }

        @Override
        public void onAppClicked(AppView.AppObject app, View view) {
        }

        @Override
        public void onGroupHeaderClicked(String groupKey) {
        }

        @Override
        public void onAppFocused(AppView.AppObject app) {
            lastFocused = LibraryModel.appKey(app);
        }

        @Override
        public void registerContextView(View view) {
            activity.registerForContextMenu(view);
        }

        @Override
        public LibraryPrefs getLibraryPrefs() {
            return prefs;
        }
    }

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.AppTheme);
        activity = controller.setup().get();
        container = new FrameLayout(activity);
        activity.setContentView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host = new StubHost(activity);
        // Window focus events can put the window back in touch mode, so settle first
        settle();
        leaveTouchMode(activity);
        settle();
        leaveTouchMode(activity);
    }

    /** Puts the window in D-pad (non touch) mode, where cards can take focus. */
    private static void leaveTouchMode(Activity activity) {
        try {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        } catch (Throwable ignored) {
            // Fall back to the view root below
        }
        View decor = activity.getWindow().getDecorView();
        if (decor.isInTouchMode()) {
            Object viewRoot;
            try {
                viewRoot = View.class.getMethod("getViewRootImpl").invoke(decor);
            } catch (Exception e) {
                throw new AssertionError("No view root", e);
            }
            for (String name : new String[] {"ensureTouchMode", "ensureTouchModeLocally"}) {
                if (!decor.isInTouchMode()) {
                    break;
                }
                try {
                    Method ensure = viewRoot.getClass().getDeclaredMethod(name, boolean.class);
                    ensure.setAccessible(true);
                    ensure.invoke(viewRoot, false);
                } catch (Exception ignored) {
                    // Try the next way
                }
            }
        }
        assertFalse("window must be out of touch mode", decor.isInTouchMode());
    }

    /** Lets list diffs (on a background thread), layouts and posted focus requests run. */
    private static void settle() {
        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static AppView.AppObject app(int id, String name, String platform) {
        NvApp nvApp = new NvApp(name, "", id, false);
        nvApp.setPlatform(platform);
        return new AppView.AppObject(nvApp);
    }

    private static LibrarySnapshot<AppView.AppObject> snapshot(List<AppView.AppObject> apps) {
        return LibraryModel.build(new LibraryModel.Input<AppView.AppObject>().apps(apps));
    }

    private String focusedAppKey() {
        View focused = activity.getCurrentFocus();
        Object tag = focused != null ? focused.getTag(R.id.tag_app_object) : null;
        return tag instanceof AppView.AppObject ? LibraryModel.appKey((AppView.AppObject) tag) : null;
    }

    /** What the window looks like, for failure messages. */
    private String describe() {
        View decor = activity.getWindow().getDecorView();
        View focused = activity.getCurrentFocus();
        return "touchMode=" + decor.isInTouchMode()
                + " focused=" + (focused != null ? focused.getClass().getSimpleName() : "none")
                + " containerSize=" + container.getWidth() + "x" + container.getHeight()
                + " children=" + (container.getChildCount() > 0 && container.getChildAt(0) instanceof ViewGroup
                        ? ((ViewGroup) container.getChildAt(0)).getChildCount() : -1)
                + " lastFocused=" + host.lastFocused
                + " card=" + describeCard(findCard(container));
    }

    private static View findCard(View view) {
        if (view.getTag(R.id.tag_app_object) != null) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View card = findCard(group.getChildAt(i));
                if (card != null) {
                    return card;
                }
            }
        }
        return null;
    }

    private static String describeCard(View card) {
        if (card == null) {
            return "none";
        }
        return "{focusable=" + card.isFocusable() + " shown=" + card.isShown()
                + " layoutRequested=" + card.isLayoutRequested() + " size=" + card.getWidth() + "x" + card.getHeight()
                + " requestFocus=" + card.requestFocus() + " focusedAfter=" + card.isFocused() + "}";
    }

    private void assertFocused(String appKey) {
        assertEquals(describe(), appKey, focusedAppKey());
    }

    private View attach(LibraryLayoutController controller) {
        View view = controller.onCreateView(LayoutInflater.from(activity), container);
        container.addView(view);
        settle();
        return view;
    }

    private static List<AppView.AppObject> twoGroups() {
        List<AppView.AppObject> apps = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            apps.add(app(i, "Example Game " + i, "PC (Windows)"));
        }
        for (int i = 7; i <= 10; i++) {
            apps.add(app(i, "Sample Game " + i, "Nintendo Switch"));
        }
        return apps;
    }

    @Test
    public void gridColdLoadGetsInitialFocus() {
        GridLayoutController grid = new GridLayoutController(host);
        attach(grid);

        // No cache: the first list is empty, then the network list arrives
        grid.submit(snapshot(new ArrayList<AppView.AppObject>()));
        settle();
        assertFalse("nothing to focus in an empty library", grid.focusFirst());

        grid.submit(snapshot(twoGroups()));
        // Asked right after the submit, before the diff has committed
        assertTrue(grid.focusFirst());
        settle();
        assertFocused("id:1");
        assertEquals("id:1", host.lastFocused);
    }

    @Test
    public void gridKeepsFocusWhenAnEarlierAppIsInserted() {
        GridLayoutController grid = new GridLayoutController(host);
        attach(grid);
        List<AppView.AppObject> apps = twoGroups();
        grid.submit(snapshot(apps));
        assertTrue(grid.focusApp("id:4"));
        settle();
        assertFocused("id:4");

        // A poll adds an app that sorts before the focused one
        apps.add(app(20, "Example Game 0", "PC (Windows)"));
        grid.submit(snapshot(apps));
        settle();
        assertFocused("id:4");

        // And the running state of the focused app changes
        apps.get(3).isRunning = true;
        grid.submit(snapshot(apps));
        settle();
        assertFocused("id:4");
    }

    @Test
    public void gridJumpsBetweenGroups() {
        GridLayoutController grid = new GridLayoutController(host);
        attach(grid);
        grid.submit(snapshot(twoGroups()));
        assertTrue(grid.focusApp("id:2"));
        settle();

        assertTrue(grid.jumpToAdjacentGroup(1));
        settle();
        assertFocused("id:7");
        assertEquals("nintendo_switch", grid.getCurrentGroupKey());

        // Wraps around to the first group
        assertTrue(grid.jumpToAdjacentGroup(1));
        settle();
        assertFocused("id:1");
    }

    private static List<AppView.AppObject> manyShelves() {
        List<AppView.AppObject> apps = new ArrayList<>();
        int id = 1;
        for (int group = 1; group <= 12; group++) {
            for (int i = 1; i <= 8; i++) {
                apps.add(app(id++, String.format("Example %02d-%d", group, i),
                        String.format("Example System %02d", group)));
            }
        }
        return apps;
    }

    @Test
    public void shelvesColdLoadGetsInitialFocus() {
        ShelfLayoutController shelves = new ShelfLayoutController(host);
        attach(shelves);
        shelves.submit(snapshot(new ArrayList<AppView.AppObject>()));
        settle();
        assertFalse(shelves.focusFirst());

        shelves.submit(snapshot(twoGroups()));
        assertTrue(shelves.focusFirst());
        settle();
        assertFocused("id:1");
    }

    @Test
    public void shelvesRememberTheirCardAfterRecycling() {
        ShelfLayoutController shelves = new ShelfLayoutController(host);
        attach(shelves);
        shelves.submit(snapshot(manyShelves()));
        settle();

        // Group 1 is ids 1 to 8: focus its fourth card
        assertTrue(shelves.focusApp("id:4"));
        settle();
        assertFocused("id:4");

        // Far down the list (shelves and cards are recycled on the way), group 12 is ids 89 to 96
        assertTrue(shelves.focusApp("id:94"));
        settle();
        assertFocused("id:94");

        // Back to group 1 (wrapping from the last group): its remembered card, not the first
        assertTrue(shelves.jumpToAdjacentGroup(1));
        settle();
        assertFocused("id:4");

        // And group 12 still remembers its own card
        assertTrue(shelves.jumpToAdjacentGroup(-1));
        settle();
        assertFocused("id:94");
    }

    @Test
    public void shelvesKeepFocusWhenAnEarlierAppIsInserted() {
        ShelfLayoutController shelves = new ShelfLayoutController(host);
        attach(shelves);
        List<AppView.AppObject> apps = twoGroups();
        shelves.submit(snapshot(apps));
        assertTrue(shelves.focusApp("id:5"));
        settle();
        assertFocused("id:5");

        apps.add(app(20, "Example Game 0", "PC (Windows)"));
        shelves.submit(snapshot(apps));
        settle();
        assertFocused("id:5");
    }
}

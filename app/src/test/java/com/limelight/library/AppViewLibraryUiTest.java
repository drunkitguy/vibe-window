package com.limelight.library;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class AppViewLibraryUiTest {
    private static final String HOST_UUID = "test-host-uuid";

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

    @Test
    public void libraryControlsInflate() {
        AppView activity = launch();
        assertNotNull(activity.findViewById(R.id.librarySearch));
        assertNotNull(activity.findViewById(R.id.libraryLayoutToggle));
        assertNotNull(activity.findViewById(R.id.libraryCollapseAll));
        assertNotNull(activity.findViewById(R.id.libraryContainer));
        assertNotNull(activity.findViewById(R.id.profilesButton));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.layoutGrid).getVisibility());
    }

    @Test
    public void restoredQueryIsVisible() {
        Context context = ApplicationProvider.getApplicationContext();
        new LibraryPrefs(context, HOST_UUID).setQuery("example");

        AppView activity = launch();
        EditText search = activity.findViewById(R.id.librarySearch);
        assertEquals("example", search.getText().toString());
    }

    @Test
    public void yFocusesSearchAndBackClearsIt() {
        AppView activity = launch();
        EditText search = activity.findViewById(R.id.librarySearch);

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y));
        assertTrue(search.hasFocus());

        search.setText("sample");
        ShadowLooper.idleMainLooper();
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals("", search.getText().toString());
        assertTrue("first back only clears the query", !activity.isFinishing());
    }
}

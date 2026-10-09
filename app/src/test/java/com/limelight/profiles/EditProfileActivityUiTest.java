package com.limelight.profiles;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CompoundButton;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.EditProfileActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class EditProfileActivityUiTest {

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        ProfilesManager.instance = null;
        ProfilesManager.getInstance().load(context);
    }

    private static void idle() {
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    private static int adapterPosition(RecyclerView list, Preference preference) {
        return ((PreferenceGroup.PreferencePositionCallback) list.getAdapter())
                .getPreferenceAdapterPosition(preference);
    }

    @Test
    public void newProfileInflatesWithTheSettingsRowsAndJumpsBetweenCategories() {
        EditProfileActivity activity = Robolectric.buildActivity(EditProfileActivity.class).setup().get();
        idle();

        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        assertNotNull("Profile preferences should be attached", fragment);
        PreferenceFragmentCompat prefs = (PreferenceFragmentCompat) fragment;
        RecyclerView list = prefs.getListView();
        assertNotNull(list);

        // Checkbox rows use the switch widget here too
        int position = adapterPosition(list, prefs.findPreference("checkbox_enable_audiofx"));
        @SuppressWarnings("unchecked")
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                (RecyclerView.Adapter<RecyclerView.ViewHolder>) list.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list, adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        View widget = holder.itemView.findViewById(android.R.id.checkbox);
        assertTrue(widget instanceof CompoundButton);

        // R1 and L1 jump between categories as in the settings screen
        LinearLayoutManager layoutManager = (LinearLayoutManager) list.getLayoutManager();
        int audioPosition = adapterPosition(list, prefs.findPreference("category_audio_settings"));
        int gamepadPosition = adapterPosition(list, prefs.findPreference("category_gamepad_settings"));

        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1));
        idle();
        assertEquals(audioPosition, layoutManager.findFirstVisibleItemPosition());

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
        idle();
        assertEquals(gamepadPosition, layoutManager.findFirstVisibleItemPosition());

        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
        idle();
        assertEquals(audioPosition, layoutManager.findFirstVisibleItemPosition());
    }
}

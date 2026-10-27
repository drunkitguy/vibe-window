package com.limelight.preferences;

import android.app.GameManager;
import android.content.Context;
import android.os.Build;
import android.os.Looper;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import java.time.Duration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Shared setup for the settings screen tests. */
final class SettingsTestUtil {
    private SettingsTestUtil() {}

    static void resetEnvironment() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        UiState.prefs(ctx).edit().clear().commit();
        ctx.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();
        Shadows.shadowOf((android.app.Application) ctx)
                .setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
    }

    static void idle() {
        for (int i = 0; i < 5; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
    }

    static StreamSettings launchActivity() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        idle();
        return activity;
    }

    static StreamSettings.SettingsFragment fragmentOf(StreamSettings activity) {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertNotNull(fragment);
        return (StreamSettings.SettingsFragment) fragment;
    }

    static StreamSettings.SettingsFragment launch() {
        return fragmentOf(launchActivity());
    }

    /** Opens every collapsed category, as tapping each "Show more" row would. */
    static void expandAll(StreamSettings.SettingsFragment fragment) {
        for (int round = 0; round < 40; round++) {
            PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
            Preference expander = null;
            for (int i = 0; i < adapter.getItemCount(); i++) {
                Preference item = adapter.getItem(i);
                if (item != null && item.getClass().getSimpleName().equals("ExpandButton")) {
                    expander = item;
                    break;
                }
            }
            if (expander == null) {
                return;
            }
            expander.performClick();
            idle();
        }
    }

    static int position(StreamSettings.SettingsFragment fragment, String key) {
        Preference preference = fragment.findPreference(key);
        assertNotNull(key, preference);
        return ((PreferenceGroup.PreferencePositionCallback) fragment.getListView().getAdapter())
                .getPreferenceAdapterPosition(preference);
    }

    static RecyclerView.ViewHolder bindRow(StreamSettings.SettingsFragment fragment, String key) {
        RecyclerView list = fragment.getListView();
        int position = position(fragment, key);
        assertTrue(key + " should be in the list", position >= 0);
        @SuppressWarnings("unchecked")
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                (RecyclerView.Adapter<RecyclerView.ViewHolder>) list.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list, adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        return holder;
    }

    /** Scrolls a row into the real list, leaves touch mode and focuses it, as a controller would. */
    static View focusRow(StreamSettings.SettingsFragment fragment, String key) {
        RecyclerView list = fragment.getListView();
        int position = position(fragment, key);
        assertTrue(key + " should be in the list", position >= 0);
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        idle();
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull(key + " should be laid out", holder);
        com.limelight.PcViewUiTest.exitTouchMode(list);
        assertTrue(key + " should take the focus", holder.itemView.requestFocus());
        return holder.itemView;
    }

    /** The row of a key that the real list currently shows, or null. */
    static View shownRow(StreamSettings.SettingsFragment fragment, String key) {
        RecyclerView.ViewHolder holder = fragment.getListView().findViewHolderForAdapterPosition(position(fragment, key));
        return holder == null ? null : holder.itemView;
    }

    /** Presses and releases a key through the activity, so it reaches the focused row. */
    static boolean press(Activity activity, int keyCode) {
        boolean down = activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return down;
    }
}

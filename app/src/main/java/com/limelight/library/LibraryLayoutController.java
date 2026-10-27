package com.limelight.library;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.AppView;

/**
 * One way of showing the library (grid, wheel or shelves). Every controller
 * renders the same {@link LibrarySnapshot} and reports clicks and focus back to
 * its {@link Host}.
 */
public interface LibraryLayoutController {
    interface Host {
        Context getContext();

        AppLibrary getAppLibrary();

        boolean isSmallIconMode();

        /** Tap or A on an app card. */
        void onAppClicked(AppView.AppObject app, View view);

        /** Tap or A on a group header. */
        void onGroupHeaderClicked(String groupKey);

        /** The focused (grid, shelves) or selected (wheel) app changed. */
        void onAppFocused(AppView.AppObject app);

        /** Makes a view open the app context menu on long press. */
        void registerContextView(View view);

        LibraryPrefs getLibraryPrefs();
    }

    /** One of the {@link LibraryPrefs} layout names. */
    String getLayoutName();

    View onCreateView(LayoutInflater inflater, ViewGroup container);

    void onDestroyView();

    /** Shows a new snapshot, keeping the focused or selected app where possible. */
    void submit(LibrarySnapshot<AppView.AppObject> snapshot);

    /** Rebinds every card (the art loader may have changed) and recomputes sizes. */
    void onConfigurationChanged();

    /** Focuses or selects the app; returns false when it is not shown. */
    boolean focusApp(String appKey);

    /** Focuses the first app or header. Returns false when there is nothing to focus. */
    boolean focusFirst();

    /** True when a card, header or wheel inside this layout has focus. */
    boolean hasFocus();

    /** The focused or selected app, or null. */
    AppView.AppObject getCurrentApp();

    /** The view to anchor the context menu of the current app on, or null. */
    View getCurrentAppView();

    /** L1 / R1: jump to the previous or next group, wrapping around. */
    boolean jumpToAdjacentGroup(int direction);

    /** The group of the focused card or header (Start toggles it), or null. */
    String getCurrentGroupKey();

    boolean supportsCollapse();
}

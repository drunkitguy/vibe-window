# Plan: simple inline settings and split Settings/Hosts screen for Vibe Window 1.4.0

Status: approved plan, edited by the Manager. Implemented step by step on this branch for 1.4.0.

Revision read: drunkitguy/vibe-window branch claude/clever-clarke-k7hhhj at de0b1ec (VERSION 1.3.5). The tv/ folder is out of scope.

Reference: four landscape screenshots of the VoidLink iOS client supplied by the user. VoidLink is the visual and interaction reference only. No VoidLink name, logo, icon, asset or wording goes into the app; every label below is our own wording and every icon is a new Material Symbols style vector drawable.

## 0. What the reference does, and what we take from it

Observed in the screenshots:

- Landscape split screen. Left: a Settings panel (about 40 percent of the width, background about #1C1C1E) with its own header: a round icon button on the left that collapses the panel, the title "Settings" centered, and a round "more" button on the right. Right: the Hosts panel on true black, with a round icon button and the title, and a host card.
- Settings is one flat scrolling list. No category dialogs and no long summaries. Each setting is a title line with its control directly below or beside it:
  - segmented pill controls (resolution, frame rate, codec, statistics overlay, touch mode, gesture options, rotation), with the current value in accent text on the title line when it is not obvious from the segments (for example the resolved resolution);
  - sliders with the value in accent text on the title line (bitrate "39.0 Mbps", a percentage, or a word such as "keep alive");
  - toggles (green when on) with the title on the left;
  - a small circled "i" button at the right edge that explains the setting.
- Host card: rounded card, large icon tile (accent when online, dark with a lock or dim glyph when offline), host name in bold, a status line with a small icon ("Offline" with a warning triangle, "Detecting..." with an antenna), a hairline, and a full-width gray Wake-on-LAN button.

What we take: the one-list structure, inline controls, the value-on-the-title-line pattern, info buttons instead of summaries, the split layout, and the card proportions. What we do not take: the exact wording, icons, the right-aligned "Hosts" title, the green toggle color (we keep our accent switch track so the app stays one color family; see 2.4 for the reason), and the ellipsized segment labels ("Touchp...") that the reference shows; we use short labels and a fallback instead.

## 1. Constraints (from the request, restated as rules for the Coder)

1. Every preference key in res/xml/preferences.xml at de0b1ec stays, with the same stored type (String for lists and edit texts, boolean for checkboxes, int for seek bars), the same entry values, the same defaults and the same meaning. Profiles (ProfilesManager, EditProfileActivity overlays) and the migrations (migrated_auto_display_v120, migrated_frame_pacing_newest_v130, migrated_prevent_packet_loss_v130, restored_122_defaults_v134, restored_122_profiles_v135) depend on them.
2. No new key is written into the default SharedPreferences. EditProfileActivity.ProfilePreferenceFragment.diff() puts every key that exists in the base preferences but not in the profile into the patch ("unknown key, include"), so a new stored key would leak into every saved profile. UI-only state (panel open, show descriptions) goes into a separate file, getSharedPreferences("vw_ui_state", MODE_PRIVATE). New preferences that only drive other keys are setPersistent(false).
3. Preference classes may change only to subclasses of the current class (a SegmentedListPreference is a ListPreference, a switch row is a CheckBoxPreference), so every existing cast in StreamSettings ((ListPreference), (CheckBoxPreference), (SeekBarPreference)) and every OnPreferenceChangeListener keeps working.
4. No new third-party library. Material Components 1.13.0 and androidx.preference as today.
5. Streaming code is untouched: Game.java, the binding/ packages, nvstream/, PreferenceConfiguration.readPreferences() semantics. PreferenceConfiguration may only gain static helpers if needed (none are planned).
6. Everything works with a controller alone and with touch alone.
7. No em dashes, no game titles in new strings.

## 2. Information architecture

### 2.1 Structure of the one list

One PreferenceScreen, inflated from res/xml/preferences.xml as today, so StreamSettings.SettingsFragment, its listeners and the EditProfileActivity subclass keep seeing every key through findPreference(). It is rebuilt in two parts:

- Part A, "quick" settings: four new PreferenceCategory groups at the top with small caption headers (not cards): Stream, Touch and controls, Audio, App. About 21 rows. These are the settings most users change. Rows are flat on the panel surface, separated by spacing only, like the reference.
- Part B, "All settings": a caption "All settings" followed by the existing categories (Video, Audio, Gamepad, Input, Host, General, UI, On-screen controls, Special keys, Virtual trackpad, Performance overlay, Advanced, Misc) holding every remaining key. Each category starts collapsed with app:initialExpandedChildrenCount="0", so it shows only its header and one "Show N settings" row (the existing expand_button.xml look). These keep the grouped card look (GroupedCardDecoration) so the advanced part reads as a separate, denser area.

Why one screen and not sub-screens or the "..." menu: androidx nested screens (rootKey) create a new fragment per screen, and then findPreference() in SettingsFragment no longer finds keys from other screens. The resolution, FPS, bitrate, HDR and auto-hint code reads across categories. Keeping one screen is the only change that keeps every listener intact, keeps the L1/R1 section jumps, and keeps EditProfileActivity working with no structural change.

The "..." button opens a small menu (PopupMenu, controller navigable) with: Jump to section (submenu of the visible category titles, uses the existing jumpToCategory logic generalized to a target index), Show descriptions (checkable; when on, rows show their summary text under the title instead of the info button; stored in vw_ui_state), Profiles (opens ProfilesActivity, hidden inside EditProfileActivity), Help (HelpActivity as the rail button does).

The rows that move into Part A are moved in the XML (the same element with the same key, moved to the new parent). No key appears twice. Composite rows (2.3) are new non-persistent preferences that read and write existing keys.

### 2.2 Part A rows, in order

Control types: SEG = segmented control (2.4), SLIDER = inline slider (2.5), SWITCH = switch row, VALUE = accent value text on the title line, INFO = info button (2.6).

Caption "Stream" (new key category_quick_stream, no stored value)

| # | Key | Control | Segments (value: label) and notes |
|---|-----|---------|-----------------------------------|
| 1 | list_resolution | SEG + VALUE + INFO | auto: Auto, 1280x720: 720p, 1920x1080: 1080p, 2560x1440: 1440p, 3840x2160: 4K, More. Value text: the auto hint ("1920 x 1080 on this screen") when Auto, else the entry name. 360p, 480p, native, native full-screen and the custom resolution entries are reached through More. Segments are shown only for values still present in the entries after the decoder and display filtering (removeValue), so a device without 4K shows no 4K segment. |
| 2 | list_fps | SEG + VALUE + INFO | auto: Auto, 30, 60, 90, 120, More. Value text: resolved rate when Auto. Native and custom refresh entries through More. Same filtering rule (90 and 120 disappear when removeEntryFromListAndSetValue removed them). |
| 3 | seekbar_bitrate_kbps | SLIDER + VALUE + INFO | Value "39.0 Mbps" (existing divisor 1000, one decimal). When bitrate_follows_resolution is true the value text gets a second word "Auto" in secondary color. |
| 4 | video_format | SEG + INFO | auto: Auto, neverh265: H.264, forceh265: HEVC, forceav1: AV1. All four entries fit, so no More. |
| 5 | checkbox_enable_hdr | SWITCH + INFO | Removed or disabled exactly as today (see 3.6 for the code change this needs). |
| 6 | (removed) | | Manager decision: frame_pacing stays in Part B Video as a DD row. The 1.3.x stutter came from non-default pacing, so the low-latency modes must not sit one tap away at the top. Default stays balanced. |
| 7 | list_video_scale_mode | SEG | fit: Fit, fill: Fill, stretch: Stretch. |
| 8 | checkbox_window_only | SWITCH + INFO | The product's core option; same default (true). |
| 9 | checkbox_use_virtual_display | SWITCH + INFO | Same default (true). |
| 10 | quick_stats_overlay (new, not persisted) | SEG + INFO | Off, Lite, Full. Composite of checkbox_enable_perf_overlay and checkbox_enable_perf_overlay_lite, see 2.3. |

Caption "Touch and controls" (category_quick_controls)

| # | Key | Control | Notes |
|---|-----|---------|-------|
| 11 | mouse_mode_list | SEG + VALUE + INFO | 0: Touch, 2: Trackpad, 1: Pointer, 4: Off, More. More reaches 3 (trackpad, hold to drag) and 5 (pointer with swapped clicks). Value text shows the full entry name. |
| 12 | checkbox_show_onscreen_controls | SWITCH + INFO | Removed on devices without a touchscreen, see 3.6. |
| 13 | seekbar_osc_opacity | SLIDER + VALUE | Value "90%". Keeps its dependency on checkbox_show_onscreen_controls (disabled look when off). Removed together with row 12. |
| 14 | checkbox_multi_touch_gestures | SWITCH + INFO | 3, 4 and 5 finger taps. |
| 15 | checkbox_enable_rumble | SWITCH | |
| 16 | checkbox_flip_face_buttons | SWITCH + INFO | |
| 17 | checkbox_mouse_emulation | SWITCH + INFO | Stays the dependency of analog_scrolling and checkbox_remember_mouse_mode (androidx dependencies work across categories). |

Caption "Audio" (category_quick_audio)

| # | Key | Control | Notes |
|---|-----|---------|-------|
| 18 | list_audio_config | SEG | 2: Stereo, 51: 5.1, 71: 7.1. |
| 19 | checkbox_host_audio | SWITCH + INFO | |

Caption "App" (category_quick_app)

| # | Key | Control | Notes |
|---|-----|---------|-------|
| 20 | checkbox_enable_pip | SWITCH + INFO | Removed on devices without PiP, see 3.6. |
| 21 | checkbox_resume_without_confirm | SWITCH + INFO | |
| 22 | checkbox_enable_quit_dialog | SWITCH + INFO | Stays the dependency of checkbox_enable_floating_button. |

### 2.3 Composite row: statistics overlay

quick_stats_overlay is a new SegmentedPreference subclass with setPersistent(false) and no default value, so PreferenceManager.setDefaultValues writes nothing and profiles never see it.

- Reading: Off when checkbox_enable_perf_overlay is false; Lite when it is true and checkbox_enable_perf_overlay_lite is true; Full when it is true and lite is false. Values are read from the fragment's getPrefs() (base or profile in-memory prefs), so it is correct inside EditProfileActivity.
- Writing: it calls setChecked() on the two real CheckBoxPreference objects (found with findPreference), which persists through the active data store and notifies their dependents (checkbox_enable_perf_overlay_lite_dialog depends on lite; bottom and logging depend on the overlay). Off sets only the overlay to false and leaves the lite value as stored, so switching back to Full or Lite restores the remembered sub-mode as the old UI did.
- The two real rows stay in the Performance overlay category but are setVisible(false), so each key has exactly one visible control. Dependencies on an invisible preference still work.
- Profile highlighting: highlightPreferences() also marks quick_stats_overlay when either backing key is in changedKeys.

No other composite rows in 1.4.0.

### 2.4 Every key and where it goes

Legend for "Part B control": SW switch, SLD inline slider, SEG segmented, DD dropdown row (accent value text plus chevron, A or tap opens the existing list dialog), ET edit text row (value in accent text, opens the existing dialog), ACT action row (keeps its one-line summary because it describes an action, not a value).

Part A (moved, see 2.2): list_resolution, list_fps, seekbar_bitrate_kbps, video_format, checkbox_enable_hdr, list_video_scale_mode, checkbox_window_only, checkbox_use_virtual_display, mouse_mode_list, checkbox_show_onscreen_controls, seekbar_osc_opacity, checkbox_multi_touch_gestures, checkbox_enable_rumble, checkbox_flip_face_buttons, checkbox_mouse_emulation, list_audio_config, checkbox_host_audio, checkbox_enable_pip, checkbox_resume_without_confirm, checkbox_enable_quit_dialog. Represented in Part A through the composite: checkbox_enable_perf_overlay, checkbox_enable_perf_overlay_lite (rows hidden in Part B).

Part B, by category (order inside each category as today, minus the moved keys):

- category_video_settings (Video): frame_pacing DD (first row, unchanged entries and default); seekbar_metered_bitrate_kbps SLD; checkbox_auto_res_prefer_external SW; pref_low_latency_frame_balance SW; checkbox_ultra_low_latency SW; checkbox_vendor_decoder_keys SW (default false, as in 1.3.4); checkbox_forceTightThresholds SW; checkbox_auto_orientation SW; checkbox_auto_invert_video_resolution SW (dependency kept); checkbox_enable_view_top_center SW; seekbar_resolution_scale_factor SLD; edit_diy_w_h ET; edit_diy_bitrate ET; custom_refresh_rate ET; checkbox_enforce_display_mode SW; render_mode_list SEG (0: 2D, 1: 3D, 2: 3D film; values 0, 1, 2 as in render_mode_values); parallax_depth SLD; convergence_ratio SLD; balance_shift SLD; checkbox_enable_fullexdisplay SW.
- category_audio_settings (Audio): checkbox_enable_audiofx SW.
- category_gamepad_settings (Gamepad): seekbar_deadzone SLD; checkbox_multi_controller SW; checkbox_usb_driver SW; checkbox_usb_bind_all SW; analog_scrolling SEG (None, Right, Left); checkbox_gamepad_touchpad_as_mouse SW; checkbox_gamepad_motion_sensors SW; checkbox_gamepad_motion_fallback SW; checkbox_force_device_motion SW; checkbox_enable_joyconfix SW; checkbox_gamepad_enable_battery_report SW; checkbox_vibrate_fallback SW; checkbox_enable_device_rumble SW; seekbar_vibrate_fallback_strength SLD.
- category_input_settings (Input): checkbox_remember_mouse_mode SW; checkbox_mouse_local_cursor SW; checkbox_mouse_nav_buttons SW; checkbox_absolute_mouse_mode SW; seekbar_trackpad_sensitivity_x SLD; seekbar_trackpad_sensitivity_y SLD; checkbox_trackpad_drag_drop_vibration SW; seekbar_trackpad_drag_drop_threshold SLD; checkbox_trackpad_swap_axis SW; checkbox_force_qwerty SW; checkbox_back_as_meta SW; checkbox_right_alt_as_meta SW; checkbox_back_as_guide SW; checkbox_ignore_synth_events SW; checkbox_enable_commit_text SW.
- category_host_settings (Host): checkbox_enable_sops SW.
- category_general_settings (Clipboard; title unchanged): checkbox_smart_clipboard_sync SW; checkbox_smart_clipboard_sync_toast SW; checkbox_hide_clipboard_content SW.
- category_ui_settings (Interface): checkbox_enable_floating_button SW; checkbox_full_screen SW; seekbar_keyboard_axi_opacity SLD; onscreen_keyboard_autofit SW; seekbar_onscreen_keyboard_height SLD; seekbar_onscreen_keyboard_width SLD; list_onscreen_keyboard_align_mode SEG (Left, Center, Right); checkbox_enable_sticky_modifier_key_virtual_keyboard SW; checkbox_disable_warnings SW; checkbox_show_overlay_zoom_toggle_button SW; checkbox_remember_zoom_pan SW; checkbox_enable_post_stream_toast SW; checkbox_small_icon_mode SW (SmallIconCheckboxPreference).
- category_onscreen_controls (On-screen controls): checkbox_hide_osc_when_has_gamepad SW; checkbox_onscreen_style_official SW; checkbox_vibrate_osc SW; checkbox_only_show_L3R3 SW; checkbox_show_guide_button SW; checkbox_enable_analog_stick_new SW; seekbar_osc_free_analog_stick_opacity SLD; option_reset_osc_preference ACT. All keep their dependency on checkbox_show_onscreen_controls, which now lives in Part A.
- category_special_key_layout (Special keys): checkbox_enable_keyboard SW; checkbox_enable_clear_default_special_button SW; checkbox_vibrate_keyboard SW; keyboard_axi_list DD (its five entry names are identical, so segments would be meaningless); checkbox_enable_keyboard_square SW; option_reset_osc ACT; import_keyboard_file ACT; export_keyboard_file ACT.
- category_virtual_trackpad_settings (Virtual trackpad): checkbox_enable_touch_sensitivity SW; checkbox_enable_global_touch_sensitivity SW; checkbox_enable_touch_sensitivity_rotation_auto SW; seekbar_touch_sensitivity_opacity_x SLD; seekbar_touch_sensitivity_opacity_y SLD; seekbar_touchpad_sensitivity_opacity SLD; seekbar_touchpad_sensitivity_y_opacity SLD.
- category_perf_monitor_settings (Performance overlay): checkbox_enable_perf_overlay (hidden, driven by the composite); checkbox_enable_perf_overlay_lite (hidden, driven by the composite); checkbox_enable_perf_overlay_lite_dialog SW; checkbox_enable_perf_overlay_bottom SW; checkbox_enable_perf_logging SW; share_performance_logs ACT; option_view_shared_pref_logs ACT.
- category_advanced_settings (Advanced): checkbox_unlock_fps SW (its listener that rebuilds the FPS list must also rebind the FPS segments, see 3.2); checkbox_reduce_refresh_rate SW; checkbox_full_range SW; checkbox_prevent_packet_loss SW (default false); checkbox_keepalive_per_frame SW (default false, dependency on checkbox_prevent_packet_loss kept).
- category_settings_misc (Misc): list_languages DD (LanguagePreference); import_special_button_file ACT; option_help_custom_keys ACT; pref_debug_info ACT; option_software_release ACT.

Category keys stay: category_video_settings, category_audio_settings, category_gamepad_settings, category_input_settings, category_host_settings, category_general_settings, category_ui_settings, category_onscreen_controls, category_special_key_layout, category_virtual_trackpad_settings, category_perf_monitor_settings, category_advanced_settings, category_settings_misc. New: category_quick_stream, category_quick_controls, category_quick_audio, category_quick_app, category_all_settings (caption only), quick_stats_overlay. None of them persists a value.

Keys that exist only in code (bitrate_follows_resolution, the migration and restore flags, number_zoom_scale, number_pan_offset_x/y, legacy keys such as checkbox_51_surround, checkbox_stretch_video, seekbar_bitrate, list_resolution_fps, checkbox_disable_frame_drop, checkbox_enforce_refresh_rate, checkbox_joycon_fix, checkbox_touchscreen_trackpad) have no row today and get none; they are not touched.

The Part B "Audio" category keeps one row. That is acceptable; merging it into Part A would change nothing for the user and removing the category would need its key gone from the L1/R1 list, which already skips removed categories.

## 3. Controls on androidx.preference

All new classes live in com.limelight.preferences. Layouts under res/layout/vw_pref_*.xml. Keep the androidx ids (@android:id/title, @android:id/summary, @android:id/widget_frame, @android:id/icon_frame) so the base classes bind as today.

### 3.1 Row model and focus (all row types)

- The whole row (the RecyclerView item view) stays the only focusable element, exactly as today. Child controls (toggle group buttons, the SeekBar, the info button, the switch) are setFocusable(false) but stay clickable, so touch hits them directly and the D-pad moves row by row with no focus traps.
- Focus ring: the existing vw_pref_row_background (surface_high fill, 2dp accent ring, 12dp radius). On segmented and slider rows the ring encloses title and control.
- The row layout gets a trailing info button (vw_pref_info, 40dp touch target, 24dp ic_info_outline vector, accent tint). For checkbox rows the order on the right is switch then info.
- Shared binder: a small package-private class PrefRowBinder with static bind(PreferenceViewHolder, Preference, boolean showDescriptions) used by every new subclass in onBindViewHolder after super. It hides the summary view and shows the info button when the summary is non-empty and descriptions are off; shows the summary and hides the info button when descriptions are on; puts the value text (accent, TextAppearance.Vibe.Label) on the title line when the preference provides one.
- Key handling: the fragment installs one View.OnKeyListener on each bound row (from the binder) that forwards to an interface RowKeyHandler implemented by the segmented and slider preferences. Rows that do not use left and right return false, so the default focus search (and the split-panel rule in 4.4) applies.
- Y on a focused row opens its info dialog (both in StreamSettings and the split panel, where Y otherwise opens Profiles from the hosts side).

### 3.2 SegmentedListPreference extends ListPreference

- XML: replace the element name for the keys marked SEG; keep every attribute. New attributes in res/values/attrs.xml (declare-styleable SegmentedListPreference): app:segmentValues (string-array of entry values to show as segments, in order), app:segmentLabels (string-array of short labels, same length), app:showMoreSegment (boolean).
- Layout vw_pref_segmented.xml: title line (title, value text, info button), then a MaterialButtonToggleGroup (singleSelection true, selectionRequired true) built in code with one MaterialButton per segment, style Widget.Vibe.Segment (new: 36dp height, pill shape via ShapeAppearance with 50 percent corners, unchecked transparent on a vw_surface_high track, checked vw_accent_container with white text, 14sp medium, maxLines 1).
- Binding: segments = configured values that are present in getEntryValues() at bind time (the fragment adds and removes entries at runtime), plus More when showMoreSegment is set and at least one entry is not a segment. Checked segment = the one equal to getValue(); if the value is not a segment, More is checked and the value text shows getEntry().
- Commit path, identical to the list dialog: commit(String v) { if (callChangeListener(v)) setValue(v); } and then notifyChanged() to rebind. So the resolution listener (native warning, resetBitrateToDefault, auto hint), the FPS listener and the bitrate follow logic run exactly as when picking in the dialog.
- More, or A on the row: calls the inherited onClick(), which shows the existing ListPreferenceDialogFragmentCompat with all entries. Touching a segment commits immediately.
- Gamepad: Left and Right move a pending highlight (outlined ring on the segment, not yet committed; More counts as a segment), A commits the highlighted segment (More opens the dialog), B or moving focus off the row without A drops the highlight and leaves the value unchanged. With no pending highlight, A opens the full dialog. Rationale: committing on every Left or Right would fire the native-resolution warning dialog and reset the bitrate while the user is only passing through. Left at the first and Right at the last segment are consumed (no wrap, no focus escape) so the user does not fall out of the row by accident.
- External value changes: when the unlock FPS listener or removeEntryFromListAndSetValue rewrites entries or the value, they already call setEntries / setValue, which call notifyChanged, so the row rebinds. Verify in a test.
- Large text and narrow panels: a pure helper SegmentFit.fits(float[] labelWidthsPx, float availablePx, float minPaddingPx) decides after measuring with the button's TextPaint. If it does not fit (font scale 1.3 and up, or many segments in a 340dp panel), the row renders as a DD row instead (value text plus chevron, A or tap opens the dialog, Left and Right still step through the segment values with the same pending rule). No ellipsized labels.
- Accessibility (kept minimal, manager decision): the row's AccessibilityNodeInfo gets the title as label and stateDescription (API 30+, else appended to contentDescription) = current entry name. Custom actions are a follow-up. With TalkBack touch exploration each MaterialButton is checkable and reports its checked state; each gets contentDescription "<title>: <full entry name>".
- LanguagePreference stays a ListPreference subclass rendered as DD (via the same binder, no segments).

### 3.3 SeekBarPreference gains an inline mode

Keep the class name com.limelight.preferences.SeekBarPreference (StreamSettings casts it for the bitrate setProgress), and all its attributes and the dialog.

- New attribute seekbar:inline (default true for every seek bar row in 1.4.0). Inline rows use vw_pref_slider.xml: title line (title, value text, info button), then a full-width SeekBar styled with an accent progress track and a white round thumb (new drawable vw_slider_thumb, vw_slider_track).
- Extract the dialog's OK handler into one method commitValue(int value) { currentValue = value; persistInt(value); callChangeListener(value); } so dialog and inline paths have the same order and semantics as 1.3.5. Do not change the order in this release even though callChangeListener normally runs before persisting; the bitrate listener depends on reading the new value.
- The value text uses the existing formatting (divisor, one decimal, suffix with a space when longer than one character), extracted into a static formatValue(int, int divisor, String suffix) for tests.
- Touch: the value text updates while dragging; commitValue runs in onStopTrackingTouch, so one drag is one change (the bitrate stops following the resolution only once).
- Gamepad: Left and Right step by keyStep when set, else step; holding (repeat count above 8) steps five times faster. The value text updates at once; commitValue runs 600 ms after the last key or immediately when focus leaves the row. A on the row opens the existing dialog for precise adjustment. Left at min and Right at max are consumed.
- Fix while here, test-pinned: getProgress() returns currentValue + minValue, which is wrong (currentValue is already the absolute value). Search shows no caller outside the class; make it return currentValue and add a test, or leave it and add a comment. Decision (manager): leave it unchanged and add a comment; it is out of scope.
- Dependency disabled state: the SeekBar and value text use the enabled state of the row (androidx sets enabled on the item view; the binder propagates setEnabled to the SeekBar).
- Accessibility (minimal): stateDescription = formatted value on the row. RangeInfo and progress actions are a follow-up.

### 3.4 Switch rows

- Checkbox rows keep CheckBoxPreference (the switch look already comes from vw_pref_switch.xml with a MaterialSwitch bound to @android:id/checkbox). New subclass VwSwitchPreference extends CheckBoxPreference only to call PrefRowBinder (info button, summary hiding). SmallIconCheckboxPreference changes its parent to VwSwitchPreference. The HDR cast (CheckBoxPreference) still holds.
- Layout: title on the left, switch then info button on the right, as today plus the info button.
- Track color stays the accent family (vw_accent_container) instead of green: the app uses green (vw_success) for "online" on host cards, and mixing it into settings would blur that meaning. This is a deliberate difference from the reference.
- A toggles, as today.

### 3.5 Info dialog

- MaterialAlertDialogBuilder with ThemeOverlay.Vibe.Dialog: title = preference title, message = the summary text that the row used to show (including dynamic summaries such as the auto hint and the SHIELD HDR message), plus for list rows a last line "Current: <entry>". One button "OK", focused by default, so A or B closes it.
- Preferences with no summary have no info button.
- Summaries are not rewritten in this release except where a row is new (quick_stats_overlay gets a new summary string) and where a summary only repeated the value (summary_resolution_list and summary_fps_list keep their text in the dialog; the value now shows on the title line).

### 3.6 StreamSettings changes required by moving keys

Today several removals go through a fixed category, for example category_video_settings.removePreference(findPreference("checkbox_enable_hdr")) and category.findPreference("checkbox_enable_hdr") followed by a (CheckBoxPreference) cast. After the move these return false or null, the HDR row would stay visible on devices without HDR10, and the SHIELD branch would throw a NullPointerException.

- Add one helper in SettingsFragment: removePreferenceByKey(String key) that finds the preference and removes it from preference.getParent() (androidx.preference 1.1+), ignoring missing keys.
- Replace every category-scoped removal in initializePreferences() with it (lines 673 to 760 and 964 to 999 at de0b1ec): checkbox_absolute_mouse_mode, checkbox_gamepad_motion_sensors, checkbox_force_device_motion, checkbox_gamepad_motion_fallback, checkbox_usb_bind_all, checkbox_usb_driver, checkbox_enable_pip, checkbox_vibrate_fallback, seekbar_vibrate_fallback_strength, checkbox_vibrate_osc, checkbox_vibrate_keyboard, checkbox_enable_device_rumble, checkbox_enable_hdr. The SHIELD branch uses findPreference directly.
- The no-touchscreen branch also removes the moved rows checkbox_show_onscreen_controls and seekbar_osc_opacity (they used to go away with category_onscreen_controls). Dependents of a removed preference: androidx logs and ignores a missing dependency only at attach time if the dependency key is absent; since the whole on-screen controls category is removed in the same branch, nothing depends on them. Pin with a test on a no-touchscreen Robolectric config.
- After all removals, a Part A caption category with no visible children is hidden (for example App on a device without PiP still has two rows, so it stays).
- The unlock FPS listener and the native resolution and frame rate code are unchanged; the segmented rows read entries at bind time.
- Add a public no-argument constructor to SettingsFragment that reads PreferenceConfiguration in onCreate when prevPrefConfig is null. FragmentManager re-instantiates fragments with the no-argument constructor after process death; today StreamSettings replaces the fragment in onAttachedToWindow, but the split panel (4.2) adds the fragment to PcView, whose state restore would otherwise crash. Keep the existing constructor. ProfilePreferenceFragment gets the same treatment (it reads the in-memory prefs from its activity).
- GroupedCardDecoration: register only the Part B category header view type as a card header; the Part A caption categories use a different layout (vw_pref_caption.xml), so they get a different view type and their rows are not carded. The decoration needs "start a card after a Part B header, never around Part A rows": add a set of "flat" view types (segmented, slider and switch rows inside Part A share layouts with Part B, so the rule is positional: no cards before the category_all_settings caption). Implement as a pure function over the adapter item types plus the index of the All settings caption; extend GroupedCardDecorationTest.
- L1 and R1 keep jumping between visible categories; captions count as categories, so Stream, Touch and controls, Audio, App, then each Part B category.
- Show descriptions: read from vw_ui_state in onCreate; toggling it from the menu calls getListView().getAdapter().notifyDataSetChanged().

### 3.7 Settings header

activity_stream_settings.xml keeps settingsBackButton and stream_settings, and gets: a round back button on the left (48dp circle, vw_surface_high fill, accent icon; in the split panel this is the collapse button with a new ic_side_panel vector), the title centered in TextAppearance.Vibe.Title, and a round more button (settingsMoreButton, ic_more_horiz) on the right that opens the menu in 2.1. Controller: the header buttons are reachable with Up from the first row; Select also opens the menu.

## 4. Split layout: Settings left, Hosts right

### 4.1 Decision

Worth doing, as the second half of the release and behind a width rule, because it is what makes the reference feel simple: settings are one glance away from the host you are about to start, with no screen change. It is not the default on narrow screens. The Thor's width in dp is unknown here (it depends on its density; verify with adb shell wm density): at 1920 px and density 2.625 it is 731dp, at 3.0 it is 640dp.

- Split when the window is landscape and its width is at least 720dp (WindowMetrics or Configuration.screenWidthDp). Settings panel width = clamp(42 percent, 340dp, 460dp); hosts get the rest.
- Below 720dp, or in portrait, nothing changes: the Settings rail button and Start open StreamSettings full screen as in 1.3.5, now with the new rows.
- If the Thor turns out to be under 720dp, the full-screen settings still deliver the main request (simple inline settings); the split can be enabled there later by lowering the threshold once host cards are confirmed to fit (see 5).

### 4.2 Implementation

- New res/layout-land/activity_pc_view.xml variant is not enough because the rule is width based; PcView inflates one layout and decides at runtime: a horizontal LinearLayout root with settingsPanel (FrameLayout, id settings_panel, background vw_surface) and the existing content (rail and pcFragmentContainer) on the right. settingsPanel is GONE when not in split mode or when collapsed.
- PcView already extends AppCompatActivity; it adds StreamSettings.SettingsFragment through getSupportFragmentManager() into settings_panel (the host grid uses the framework fragment manager, AdapterFragment, which is independent). The panel header (3.7) is part of a new include layout vw_settings_panel.xml shared with activity_stream_settings.xml.
- Move the reload logic from StreamSettings (reloadSettings, the display pixel count and display id checks, the Android 9 cutout hack) into a small SettingsHost helper used by both StreamSettings and PcView, so the auto hints and native entries are rebuilt when the display changes.
- Language: StreamSettings.onBackPressed restarts PcView when the language changed. In the panel, list_languages gets an extra change listener (in PcView, via the fragment) that runs the same check after commit and recreates PcView on Android 12 and older, or shows the existing toast path on 13 and newer.
- Panel open or collapsed is stored as vw_ui_state "settings_panel_open" (default open). The collapse button hides the panel with a 180 ms width animation; the Settings rail button (settingsButton) reopens it in split mode and starts StreamSettings otherwise.
- Settings changes in the panel are written at once, as in the activity, so the next stream started from the hosts side uses them. Nothing else in PcView reads preferences after onCreate except where it already does.

### 4.3 Hosts panel header and rail

- In split mode the rail moves into a header row above the grid (like portrait today): Add PC, Help, and the Settings button (which toggles the panel). Same ids, same content descriptions. Round 48dp buttons with vw_surface_high fill and accent icons (Widget.Vibe.RoundIconButton, new), matching the settings header.
- The header title stays pcHeaderTitle with our own string, left aligned.
- Profiles FAB stays.

### 4.4 Controller and back behaviour in split mode

- Focus starts on the hosts grid (as today), so A starts a host immediately.
- Start moves focus to the settings panel (first row, or the last focused row) when it is open, and opens it first when collapsed. Start again, or B in the settings panel, moves focus back to the selected host card. B on the hosts side keeps today's behaviour.
- D-pad Right from a settings row that does not use Left and Right (switch, action, dropdown, edit text rows) moves focus to the selected host card; on segmented and slider rows Right changes the value (3.2, 3.3), so the user uses B or Start to switch panels. D-pad Left from the first column of the grid moves to the settings panel when open (nextFocusLeft set at runtime), else to the header buttons as today.
- L1 and R1 jump sections only while focus is in the settings panel; Y opens info in the panel and Profiles on the hosts side; X keeps the host context menu on the hosts side and does nothing in the panel.
- PcView.dispatchKeyEvent checks whether the focused view is inside settings_panel before applying its X, Y and Start shortcuts.
- Touch: both panels scroll independently; taps work everywhere.

### 4.5 Profiles

EditProfileActivity is unchanged in structure: it hosts ProfilePreferenceFragment (a SettingsFragment subclass) full screen, so it gets the new rows, info buttons and composite automatically, writing to the in-memory data store. Checks: segmented commits go through setValue, which persists through the PreferenceDataStore; the composite writes through setChecked on the real preferences, which also uses the data store; the "*" highlight prefix still applies to titles. The split panel never edits a profile.

## 5. Host card restyle

Reuse pc_grid_item.xml ids and HostCardState. Changes:

- Icon tile 72dp (from 64dp), radius 18dp; glyph 44dp. Offline tile vw_surface_low with dim glyph and the existing badge, unknown state tonal with the spinner, online accent, as today.
- Title and status line unchanged in content; the status icon for "checking" stays ic_sensors, offline stays ic_warning.
- Remove host_secondary_label from the bottom row and put the action pill full width (match_parent, 44dp, centered icon and text, radius 12dp). Styles: FILLED (online: Open or Resume), TONAL (Pair), NEUTRAL (vw_surface_high fill, secondary text color: Wake and Options). The secondary text moves to a second status line under the status row only when it adds information (wake available, refreshing); otherwise it is hidden.
- Offline with a MAC address: today A opens the context menu, where Wake is an item. New: HostCardState.Action.WAKE, and PcView sends Wake-on-LAN directly (existing doWakeOnLan) on A or tap; the context menu stays on X and long press. Offline without a MAC address keeps CONTEXT_MENU with the Options pill. This is a behaviour change; it matches the full-width button, which now reads as the card's action. The pill remains a label for what A or a tap does; the card stays the single focus target so GridView selection and focus scale work as before.
- Card size: width stays computed by the 1.2.1 layout logic (fits two cards side by side on handheld landscape). In split mode the available width is smaller; the existing column logic already shrinks cards down to its minimum and drops to one column. Verify at 720dp minus a 340dp panel (380dp), which fits one card; if the minimum card width is above that, lower the minimum to 300dp.
- HostCardStateTest: add rows for offline with and without MAC (Action.WAKE vs CONTEXT_MENU, PillStyle.NEUTRAL).

## 6. Strings, drawables, styles

- New strings in values/strings.xml only (other languages fall back to English, as in 1.3): caption titles, segment labels (seg_auto, seg_720p, seg_1080p, seg_1440p, seg_4k, seg_more, seg_30 ... seg_120, seg_h264, seg_hevc, seg_av1, seg_fit, seg_fill, seg_stretch, seg_off, seg_lite, seg_full, seg_touch, seg_trackpad, seg_pointer, seg_stereo, seg_51, seg_71, seg_left, seg_center, seg_right, seg_none, the render mode labels), title and summary for quick_stats_overlay, "All settings", "Show descriptions", "Jump to section", "About this setting", "Current: %1$s", "Collapse settings", "Show settings", accessibility action labels. Our own wording; none taken from the reference.
- Drawables (new vectors): ic_info_outline, ic_side_panel, ic_chevron_down (if not present), vw_slider_thumb, vw_slider_track, vw_segment_track. ic_more_horiz exists.
- Styles: Widget.Vibe.Segment, Widget.Vibe.SegmentGroup, Widget.Vibe.RoundIconButton, Widget.Vibe.Slider (SeekBar), TextAppearance.Vibe.Caption for Part A captions.
- Colors: no new tokens needed (vw_bg #000000, vw_surface #1C1C1E, vw_surface_high #2C2C2E, vw_accent #0A84FF, vw_accent_text #54A8FF already exist).

## 7. Tests

All new tests go into packages the CI filter already runs (com.limelight.preferences.*, com.limelight.profiles.*, com.limelight.ui.*, com.limelight.PcViewUiTest), so .github/workflows/release.yml needs no filter change. If the Coder adds a new package, add it to the --tests list in the same commit.

JVM (pure):

- SegmentFitTest: fits and does not fit at 1.0 and 1.3 font scale widths; More counts as a segment.
- SeekBarPreferenceFormatTest: formatValue for bitrate (39000 to "39.0 Mbps"), percent suffix, divisor 1.
- GroupedCardDecorationTest: no cards before the All settings caption; cards after each Part B header; existing cases unchanged.
- HostCardStateTest: the new WAKE rows.

Robolectric:

- PreferenceKeyInventoryTest: parses res/xml/preferences.xml and compares against a checked-in golden file app/src/test/resources/preference_keys_v135.txt (key, base class family List/CheckBox/SeekBar/EditText/Dialog/Plain, defaultValue, entryValues array name, dependency). Every 1.3.5 key must be present with the same family, default, values and dependency; the only allowed additions are the new caption categories and quick_stats_overlay, which must be non-persistent. Generate the golden file from de0b1ec before editing the XML.
- DefaultValuesTest: PreferenceManager.setDefaultValues on a clean install writes exactly the same key and value map as 1.3.5 (golden map in test resources), proving no new stored key and no default change (frame_pacing balanced, vendor keys false, keepalive false, keepalive per frame false).
- SegmentedListPreferenceTest (in StreamSettings): tapping the 1080p segment stores "1920x1080", runs the resolution listener (bitrate reset, bitrate_follows_resolution true); a value outside the segments (a native entry) checks More and shows the entry name; More opens the ListPreference dialog; with the row focused, DPAD_RIGHT twice then A commits the second next value; DPAD_RIGHT then focus loss commits nothing; Left at the first segment is consumed; segments disappear for values removed from entries; unlock FPS rebinding shows the new entries.
- InlineSliderTest: DPAD_RIGHT on the bitrate row raises the value by keyStep, commits after 600 ms (ShadowLooper idleFor), and sets bitrate_follows_resolution false; a drag that ends where it started does not stop following (existing semantics); A opens the dialog; disabled state follows the dependency (osc opacity with on-screen controls off).
- StatsOverlayCompositeTest: Off, Lite, Full map to the two booleans; Off keeps the stored lite value; the lite dialog row is enabled only for Lite; inside EditProfileActivity the in-memory prefs change and the base prefs do not.
- InfoButtonTest: a row with a summary shows the info button and no summary; Y on the focused row opens the dialog with the summary text; Show descriptions brings the summaries back.
- DeviceRemovalTest: with Robolectric configured without touchscreen, PiP and HDR10, the moved rows (checkbox_show_onscreen_controls, seekbar_osc_opacity, checkbox_enable_pip, checkbox_enable_hdr) are absent and no exception is thrown; SHIELD broken-HDR path disables HDR without a null pointer (call the branch through a test seam if the SHIELD check cannot be faked).
- Existing StreamSettingsUiTest and EditProfileActivityUiTest keep passing; update only assertions that depended on summaries being visible or on categories starting expanded (collapsedGroupsShowAShowMoreRow, shoulderButtons tests).
- PcViewUiTest: at qualifiers w960dp-h540dp-land the settings panel is visible and holds the fragment; the collapse button hides it and the state survives recreate; Start moves focus into the panel and B moves it back; Left from the first host column enters the panel; X, Y in the panel do not trigger host shortcuts. At w640dp-h360dp-land and in portrait there is no panel and the Settings button starts StreamSettings.
- Process death: recreate PcView and StreamSettings with saved state; no InstantiationException (no-argument constructor).

Manual on the Thor (and one phone in portrait):

- adb shell wm density, record the width in dp; check whether split mode is active.
- Controller only: change resolution, FPS, bitrate, codec, pacing, stats overlay, touch mode, a Part B switch and slider, open an info dialog, jump sections with L1 and R1, start a stream, return.
- Touch only: same list.
- Profiles: create a profile, change three Part A rows and the stats overlay, save, reopen: values and "*" highlights correct; base settings unchanged.
- Font scale 1.3 and 2.0: no ellipsized segment labels; rows fall back to dropdown rows.
- TalkBack: segmented and slider rows announce title and value.

## 8. Version and release notes

- VERSION 1.3.5 to 1.4.0.
- release.yml body: add "New in 1.4.0" above "Fixes in 1.3.5":
  - Simpler settings: the settings you change most are at the top with their controls in place: tap or press Left and Right then A on a choice, slide the bitrate, flip switches. The "i" button (or Y on a controller) explains a setting; turn on Show descriptions in the "..." menu to see the explanations inline again. Everything else is under All settings, grouped as before and collapsed. All your settings and profiles are kept as they were.
  - Statistics overlay: one control with Off, Lite and Full replaces the two overlay switches.
  - On wide landscape screens, Settings opens beside the PC list; Start moves between them, and the panel button hides or shows Settings.
  - PC cards: larger icon, the action as a full-width button. Offline PCs with Wake-on-LAN wake with A or a tap; X still opens the menu.
  - Update the opt-in paths in the 1.3.4 and 1.3.5 notes: "Settings, Video Settings, Show more" becomes "Settings, All settings, Video, Frame pacing" for the pacing modes and "Settings, All settings, Video" for the vendor keys; "Settings, Advanced Settings, Show more" becomes "Settings, All settings, Advanced" for both keepalive options.

## 9. Order of work (each step builds and passes tests)

1. Golden files (key inventory, default value map) from de0b1ec, and the two tests. No behaviour change.
2. removePreferenceByKey refactor and the no-argument constructors, with DeviceRemovalTest. No visual change.
3. PrefRowBinder, info button, info dialog, Show descriptions, VwSwitchPreference.
4. SeekBarPreference inline mode.
5. SegmentedListPreference and SegmentFit, applied to Part B lists first (analog_scrolling, align mode, render mode), then the Part A lists.
6. XML reorganization into Part A and Part B, caption layout, decoration rule, collapsed categories, composite stats row.
7. Header with the more menu.
8. Host card restyle and Action.WAKE.
9. Split layout in PcView with SettingsHost, focus rules and tests.
10. Version bump and release notes.

Steps 1 to 8 are shippable alone if step 9 slips.

## 10. Risks

- Moving keys out of their categories breaks category-scoped code paths. Mitigation: 3.6 lists every one found at de0b1ec; DeviceRemovalTest; grep for "category_" lookups in StreamSettings, EditProfileActivity and GameMenu before step 6.
- Segment rebinding while entries change (native entries appended late, unlock FPS listener). Mitigation: bind from current entries every time; tests for both.
- Collapsed Part B categories cannot be re-collapsed after "Show N settings" (androidx behaviour). Acceptable; they collapse again next time Settings opens.
- Two fragment managers in PcView (framework for the grid, support for settings). They do not interact; still, test recreate and process death.
- Controller conflicts in split mode (Left and Right inside rows vs moving between panels). Mitigation: Start and B as explicit panel switches; Right escapes only from rows that do not use it.
- Width threshold on the Thor may keep split mode off. Acceptable; full-screen settings deliver the main request. Lowering the threshold is a one-line change after a device check.
- Inline sliders commit more often than the dialog (each drag or key pause). The only listener with side effects is the bitrate one, and it is idempotent for the same value.
- Users who relied on summaries. Mitigation: info button, Y, and the Show descriptions option.
- The Wake-on-LAN action change surprises users who expect the menu on A. Mitigation: release notes; the menu stays on X and long press.

## 11. Acceptance criteria

1. Every key from preferences.xml at de0b1ec is present with the same type, default, entry values and dependency (PreferenceKeyInventoryTest), and a clean install writes the same default map as 1.3.5 (DefaultValuesTest). No new key appears in the default SharedPreferences after using every new control.
2. The top of Settings shows the 21 rows of 2.2 in order, each with its control inline; no row in Part A shows a multi-line summary unless Show descriptions is on.
3. Every Part A control can be changed with touch alone and with a controller alone; segmented rows commit only on tap or A; sliders commit on release or after the key pause.
4. Changing resolution or FPS through a segment has the same effects as through the list dialog (bitrate reset, follow flag, native warning, auto hint).
5. Out-of-segment values (native or custom resolution, 360p, warp pacing, touch modes 3 and 5) are shown on the title line with More selected, and can be chosen through More.
6. EditProfileActivity edits only the in-memory prefs with the new controls, including the statistics overlay, and highlights changed rows.
7. Devices without touchscreen, PiP or HDR10 hide the matching rows without errors.
8. At 720dp or wider in landscape, Settings and Hosts show side by side; the panel collapses and reopens; Start and B move focus between panels; narrow and portrait behave as 1.3.5 with the new rows.
9. Host cards show the larger tile and a full-width action; offline with MAC wakes on A or tap.
10. No file under binding/, nvstream/ or Game.java changes; no new dependency in app/build.gradle; no string or asset contains the reference app's name or wording.
11. CI unit tests pass with the existing filter; VERSION is 1.4.0 and the release notes describe the changes and the new paths to the opt-in options.

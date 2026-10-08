# UI refresh and auto resolution plan (Vibe Window 1.2.0)

Status: plan only, nothing implemented. Target release: client 1.2.0 (bump `VERSION` from 1.1.3).

Primary device: AYN Thor, 1920x1080 landscape at 120 Hz main panel, a small second bottom panel, touch plus built-in gamepad, USB-C video out. Every interaction on the new screens must work with D-pad and face buttons alone and with touch alone.

## 1. Goals

1. Give the PC list (`PcView`) and the settings screen (`StreamSettings`, also used by `EditProfileActivity`) a modern, minimalist look in the style of VoidLink, using only Material Components already in the project, vector drawables and the system font.
2. Make the app library (`AppView`) use the same tokens so the three screens read as one product.
3. Add "Auto (match display)" to the video resolution list (and to the FPS list), make it the default, and resolve it into a concrete width, height and frame rate at stream start from the display the app is actually shown on, clamped to what the decoder can do.
4. Keep every existing explicit choice (360p to 4K, native and custom entries), the virtual display flag, the window-only launch flag and the rest of the streaming path unchanged.

## 2. VoidLink research findings

### 2.1 What VoidLink is (found and verified)

VoidLink exists and was verified from its public source repository and its App Store listings.

* It is an iOS, iPadOS and tvOS game streaming client for Sunshine and Apollo style hosts, a fork of moonlight-iOS that its README describes as "extensively reworked, redesigned, and expanded" with "a completely new user interface". Repository: https://github.com/The-Fried-Fish/VoidLink-previously-moonlight-zwm (default branch `master`, code on branch `Integration`, license GPL-3.0 in `LICENSE.txt`).
* App Store listings: "VoidLink - Extreme" https://apps.apple.com/app/voidlink-extreme/id6755103808 (game category) and "VoidLink" https://apps.apple.com/app/voidlink/id6747717070 (tool category, regional). Both are paid, designed for iPad.
* There is no Android build. Searches for a Google Play listing, an APK or an Android launcher named VoidLink returned nothing; every result is the iOS app. So "styled like VoidLink" can only mean: recreate an iOS client's visual language on Android.

### 2.2 What could not be verified

* Screenshots: the README embeds seven screenshots (host view, favorites, app view, settings during streaming, on-screen widgets) as GitHub `user-attachments` URLs, which could not be downloaded from this environment, and the App Store pages could not be fetched either. The repository's own `Screenshots/` folder only holds 2018 and 2019 images from upstream moonlight-iOS, not VoidLink. So nobody in this plan has looked at a VoidLink screenshot. The tokens below are read from VoidLink's source code (exact numbers), not eyeballed from images.
* Exact layout of the host screen beyond the host card (navigation placement, header) is not known; section 4 fills that in with a direction that fits the source-derived tokens. Treat it as a proposal for the user to confirm (open question Q1 in section 14).

### 2.3 Design language extracted from the VoidLink source

All paths are inside the VoidLink repository, branch `Integration`.

* Theme (`VoidLink/ThemeManager.swift`): follows the iOS grouped style. Dark mode host background is iOS `systemGroupedBackground` (pure black, #000000); menu and panel background is `secondarySystemBackground` (#1C1C1E); cards ("widgets") are `secondarySystemGroupedBackground` (#1C1C1E, #2C2C2E on tvOS); offline host icon tile #111113. Light mode uses #F2F2F7 background and white cards. Accent ("appPrimaryColor") is iOS system blue `(0, 0.48, 1.0)` = #007AFF (the comment says #0A84FF, the dark mode variant), with a 24 percent alpha tonal variant for secondary buttons. Gray text `(0.55, 0.55, 0.60, 0.95)`. Separators: dark `(84, 84, 88)` at 60 percent alpha, light `(60, 60, 67)` at 29 percent.
* Host card (`VoidLink/HostCardView.swift`): one card per host on the widget background, corner radius about 6 percent of the card width; an 80 pt rounded square icon tile (radius 20, so 25 percent) filled with the accent and a white computer glyph; host name bold 18 pt; a status line in medium 14 pt with a small icon, green #34C759 for "Online", gray with a warning triangle for offline, gray with an antenna icon while checking; a hairline separator; a bottom row with a text button "Applications" and a filled accent "Launch" button with a play icon (39 pt tall, radius about 3.8 percent of card width); "Pair with PIN" and "Wake-on-LAN" are tonal buttons (accent at 24 percent alpha, accent text, lock or power icon); offline tiles drop to #111113 with a 40 percent gray glyph. A soft diagonal blue glow (#0051A3, full strength in dark mode, 52 percent in light) sits in one corner of the card.
* Grid (`VoidLink/ViewControllers/HostCollectionViewController.swift`): line spacing 10 pt, small top inset (10 to 17 pt).
* Settings (`VoidLink/MenuSectionView.swift`, `VoidLink/ViewControllers/SettingsSwiftUI.swift`): collapsible sections, each with an icon and a 19.5 pt medium title and a right chevron that rotates when expanded; header 37 pt plus 24 pt vertical spacing; row spacing 10 to 12 pt; section spacing 12 pt; item label 17 pt, value label 16 pt; segmented pickers and switches (switch column 150 pt); radii 10 for sections, 6 to 8 for small controls; a "Favorites" section at the top that users fill by long pressing any setting (default favorites: resolution, FPS, bitrate, codec, HDR, YUV 4:4:4, PiP, touch mode and a few others); sections in order Video, Touch and Controller, Controller, Motion Control, Pencil, Gestures, Peripherals, Audio, Others, Experimental.
* Motion (all files): short eased animations of 0.12 to 0.2 s, press feedback scale 0.97, focus scale 1.06 (tvOS style), one spring with damping 0.9.
* Controller UX (`VoidLink/GamepadNavigationIllustrationHud.swift`): an optional on-screen hint bar that shows controller button glyphs next to their current action.
* Typography and icons: Apple system font (SF Pro) and SF Symbols, both Apple-licensed and not usable on Android. The app also bundles Roboto (Apache 2.0).

### 2.4 Translation to Android and licensing

* No VoidLink name, logo or wording appears in the app or its release notes; it is only cited in this doc. Vibe Window is GPL-3.0 like VoidLink, but nothing is copied: no code, no images, no logos, no icon assets, no fonts. Only numeric design decisions (colors, sizes, radii, durations), which are recreated from scratch.
* Font: Roboto, which is already the Android system font (Apache 2.0). No font file is added, so no licensing question arises. Weights via `sans-serif` and `sans-serif-medium`.
* Icons: Material Symbols Rounded path data as vector drawables (Apache 2.0, https://github.com/google/material-design-icons). The rounded variant is the closest free match to the SF Symbols feel.
* Components: Material Components 1.13.0 (`app/build.gradle` line 170): `MaterialCardView`, `MaterialButton`, `MaterialSwitch`, `ExtendedFloatingActionButton`, `CircularProgressIndicator`, `MaterialAlertDialogBuilder` theme overlays.

## 3. Design tokens

All tokens live in new resources so every screen references names, not literals.

### 3.1 Color (`app/src/main/res/values/colors.xml`)

| Token | Value | Use | Contrast notes |
| --- | --- | --- | --- |
| `vw_bg` | #000000 | Window and screen background (OLED friendly, same as VoidLink dark host view) | |
| `vw_surface` | #1C1C1E | Cards, settings groups, search field | |
| `vw_surface_high` | #2C2C2E | Focused rows, dialogs, FAB, chips | |
| `vw_surface_low` | #111113 | Offline host icon tile | |
| `vw_on_surface` | #FFFFFF | Primary text | 21:1 on bg, 17:1 on surface |
| `vw_on_surface_variant` | #8E8E93 | Secondary text, counts, summaries | about 6.4:1 on bg, 5.2:1 on surface |
| `vw_accent` | #0A84FF | Focus rings, icons, accent text, selected states | about 5.8:1 on bg, 4.7:1 on surface |
| `vw_accent_container` | #0071E3 | Filled buttons and selected chips with white text | white on it about 4.7:1 (AA) |
| `vw_accent_tonal` | #3D0A84FF (24 percent) | Tonal buttons (Pair, Wake), selected rail item | |
| `vw_success` | #30D158 | Online status dot and text | about 11:1 on surface |
| `vw_warning` | #FF9F0A | "Not paired" status | about 9:1 on surface |
| `vw_separator` | #99545458 | Hairlines between rows | |
| `vw_glow` | #0051A3 | Host card corner glow (gradient end, 55 percent alpha) | decorative |
| `vw_scrim` | #66000000 | Running app mask on cards (replaces literal `0x66000000` in `AppCardBinder.java` line 46) | |

White text on #0A84FF is only about 3.6:1, which fails AA for normal size text; that is why filled buttons use `vw_accent_container`. The existing `libraryFocus` (#4FC3F7, `colors.xml` line 4) becomes an alias of `vw_accent` so library drawables follow automatically. A light theme is out of scope (the app forces dark today, `values-v29/styles.xml`).

### 3.2 Type scale (`values/styles.xml`, `TextAppearance.Vibe.*`, Roboto)

| Style | Size | Weight | Use |
| --- | --- | --- | --- |
| `Display` | 28sp | medium | Screen titles ("Computers", "Settings") |
| `Title` | 22sp | medium | Library PC name (`appListText`), dialog titles |
| `Section` | 20sp | medium | Settings section headers, library group headers (VoidLink 19.5) |
| `CardTitle` | 20sp | bold | Host name on the host card (VoidLink 18 on a larger card) |
| `Body` | 16sp | regular | Setting titles, list items (VoidLink 17) |
| `Label` | 15sp | medium | Buttons, chips, values |
| `Secondary` | 14sp | regular or medium | Summaries, status line, counts |
| `Caption` | 12sp | medium | Controller hint bar labels |

### 3.3 Shape (`values/dimens.xml` and `ShapeAppearance.Vibe.*`)

`vw_radius_xs` 6dp (badges), `vw_radius_sm` 10dp (small buttons), `vw_radius_md` 12dp (app cards, buttons, focus ring on rows), `vw_radius_lg` 16dp (settings groups, icon tiles, FAB), `vw_radius_xl` 20dp (host cards, dialogs, search field), pill for chips. Focus ring radius is the element radius plus 2dp.

### 3.4 Spacing

4dp grid: `vw_space_1` 4, `vw_space_2` 8, `vw_space_3` 12, `vw_space_4` 16, `vw_space_5` 24, `vw_space_6` 32. Screen gutter 24dp in landscape, 16dp in portrait. Row spacing 12dp, section spacing 28dp (header top margin).

### 3.5 Elevation

Flat, like VoidLink: hierarchy comes from surface tone (bg, surface, surface_high) and hairlines, not shadows. `cardElevation` 0 everywhere (today 8dp in `app_grid_item.xml`). The FAB keeps 2dp so it reads as floating over content.

### 3.6 Motion (`values/integers.xml`)

`vw_motion_fast` 80 ms (press), `vw_motion_normal` 120 ms (focus scale, matches `AppCardBinder.FOCUS_ANIMATION_MS`), `vw_motion_slow` 180 ms (crossfades, chevron rotation, matches the wheel art fade). Interpolator: `FastOutSlowInInterpolator`. Press scale 0.97, focus scale 1.06 for small cards and 1.04 for host cards (larger, avoids clipping). Respect the system animator duration scale (ViewPropertyAnimator already does).

### 3.7 Focus

A single focus language for gamepad users on all three screens: 3dp `vw_accent` stroke drawn over the element with radius plus 2dp, plus the focus scale. Rows in lists use a `vw_surface_high` fill and a 2dp ring. Focus is never shown in touch mode (Android handles that once a touch happens).

### 3.8 Theme wiring

* `values/styles.xml` `AppTheme` (lines 18 to 26): replace the four #1A1A1A items with `@color/vw_bg`, add `colorPrimary` `vw_accent_container`, `colorOnPrimary` white, `colorSecondary` `vw_accent`, `colorSurface` `vw_surface`, `colorOnSurface` white, `colorSurfaceVariant` `vw_surface_high`, `colorOutline` `vw_separator`, `materialAlertDialogTheme` and `alertDialogTheme` to a new `ThemeOverlay.Vibe.Dialog` (surface_high background, `vw_radius_xl` corners), and `preferenceTheme` to `PreferenceThemeOverlay.Vibe` (section 5).
* Put tokens on `AppTheme`, not on `AppBaseTheme`, so `StreamTheme` (which extends `AppBaseTheme`, `values/styles.xml` line 29 and `values-v24/styles.xml`) and therefore the streaming activity and its in-stream menus are untouched.
* `SettingsTheme` (`values/styles.xml` lines 48 to 53, and `values-v29/styles.xml`): parent `AppTheme` on all API levels with `android:windowBackground` `vw_bg` (today it is transparent below API 29).
* `layout/activity_add_computer_manually.xml` line 3: background literal to `@color/vw_bg`.

## 4. Screen spec: PC list (`PcView`)

### 4.1 Approach

Keep the data path exactly as it is: `PcView` plus `AdapterFragment` plus `GridView` plus `PcGridAdapter` (`GenericGridAdapter`). The context menu keeps using `AdapterContextMenuInfo` (`PcView.java` lines 399 to 460 and 733 to 822), pairing, wake and app list code is not touched. Only layouts, `PcGridAdapter.populateView()` and some view wiring change. A `RecyclerView` migration (as done for the library) is not needed for a list that usually holds one to three hosts, and would put the context menu at risk for little gain.

### 4.2 Layout (landscape, `res/layout-land/activity_pc_view.xml`, the one the Thor uses)

* Root background `vw_bg`.
* Left navigation rail, 80dp wide, full height, vertically centered column of three icon buttons that keep their ids: `settingsButton`, `helpButton`, `manuallyAddPc`. Each is a 48dp `MaterialButton` (icon only, style `Widget.Vibe.RailButton`): 24dp Material Symbols Rounded icon in `vw_on_surface_variant`, 12dp radius container that turns `vw_accent_tonal` with an accent icon and the focus ring when focused. Content descriptions from existing strings. Spacing 12dp. Order top to bottom: Add PC, Settings, Help (most used first; `nextFocusDown` chain kept explicit).
* Content area to the right of the rail, 24dp gutter:
  * Header: "Computers" (`TextAppearance.Vibe.Display`, new id `pcHeaderTitle`) and a secondary line (`pcHeaderSubtitle`, 14sp) such as "2 found, 1 online", updated from `updateComputer()` and `removeComputer()`.
  * `pcFragmentContainer` (id kept) below the header, holding the grid.
* Empty state (`no_pc_found_layout`, id kept): centered column with a 48dp `CircularProgressIndicator` in `vw_accent` (keeps id `pcs_loading`), "Searching for PCs on your network" in Section style, and a focusable tonal button "Add PC manually" (new id `noPcAddButton`, same action as `manuallyAddPc`) so a controller user always has something to focus.
* Profiles: `profilesButton` (id kept, tests depend on it) stays an `ExtendedFloatingActionButton` bottom right with 24dp margins, restyled to `vw_surface_high` background, white icon and text, `vw_radius_lg` corners, 2dp elevation.
* Controller hint bar: cut from this version (manager decision, consistent with the library revamp).
* `res/layout/activity_pc_view.xml` (portrait twin) gets the same structure with the rail turned into a top row of the same three buttons.

### 4.3 Host card (`res/layout/pc_grid_item.xml`, rewritten)

Card 320dp by 196dp (VoidLink proportions), `vw_surface`, radius `vw_radius_xl`, no elevation, 16dp inner padding, new root id `host_card`. Ids used by `GenericGridAdapter.getView()` are kept: `grid_image`, `grid_overlay`, `grid_spinner`, `grid_text` (`grid_mask` stays absent; the adapter already tolerates null).

* Top row: icon tile 64dp, radius 16dp (new id `host_icon_tile`) holding `grid_image` (computer glyph, 40dp) with `grid_overlay` (lock or offline badge, 20dp, bottom right of the tile) and `grid_spinner` (centered, 32dp, shown while the state is unknown). To its right: `grid_text` (host name, CardTitle, one line, ellipsize end) and a status row: 8dp dot (`host_status_dot`) plus `host_status_text` (Secondary, medium).
* Hairline (`vw_separator`, 1px) 16dp below the top row.
* Bottom row: left a secondary label `host_secondary_label` (for example "Library" or "Last seen offline"), right a 40dp pill `host_action_pill` (icon plus label, radius `vw_radius_md`). The pill is not separately focusable or clickable; it tells the user what A or a tap will do, which matches today's click behavior in `receiveAbsListView()` (lines 891 to 912).
* Glow: `host_glow`, a `GradientDrawable` (radial, `vw_glow` at 55 percent to transparent) anchored bottom right, visible only for online paired hosts.

State table (computed by a new pure helper `com.limelight.ui.HostCardState.from(state, pairState, runningGameId, hasMacAddress)` so it is unit testable):

| Host state | Tile | Status | Pill (A or tap) |
| --- | --- | --- | --- |
| Online, paired, idle | `vw_accent` fill, white glyph, glow on | green dot, "Online" | filled `vw_accent_container`, play icon, "Open" (app list) |
| Online, paired, app running | as above | green dot, "Streaming session active" | filled, play icon, "Resume" (still opens the app list, as today) |
| Online, not paired | `vw_accent_tonal`, 63 percent white glyph, lock badge | orange dot, "Not paired" | tonal, lock-open icon, "Pair" |
| Offline | `vw_surface_low`, 40 percent glyph | gray warning icon, "Offline" | tonal, power icon, "Wake" (gray and labelled "Options" when no MAC address is known); opens the context menu as today |
| Unknown or refreshing | `vw_accent_tonal`, spinner | gray antenna icon, "Checking" | tonal "Options", opens the context menu as today |

The whole-card alpha trick (0.4 for offline, `PcGridAdapter.java` lines 55 to 75) is replaced by the tile and status colors above.

### 4.4 Grid (`res/layout/pc_grid_view.xml`)

`columnWidth` 336dp, `verticalSpacing` and `horizontalSpacing` 16dp, `stretchMode` `none`, `clipToPadding` false, 8dp padding so focus scale is not clipped, `listSelector` a new `host_card_selector.xml` (transparent fill, 3dp accent stroke, 22dp radius), `drawSelectorOnTop` true. Centering: with one to three hosts the cards are centered by computing `numColumns = min(count, fit)` and horizontal padding in a layout change listener set in `receiveAbsListView()`; with more hosts the grid fills the width.

### 4.5 Motion

On `OnItemSelectedListener` (added in `receiveAbsListView()`), the selected card scales to 1.04 in 120 ms and the previous one back to 1.0. On press, 0.97 in 80 ms. State changes (online to offline) crossfade the tile color in 180 ms.

## 5. Screen spec: Settings (`StreamSettings`, shared by `EditProfileActivity`)

### 5.1 Approach

Keep `preferences.xml`, every key, every listener in `StreamSettings.SettingsFragment` and the inheritance used by `EditProfileActivity.ProfilePreferenceFragment` (`EditProfileActivity.java` line 195). Restyle through a preference theme, custom row layouts and one `RecyclerView.ItemDecoration`. No preference class changes that could break casts such as the `(CheckBoxPreference)` cast for HDR (`StreamSettings.java` around line 655) or the `(ListPreference)` casts (lines 191 to 290).

### 5.2 Frame (`res/layout/activity_stream_settings.xml`, today an empty `RelativeLayout`)

* Horizontal `LinearLayout` with background `vw_bg`.
* No category rail (manager decision: VoidLink itself uses a single scrolling list of sections, and the rail adds focus and scroll-sync code for little gain). The single pane holds a top bar (back `MaterialButton` with new `ic_arrow_back`, title "Settings" in Display style; on `EditProfileActivity` the activity title stays as it is) and the fragment container `stream_settings` (id kept), content max width 840dp, centered.
* L1 and R1 jump to the previous or next top-level category with `fragment.scrollToPreference(categoryKey)` and focus its first row. The category list is read at runtime from the inflated screen, so categories removed on some devices are skipped.
* Three categories have no key today and need one for lookup (no stored values involved): audio (`preferences.xml` line 228) `category_audio_settings`, host (line 497) `category_host_settings`, general (line 514) `category_general_settings`.

### 5.3 Rows (`PreferenceThemeOverlay.Vibe`)

* `preferenceTheme` overlay sets `preferenceStyle`, `checkBoxPreferenceStyle`, `dialogPreferenceStyle`, `editTextPreferenceStyle`, `preferenceCategoryStyle` and `seekBarPreferenceStyle` to layouts under `res/layout/vw_pref_*.xml`.
* `vw_pref_row.xml`: min height 56dp, 16dp horizontal and 12dp vertical padding, `@android:id/title` Body white, `@android:id/summary` Secondary `vw_on_surface_variant` (no line limit, descriptions stay complete), optional `@android:id/icon` hidden (`iconSpaceReserved` is already false everywhere), `@android:id/widget_frame` at the end. Background `vw_pref_row_background.xml`: transparent; focused `vw_surface_high` with a 2dp accent ring, radius 12dp, 4dp inset; pressed `vw_surface_high`.
* Checkboxes become switches visually: `checkBoxPreferenceStyle` uses widget layout `vw_pref_switch.xml`, a `MaterialSwitch` with id `@android:id/checkbox`. `CheckBoxPreference` binds any `CompoundButton` with that id, so `CheckBoxPreference` and `SmallIconCheckboxPreference` keep working without class changes. Track on `vw_accent_container`, thumb white.
* `ListPreference` rows show the current value at the end in `Label` style `vw_accent` (summary keeps its description). Cut from this version (it would change preference class names); the summary keeps showing the value as today.
* Category header `vw_pref_category.xml`: Section style title in white with a 20dp accent category icon (Video `ic_videocam`, Audio `ic_volume_up`, Gamepad `ic_sports_esports`, Input `ic_mouse`, Host `ic_computer`, General `ic_tune`, UI `ic_palette`, On-screen controls `ic_touch_app`, Keyboard `ic_keyboard`, Trackpad `ic_trackpad_input`, Performance `ic_speed`, Advanced `ic_science`, Misc `ic_more_horiz`), 28dp top margin, 8dp bottom margin, not focusable.
* Grouped cards: `GroupedCardDecoration` (new, `com.limelight.preferences`) added to `getListView()` in `SettingsFragment.onViewCreated`. It draws, behind each run of rows between two category headers, a `vw_surface` rounded rectangle (`vw_radius_lg` on the outer corners only) and 1px `vw_separator` hairlines inset 16dp between rows. The position rule ("first, middle, last, single" from the adapter item types of the previous and next positions) lives in a pure static function for unit tests. `setDivider(null)` removes the default dividers.
* The "Advanced" expander created by `app:initialExpandedChildrenCount` (eleven categories use it) keeps its behavior. Its look is changed by providing `res/layout/expand_button.xml` in the app, which overrides the androidx resource of the same name (same ids: `@android:id/icon`, `@android:id/title`, `@android:id/summary`): a row inside the card reading "Show more" in `vw_accent` with a chevron-down icon and the hidden titles as the secondary line. If overriding the library layout proves fragile in CI, fall back to theming via `preferenceStyle` only.
* Dialogs (list choices, edit text, the custom `SeekBarPreference` dialog): `ThemeOverlay.Vibe.Dialog` from section 3.8, 20dp corners, `vw_surface_high`, radio buttons and seek bars tinted `vw_accent`.
* Not in this version: VoidLink's Favorites section. Androidx preferences cannot show the same key twice without breaking `findPreference()` based listeners. Offered as a follow-up question (Q4).

## 6. App library (`AppView`) adoption

No behavior changes, token swaps only:

* `colors.xml` line 4: `libraryFocus` becomes `@color/vw_accent`, so `app_card_focus.xml`, `library_header_background.xml`, `library_search_background.xml`, `library_badge_background.xml` and `library_chip_background.xml` follow.
* `activity_app_view.xml`: root background `vw_bg`; `appListText` uses `TextAppearance.Vibe.Title`; `librarySearch` uses `vw_surface` fill (replace #1AFFFFFF in `library_search_background.xml`) with `vw_radius_xl`; the layout toggle buttons switch from outlined to a tonal segmented style (`vw_surface_high` container, checked button `vw_accent_tonal` with accent icon); `profilesButton` gets the same FAB style as `PcView`.
* `library_group_header.xml`: label to Section style (20sp medium), count to `vw_on_surface_variant`.
* `app_grid_item.xml` and `app_grid_item_small.xml`: `cardElevation` and `cardMaxElevation` 0, `cardBackgroundColor` `vw_surface`, radius stays 12dp (`vw_radius_md`).
* Chips and badge: selected fill `vw_accent_container` and white text (today `library_chip_text.xml` uses black on light blue); badge text white.
* `AppCardBinder.java` line 46: use `vw_scrim`. Focus scale 1.06 and 120 ms already match the motion tokens, so they stay; `WheelLayoutController.java` line 420 keeps 180 ms (`vw_motion_slow`).

## 7. Gamepad and D-pad behavior

AOSP fallbacks apply only when the app does not consume the key: A to DPAD_CENTER, B to BACK, X to DEL, Y to SPACE, Start to DPAD_CENTER, Select to MENU, L1 and R1 none (`Generic.kcm`, see sources). Keys below are handled on ACTION_DOWN in `dispatchKeyEvent` and only claimed when listed.

### 7.1 PC list

| Key | Action |
| --- | --- |
| D-pad, left stick | Move between host cards (GridView selection). Left from the first column moves to the rail (`nextFocusLeft` already set in `pc_grid_view.xml`); up and down move within the rail; right from the rail returns to the grid |
| A | Primary action of the selected card (same rules as today's item click) or press the focused rail button |
| Long press A | Context menu (existing long-click path) |
| X | Context menu of the selected card (`openContextMenu(gridView.getSelectedView())`) |
| Y | Open Profiles (same as `profilesButton`) |
| Start | Open Settings |
| B | Back, unchanged (leaves the app; predictive back stays enabled) |

Initial focus: the first online paired host, else the first host, else `noPcAddButton` in the empty state. After returning from the library or a stream, the previously selected host stays selected (store the uuid in `onPause`, restore after the next `notifyDataSetChanged`).

### 7.2 Settings

| Key | Action |
| --- | --- |
| D-pad up and down | Move between rows (RecyclerView focus); category headers are skipped |
| A | Toggle a switch, open a list, edit or seek bar dialog, or expand "Show more" |
| B | Close a dialog, otherwise leave settings (existing back handling, `StreamSettings.java` line 144) |
| L1 and R1 | Previous and next category (scroll plus focus first row), wrapping |
| Held D-pad | Key repeat honored; the list scrolls so the focused row stays one row away from the edge |

Initial focus: first row of the Video category (resolution).

### 7.3 Library

Unchanged from `docs/library-revamp-plan.md` section 6.2.

## 8. Auto resolution

### 8.1 How Android exposes the relevant information

* The display an activity is on: `Activity.getDisplay()` (API 30, Android 11); before that `getWindowManager().getDefaultDisplay()` on the activity context, which returns the activity's display. Always use the activity context, never the application context (Android connected displays guide, see sources).
* Window size: `WindowManager.getCurrentWindowMetrics()` (API 30). Not used for the stream size because a windowed or split app would then request odd sizes; the stream should match the display, and the existing scale mode (fit, fill, stretch) handles the window.
* Display modes: `Display.getMode()` and `Display.getSupportedModes()` (API 23) give physical width, height and refresh rate per mode. Some panels report portrait physical sizes (the Thor's main panel is a 1080 by 1920 portrait panel mounted in landscape), so sizes are normalized to landscape the way upstream already does (`StreamSettings.java` lines 502 to 509).
* External displays: `DisplayManager.getDisplays()` lists all logical displays; external HDMI or DisplayPort monitors normally carry `Display.FLAG_PRESENTATION`; `Display.getDeviceProductInfo()` (API 31) returns EDID derived data that only real external monitors have. Hotplug: `DisplayManager.registerDisplayListener` (`onDisplayAdded`, `onDisplayRemoved`, `onDisplayChanged`).
* When an activity moves between displays (or its display is unplugged and the system moves it to the primary display), it gets a configuration change, and is recreated if it does not handle all of them, notably density.
* Upstream Moonlight and Artemis today (this repo): `StreamSettings` appends "Native (WxH)" and "Native Full-Screen" entries from the default display's supported modes and cutout insets (lines 456 to 602), a "Native (N FPS)" entry from the highest refresh rate (lines 605 to 616), removes 4K, 1440p or 1080p when neither the display nor the AVC or HEVC decoder width range reaches them (lines 538 to 592), and shows a warning dialog for native picks (lines 673 to 703). `Game.onCreate` uses the stored width and height directly (`Game.java` lines 429 to 432), except in "Fully External Display Mode", where it already overrides width, height and FPS from the external display's current mode (lines 398 to 411). `PreferenceConfiguration.isNativeResolution()` treats any non-standard size as native, which turns on inset ignoring and display mode switching in `Game` (lines 1427 to 1444 and 1460 to 1500). The host gets `mode=WxHxFPS`, `scaleFactor`, `virtualDisplay` and `windowOnly` in the launch request (`NvHTTP.java` lines 890 to 897); Apollo style hosts create the virtual display at width and height times the scale factor. `NvConnection` refuses above 4096 without host support and drops to 1080p on old GFE without 4K (`NvConnection.java` lines 261 to 283).

### 8.2 AYN Thor facts and unknowns

* Main panel 6 inch 1080 by 1920 (used landscape) at 120 Hz; bottom panel 3.92 inch 1080 by 1240 at 60 Hz; USB-C video out up to 4K60 on Snapdragon 8 Gen 2 models and only 1080p on the Snapdragon 865 Lite; firmware 1.0.0.293 added "support to enable DP output when docked"; Android 13 (API 33). Sources in section 15.
* Not documented anywhere found: whether external output mirrors the main panel, extends the desktop, or replaces the main panel when docked, and which display flags the bottom panel carries. Stock Android 13 mirrors the default display onto a wired external display unless an app places content there (a `Presentation`, a launch on that display, or desktop mode in developer options). The algorithm below handles all three cases, and section 8.9 adds a display dump to verify on the device.

### 8.3 Settings model

* `arrays.xml` lines 3 to 20: add `@string/resolution_auto` ("Auto (match display)") and value `auto` as the first entry of `resolution_names` and `resolution_values`; lines 22 to 33: same for `fps_names` and `fps_values` ("Auto (match display)"). The comment on line 12 about `isNativeResolution()` stays true because `auto` never reaches that function.
* `preferences.xml` lines 13 and 21: `android:defaultValue="auto"` for `list_resolution` and `list_fps`.
* `PreferenceConfiguration.java` lines 143 and 144: `DEFAULT_RESOLUTION = "auto"`, `DEFAULT_FPS = "auto"`; new constants `RES_AUTO`, `FPS_AUTO`, placeholders `AUTO_PLACEHOLDER_WIDTH = 1920`, `AUTO_PLACEHOLDER_HEIGHT = 1080`, `AUTO_PLACEHOLDER_FPS = 60`; new fields `autoResolution`, `autoFps`, `bitrateFollowsResolution`, `meteredBitrateDerived`; new keys `checkbox_auto_res_prefer_external` (default true) and the internal flag `bitrate_follows_resolution`.
* `readPreferences()` lines 792 to 805: if the stored resolution is `auto`, set `autoResolution` and the placeholders, and skip the legacy conversion on lines 797 to 800 (which would otherwise rewrite `auto` to 720p, because `auto` has no "x"). Same for `auto` FPS before `Float.parseFloat` on line 804.
* `getDefaultBitrate(String, String)` lines 500 to 581: split into `getDefaultBitrate(int width, int height, float fps)` plus a string wrapper that maps `auto` to the placeholders, so `getDefaultBitrate(Context)` (lines 583 to 588), `readPreferences()` line 841 and `StreamSettings.resetBitrateToDefault()` (lines 304 to 317) never throw on `auto`.
* `resetStreamingSettings()` lines 688 to 701 already removes the keys, so reset lands on the new `auto` defaults.

### 8.4 Migration of existing installs

`PreferenceManager.setDefaultValues(this, R.xml.preferences, false)` (`PcView.java` line 151) writes defaults only once per install, so existing users keep the stored "1920x1080" and "120". New `PreferenceConfiguration.migrateToAutoDisplayDefaults(Context)` runs right after that call, once, guarded by the flag `migrated_auto_display_v120` in the base preferences (never in profile overlays): if the stored resolution is exactly "1920x1080" and FPS exactly "120" (the old defaults), set both to `auto`, and if the stored bitrate is exactly 28000 kbps (the old default, `getDefaultBitrate("1920x1080", "120")`), set `bitrate_follows_resolution` true. Any other combination is a deliberate user choice and is left alone. Compare the bitrate against `getDefaultBitrate(1920, 1080, 120)` rather than a literal. Decided (Q2): migrate existing installs as described; the release notes say so and how to pick an explicit value again.

### 8.5 The resolver (pure, unit testable)

New `app/src/main/java/com/limelight/preferences/AutoResolution.java`, no Android imports.

Inputs (`AutoResolution.Request`): `autoResolution`, `autoFps`, explicit `width`, `height`, `fps`; `activityDisplay` and `otherDisplays` as `DisplayInfo` records (id, isDefault, physical width and height of the current mode, current refresh, list of supported modes, `presentation` flag, `privateDisplay` flag, `hasProductInfo`, state on); `preferExternalWhenMirrored`; a `DecoderCaps` interface (`trustworthy()`, `isSizeSupported(w, h)`, `isSizeAndRateSupported(w, h, fps)`); policy limits `maxLongEdge = 4096`, `maxShortEdge = 2160`, `maxPixels = 3840 * 2160`.

Output (`AutoResolution.Result`): `width`, `height`, `fps`, `targetDisplayId`, `mode` (`EXPLICIT`, `ACTIVITY_DISPLAY`, `MIRRORED_EXTERNAL`), `clamp` (`NONE`, `POLICY`, `DECODER`), and a one-line `reason` for the log.

Algorithm:

1. If neither `autoResolution` nor `autoFps` is set, return the explicit values unchanged (mode `EXPLICIT`). Explicit choices therefore behave exactly as today.
2. Target display T is the activity display.
3. Mirroring case: if T is the default display, `preferExternalWhenMirrored` is on, and there is another display E that is on, not private, has `presentation` or `hasProductInfo`, and has more pixels than T (pick the largest, prefer `hasProductInfo` on ties), then the user is most likely watching the mirror on E. The Thor's bottom panel (1240 by 1080, fewer pixels than the main panel) can never win this test even if it carries the presentation flag.
4. Native size of the chosen display: the physical size of its current mode, normalized to landscape (long edge first). The current mode is what the OS actually drives (an external 4K TV behind a 1080p capable USB-C link reports 1080p here, so the stream follows reality). For a mirrored E, fit T's aspect ratio inside E's size, because Android letterboxes the mirrored default display: a 16:9 panel mirrored onto a 3440 by 1440 monitor gives 2560 by 1440; onto a 4K TV, 3840 by 2160.
5. FPS (only if `autoFps`): the highest refresh rate among the chosen display's supported modes with the same physical size as the current mode (the Thor may idle its panel at 60 Hz while 120 Hz is available; `prepareDisplayForRendering()` switches to the matching mode at stream start, `Game.java` lines 1460 to 1600). Rounded to an integer when within 0.05 of one (59.94 stays 59.94 for custom setups; 119.99 becomes 120), and clamped to 30 to 240. If only the resolution is auto, FPS stays the explicit value; if only FPS is auto, the size stays explicit.
6. Policy clamp (only if `autoResolution`): if the long edge exceeds 4096, the short edge exceeds 2160 or the pixel count exceeds 3840 by 2160, scale down uniformly by the smallest of the three ratios, keeping the aspect ratio (5120 by 2160 becomes 4096 by 1728; 7680 by 4320 becomes 3840 by 2160). This keeps auto inside what `NvConnection` and common host encoders accept.
7. Alignment: floor the width to a multiple of 8 and the height to a multiple of 2 (1366 by 768 becomes 1360 by 768), never below 640 by 360.
8. Decoder clamp, only when `caps.trustworthy()` and the size is above 1920 by 1080. Sizes up to 1080p are always allowed, because decoders report nonsense on some devices (upstream comment, `StreamSettings.java` lines 495 to 501). While `isSizeSupported` fails, step down along the short-edge ladder 2160, 1600, 1440, 1200, 1080 keeping the aspect ratio. Then, while `isSizeAndRateSupported` fails: if FPS is auto, lower it to the next lower supported display refresh rate but not below 60; otherwise (or once at 60) continue stepping the size down. `trustworthy()` is false when the decoder does not even report 1280 wide support, mirroring upstream's rule on lines 547 and 563.
9. Return the result.

Aspect ratio: auto keeps the display's own aspect ratio (16:10, 21:9, 32:9, 4:3 all stream at their native shape), so with "Use Virtual Display" on, Apollo style hosts create a virtual display of exactly that shape and the picture fills the screen without bars. Without a virtual display the host desktop's own aspect applies, as today.

Scale factor: unchanged. The host still multiplies the requested size by `resolutionScaleFactor`; at 4K with 200 percent that is a 7680 by 4320 host virtual display. The settings summary shows the resulting host size when auto and a factor above 100 are combined (see 8.7), nothing is clamped silently.

### 8.6 Android adapter and the single hook in Game

New `app/src/main/java/com/limelight/preferences/AutoResolutionAndroid.java`:

* `static void resolveInto(Activity activity, PreferenceConfiguration prefConfig)`: returns immediately, before any display or codec query, when neither `autoResolution` nor `autoFps` is set, so explicit choices run exactly the 1.1.3 code path. The whole body is wrapped in try/catch of `Throwable`; on failure it logs and uses the activity display's current mode normalized to landscape, else 1920x1080 at 60, and never throws. builds `DisplayInfo` records (activity display from `activity.getDisplay()` on API 30+, else `getWindowManager().getDefaultDisplay()`; others from `DisplayManager.getDisplays()`), builds `DecoderCaps` by calling `MediaCodecHelper.initialize(activity, GlPreferences.readPreferences(activity).glRenderer)` (idempotent, `MediaCodecHelper.java` line 337 returns early when already initialized, and `Game` calls it again on line 597) and then `findProbableSafeDecoder` for the codec the user can get: AV1 when forced, AVC when H.264 is forced, else HEVC if present, else AVC. On API 29+ `VideoCapabilities.getSupportedPerformancePoints()` decides rate support, else `areSizeAndRateSupported()`, the same order `MediaCodecDecoderRenderer.decoderCanMeetPerformancePoint()` uses (lines 208 to 247). Below API 23 there are no display modes: auto falls back to `getRealMetrics()` size and the explicit placeholder FPS.
* Writes `prefConfig.width`, `height`, `fps`; if `bitrateFollowsResolution`, sets `prefConfig.bitrate = getDefaultBitrate(width, height, fps)` (capped at the 300000 kbps seek bar maximum) and, if `meteredBitrateDerived`, `meteredBitrate = bitrate / 4`. Logs the reason with `LimeLog.info`.
* No `DisplayListener` and no toast (manager decision): auto is resolved fresh at every stream start, which already gives the next launch the new display's mode, and keeps the stream activity free of new callbacks.

Changes in `Game.java`, the only streaming path edits:

1. After line 396 (`onExternelDisplay = ...`) and before line 398, one statement: `AutoResolutionAndroid.resolveInto(this, prefConfig);` with a comment. Everything after it (the external display override on lines 398 to 411, orientation and inversion on lines 413 to 433, HDR, decoder setup, `prepareDisplayForRendering`, the `StreamConfiguration.Builder` on lines 779 to 801 including `setVirtualDisplay`, `setWindowOnly` and `setResolutionScaleFactor`) reads `prefConfig` exactly as before, now with concrete values.
2. Nothing else in `Game.java` changes (no field, no `onDestroy` change).

Why there: it runs before the decoder renderer is created (line 648), so `MediaCodecDecoderRenderer` sees the resolved FPS; it runs after the display is known; and "Fully External Display Mode" keeps its own override because it runs afterwards.

### 8.7 Settings UI for auto

* `StreamSettings.SettingsFragment`: the resolution and FPS rows show a first summary line "Currently 1920x1080 at 120 FPS on the built-in display" (or "on the external display, mirrored"), computed with the same resolver against the settings activity's own display, plus "host virtual display 3840x2160" when the scale factor is not 100. `StreamSettings.onConfigurationChanged()` (lines 124 to 140) already reloads when the display pixel count changes; extend the check to the display id so moving the settings window between displays refreshes the hint.
* Picking `auto` in either list: `resetBitrateToDefault()` gets the resolved values instead of parsing the string, and sets `bitrate_follows_resolution` true. Moving the bitrate seek bar (`SeekBarPreference` calls `callChangeListener` after saving, line 153) or entering a custom bitrate (lines 862 to 880) sets it false.
* `auto` is inserted at index 0, before `nativeResolutionStartIndex`, so the native resolution warning dialog (lines 673 to 703) never fires for it; the 4K, 1440p and 1080p removal logic (lines 579 to 592) and FPS removal (lines 605 to 614) never remove or replace `auto` because they only act when the current value equals the removed entry.
* New checkbox "Use the external display's resolution when mirrored" (`checkbox_auto_res_prefer_external`, default on) placed after `list_fps` inside the Video category's "Show more" group.

### 8.8 Behavior matrix

| Situation | Auto result |
| --- | --- |
| Thor, nothing connected | 1920x1080, 120 FPS |
| Thor, external 4K60 TV, Android mirrors the main panel, prefer external on | 3840x2160, 60 FPS (decoder permitting) |
| Same, prefer external off | 1920x1080, 120 FPS |
| App moved to or launched on the external display (desktop mode, "Fully External Display Mode", or docked output that becomes the activity's display) | That display's native mode, for example 3840x2160 at 60, or 3440x1440 at its refresh |
| Thor Lite (1080p output only) on a 4K TV | 1920x1080 (the current mode is 1080p) |
| External 5K2K ultrawide as the activity display | 4096x1728 (policy clamp, aspect kept) |
| Decoder without 4K support | Next ladder size with the same aspect, for example 2560x1440, or 1920x1080 |
| App on the Thor's bottom panel | 1240x1080 at 60 (the panel it is on; squarish screen orientation rules apply as today) |
| Monitor plugged in or removed mid-stream | Stream unchanged; next launch uses the new display. If Android moves the stream activity to another display with a different density, the activity is recreated by the system (existing behavior, `density` is not in Game's `configChanges`) and reconnects at the new auto size |
| Explicit 1920x1080 or 4K, or a native or custom entry | Exactly as today |

### 8.9 Verification aid on the device

`DebugInfoActivity` (reachable from settings, `pref_debug_info`, `preferences.xml` line 1019) gets a "Displays" section: for every display its id, name, state, flags (presentation, private, secure), current mode, supported modes, HDR types, whether product info exists (API 31+), and the auto resolver's decision with its reason. This is how the Thor's mirroring and bottom panel behavior gets confirmed (Q3).

## 9. Files to change

Design (phase A):

* `app/src/main/res/values/colors.xml` (tokens, `libraryFocus` alias), new `values/dimens.xml` entries, new `values/integers.xml`, `values/styles.xml` (AppTheme lines 18 to 26, SettingsTheme lines 48 to 53, new TextAppearance, ShapeAppearance, Widget and ThemeOverlay styles, `PreferenceThemeOverlay.Vibe`), `values-v29/styles.xml` (SettingsTheme).
* New drawables: Material Symbols Rounded `ic_arrow_back`, `ic_play_arrow`, `ic_lock_open`, `ic_power`, `ic_warning`, `ic_sensors`, `ic_videocam`, `ic_volume_up`, `ic_sports_esports`, `ic_mouse`, `ic_tune`, `ic_palette`, `ic_touch_app`, `ic_keyboard`, `ic_trackpad_input`, `ic_speed`, `ic_science`, `ic_more_horiz`; refreshed path data for `ic_settings`, `ic_help`, `ic_add`, `ic_computer`, `ic_profiles`; `host_card_selector.xml`, `host_card_glow.xml`, `host_status_dot.xml`, `vw_pref_row_background.xml`, `vw_hint_glyph.xml`.
* `app/src/main/res/layout-land/activity_pc_view.xml`, `layout/activity_pc_view.xml`, `layout/pc_grid_item.xml`, `layout/pc_grid_view.xml`.
* `app/src/main/java/com/limelight/grid/PcGridAdapter.java` (`populateView`, lines 53 to 98).
* New `app/src/main/java/com/limelight/ui/HostCardState.java` (pure).
* `app/src/main/java/com/limelight/PcView.java`: `initializeViews()` lines 140 to 205 (header, hint bar, empty state button), `receiveAbsListView()` lines 891 to 912 (selection listener, centering, initial selection), `updateComputer()` and `removeComputer()` lines 824 to 884 (header subtitle), new `dispatchKeyEvent`, `onPause` (remember selection).
* `app/src/main/res/layout/activity_stream_settings.xml`, new `vw_pref_row.xml`, `vw_pref_switch.xml`, `vw_pref_category.xml`, `expand_button.xml`.
* `app/src/main/java/com/limelight/preferences/StreamSettings.java`: `onCreate` lines 92 to 103 (top bar), `SettingsFragment.onCreateView` lines 319 to 325 and a new `onViewCreated` (decoration, scroll listener), new `dispatchKeyEvent` in the activity for L1 and R1.
* New `app/src/main/java/com/limelight/preferences/GroupedCardDecoration.java`.
* `app/src/main/res/xml/preferences.xml`: keys for three categories (lines 228, 497, 514).
* Library: `activity_app_view.xml`, `library_group_header.xml`, `app_grid_item.xml`, `app_grid_item_small.xml`, `library_search_background.xml`, `library_chip_background.xml`, `res/color/library_chip_text.xml`, `library_wheel.xml` (badge text color, line 99), `AppCardBinder.java` line 46.
* `app/src/main/res/layout/activity_add_computer_manually.xml` line 3.
* `app/src/main/res/values/strings.xml`: new strings (English only; other locales fall back).

Auto resolution (phase B):

* `app/src/main/res/values/arrays.xml` lines 3 to 33, `res/xml/preferences.xml` lines 12 to 27 plus the new checkbox, `res/values/strings.xml`.
* `app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java` lines 42 to 44, 143 to 144, 226 to 232, 500 to 588, 688 to 701, 792 to 805, 838 to 848, plus `migrateToAutoDisplayDefaults`.
* New `AutoResolution.java` and `AutoResolutionAndroid.java` in `com.limelight.preferences`.
* `app/src/main/java/com/limelight/Game.java`: one call after line 396, nothing else.
* `app/src/main/java/com/limelight/preferences/StreamSettings.java` lines 124 to 140 (reload on display id), 304 to 317 (`resetBitrateToDefault`), 673 to 725 (listeners), summary hint.
* `app/src/main/java/com/limelight/PcView.java` after line 151 (migration call).
* `app/src/main/java/com/limelight/DebugInfoActivity.java` (Displays section).
* `.github/workflows/release.yml` lines 79 to 85 (test filters), `VERSION`.

Explicitly not changed: `AdapterFragment`, `GenericGridAdapter`, `NvConnection`, `NvHTTP`, `StreamConfiguration`, `MediaCodecDecoderRenderer`, `ServerHelper`, the window-only flag and its default, the virtual display flag and its default, `StreamTheme`.

## 10. Tests

JVM (no Android):

* `app/src/test/java/com/limelight/preferences/AutoResolutionTest.java`: explicit values pass through untouched; Thor panel reported as 1080 by 1920 portrait gives 1920x1080; current mode 60 Hz with a 120 Hz mode of the same size gives 120; external 4K60 as activity display; mirrored 4K with prefer on and off; mirrored ultrawide gives 2560x1440; ultrawide as activity display keeps 3440x1440; 5120x2160 to 4096x1728; 7680x4320 to 3840x2160; 1366x768 to 1360x768; a presentation flagged display smaller than the main panel is ignored; decoder without 4K steps to 1440p keeping aspect; decoder 4K60 only with auto FPS on a 120 Hz display gives 4K60; same with explicit 120 FPS steps the size down; untrustworthy decoder caps are ignored; auto FPS with explicit size; auto size with explicit FPS.
* `app/src/test/java/com/limelight/ui/HostCardStateTest.java`: every row of the table in 4.3.
* `GroupedCardDecorationTest`: first, middle, last and single positions, headers and the expand button row.

Robolectric:

* `app/src/test/java/com/limelight/preferences/AutoPreferencesTest.java`: `readPreferences` with `auto` sets the flags and placeholders and does not rewrite the value to 720p; `getDefaultBitrate(Context)` with `auto` does not throw; `StartupCrashTest`'s `invalid_resolution` still maps to 720p; migration converts exactly the old defaults once and leaves other values alone; `resetStreamingSettings` lands on `auto`.
* `app/src/test/java/com/limelight/preferences/StreamSettingsUiTest.java`: the activity inflates with the new layouts; the resolution list has `auto` first and keeps all explicit entries; choosing `auto` sets the follow flag; changing the bitrate clears it; L1 and R1 move between categories; checkbox rows contain a `MaterialSwitch` bound to the stored value.
* `app/src/test/java/com/limelight/PcViewUiTest.java`: ids `settingsButton`, `helpButton`, `manuallyAddPc`, `profilesButton`, `pcFragmentContainer`, `no_pc_found_layout` exist in landscape and portrait; Start opens `StreamSettings`, Y opens `ProfilesActivity` (via `shadowOf(activity).getNextStartedActivity()`).
* Existing `StartupTest`, `StartupCrashTest`, `ProfilesNavigationTest`, `LayoutInflationTest` and all `com.limelight.library.*` tests keep passing.
* CI: add `--tests 'com.limelight.preferences.*' --tests 'com.limelight.ui.*' --tests 'com.limelight.PcViewUiTest'` to the unit test step in `.github/workflows/release.yml` (lines 79 to 85).

Manual on the Thor (CI built APK, no local SDK): the acceptance list below with the controller only and with touch only, plus the Displays dump with and without an external monitor.

## 11. Acceptance criteria

1. PC list, settings and library share the background, surfaces, accent, type scale, radii and focus ring defined in section 3; no #1A1A1A or #4FC3F7 literal remains in layouts or drawables.
2. PC list shows centered host cards with the state table of 4.3; A, X, Y, Start and long press behave as in 7.1; the empty state has a focusable "Add PC manually" button.
3. Settings shows grouped cards, switches for every checkbox, styled "Show more" expanders and dialogs; every existing preference still reads and writes the same key; profile editing (`EditProfileActivity`) works and looks the same.
4. D-pad focus is always visible and never lost on all three screens; L1 and R1 jump between settings categories.
5. A fresh install defaults to Auto for resolution and FPS; an existing install that had exactly 1080p, 120 FPS and the default bitrate is migrated once; any other existing choice is kept.
6. On the Thor with nothing connected, a stream started with Auto requests 1920x1080 at 120 FPS (visible in the host log and the performance overlay) and the host virtual display is created at that size times the scale factor.
7. With an external 4K display that the app runs on or mirrors (prefer external on), the next stream requests 3840x2160 at that display's refresh rate if the decoder supports it, else the clamped size from 8.5; with prefer external off it requests the Thor panel size.
8. Plugging or unplugging a display mid-stream does not change the running stream; the next launch uses the new display.
9. Every explicit resolution, native, native full-screen and custom entry works exactly as in 1.1.3; window-only and virtual display flags are sent exactly as before.
10. CI is green: unit tests (including the new ones) and the release APK build.

## 12. Risks and mitigations

* Thor display behavior is undocumented (mirroring, docked DP output, bottom panel flags). Mitigation: the resolver only switches to another display when it is clearly external and larger than the panel, a settings switch turns the mirror rule off, and the Displays dump makes the behavior visible on the device before release.
* Mirroring at 4K costs decode power and heat for a picture that the built-in panel shows at 1080p; and some compositors may scale the mirror from the panel resolution, which would make 4K pointless. Mitigation: confirm image sharpness on the TV during manual testing; the prefer external switch exists so the user can choose; if it is pointless on the Thor, flip its default to off.
* `ServerHelper.getSecondaryDisplay()` (lines 73 to 91, used by "Fully External Display Mode") picks the first non-default display, which on the Thor may be the bottom panel. Existing behavior, not changed here; follow-up candidate using the same external display test.
* A display move with a density change recreates the stream activity (no `density` in `configChanges`). Existing behavior; changing the manifest for `Game` is out of scope because it touches the streaming path.
* Bitrate: auto at 4K raises the default bitrate (4K60 about 80 Mbps, 4K120 about 113 Mbps) only when the bitrate follows the resolution; users with a manual bitrate keep it, which may look soft at 4K. The settings summary shows the effective value.
* Scale factor multiplies the host virtual display (4K at 200 percent is 8K on the host). Not clamped, but shown in the summary.
* Decoder capability data is unreliable on some chips; mitigated by never clamping at or below 1080p and ignoring caps that do not report 1280 wide support, as upstream does.
* Host limits: auto stays at or below 4096 by 2160 and 3840 by 2160 pixels, inside `NvConnection`'s checks; old GFE hosts still drop 4K to 1080p as today.
* Overriding androidx's `expand_button` layout depends on a library resource name; covered by a Robolectric inflation test, with a theming-only fallback.
* `MaterialSwitch` inside `CheckBoxPreference` relies on the documented `android:id/checkbox` binding; covered by the settings UI test.
* Contrast: white on #0A84FF fails AA, so filled controls use `vw_accent_container`; secondary text #8E8E93 passes on both surfaces.
* The VoidLink look was reconstructed from source code, not screenshots; the overall composition may differ from what the user has in mind (Q1).
* No local Android SDK: land in small commits (tokens, PC list, settings, library, auto resolution data layer, Game hook) so each CI run is bisectable.

## 13. Phasing and version

1. Tokens and theme (section 3), library token swap (section 6).
2. PC list (section 4) with `HostCardState` tests.
3. Settings (section 5) with decoration and UI tests.
4. Auto resolution data layer: arrays, defaults, `PreferenceConfiguration` changes, migration, `AutoResolution` with tests, settings integration, Displays dump.
5. `Game.java` hook (one line) and `AutoResolutionAndroid`.
6. Bump `VERSION` from 1.1.3 to 1.2.0 (the release workflow derives the version name from it and the version code from the run number) and release after a Thor test pass.

## 14. Open questions for the user

None of these block coding. The coordinator proceeds on the stated assumptions (Q1 the direction in sections 3 to 5, Q3 mirror rule on by default) and tells the user.


* Q1: VoidLink is an iOS only client and its screenshots could not be viewed here. Is the direction in sections 3 to 5 (true black, iOS style blue accent, grouped cards, host cards with a primary action pill) what you mean by "styled like VoidLink", or did you mean a different product?
* Q2: decided by the manager: existing installs on exactly the old defaults are migrated to Auto once (8.4).
* Q3: On your Thor with an external monitor, does the screen mirror the main panel or does the app move to the monitor? The Displays dump in debug info will tell; the mirror rule defaults to "use the external display's resolution".
* Q4: Do you want VoidLink's "Favorites" (pin settings to the top) in a later version?

## 15. Sources

VoidLink:

* Repository and README: https://github.com/The-Fried-Fish/VoidLink-previously-moonlight-zwm (files cited: `VoidLink/ThemeManager.swift`, `VoidLink/HostCardView.swift`, `VoidLink/MenuSectionView.swift`, `VoidLink/ViewControllers/SettingsSwiftUI.swift`, `VoidLink/ViewControllers/HostCollectionViewController.swift`, `VoidLink/GamepadNavigationIllustrationHud.swift`, `LICENSE.txt`, branch `Integration`)
* App Store: https://apps.apple.com/app/voidlink-extreme/id6755103808 , https://apps.apple.com/app/voidlink/id6747717070
* Project page mention: https://www.patreon.com/cw/TrueZhuanjia

AYN Thor:

* Specs (panels, chipset): https://retrohandhelds.gg/ayn-thor-specs-confirmed-chipset-displays-battery-and-more/
* 4K60 output on Snapdragon 8 Gen 2, 1080p on the 865 model: https://www.notebookcheck.net/AYN-Thor-dual-screen-handheld-begins-shipping-but-only-the-Snapdragon-8-Gen-2-version.1138344.0.html
* Firmware notes (docked DP output, later OTAs): https://retrohandhelds.gg/the-ayn-thor-just-got-a-quality-of-life-ota-that-fixes-the-annoying-stuff/ , https://steamdeckhq.com/news/ayn-thor-gets-oled-ultra-black-mode-fixes-bugs/

Android:

* Connected displays (display moves, configuration changes, DisplayManager, launch display): https://developer.android.com/develop/adaptive-apps/guides/support-connected-displays
* Multi-window and display changes: https://developer.android.com/guide/topics/ui/multi-window
* Display API (`getMode`, `getSupportedModes`, `FLAG_PRESENTATION`, `getDeviceProductInfo`): https://developer.android.com/reference/android/view/Display
* DisplayManager listeners: https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener
* Window metrics: https://developer.android.com/reference/android/view/WindowManager#getCurrentWindowMetrics()
* Decoder capabilities: https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
* Gamepad key fallbacks: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/data/keyboards/Generic.kcm
* Material Symbols (Apache 2.0): https://github.com/google/material-design-icons
* Material Components for Android (MaterialSwitch, dialogs, shapes): https://github.com/material-components/material-components-android
* Upstream Moonlight Android settings and native resolution handling: https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/preferences/StreamSettings.java

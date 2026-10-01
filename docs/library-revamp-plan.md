# Library revamp plan (app list grouping, search, collapse, layouts)

Status: plan only, nothing implemented. Targets client 1.1.0 and host 1.1.0.

## 1. Goals

1. Group the app list by system (PC, Nintendo Switch, Nintendo 3DS, GameCube, PlayStation 2, ...) with non-game utilities (Desktop, Playnite, Remote Input, Lossless Scaling, ...) in their own "Apps" group.
2. Search across all groups.
3. Expand and collapse groups.
4. Selectable layout: Grid, Wheel (ES-DE / HyperSpin style), and Shelves (recommended third option, see section 6.4).
5. Keep every existing behavior: tap to launch, long press context menu (resume, quit, quit and start, virtual display variants, hide app, view details, pinned shortcut, export launcher file), hidden apps and the "view hidden apps" entry from the PC list, running app overlay, box art caching, profiles button.

Primary device: AYN Thor, 1920x1080 landscape at 120 Hz, touch plus built-in gamepad. Every interaction must work with D-pad and face buttons alone, and with touch alone.

## 2. Research findings

### 2.1 What the host sends today

`/applist` is built in `src/nvhttp.cpp` `applist()` (lines 3044 to 3138 in the host repo). Per app it emits only `IsHdrSupported`, `AppTitle`, `UUID`, `IDX`, `ID` and `ArtVersion` (lines 3116 to 3121). There is no platform, category, source or playnite id in the response.

`proc::ctx_t` (`src/process.h` lines 106 to 173) has no platform field. It does hold `playnite_id` (line 137) and `playnite_fullscreen` (line 139), parsed in `src/process.cpp` lines 3661 to 3686.

### 2.2 What apps.json stores for Playnite games

Playnite sync writes, per synced app (`src/platform/windows/playnite_sync_policy.cpp`):

* `apply_game_data()` lines 101 to 121: `name`, `playnite-id`, `playnite-plugin-id`, `playnite-plugin-name` (the library plugin, for example "Steam" or empty for emulated games).
* `mark_app_as_playnite_auto()` lines 528 to 533: `playnite-source` (this is the sync reason such as `recent+category`, not the Playnite "Source" field) and `playnite-managed`.
* Image paths from `apply_game_metadata_to_app()` in `playnite_sync.cpp` lines 60 to 82.

`apply_game_data()` runs both for new entries (`add_missing_auto_entries()`, line 539) and for every existing app matched by id, exe, working dir or unique name (`iterate_existing_apps()`, lines 144 to 169), so a new field written there is backfilled on the next snapshot for all matched apps.

### 2.3 What the Playnite plugin sends

The connector is a PowerShell script extension, `plugins/playnite/SunshinePlaynite/SunshinePlaynite.psm1` (version 0.4.14 in `extension.yaml`). `Get-PlayniteGames` (lines 1561 to 1613) sends: id, name, exe, args, workingDir, installDir, categories, pluginId, pluginName, playtimeMinutes, lastPlayed, boxArtPath, iconPath, installed, and an always empty `tags`. It does not send Platforms, Source or emulator information. The host parser (`src/platform/windows/playnite_protocol.cpp` lines 85 to 125, struct `Game` in `playnite_protocol.h` lines 51 to 68) matches that.

The plugin already knows how to detect an emulator play action (`Build-StatusPayload`, lines 1891 to 1937: `GameAction.Type` contains "Emulator" or a non-empty `EmulatorId`, then looks up `$PlayniteApi.Database.Emulators`).

### 2.4 What Playnite can provide (SDK)

From the Playnite SDK sources:

* `Game.PlatformIds` (`List<Guid>`) and the resolved `Game.Platforms` (`List<Platform>`, sorted by name, resolved through the database reference), `Game.SourceId` and `Game.Source` (`GameSource`, has `Name`), `Game.GameActions`. Source: https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Game.cs
* `Platform.Name` and `Platform.SpecificationId`, a stable id such as `nintendo_switch`, `nintendo_3ds`, `nintendo_gamecube`, `sony_playstation2`, `nintendo_wiiu`, `pc_windows`. Sources: https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Platform.cs and the built-in list https://github.com/JosefNemec/Playnite/blob/master/source/Playnite/Emulation/Platforms.yaml ; API docs https://api.playnite.link/docs/api/Playnite.SDK.Models.Platform.html
* `GameAction.Type` (`GameActionType.Emulator = 2`), `GameAction.EmulatorId`, `GameAction.EmulatorProfileId`. Source: https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/GameAction.cs
* `Emulator.BuiltInConfigId` (for example `ryujinx`), and `IEmulationAPI.GetEmulator(id)` returns an `EmulatorDefinition` whose profiles list platform specification ids (Ryujinx: `[nintendo_switch]`). Sources: https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Emulator.cs , https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/IEmulationAPI.cs , https://github.com/JosefNemec/Playnite/blob/master/source/Playnite/Emulation/Emulators/Ryujinx/emulator.yaml
* Playnite emulation docs: https://api.playnite.link/docs/manual/features/emulationSupport/emulationSupportOverview.html

Conclusion: platform is not available to the client today. It is cheaply available inside Playnite, so the minimal path is: plugin sends platform data, host stores it in apps.json, host exposes it in `/applist`.

### 2.5 How the client parses /applist

`NvHTTP.getAppListByReader()` (client `app/src/main/java/com/limelight/nvstream/http/NvHTTP.java` lines 717 to 781) is a pull parser that keeps a tag stack and, on TEXT events (lines 745 to 757), only reacts to known tag names. Unknown elements are ignored, which is why the host's existing `ArtVersion` element never broke anything. Old clients (this app before 1.1.0, upstream Moonlight and Artemis) will ignore new elements the same way. Because the raw XML is cached (`CacheHelper` "applist" file, `AppView.populateAppGridWithCache()` lines 356 to 372) the new fields are also available from cache on startup.

### 2.6 Current client UI

* `AppView` (`app/src/main/java/com/limelight/AppView.java`, 795 lines) hosts an `AdapterFragment` that inflates `app_grid_view.xml` or `app_grid_view_small.xml`, both a classic `GridView` (`AbsListView`), and receives it via `receiveAbsListView()` (lines 743 to 776).
* `AppGridAdapter` (`grid/AppGridAdapter.java`) extends `GenericGridAdapter` (a `BaseAdapter`), keeps `allApps` plus a filtered `itemList`, owns the `CachedAppAssetLoader`, sorts by host index then name (lines 125 to 138), and applies hidden state (lines 52 to 74).
* The context menu relies on `AdapterContextMenuInfo` from the `GridView` (lines 447 and 506) and on `info.targetView` to find `R.id.grid_image` for pinned shortcuts (lines 486 and 594).
* `AdapterFragment` and `GenericGridAdapter` are also used by `PcView` / `PcGridAdapter`, so they must stay untouched.
* `PreferenceConfiguration.smallIconMode` (lines 61, 255, 891) switches 170 dp vs 110 dp cards.
* Dependencies already present: `androidx.recyclerview:recyclerview:1.4.0`, `com.google.android.material:material:1.13.0`, `androidx.cardview`, `androidx.appcompat:1.7.1` (`app/build.gradle` lines 158 to 163). No new library is needed.
* `CachedAppAssetLoader.populateImageView(app, imageView, textView)` (lines 334 to 370) is view-recycling safe (it uses an `AsyncDrawable` tagged with its task), so it works unchanged inside `RecyclerView` view holders.
* The manifest entry for `AppView` (lines 131 to 137) handles configuration changes itself and enables predictive back (`enableOnBackInvokedCallback`).

### 2.7 Gamepad key behavior on Android

AOSP `Generic.kcm` defines fallbacks that apply only when the app does not consume the original key: `BUTTON_A` to `DPAD_CENTER`, `BUTTON_B` to `BACK`, `BUTTON_X` to `DEL`, `BUTTON_Y` to `SPACE`, `BUTTON_START` to `DPAD_CENTER`, `BUTTON_SELECT` to `MENU`, `L1`/`R1` none. Source: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/data/keyboards/Generic.kcm . So `AppView.dispatchKeyEvent()` can claim X, Y, L1, R1, START and SELECT for library actions, while A and B keep their default meaning (confirm, back). Long press on a confirm key triggers `performLongClick()` on the focused view, which is how the context menu is reached with a controller today. Android focus and D-pad guidance: https://developer.android.com/training/tv/get-started/navigation

## 3. Architecture decision

### 3.1 Data source: host-provided platform with client fallback and client override

Effective group of an app, highest priority first:

1. Client-side per-app override (set from the context menu, stored on the device).
2. Host `Platform` / `PlatformId` from `/applist` (which already folds in the host's manual override and Playnite data, see 4.4).
3. Client heuristic: built-in utility UUIDs and known utility names go to "Apps".
4. Fallback: if the host advertises platforms (at least one app carries a `Platform` element) an app without one goes to "PC" (on this Windows host an un-tagged custom app with a command is almost always a PC game or tool, and tools are caught by step 3). If the host does not advertise platforms at all (old host), everything that is not a utility goes to a single "Games" group.

### 3.2 UI: RecyclerView with one pure model and three layout controllers

* Replace the `GridView` path in `AppView` with a single `RecyclerView` based screen. Do not touch `AdapterFragment`, `GenericGridAdapter`, `PcGridAdapter` or `PcView`.
* A pure Java model (`LibraryModel`, no Android imports) turns (apps, hidden set, show hidden flag, query, collapsed set, overrides, host platform support) into an immutable snapshot: ordered groups, each with its apps, plus a flat row list (header rows and app rows) for sectioned layouts. This is where grouping, search, sorting and collapse live, and it is fully unit testable on the JVM.
* Three `LibraryLayoutController` implementations render the same snapshot:
  * Grid: `GridLayoutManager` with a `SpanSizeLookup` that gives header rows the full span (https://developer.android.com/reference/androidx/recyclerview/widget/GridLayoutManager.SpanSizeLookup).
  * Shelves: vertical `RecyclerView` of rows, each row a header plus a nested horizontal `RecyclerView`, all rows sharing one `RecycledViewPool`.
  * Wheel: vertical `RecyclerView` with `LinearLayoutManager`, `LinearSnapHelper` (https://developer.android.com/reference/androidx/recyclerview/widget/LinearSnapHelper) and per-frame scale, alpha and arc offset transforms computed in an `OnScrollListener`, next to a large hero panel.
* Adapters use stable ids and `DiffUtil` (`ListAdapter`) so the periodic applist and running-state polling never rebuilds views or steals D-pad focus.

Alternatives rejected:

* Material `CarouselLayoutManager` (https://developer.android.com/reference/com/google/android/material/carousel/CarouselLayoutManager): requires every item to be a `MaskableFrameLayout` and conveys focus by masking (cropping) items rather than scaling covers, which crops box art; its docs say nothing about D-pad or keyboard focus. Too risky for the controller-first Thor.
* `androidx.leanback` (`HorizontalGridView` / `VerticalGridView`): good D-pad behavior but a new, TV-oriented dependency with its own theming. The few behaviors we need (remember last focused child per row, center selection) are small to implement on plain `RecyclerView`.
* Keeping `GridView` and faking headers: `GridView` has no full-span rows; not viable.
* A custom `LayoutManager` for the wheel: more code and more focus edge cases than a `LinearLayoutManager` with view transforms.

## 4. Host changes (host repo, branch claude/clever-clarke-k7hhhj)

### 4.1 Playnite plugin: send platform data

File `plugins/playnite/SunshinePlaynite/SunshinePlaynite.psm1`:

* New helper `Get-EmulatorInfoMap` next to `Get-LibraryPluginMap` (line 1464 area). Built once per snapshot: for each `$PlayniteApi.Database.Emulators` entry, map emulator id (lower-case string) to `@{ name; builtInId = $emu.BuiltInConfigId; platforms = <spec ids> }`. Platform spec ids come from `$PlayniteApi.Emulation.GetEmulator($emu.BuiltInConfigId).Profiles[*].Platforms` for built-in emulators, and for custom profiles from `$emu.CustomProfiles[*].Platforms` (Guids) mapped through `$PlayniteApi.Database.Platforms` to `SpecificationId` (or Name when the spec id is empty). Every access wrapped in try/catch like the existing helpers.
* New helper `Get-PlatformList -Game $g`: iterate `$g.Platforms` (null when none) and return `@(@{ name = $p.Name; specId = [string]$p.SpecificationId })`.
* In `Get-PlayniteGames` (lines 1561 to 1613) add to each game hash:
  * `platforms` = `Get-PlatformList` result (always present, possibly empty array; its presence is how the host knows the plugin is new),
  * `source` = `$g.Source.Name` or empty,
  * `emulated` = true when the play action is an emulator action (reuse the detection logic from `Build-StatusPayload` lines 1898 to 1912, factored into a shared `Get-PlayActionEmulator` helper),
  * `emulatorName`, `emulatorBuiltInId`, `emulatorPlatforms` (spec ids) from the emulator map when emulated.
* Bump `extension.yaml` `Version: 0.4.14` to `0.5.0` so the host's packaged vs installed version check (`playnite_integration.cpp` lines 2101 to 2125) offers the plugin update in the web UI.

### 4.2 Protocol: parse the new fields

* `src/platform/windows/playnite_protocol.h` struct `Game` (lines 51 to 68): add `struct PlatformRef { std::string name; std::string spec_id; };`, `std::vector<PlatformRef> platforms;`, `bool has_platform_info = false;`, `std::string source_name;`, `bool emulated = false;`, `std::string emulator_name;`, `std::string emulator_builtin_id;`, `std::vector<std::string> emulator_platforms;`.
* `playnite_protocol.cpp` games branch (lines 85 to 125): `has_platform_info = g.contains("platforms")`; parse each element defensively (object with name/specId, or plain string treated as name); `source`, `emulated`, `emulatorName`, `emulatorBuiltInId`, `emulatorPlatforms` via `value()` with defaults. Tolerate PowerShell's habit of serializing single-element arrays as objects (reuse `to_string_list` style handling).

### 4.3 Sync: derive and store the platform

In `src/platform/windows/playnite_sync_policy.cpp` add a pure, exported function (declared in `playnite_sync_policy.h`) `PlatformGuess derive_platform(const Game &game)` returning `{name, spec_id}`:

1. If `!game.has_platform_info`: return empty (old plugin; do not guess, otherwise emulated games would be mislabeled as PC).
2. If `game.emulated`: pick the first game platform whose spec id is in `emulator_platforms`; else the first game platform whose spec id does not start with `pc_`; else the first emulator platform mapped to a display name through a small built-in table (spec id to Playnite name, copied from `Platforms.yaml` for the common systems); else a name table keyed on `emulator_builtin_id` or emulator name (yuzu, suyu, sudachi, citron, eden, ryujinx to Nintendo Switch; citra, azahar, lime3ds to Nintendo 3DS; dolphin to Nintendo GameCube; cemu to Nintendo Wii U; pcsx2 to Sony PlayStation 2; rpcs3 to Sony PlayStation 3; duckstation to Sony PlayStation; ppsspp to Sony PlayStation Portable; melonds to Nintendo DS; mgba to Nintendo Game Boy Advance); else `{"Emulated", ""}`.
3. Not emulated: prefer a `pc_windows` platform when present, else the first game platform, else `{"PC (Windows)", "pc_windows"}` (a non-emulated Playnite game is a PC game).

Store it in `apply_game_data()` (lines 101 to 121) as `playnite-platform` and `playnite-platform-id`, erasing both when the result is empty, mirroring the `playnite-plugin-name` handling. Because `apply_game_data()` runs for new and existing matched apps, the next library snapshot backfills every Playnite-linked entry. The sync never writes the manual `platform` key.

### 4.4 apps.json parsing and /applist output

* `src/process.h` `ctx_t` (after `playnite_fullscreen`, line 139): add `std::string platform;` and `std::string platform_id;`.
* `src/process.cpp` `parse()`, right after the `playnite-fullscreen` block (line 3686): resolve the effective platform:
  1. `platform` (manual override, string) and optional `platform-id`;
  2. else `playnite-platform` / `playnite-platform-id`;
  3. else if `playnite_fullscreen` is true, or the app has no `cmd` and no `playnite-id` (Desktop, Steam Big Picture style launchers): `"Apps"` / `"apps"`;
  4. else empty.
  Trim, cap at 64 characters, ignore non-string values.
* Built-in entries get `"Apps"` / `"apps"`: Desktop fallback (line 3799), Virtual Display (line 3833), Remote Input (line 3867), Terminate (line 3903).
* `src/nvhttp.cpp` `applist()` after `ArtVersion` (line 3121): `if (!app.platform.empty()) app_node.put("Platform", app.platform); if (!app.platform_id.empty()) app_node.put("PlatformId", app.platform_id);`. Elements are only emitted when non-empty. The permission-denied branch is unchanged.

Resulting XML per app (example):

```
<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Example Switch Game</AppTitle><UUID>...</UUID><IDX>12</IDX><ID>123456</ID><ArtVersion>...</ArtVersion><Platform>Nintendo Switch</Platform><PlatformId>nintendo_switch</PlatformId></App>
```

### 4.5 Web UI manual override

Cut from this version. The client "Move to group" override (6.6) covers manual regrouping on the Thor, and a hand-edited `platform` key in apps.json is still honored by 4.4. A web UI field is a follow-up.

### 4.6 Host tests

* `tests/test_playnite_protocol.cpp`: new plugin payload with platforms, single-object arrays, missing `platforms` key (has_platform_info false).
* `tests/test_playnite_sync.cpp` or `tests/test_playnite_autosync_reconcile.cpp`: `derive_platform()` table tests (multi-platform IGDB style game emulated by Ryujinx gives Nintendo Switch; Steam game with PC and Switch platforms gives PC; old plugin gives empty) and that `apply_game_data()` writes and erases the keys and never touches `platform`.
* `tests/unit/test_process.cpp`: apps.json with `platform`, with only `playnite-platform`, with no cmd, and with a cmd and nothing else; built-ins report "Apps".

### 4.7 Host versioning

Bump `.github/window-only-host-version.txt` from 1.0.2 to 1.1.0 and the plugin to 0.5.0. Release notes: the Playnite connector must be updated from the web UI (Integrations page) and Playnite restarted, then one library snapshot fills platforms. The Windows CI run (about 25 minutes, startup smoke test) must pass before tagging.

## 5. Client data layer (client repo)

### 5.1 NvApp and parsing

* `nvstream/http/NvApp.java`: add fields `platform` and `platformId` (default empty) with getters and setters, include `Platform:` in `toString()` (lines 93 to 100) so "View details" shows it.
* `NvHTTP.getAppListByReader()` TEXT branch (lines 745 to 757): add `else if ("Platform")` and `else if ("PlatformId")` branches. Concatenate rather than assign in case the parser delivers text in several events (`app.setPlatform(app.getPlatform() + xpp.getText())`), and trim at the end of parsing. Add the same two branches nowhere else; unknown elements remain ignored.
* `AppView.updateUiWithAppList()` (lines 663 to 735) currently only refreshes the name of an existing app; extend it to refresh `platform` and `platformId` too and mark the snapshot dirty.

### 5.2 New package com.limelight.library

* `PlatformCatalog` (pure Java): maps a host `PlatformId` or name to a group key, a short display label and a sort rank. Aliases: `pc_windows`, `pc_dos`, `pc_linux`, "PC (Windows)" and "PC" all become key `pc`, label "PC". Short labels for common spec ids (`nintendo_switch` "Nintendo Switch", `nintendo_3ds` "Nintendo 3DS", `nintendo_gamecube` "GameCube", `nintendo_wii` "Wii", `nintendo_wiiu` "Wii U", `sony_playstation2` "PlayStation 2", and so on). Unknown values keep the host text as label and use its lower-cased text as key. Reserved keys: `apps` (label "Apps"), `games` (label "Games", only used when the host has no platform support), `results` (search results pseudo group in the wheel).
* `UtilityDetector` (pure Java): built-in UUIDs from the host (`8902CB19-674A-403D-A587-41B092E900BA` Virtual Display, `EAAC6159-089A-46A9-9E24-6436885F6610` Desktop fallback, `8CB5C136-DA67-4F99-B4A1-F9CD35005CF4` Remote Input, already `NvApp.REMOTE_INPUT_UUID`, `E16CBE1B-295D-4632-9A76-EC4180C857D3` Terminate) and case-insensitive name rules: exact "Desktop", "Steam Big Picture", "Playnite", "Virtual Display", "Terminate"; contains "Lossless Scaling"; starts with "Remote " (covers Remote Input and similar host tools). Names are normalized first (see search).
* `LibraryModel` (pure Java): input record with all `AppObject`s, hidden ids, showHidden, query, collapsed keys, override map, `hostHasPlatforms`. Output `LibrarySnapshot` with `List<Group>` (key, label, total count, visible apps, collapsed flag) and `List<Row>` (`HeaderRow(groupKey, label, count, collapsed)` and `AppRow(app, groupKey)`). Rules:
  * Group order: `pc` first, then other systems alphabetically by label, then any unknown or "Emulated" groups alphabetically, then `apps` last.
  * App order inside a group: name, case and accent insensitive, ignoring the zero-width characters the host inserts for legacy ordering. (The host index order mostly reflects sync order, which is meaningless inside a system group.)
  * Hidden apps are excluded unless `showHidden`, in which case they stay in their group and render at 40 percent alpha as today.
  * Empty groups are omitted.
  * Collapsed groups produce a header row and no app rows.
  * Search: query normalized (NFD, strip combining marks, lower case, strip zero-width characters, collapse whitespace), split into tokens, every token must be a substring of the normalized "name + space + group label". So "kart" finds every title containing that word and "switch" lists the Switch group. While a query is active, collapse state is ignored (matching groups render expanded) but is not modified, and groups without matches are omitted.
* `LibraryPrefs`: wraps SharedPreferences file `LibraryPrefs` (see section 8).
* `LibraryLayoutController` interface plus `GridLayoutController`, `ShelfLayoutController`, `WheelLayoutController` (section 6).
* `AppCardBinder`: binds one app card view (box art via `CachedAppAssetLoader.populateImageView`, name text, running overlay `ic_play` with `0x66000000` mask, hidden alpha), moved from `AppGridAdapter.populateView()` (lines 166 to 192).
* `AppLibrary`: the non-UI part of `AppGridAdapter` (all apps list, hidden id handling, `CachedAppAssetLoader` ownership and `cancelQueuedOperations()`, `updateLayoutWithPreferences()` scaling logic, `queueCacheLoad` on add). After the move `grid/AppGridAdapter.java` and `res/layout/app_grid_view*.xml` are deleted (only `AppView` used them). Keep `app_grid_item.xml` and `app_grid_item_small.xml` as the card layouts, adding a focus foreground.

## 6. Client UI spec

### 6.1 Screen frame (all layouts)

`res/layout/activity_app_view.xml` becomes a vertical structure:

* Top bar (height 56 dp, status bar padding via `UiHelper.applyStatusBarPadding`): PC name (keeps id `appListText`, 22 sp, left aligned), search field (`AppCompatEditText`, id `librarySearch`, single line, `imeOptions="actionSearch"`, search icon start drawable, clear icon end drawable when non-empty, hint "Search library"), a three-button `MaterialButtonToggleGroup` (ids `layoutGrid`, `layoutWheel`, `layoutShelves`, icon only, content descriptions), and a collapse/expand-all icon button.
* Content `FrameLayout` id `libraryContainer`, into which the active controller inflates its view.
* The existing `profilesButton` FAB stays (id unchanged, tests depend on it).
* Manifest `AppView` entry: add `android:windowSoftInputMode="adjustNothing|stateHidden"` so the on-screen keyboard does not relayout the library.

New vector icons in `res/drawable`: `ic_search`, `ic_layout_grid`, `ic_layout_wheel`, `ic_layout_shelves`, `ic_unfold_less`, `ic_unfold_more`, `ic_chevron_down`, `ic_chevron_right` (Material Symbols path data, Apache 2.0). New `res/drawable/app_card_focus.xml`: state list, focused state is a 3 dp rounded stroke in the accent color, otherwise transparent. All new strings in `res/values/strings.xml` (after `applist_menu_export_launcher`, line 163).

### 6.2 Common gamepad map (handled in `AppView.dispatchKeyEvent`, only on ACTION_DOWN, and only when focus is not inside the search field unless noted)

| Key | Action |
| --- | --- |
| D-pad / left stick | Move focus (grid, shelves) or move selection (wheel) |
| A | Launch, or open the context menu if something else is running (existing rules from `receiveAbsListView`, lines 746 to 771). On a focused group header: toggle collapse |
| Long press A | Context menu (existing long-click path) |
| X | Context menu for the focused or selected app (same menu as long press) |
| Y | Focus the search field and show the keyboard. Inside the field Y is left alone (falls back to SPACE), X falls back to DEL, which is useful for typing |
| L1 / R1 | Jump to the first app of the previous / next group (grid, shelves); previous / next system (wheel). Wraps around |
| Start | Toggle collapse of the group that owns the focused item (grid, shelves); no-op in the wheel |
| Select | Open the "View" popup: Grid, Wheel, Shelves, Collapse all, Expand all |
| B | Back stack: hide keyboard, then clear a non-empty query, then leave the search field, then finish the activity (implemented with an `OnBackPressedCallback` enabled only while the query is non-empty or the field has focus, compatible with predictive back) |

Key repeat (held D-pad) is honored; in the wheel, repeats after the first two use a shorter smooth scroll so a held direction moves about 12 items per second.

### 6.3 Grid layout (default)

* One `RecyclerView` (`res/layout/library_grid.xml`), `GridLayoutManager`, span count recomputed on every size change: `max(2, floor(contentWidth / cardWidth))` with card width 170 dp or 110 dp from `smallIconMode`. Horizontal padding centers the columns. `clipToPadding=false`, `descendantFocusability=afterDescendants`.
* View types: header (`res/layout/library_group_header.xml`: chevron, label, count, 44 dp tall, full span) and card (`app_grid_item.xml` / `app_grid_item_small.xml` with `android:foreground="@drawable/app_card_focus"` on the card).
* Focus: cards are focusable. Headers are focusable only when their group is collapsed (otherwise they would add one extra D-pad stop per group); expanded headers are still tappable. Focused card scales to 1.06 with a 120 ms `ViewPropertyAnimator`.
* Touch: tap header toggles, tap card launches, long press card opens the context menu. Collapse-all button collapses every group (headers become a compact vertical list of systems, which doubles as a system picker).
* Initial focus: the running app if any, else the last focused app for this host, else the first card.

### 6.4 Shelves layout (recommended third option)

Why shelves rather than a compact list: the host gives only title, art and platform, so a details list has little to show, while shelves map one-to-one onto "group by system", fit the 16:9 screen (about 2.5 shelves visible at 1080p), and are the convention gamepad users already know from Steam Big Picture, Netflix and Android TV.

* Vertical `RecyclerView` of shelf rows (`res/layout/library_shelf_row.xml`: header plus a horizontal `RecyclerView`, `LinearLayoutManager.HORIZONTAL`, cards 150 dp wide). All nested lists share one `RecycledViewPool`; nested lists set `setInitialPrefetchItemCount(6)`.
* Collapsed shelf: header only, focusable. Expanded header: not focusable, tappable.
* D-pad: left/right within a shelf; up/down moves to the previous or next shelf and restores that shelf's last focused position (kept in a map keyed by group key; implemented by overriding `onRequestFocusInDescendants` in a small `ShelfRecyclerView` subclass). The vertical list scrolls so the focused shelf sits near the top third.
* Touch: horizontal swipe per shelf, vertical swipe for the page, tap header toggles.

### 6.5 Wheel layout

ES-DE and HyperSpin style, one system at a time (ES-DE themes support `vertical_wheel` carousels; see https://gitlab.com/es-de/emulationstation-de/-/blob/master/THEMES.md):

* `res/layout/library_wheel.xml`: top system strip (horizontal row of group chips; current one highlighted), left hero panel (about 55 percent width: large box art with a crossfade on selection change, title, system label, "Running" badge), right wheel (about 45 percent width).
* Wheel: vertical `RecyclerView`, `LinearLayoutManager`, `LinearSnapHelper`, `clipToPadding=false` and top/bottom padding of half the height minus half an item so the first and last items can reach the center. Item layout `res/layout/library_wheel_item.xml`: small cover (64 x 85 dp) plus title, 96 dp tall.
* Transform in `onScrolled` and after layout: for each child compute `d = (childCenterY - centerY) / itemHeight`; `scale = max(0.7, 1 - 0.12 * |d|)`, `alpha = max(0.35, 1 - 0.22 * |d|)`, `translationX = arcRadius * (1 - cos(min(|d|, 4) * 0.28))` so items curve away from the center like a wheel. Cheap enough for 120 Hz (a handful of property sets per frame, no layout passes).
* Selection is the snapped center item, not Android focus: the wheel `RecyclerView` itself holds focus and consumes D-pad up/down by smooth scrolling one position (a `LinearSmoothScroller` whose `calculateDtToFit` centers the target). This avoids focus fights with transformed children. Up at the first item and down at the last item do nothing (no wrap in 1.1.0).
* L1/R1 or tapping a chip changes the system; the wheel restores that system's last selected index. A query switches the strip to a single "Results" chip containing matches from all systems.
* A launches the centered app; X opens its context menu; tapping a non-centered item scrolls it to center, tapping the centered item launches; long press opens the context menu.
* Collapse does not apply (only one system is shown at a time); Start is a no-op here, and the collapse-all button is hidden.

### 6.6 Context menu migration

`RecyclerView` items provide no `AdapterContextMenuInfo`. Plan:

* Each view holder calls `activity.registerForContextMenu(itemView)` once at creation and stores the bound `AppObject` in a view tag (`R.id.tag_app_object`, new id in `res/values/ids.xml`).
* `onCreateContextMenu` (lines 443 to 498) reads the app from `v.getTag(...)` instead of `info.position`, stores it with the view in fields `contextApp` and `contextView`, and builds the same items. The pinned shortcut check uses `contextView.findViewById(R.id.grid_image)`; the wheel passes the hero image view as `contextView`.
* `onContextItemSelected` (lines 504 to 623) uses `contextApp` and `contextView` instead of `AdapterContextMenuInfo`.
* New item `CHANGE_GROUP_ID = 9`, "Move to group...": dialog listing existing group labels, "Automatic", and "New group..." (text input). Writes or clears the client override (section 8).
* The "Only open the context menu if something is running" click path uses `openContextMenu(view)` on the clicked item view, as today.

### 6.7 AppView wiring changes

* Remove `implements AdapterFragmentCallbacks`, `getAdapterFragmentLayoutId()` and `receiveAbsListView()` (lines 737 to 776) and the `AdapterFragment` transactions (lines 153 to 159 and 183 to 190).
* `onServiceConnected` (lines 116 to 160): create `AppLibrary` instead of `AppGridAdapter`, then `showLayout(prefs.layout)` on the UI thread.
* `onConfigurationChanged` (lines 171 to 192): call `appLibrary.updateLayoutWithPreferences()` and `controller.onConfigurationChanged()` (recompute spans) instead of replacing the fragment.
* `updateHiddenApps()` (lines 341 to 354), `updateUiWithServerinfo()` (lines 625 to 661) and `updateUiWithAppList()` (lines 663 to 735): iterate `appLibrary.getAllApps()` instead of adapter positions, then call `rebuildSnapshot()`, which runs `LibraryModel` and calls `controller.submit(snapshot)`. Running-state changes are submitted as item change payloads so cards are rebound without losing focus.
* `onCreate` (lines 296 to 339): read `LibraryPrefs`, set up search (`TextWatcher` with 150 ms debounce posting to the main looper), the toggle group, the collapse-all button, and the back callback.
* `onPause` (lines 416 to 422): persist query, collapsed set, wheel group and last focused app key.
* `dispatchKeyEvent` override: the key map from 6.2.

## 7. Data flow

1. Playnite (plugin 0.5.0) sends games with `platforms`, `emulated` and emulator info over the existing named pipe.
2. Host parses them (`playnite_protocol.cpp`), keeps the snapshot, and on snapshot completion with auto sync on runs `autosync_reconcile`, whose `apply_game_data()` writes `playnite-platform` and `playnite-platform-id` into apps.json.
3. `proc::parse()` resolves `ctx.platform` / `ctx.platform_id` (manual `platform`, then Playnite, then "Apps" rules).
4. `/applist` adds `<Platform>` and `<PlatformId>` per app when non-empty.
5. Client `ComputerManagerService` polls `/applist`, caches the raw XML; `NvHTTP.getAppListByReader()` fills `NvApp.platform` / `platformId`.
6. `AppView` passes apps, hidden ids, overrides, query and collapse state to `LibraryModel`; the active controller renders the snapshot.

Compatibility matrix:

* New host, old client: unknown elements ignored, nothing changes.
* Old host, new client: no `Platform` anywhere, so the client shows "Games" plus "Apps" (utility heuristic), with per-app "Move to group" available.
* New host, old plugin: `has_platform_info` false, no `playnite-platform` written; built-ins and launcher-style apps still report "Apps"; other apps fall to the client's "PC" default until the plugin is updated.
* Auto sync disabled on the host: no backfill; a hand-edited `platform` key and the client override still work.

## 8. Persistence

SharedPreferences file `LibraryPrefs` (all writes with `apply()`):

* `layout`: `grid`, `wheel` or `shelves`; global for the device; default `grid`.
* `collapsed:<hostUuid>`: string set of collapsed group keys.
* `query:<hostUuid>`: last search text, restored into the field on open. A restored non-empty query is always visible (filled field with clear icon and a "Filtered" count label "7 of 21") so the library never looks silently incomplete.
* `wheelGroup:<hostUuid>`: last selected wheel system key.
* `lastFocus:<hostUuid>`: app key of the last focused or selected app.

SharedPreferences file `AppGroupOverrides`: key `<hostUuid>|<appKey>`, value group label; `appKey` is the app UUID when non-empty, else `id:<appId>`. Existing `HiddenApps` (`AppView.HIDDEN_APPS_PREF_FILENAME`, keyed by host UUID with app id strings) is unchanged so hidden apps survive the upgrade.

## 9. Testing

Client (Robolectric and JUnit already configured, `app/build.gradle` lines 171 to 174; CI currently only assembles):

* New JVM tests in `app/src/test/java/com/limelight/library/`: `PlatformCatalogTest` (aliases, labels, unknown values), `UtilityDetectorTest` (built-in UUIDs; generic utility names such as Desktop, Playnite, Remote Input, Lossless Scaling go to Apps; a generic title such as "Example Game" does not), `LibraryModelTest` (ordering, hidden handling, collapse, search tokens, accents, zero-width characters, old host fallback, override precedence), and an `NvHTTP` parse test with and without `Platform` elements and with an unknown extra element.
* Extend the existing `StartupTest` / `ProfilesNavigationTest` only as needed (they must keep passing; `profilesButton` id is kept).
* Add a unit test step to `.github/workflows/release.yml` before assembling: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.library.*' --no-daemon` (task name to be confirmed with `./gradlew tasks` in CI).

Host: section 4.6, run in the existing Windows CI.

Manual on the Thor (no local SDK, so via the CI-built APK): every row of the acceptance list below, with both the controller and touch only.

## 10. Acceptance criteria

1. With host 1.1.0, plugin 0.5.0 and one Playnite snapshot, native PC games appear under PC (first), emulated games appear under their system (for example a Ryujinx game under Nintendo Switch, a Citra or Azahar game under Nintendo 3DS, a Dolphin game under GameCube), and utilities (Desktop, Playnite, Remote Input, Lossless Scaling style tools) appear under Apps (last). Test fixtures and docs use generic titles only, never the user's real library.
2. An old host gives "Games" plus "Apps" and nothing crashes; an old client against the new host behaves exactly as before.
3. A manual `platform` key in apps.json wins over Playnite data and survives later syncs; a client "Move to group" wins over both on that device and "Automatic" clears it.
4. Search: Y focuses the field, typing a partial word shows only matching titles across groups within one frame after the 150 ms debounce, "switch" lists the Switch group, B clears then exits search, the query is restored on reopen and visibly indicated.
5. Collapse: Start toggles the focused group; collapsed headers are reachable with the D-pad; collapse-all and expand-all work; the state persists per host across app restarts.
6. Layouts: the toggle and Select popup switch between Grid, Wheel and Shelves without restarting the activity; the choice persists; each layout launches apps with A and tap, and opens the context menu with X, long press A and long press touch.
7. D-pad focus never gets lost: after an applist poll, a running state change, a hide or unhide, or returning from a stream, the same app stays focused or selected. L1/R1 jump between groups or systems.
8. All existing context menu actions work in every layout, including pinned shortcut creation when art is loaded, hidden apps and "view hidden apps" from the PC list.
9. Scrolling stays smooth at 120 Hz on the Thor with the full library (no visible frame drops in grid and wheel when holding the D-pad).
10. Host CI (including the startup smoke test) and client CI (unit tests plus release assemble) are green.

## 11. Risks and mitigations

* Focus loss on data updates in `RecyclerView`: stable ids, `DiffUtil`, change payloads for running state, and restoring `lastFocus` after `submit`.
* Context menu regressions from losing `AdapterContextMenuInfo`: covered by section 6.6 and acceptance item 8.
* Multi-platform metadata (IGDB adds PC and Switch to the same game): `derive_platform()` prefers the emulator's platforms for emulated games and `pc_windows` for native ones.
* Users who do not update the Playnite plugin: no wrong guesses (empty instead of PC on the host), clear release note, web UI already flags plugin updates.
* PowerShell serialization quirks (single-element arrays become objects, null lists): defensive parsing on both sides and protocol tests.
* Plugin cost on large libraries: the emulator map is built once per snapshot; `Game.Platforms` is an in-memory lookup.
* Gamepad keys consumed by the IME or the search field: X, Y and Start are only intercepted when focus is outside the field; B is handled through `OnBackPressedCallback` so the IME can close first.
* Persisted search making the library look empty: visible filter indicator and count.
* Wheel transforms and touch: view transforms are honored by hit testing; tap on non-centered items only recenters.
* Thor density is not known here; span counts and wheel sizes are computed from measured width in dp rather than fixed values. Verify with `adb shell wm density` on the device.
* No local Android SDK: every client change is validated only in GitHub Actions, so land the work in phases (below) to keep each CI run small and bisectable.

## 12. Phasing and versioning

1. Host: plugin, protocol, sync, parse, `/applist`, tests. Bump host to 1.1.0 and plugin to 0.5.0. Can ship independently because old clients ignore the new elements.
2. Client data layer: `NvApp`, parser, `PlatformCatalog`, `UtilityDetector`, `LibraryModel`, `LibraryPrefs`, unit tests and the CI test step.
3. Client grid with sections, search, collapse, gamepad map and context menu migration (feature parity checkpoint, removes `AppGridAdapter`).
4. Client wheel (explicitly requested by the user, so it lands before shelves).
5. Client shelves.
6. Bump client `VERSION` from 1.0.2 to 1.1.0 (the release workflow derives the version name from it and the version code from the run number) and release.

## 13. Files touched (summary)

Host repo:

* `plugins/playnite/SunshinePlaynite/SunshinePlaynite.psm1`, `plugins/playnite/SunshinePlaynite/extension.yaml`
* `src/platform/windows/playnite_protocol.h`, `src/platform/windows/playnite_protocol.cpp`
* `src/platform/windows/playnite_sync_policy.h`, `src/platform/windows/playnite_sync_policy.cpp`
* `src/process.h`, `src/process.cpp`, `src/nvhttp.cpp`
* `tests/test_playnite_protocol.cpp`, `tests/test_playnite_sync.cpp` or `tests/test_playnite_autosync_reconcile.cpp`, `tests/unit/test_process.cpp`
* `.github/window-only-host-version.txt`

Client repo:

* `app/src/main/java/com/limelight/nvstream/http/NvApp.java`, `app/src/main/java/com/limelight/nvstream/http/NvHTTP.java`
* `app/src/main/java/com/limelight/AppView.java`
* New `app/src/main/java/com/limelight/library/` (PlatformCatalog, UtilityDetector, LibraryModel, LibrarySnapshot, LibraryPrefs, AppLibrary, AppCardBinder, LibraryLayoutController, GridLayoutController, ShelfLayoutController, ShelfRecyclerView, WheelLayoutController and their adapters)
* Removed `app/src/main/java/com/limelight/grid/AppGridAdapter.java`, `app/src/main/res/layout/app_grid_view.xml`, `app/src/main/res/layout/app_grid_view_small.xml`
* `app/src/main/res/layout/activity_app_view.xml`, `app_grid_item.xml`, `app_grid_item_small.xml`, new `library_grid.xml`, `library_group_header.xml`, `library_shelf_row.xml`, `library_wheel.xml`, `library_wheel_item.xml`
* New drawables listed in 6.1, `res/values/ids.xml`, `res/values/strings.xml`
* `app/src/main/AndroidManifest.xml` (soft input mode)
* `app/src/test/java/com/limelight/library/*`
* `.github/workflows/release.yml` (unit test step), `VERSION`

## 14. Sources

* Playnite SDK models: https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Game.cs , https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Platform.cs , https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/GameAction.cs , https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/Models/Emulator.cs , https://github.com/JosefNemec/Playnite/blob/master/source/PlayniteSDK/IEmulationAPI.cs
* Playnite built-in platforms and emulators: https://github.com/JosefNemec/Playnite/blob/master/source/Playnite/Emulation/Platforms.yaml , https://github.com/JosefNemec/Playnite/blob/master/source/Playnite/Emulation/Emulators/Ryujinx/emulator.yaml
* Playnite API docs: https://api.playnite.link/docs/api/Playnite.SDK.Models.Platform.html , https://api.playnite.link/docs/manual/features/emulationSupport/emulationSupportOverview.html , https://api.playnite.link/docs/manual/library/games/gameActions.html
* Android: https://developer.android.com/reference/androidx/recyclerview/widget/GridLayoutManager.SpanSizeLookup , https://developer.android.com/reference/androidx/recyclerview/widget/LinearSnapHelper , https://developer.android.com/reference/com/google/android/material/carousel/CarouselLayoutManager , https://github.com/material-components/material-components-android/blob/master/docs/components/Carousel.md , https://developer.android.com/training/tv/get-started/navigation
* Gamepad key fallbacks: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/data/keyboards/Generic.kcm
* ES-DE carousel and wheel types: https://gitlab.com/es-de/emulationstation-de/-/blob/master/THEMES.md

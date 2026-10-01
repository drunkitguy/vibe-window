# Vibe Window

Vibe Window is an Android streaming client for Vibepollo hosts. When you launch an app from it, the stream shows only that app's window. The Windows desktop, taskbar and other windows stay hidden.

It is tuned for the AYN Thor and defaults to 1920x1080 at 120 FPS with a host virtual display.

## Requirements

Window-only capture happens on the PC, so the host needs the patched Vibepollo build:

1. On the Windows PC, install the latest `window-host-v*` MSI from https://github.com/drunkitguy/apollo2-vibe/releases over your existing Vibepollo. The MSI is unsigned, so SmartScreen may ask you to confirm with "More info", then "Run anyway".
2. On the Android device, install `VibeWindow-<version>-arm64-v8a.apk` from this repository's releases page.
3. Pair Vibe Window with the host as you would with Artemis or Moonlight.

A stock Vibepollo, Apollo or Sunshine host ignores the window-only request and streams the full display as usual.

## Settings

- Resolution: 1920x1080 (default)
- Frame rate: 120 FPS (default)
- Use Virtual Display: on (default). The host creates a display that matches the client, so the window can fill it at 120 Hz.
- Show only the app window: on (default). Sends `windowOnly=1` with each launch and resume request. Turn it off to stream the full display.

Vibe Window installs beside Artemis and Moonlight because it uses its own application ID (`app.vibewindow.client`).

## App library

The app list of a PC is grouped by system, with PC first, other systems in alphabetical order and utilities such as Desktop, Playnite, Remote Input or Lossless Scaling in an "Apps" group at the end.

- Layouts: Grid (default), Wheel and Shelves, picked with the buttons at the top right or the View menu (Select on a controller). The choice is remembered.
  - Grid: group headers with the cards of each group below them.
  - Wheel: one system at a time, a large cover of the selected app beside a wheel of titles, in the style of ES-DE or HyperSpin.
  - Shelves: one horizontal row of cards per group, like a TV launcher.
- Search: type in the search field (Y on a controller) to filter every group by title or system name, for example "switch". The search is kept per PC, and the field and a "7 of 21" count stay visible while it filters.
- Collapse: tap a group header (or press Start) to fold it; the button next to the layout buttons folds or unfolds every group. The state is kept per PC.
- Move to group: long press an app (or press X) and pick "Move to Group" to file it under another or a new group on this device. "Automatic" undoes it.

Controller keys: D-pad moves, A launches (long press A opens the app menu), X opens the app menu, Y searches, L1 and R1 jump to the previous or next group or system, Start folds the current group, Select opens the View menu, and B clears the search before leaving the list. Everything also works by touch.

Grouping by system needs host 1.1.0 or later and its Playnite connector 0.5.0. After installing the host, update the connector from the Integrations page of the host web UI, restart Playnite and let one library sync run. Older hosts still work: their apps appear under "Games" and "Apps".

## How it works

The client adds `windowOnly=1` to the host's `/launch` and `/resume` requests. The patched host then captures the launched app's top-level window with Windows Graphics Capture, moves it onto the streamed display, and paints everything else black. Until the app's window appears, the stream is black instead of showing the desktop.

## Limitations

- UAC prompts and the lock screen are still shown so they can be answered.
- Switching from a launcher to the game window takes about a second while capture restarts.
- Menus and popups that open as separate windows are not captured.
- Games in exclusive fullscreen may not produce frames. Use borderless or windowed mode.

## Building

CI builds the APK with `./gradlew :app:assembleNonRoot_gameRelease` (JDK 17, NDK 27.0.12077973) and publishes it as a GitHub release whenever `VERSION` changes. Clone with `--recurse-submodules` to fetch moonlight-common-c.

Release APKs are signed with `signing/vibe-window-ci.p12`. The key and its password (`vibewindow-public`) are public on purpose so every CI build has the same signature and new versions install as updates. Only install APKs from this repository's releases page.

## License

GPL-3.0, see `LICENSE.txt` and `NOTICE`. Based on Artemis by ClassicOldSong, which is based on Moonlight for Android. The upstream README is kept as `README.upstream.md`.

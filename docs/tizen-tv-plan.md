# Vibe Window TV: plan for the Samsung Tizen build

Status: approved plan. The TV app lives in `tv/` of this repository: `brightcraft/moonlight-tizen`
v1.19.0 (commit `4bb3134`, GPL-3.0) was imported there unmodified, and the Vibe Window changes are
separate commits on top. Line references point at that unmodified import (paths are relative to
`tv/`) unless marked "OneLiberty", which means `OneLiberty/moonlight-chrome-tizen` at commit
`0119eae` (2026-02-15).

Priorities, in order:

1. Controller input latency, including 2.4 GHz USB dongle controllers.
2. Other performance problems that can be checked in code (frame pacing latency, main thread stalls).
3. Back on the remote quits the app, after a confirmation dialog.
4. Small Vibe features (window-only capture flag, TV defaults).
5. A CI build that publishes a signed `.wgt` to GitHub Releases.

## 1. Which upstream to build on

An Android APK cannot run on Tizen. The TV app has to start from one of the Moonlight web/WASM
clients for Samsung TVs. They all descend from Samsung's WASM port of Moonlight Chrome.

| Project | State (Oct 2026) | Notes |
|---|---|---|
| brightcraft/moonlight-tizen | Very active: v1.19.0 released 2026-10-18, v1.18.0 on 10-13, v1.17.2 on 10-01. 1081 commits. GPL-3.0. | Most features: 30 to 144 FPS options, Game Mode toggle plus a ForceGM variant, EMSS and Web Audio backends, stats overlay, gamepad identity reporting, i18n, Smart Hub preview. CI already builds `Moonlight.wgt` with the Dockerfile on GitHub Actions. It has merged outside PRs this month (frame pacer, stats, pairing). The maintainer says this is likely their last year and new issues are restricted. |
| OneLiberty/moonlight-chrome-tizen | Last push 2026-02-15, 53 open issues. GPL-3.0. | Older code: 30 to 60 FPS only, Game Mode always on, busy-wait frame pacer. Users in its own Discussion #121 and Issue #134 point to brightcraft as the most up to date fork. |
| toypoodlegaming, MrPhaze62 (no Game Mode), ruanformigoni (OpenAL audio, merged into brightcraft), dos-ise, KyroFrCode (legacy) | Forks of the two above | Their useful parts are already merged into brightcraft. |

**Decision: base on brightcraft/moonlight-tizen at `4bb3134`.** It is maintained, has the most
upstream fixes (the busy-wait pacer fix, video setup timeouts, pairing fixes), its Dockerfile
build already works on GitHub Actions, and it is GPL-3.0, which matches Vibe Window's GPL-3.0.
It is imported into `tv/` of this repository (section 9). Later upstream releases are merged by
importing the new upstream tree over `tv/` in a separate commit. Keep our changes small and in
separate commits so the generic fixes (pacer, gamepad sampling) can be offered upstream as PRs.

The user's current build is probably OneLiberty's `Moonlight.wgt`. That is what the
Samsung-Jellyfin-Installer "Tizen Community" list offered. The "cannot quit" report matches
OneLiberty's code (section 5.1).

Sources: https://github.com/brightcraft/moonlight-tizen (README, CHANGELOG, releases),
https://github.com/OneLiberty/moonlight-chrome-tizen (README, issues),
https://github.com/OneLiberty/moonlight-chrome-tizen/discussions/121 (fork list),
https://github.com/OneLiberty/moonlight-chrome-tizen/issues/134.

## 2. Build, signing and install

### 2.1 What the upstream Dockerfile does (`Dockerfile`, 1 to 168)

- Base image `ubuntu:22.04`. It installs cmake, ccache, expect, git, ninja, python2, unzip and aria2.
- Downloads Tizen Studio 6.1 web CLI from `download.tizen.org` (line 25) and installs it.
- Creates an author certificate with `tizen certificate -a Moonlight -f Moonlight -p 123456` and a
  security profile (lines 33 to 41). The profile uses the default Tizen distributor certificate
  (`tizen-distributor-signer`, public level). It then patches `profiles.xml` so packaging works
  without gnome-keyring (lines 46 and 47).
- Downloads Samsung's Emscripten 1.39.4.7 fastcomp bundle from `developer.samsung.com`
  (line 50). It rewrites two OpenSSL URLs in the SDK ports to GitHub (lines 54 to 57) and runs
  `emsdk activate latest-fastcomp`.
- Builds with CMake and Ninja using BuildKit cache mounts (`RUN --mount=type=cache`, lines 76 to 79),
  so BuildKit is required.
- Injects `__BUILD_TYPE__`, `__REPO_OWNER__` and `__REPO_NAME__` into `wasm/platform/index.js`
  (lines 93 to 118). The in-app update checker queries `api.github.com/repos/<owner>/<name>/releases/latest`
  (`index.js` 1852 to 1856), so our build points it at our own repository automatically.
- Runs `cmake --install`, then `tizen package -t wgt` through `expect` (lines 136 to 143).
- Output: `/home/moonlight/Moonlight.wgt`, or `Moonlight-ForceGM.wgt` when `FORCE_GAME_MODE=true`.
  The final image keeps Tizen Studio, so `sdb` and `tizen install` can run inside it.

Network needs: `download.tizen.org`, `developer.samsung.com`, GitHub (OpenSSL tarballs) and the
Ubuntu mirrors. All of them are reachable from GitHub-hosted runners: brightcraft's own release
workflow (`release-stable.yml`) produced v1.19.0 today. None are reachable from this sandbox, so
**the only real build and compile check is GitHub Actions**. The Coder cannot compile locally.
Keep C++ changes small and C++17/fastcomp friendly (`CMakeLists.txt` uses `cxx_std_17`).

### 2.2 Signing, and what the user must do to install

A `.wgt` is signed twice: an author signature (`author-signature.xml`) and a distributor
signature (`signature1.xml`). Upstream CI signs with a freshly generated author certificate and
the generic Tizen public distributor certificate.

- **Tizen 5.5 to 6.5 TVs (2020 to 2022 models):** this "generic" signature installs on a TV in
  Developer Mode with `sdb` and `tizen install`. The brightcraft wiki recommends the Docker
  method for these versions.
- **Tizen 7.0 and newer (2023+ models):** the TV rejects that signature. The package must be
  re-signed with a Samsung certificate: a Samsung author certificate plus a Samsung distributor
  certificate that lists the TV's DUID. This needs a Samsung account. The easiest tool is
  Apps2Samsung (https://github.com/Apps2Samsung/Apps2Samsung; Windows, macOS, Linux, Android). It
  signs in to Samsung once, keeps a certificate per TV, re-signs, and installs a "Custom WGT File".
- CI cannot do the Samsung re-sign: it needs the user's Samsung account and the TV's DUID.
  CI only produces the generic-signed `.wgt`.

User steps, written for the release notes and README:

1. Enable Developer Mode on the TV: open Apps, then App Settings (or the Apps panel), and type
   `1 2 3 4 5` with the remote (or the on-screen 123 pad). Turn Developer mode ON and enter the
   IP address of the PC that will run the installer as "Host PC IP". Hold the power button for
   at least 5 seconds to fully restart the TV, then check that "DEVELOP MODE" is shown in Apps.
   Find the TV's IP under Settings, General, Network, Network Status, IP Settings.
2. Download `VibeWindowTV-<version>.wgt` (and the `.sha256` file) from the GitHub release.
3. Recommended for all Tizen versions, and required for Tizen 7+: in Apps2Samsung, choose
   "Custom WGT File", select the `.wgt`, pick the TV, and sign in with a Samsung account when
   asked.
4. Alternative for Tizen 5.5 to 6.5: run `sdb connect <TV_IP>`, then `sdb devices` to get the
   device id, then `tizen install -n VibeWindowTV-<version>.wgt -t <device_id>`. The tools can
   come from the image built by CI or from a local Tizen Studio CLI.
5. Updating: install the newer `.wgt` the same way. If the install fails with a signature or
   certificate mismatch (each CI build has a new random author certificate, and Apps2Samsung
   uses its own), uninstall the app first. Uninstalling deletes its saved hosts, so pairing has
   to be done again.

Vibe Window TV uses **its own package id**, so it installs next to an existing Moonlight instead
of replacing it (section 7.2). A second install of Vibe Window TV with a different signature
has the same id and is refused, which is why updates may need an uninstall. 

## 3. Known performance issues and what this plan does about each

Each item says how it was checked against the code, and whether it is fixed here or out of scope.

### 3.1 Fix in Vibe Window TV

**P1. Gamepad sampling blocks on the main thread 3+N times every 5 ms (input latency).**
`InputThreadFunc` (`wasm/main.cpp` 188 to 200) calls `PollGamepads()` and then `usleep(5 ms)`.
`PollGamepads` (`wasm/gamepad.cpp` 241 to 479) calls `emscripten_sample_gamepad_data()`,
`emscripten_get_num_gamepads()` and `emscripten_get_gamepad_status(i)` for every slot. In
Emscripten 1.39.4 (which Samsung's 1.39.4.7 is based on), all three are declared
`__proxy: 'sync'` (`src/library_html5.js` lines 2018, 2026 and 2038 at tag 1.39.4). The input
thread therefore waits for the browser main thread 2 + slots times per poll. That is 400 or more
blocking round trips per second, even with no controller. When the main thread is busy (DOM work,
GC, Web Audio scheduling, the 5 ms UI poll in P2), each poll is delayed by the whole main-thread
task. The poll period grows past 5 ms and button presses wait. Fix in section 4.

**P2. The UI's own gamepad poller keeps running during the stream.**
`Controller.startWatching()` (`wasm/platform/index.js` 154, `wasm/platform/gamepad.js` 88 to 98)
starts `setInterval(pollGamepads, 5)` on the main thread. `stopWatching()` is never called.
Each poll allocates new `Button` objects and an axes copy for every pad, and dispatches a
`gamepadinputchanged` CustomEvent on every change (`gamepad.js` 18 to 55). The handler
(`index.js` 155 to 215) builds mapping objects for each change and starts
`requestAnimationFrame` repeat loops for D-pad and stick input, even though `Navigation` is
stopped while streaming. All of this competes for the main thread with P1. Fix in section 4.

**P3. Controller state is sent on every poll, changed or not.**
`gamepad.cpp` 474 to 476 calls `LiSendMultiControllerEvent` for every announced pad on every poll,
which is about 200 packets per second per pad. moonlight-common-c does not drop repeated states
(`InputStream.c` `sendControllerEventInternal`, from 1036). Each packet is a reliable ENet
packet, and `sendMessageEnet` waits up to 10 ms for it to go out (`ControlStream.c` 727 to 750,
which logs "Control message took over 10 ms to send"). On Wi-Fi with loss, reliable in-order
delivery plus this backpressure queues real button changes behind stale repeats. Other Moonlight
clients only send when the state changes. Fix in section 4.

**P4. The frame pacer adds latency after a single late frame and loses it only very slowly.**
`VidDecSubmitDecodeUnit` (`wasm/wasmplayer.cpp` 429 to 456) holds each frame until
`s_pktPts <= fromStart - s_ptsDiff + 0.5 ms`. `s_pktPts` is a made-up timestamp that grows by
1/fps for every submitted frame. Once per second it sets `s_ptsDiff = fromStart - s_pktPts` from
whichever frame arrives at that moment:

- If that frame was late by L ms, the whole schedule moves L ms later. Every following on-time
  frame is then held about L ms.
- If that frame was early, the schedule only moves 0.5 ms earlier (the margin).

So one Wi-Fi hiccup at a resync point adds lasting latency that drains at 0.5 ms per second. Upstream
turned frame pacing **on by default** in v1.19.0 (commit `ac3fe25`), so every new install is
affected. This shows up directly in the existing overlay line "Average frame queue delay"
(`totalPacerTime / renderedFrames`, `wasmplayer.cpp` 739 to 745). Fix in section 6.

**P5. Synchronous main-thread posts from streaming threads.** (Low risk: the use-after-free is in a function with no callers today, confirmed at `main.cpp` 722 to 727.)
`PostToJs` uses `MAIN_THREAD_EM_ASM`, which blocks until the main thread has run it
(`main.cpp` 715 to 720). It is called once per second from the video decoder thread for the stats
overlay (`wasmplayer.cpp` 499), from the connection status callback (`connectionlistener.cpp`
78 to 90), from rumble (`gamepad.cpp` 490) and from mouse-emulation toggles on the input thread
(`gamepad.cpp` 409 and 413). With stats on, the decoder thread stalls about once per second, and
the stats then measure that stall. A related latent bug: `PostToJsAsync` (`main.cpp` 722 to 727)
passes `msg.c_str()` of a by-value `std::string` to `MAIN_THREAD_ASYNC_EM_ASM`. The string is
freed before the async JS reads it (use after free). It has no callers today. Fix: make it safe
by copying with `strdup` and calling `_free($0)` in JS, then use it for StatMsg, warnings, rumble
and mouse-emulation messages. Keep stream start/stop messages synchronous, since they are
ordered with promise handling.

**P6. Controllers with a non-standard mapping (2.4 GHz dongles in DInput/HID mode).**
`GetButtonFlags` and the axis reads (`gamepad.cpp` 147 to 224 and 362 to 373) assume the W3C
"standard" layout (buttons 0 to 16, triggers as analog buttons 6 and 7, sticks on axes 0 to 3).
Chromium on Linux only reports `mapping == "standard"` for controllers in its mapping database.
Generic HID dongles, or 8BitDo/GameSir dongles switched to D-input, can report `mapping == ""`
with raw order. Then buttons, the right stick and the triggers come out wrong or dead, and the
D-pad may be a hat axis. `EmscriptenGamepadEvent` already carries `mapping`, but nothing reads
it. Fix in Vibe Window TV:

- Detect `mapping != "standard"`, show it in the overlay and in a one-time toast ("Controller
  layout not recognized; switch the controller or dongle to X-input mode for best results"), and
  log `id`, buttons and axes counts.
- Decode a D-pad hat axis when present (value steps of 2/7 on the last axis, Chromium's Linux
  convention).
- A full remap table per VID:PID is P2. It needs the user's exact controller id from the new log.

X-input mode makes the TV use the Linux `xpad` driver. Chromium maps that as "standard", and it
is the recommended setup.

### 3.2 Platform limits that cannot be fixed in the app (document only)

- **Tizen 5.5 (Chromium 69) samples gamepads every 16 ms.** Chromium 69's
  `device/gamepad/public/cpp/gamepad_features.cc` uses `kPollingIntervalMillisecondsMax = 16` as the
  default. Chromium 76 and later default to 4 ms (Chromium 94 and 108 hard-code
  `kPollingIntervalMilliseconds = 4`). The Tizen to Chromium mapping is from Samsung's web engine
  spec, as best known: 5.5 = M69, 6.0 = M76, 6.5 = M85, 7.0 = M94, 8.0 = M108, 9.0 = M120.
  On 2020 TVs the browser adds about 8 ms on average (up to 16 ms) before any app code runs.
  Workaround: plug the dongle into the host PC if it is within range. Input then bypasses the TV
  and the network.
- 90 and 120 FPS are shown at 60 Hz on many models (brightcraft #112, labelled tizen-limit).
- Refresh-rate start-up stutter: the TV sometimes stays at 50 Hz until an HDMI source is powered
  on (OneLiberty #113, brightcraft #66). Workaround is in the brightcraft wiki.
- Game Mode problems on Tizen 9 and Tizen 5.5 (brightcraft #73 and #52). Upstream already sets the
  default per Tizen version (`index.js` 4263 to 4277) and has the ForceGM variant.
- Video corruption above 1080p on Tizen 9 (#77 and #80, fixed upstream), and 10-bit HEVC timeouts
  on Tizen 8 (OneLiberty #131).
- Wi-Fi vs Ethernet: many Samsung TV Ethernet ports are 100 Mbit/s, which caps bitrate.
  OneLiberty #127 was fixed by switching network type.
- Audio crackle with EMSS: upstream already offers the Web Audio backend (v1.16.0).
- Not enabling reference frame invalidation (RFI): TV decoder support is unknown and it cannot
  be tested here. Possible later work.
- `CAPABILITY_SLICES_PER_FRAME(4)`: keep it. No measurable gain is expected with a hardware decoder.
- The Smart Hub preview background service (`res/config.xml` 33 to 38, `on-boot`, `auto-restart`)
  keeps running after the UI app exits. That is by design and not part of "cannot quit".

## 4. Controller input latency: design

Goal: shorten the time from a controller state change to the ENet send, and make it steady.

1. **One main-thread hop per poll** (`gamepad.cpp` `PollGamepads`).
   - Replace the separate sample, count and status calls with one `MAIN_THREAD_EM_ASM_INT` (this
     macro is already used at `gamepad.cpp` 334). It calls `navigator.getGamepads()` once and
     writes a compact snapshot for up to 16 pads into a static C buffer passed by pointer:
     `connected`, `standardMapping`, `timestamp` (double), `numButtons`, `numAxes`, a
     `pressedMask` (uint32), analog values for buttons 6 and 7, `axes[0..9]`, a 64-byte `id`, and
     `sampleAgeMs = performance.now() - gp.timestamp`.
   - It returns the pad count. The JS must not allocate per call: reuse one typed-array view and
     read `HEAPF64`/`HEAP32` offsets.
   - Keep the existing arrival, removal and placeholder logic (`timestamp != 0` workaround,
     `gamepad.cpp` 286 to 315) on top of this snapshot.
2. **Skip sampling when no controller is connected.** JS `gamepadconnected` and
   `gamepaddisconnected` handlers call a new embind function `setGamepadCount(n)` (embind
   functions run on the main thread and only write a `std::atomic<int>`). `InputThreadFunc`
   samples only when the count is above 0. Otherwise it sleeps 20 ms and still runs
   `ReportMouseMovement`.
3. **Poll period 4 ms**, matching Chromium's 250 Hz on Tizen 6.0+. Sleep until the next deadline
   (`next += 4 ms`), not a fixed `usleep(5000)` after the work, so the period does not drift.
4. **Send only on change.** Keep the last sent state per pad (buttons, triggers, four stick
   values, after the int conversion). Call `LiSendMultiControllerEvent` only when it differs.
   Also skip the comparison when `gp.timestamp` has not changed. Arrival and removal events stay
   as they are.
5. **Stop the UI poller during the stream.** In `showStreamMode` (`index.js` 2777 to 2790) call
   `Controller.stopWatching()`. Restart it in `showAppsMode` (2416 to 2443) and the hosts-mode
   function. Add `if (isInGame) return;` at the top of the `gamepadinputchanged` handler
   (`index.js` 155) and clear `repeatAction`/`repeatTimeout` when the stream starts. Also make
   `gamepad.js` allocation-free: store `pressed` booleans in a reused array, compare axes in place,
   and fix `startWatching` adding the connected/disconnected listeners again after a stop.
6. **Asynchronous posts** from the input thread (mouse-emulation toggles) and from rumble (P5).
7. **Input stats in the existing overlay.** The input thread fills a small struct of atomics or
   mutex-protected counters, and `FormatVideoStats` (`wasmplayer.cpp` 627 to 754) appends:
   - `Gamepad: <type> <standard|non-standard>, poll interval avg/max: x/y ms` (measured between
     sampler calls with `steady_clock`)
   - `Gamepad sample age avg/max: x/y ms` (`sampleAgeMs` from item 1; shown as `n/a` if negative
     or over 1000 ms, in case the TV's `Gamepad.timestamp` uses another time base)
   - `Controller packets sent: n/s`
   - `Input send stalls (>10 ms): n`, counted in `ClLogMessage` (`connectionlistener.cpp` 65 to 76)
     by matching the moonlight-common-c log text "Control message took over 10 ms"

Expected effect, to be confirmed on a TV:

- The main-thread hops per second at 1 pad go from about 600 to 250, and to 0 with no pad.
- The worst-case poll interval drops from spikes as long as a main-thread task (tens of ms when
  the UI or Web Audio is busy) to about 5 to 6 ms.
- The average extra delay from polling drops from about 2.5 ms plus main-thread waiting to about
  2 ms.
- Controller packets drop from about 200/s/pad to the actual rate of changes (often under 60/s),
  which removes most ENet backpressure stalls on lossy Wi-Fi.
- On Tizen 5.5 the browser's 16 ms sampling remains.

Two settings the user should check, written in the README:

- Game Mode ON in the app (upstream defaults it on for Tizen 6.0 to 8.0). It switches the TV to
  ultra-low-latency picture processing (`main.cpp` 446), which is usually the biggest display
  latency factor.
- X-input mode on the controller or dongle.

## 5. Back to quit, with confirmation

### 5.1 Why the app could not be quit (current code)

OneLiberty (likely the user's current build):

- `HostsNav` has no `back` handler (`navigation.js` 108 to 128). With focus on the top
  settings row, Back does nothing.
- The first key after any mouse or pointer movement only restores focus and is otherwise ignored
  (`navigation.js` 392 to 419, `runOp` returns after `focus()`). Samsung remotes with a pointer
  trigger `mousemove` and therefore `loseFocus`.
- In a stream, Back only sends Esc to the host (`index.js` 1437 to 1444). Navigation is stopped,
  so the remote cannot leave the stream except with the RED key (`remote_controller.js` 51 to 53).
- `index.html` has two `<dialog id="terminateMoonlightDialog">` blocks (lines 187 and 256),
  which means duplicate ids.

brightcraft (our base) is better but still has gaps:

- `Hosts.back` opens the exit dialog (`navigation.js` 471 to 474). But `HostsNav.back` and
  `HostsMenuHighlight.back` (535 and 584) only go back to the host grid, so it takes two presses.
- `runOp` still swallows the first key after pointer movement (`navigation.js` 1913 to 1927).
- Navigation is stopped on loading screens (`index.js` 469 and 510, `Navigation.stop()`), so Back
  does nothing if the app hangs while loading.
- `remoteControllerHandler` does not call `preventDefault()` for Return
  (`remote_controller.js` 28 to 31), so the platform's default Back action can still run.
- The exit dialog's default focus is "Exit" (`ExitMoonlightDialog.view` index 0 is
  `continueExitApp`, `navigation.js` 1861 to 1864).

### 5.2 Design

Key codes:

- Return/Back is `10009` (`tvKey.KEY_RETURN`, `res/platform.js`; `event.key === 'XF86Back'`).
  Arrows are 37 to 40 and Enter is 13. Return is delivered to web apps without `registerKey`.
- Do not register or handle Exit (`10182`). Samsung's guidance is to leave Exit to the system.
  Long-press Return/Exit is also handled by the system, which terminates the app.
- Samsung's app policy: on the app's home page, Return shows an exit confirmation and closes only
  if the user confirms; on inner pages, Return goes back one page.

Behaviour:

1. `remoteControllerHandler` (`remote_controller.js` 28 to 31): call `e.preventDefault()` for
   `KEY_RETURN`. Then:
   - If the exit dialog is open, route to it (item 4).
   - If `isInGame`, do nothing here and keep upstream behaviour (Esc to the host,
     `index.js` 3969 to 3990).
   - If Navigation is stopped and no stream is running (loading or error screens), open the exit
     dialog directly.
   - Otherwise call `Navigation.back()`.
2. `runOp` (`navigation.js` 1913 to 1927): for `back`, run `focus()` and then the view's `back`
   in the same press. Do not swallow it.
3. Top-level views open the dialog on the first press: `Hosts.back`, `HostsNav.back` and
   `HostsMenuHighlight.back` all call `exitAppDialog()`. Inner screens (Apps, Settings and the
   other dialogs) keep their current back behaviour, which is one level up.
4. The exit dialog (`index.html` 809 to 821, `exitAppDialog()` in `index.js` 2340 to 2372,
   `Views.ExitMoonlightDialog` in `navigation.js` 1860 to 1893):
   - Default focus is **Cancel**: set `view.index = 1` in `exitAppDialog()` before
     `Navigation.push`.
   - Left/Right move between Exit and Cancel, Enter activates, Back cancels.
   - Title "Exit Vibe Window?", text "Are you sure you want to exit?". Use i18n keys, with
     English fallback.
   - Opening the dialog sets a module flag `exitDialogOpen`. While it is set,
     `remoteControllerHandler` and the gamepad handler route keys straight to the dialog view,
     even if Navigation is stopped. Closing clears the flag.
   - Exit: if a stream is somehow active, `Module.stopStream()` first. Then
     `tizen.application.getCurrentApplication().exit()` inside try/catch, falling back to
     `window.close()`.
   - Guard against a double open: if it is already open, Back means Cancel.
5. Gamepad B (button 1) already maps to `Navigation.back()` (`index.js` 164). It gets the same
   behaviour.
6. In a stream (required, manager decision, because the user asked for Back to quit): add a setting
   "Back button during stream" with "Ask" (default) and "Send Esc to PC" (upstream behaviour).
   "Ask" opens a dialog with Cancel (focused), Stop streaming, and Exit app; Exit stops the stream
   and then exits as in item 4. RED and Select+Start+LB+RB on a gamepad (`gamepad.cpp` 24) keep
   stopping the stream in both modes.

## 6. Frame pacer fix (P4)

Evidence: confirmed by reading `wasmplayer.cpp` 429 to 456 at `4bb3134` (resync copies the offset of whichever frame arrives at the window boundary). Risk control (manager decision): the upstream block stays in the code unchanged as mode "Legacy". Add a setting "Frame pacing" with Off, Legacy and Adaptive (default Adaptive; existing stored on/off values map to Adaptive/Off), passed to wasm next to the existing `framePacing` value. The new Adaptive path, in `VidDecSubmitDecodeUnit`, keeps the EMSS packet timestamps as they are (synthetic `s_pktPts`) and only changes how long each frame is held:

- Pacing clock: use `decodeUnit->presentationTimeMs`, the host capture time
  (`Limelight.h` 168 to 171). Fall back to the synthetic timestamp if it is 0 or goes backwards.
- Track `offset = arrival_ms - pts_ms` for each frame. Keep the minimum over the current 1 s
  window and the last finished window. The release target is `pts_ms + minOffset + 1 ms`.
- Cap the hold at one frame duration. If a frame would be held longer, release it now and reset
  the window. Never hold IDR frames.
- When the host sends frames below the stream rate (static window content, common with
  window-only capture), arrivals follow host time, so frames are not held.
- Keep the sleep-then-short-spin wait (`kPacingSpinTime`, 440 to 444) and the existing stats.

This bounds the added latency to the jitter seen in the last 1 to 2 s, and it recovers within
2 s after a hiccup. Measure with "Average frame queue delay".

## 7. Vibe features (kept small)

### 7.1 Window-only capture flag

- The Vibepollo host already parses `windowOnly` on launch and resume (`src/nvhttp.cpp` 2114 in
  the host repository, in the shared launch-session builder that `/resume` also uses, 3830). Stock Sunshine and
  Apollo ignore the extra argument.
- Client: add a `windowOnly` argument (0 or 1) to `launchApp` and `resumeApp`
  (`wasm/static/js/utils.js` 966 to 972 and 975 to 981), appending `&windowOnly=<0|1>`.
- Pass it from `startGame` at `index.js` 2955 (resume) and 3001 (launch).
- Add a switch "Show only the app window" in Host Settings (`index.html` settings list, same
  pattern as the existing switches: `storeData('windowOnly', ...)`, loaded in `loadUserDataCb`).
  Default **on**, which matches Vibe Window Android (`DEFAULT_WINDOW_ONLY = true`).
- Add an i18n key with English fallback.

### 7.2 Identity and TV defaults

- `res/config.xml`:
  - Widget id `http://vibewindow.tv/VibeWindowTV`.
  - `tizen:application id="VibeWinTV1.MoonlightWasm" package="VibeWinTV1"`. The package id must be
    exactly 10 alphanumeric characters. Keep the `.MoonlightWasm` suffix, because
    `res/service/service.js` line 17 builds `packageId + '.MoonlightWasm'`.
  - Service id `VibeWinTV1.service`, `<name>Vibe Window TV</name>`, version from `tv/VERSION`.
- The `Moonlight.wgt` name inside the Dockerfile can stay; CI renames the asset. The packager
  names its output after `<name>`, so the Dockerfile's rename step takes whichever single `.wgt`
  it finds.
- Defaults on first run only, when there is no stored value (`index.js` `loadUserDataCb`
  4024 to 4037 and 4054 to 4064):
  - Resolution `1920:1080` on every panel (manager decision: performance is the user's priority,
    many TV Ethernet ports are 100 Mbit/s, and 4K stays selectable).
  - 60 FPS.
  - Bitrate from the upstream preset logic for that resolution.
  - Apply the same values in `restoreDefaultsSettingsValues` (3825 onward, currently 720p,
    60 FPS, 10 Mbps).
  - Stored user choices are never changed.
- Grouping apps by `<Platform>` from `/applist`: **out of scope**. It would need UI work in the
  apps grid. Parsing it later is easy (`utils.js` 556 to 561).
- README (`tv/README.md`): a short notice about the upstream base, install steps (section 2.2), latency tips (section 4), credits to
  upstream, and the GPL-3.0 notice.

## 8. CI workflow (`.github/workflows/tv-release.yml`)

The imported upstream workflows under `tv/.github/` never run from a subdirectory, and most of
them make no sense here, so they are removed: `release-stable.yml` and `release-development.yml`
(GHCR pushes and upstream release text), the three bot workflows, `i18n-sync.yml` (pushes
commits), plus `dependabot.yml` and `FUNDING.yml`.

Add `tv/VERSION`, starting at `1.0.0`, kept equal to `version` in `tv/res/config.xml`. The TV
app has its own version line, separate from the Android `VERSION`. Tizen allows up to
255.255.65535.

Triggers: `push` to `main` and `claude/clever-clarke-k7hhhj` with `paths` `tv/**` and the
workflow file itself, plus `workflow_dispatch`. The Android `release.yml` gets
`paths-ignore: ['tv/**', 'docs/tizen-tv-plan.md', '.github/workflows/tv-release.yml']`, so TV
pushes do not rebuild the APK. Top-level `permissions: {}`. Actions are pinned by full SHA,
reusing the pins in `release.yml`:

- checkout `de0fac2e4500dabe0009e67214ff5f5447ce83dd`
- upload-artifact `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`
- download-artifact `37930b1c2abaa49bbe596cd826c3c89aef350131`
- softprops/action-gh-release `3bb12739c298aeb8a4eeaf626c5b8d85266b0e65`

Jobs:

1. `check` (contents: read):
   - Read `tv/VERSION` and require `MAJOR.MINOR.PATCH`.
   - Fail if `tv/res/config.xml` `version="..."` differs from it, or the package id is not
     `VibeWinTV1`.
   - `notes=true` only if `tv/RELEASE_NOTES.md` starts with `# Vibe Window TV <version>`. Pushes
     between releases therefore build and verify but never publish a half-finished version.
   - `published=true` if release `tv-v<version>` already has every `.wgt` and `.wgt.sha256`
     (`gh release view`, as in `release.yml`).
2. `build` (contents: read, timeout 90 min, concurrency per ref and variant), a matrix of the
   Standard and ForceGM variants that run in parallel:
   - Check out the repository.
   - Run `DOCKER_BUILDKIT=1 docker build --build-arg BUILD_TYPE=release --build-arg REPO_OWNER=<owner> --build-arg REPO_NAME=<repo> --build-arg FORCE_GAME_MODE=<false|true> -t vwtv:ci tv`.
     No GHCR push is needed.
   - `docker create` plus `docker cp` of `/home/moonlight/Moonlight.wgt` (or
     `Moonlight-ForceGM.wgt`) to `dist/VibeWindowTV-<v>.wgt` (or `VibeWindowTV-ForceGM-<v>.wgt`).
   - Verify the package: `config.xml`, `author-signature.xml`, `signature1.xml`, `index.html`,
     `platform/index.js` and a `.wasm` file are present; `config.xml` has `package="VibeWinTV1"`
     and `version="<v>"`; only the ForceGM package has the Game Mode metadata;
     `platform/index.js` no longer contains `__REPO_OWNER__`.
   - Write `sha256sum` to `.sha256` and upload the artifact `vibe-window-tv-wgt-<variant>`.
   - ForceGM is only useful on Tizen 9. It runs in parallel, so it does not add wall time; drop
     it if it ever pushes the run past 90 minutes.
3. `release` (needs check and build, only if `published == 'false'` and `notes == 'true'`,
   contents: write, concurrency `publish-tv-release` with no cancelling):
   - Check `published` again inside the lock.
   - Download both artifacts.
   - Create or update release `tv-v<version>` with `make_latest: false` (the APK release stays
     the repository's latest), `fail_on_unmatched_files: true`, and the body from
     `tv/RELEASE_NOTES.md`: what it is, that window-only needs the Vibepollo host, the install
     steps from section 2.2, signing notes, latency tips, and the GPL source note.

The in-app update checker (`fetchLatestRelease`) must not use `releases/latest`, which is the APK
release. It lists releases and takes the newest `tv-v*` tag. Upstream's check also had a
placeholder bug: the Dockerfile's `sed` replaces `__REPO_OWNER__` everywhere, including the
fallback comparison, so every CI build fell back to the upstream repository. The comparison is
written so that `sed` cannot rewrite it.

Persistent author certificate: cut (manager decision). Apps2Samsung, the recommended path,
re-signs with the user's own certificate anyway, and no repository secrets are set up.

## 9. Repository and publishing

- The TV app lives in `tv/` of the Vibe Window repository (user decision), not in a separate
  fork. The upstream tree was imported unmodified in one commit, so `git diff <import> -- tv/`
  shows every Vibe Window change.
- Upstream history is not kept in this repository. To take a newer upstream release, import its
  tree over `tv/` in a commit of its own and re-apply or rebase the Vibe Window commits.
- The Android app, its workflow and its releases are untouched. TV releases use `tv-v*` tags.
- Work branch: `claude/clever-clarke-k7hhhj`. This plan is `docs/tizen-tv-plan.md`.

## 10. Implementation order and files

1. Repository bootstrap: remove the upstream workflows, add `tv/VERSION`, change
   `res/config.xml` identity, add `.github/workflows/tv-release.yml`, README notice. Push, and confirm CI builds an untouched
   `.wgt` before changing any code.
2. Back to quit: `wasm/platform/remote_controller.js`, `wasm/platform/navigation.js`
   (`Hosts`, `HostsNav`, `HostsMenuHighlight`, `ExitMoonlightDialog`, `runOp`),
   `wasm/platform/index.js` (`exitAppDialog`), `wasm/index.html` (dialog text), locale source file.
3. Input latency:
   - `wasm/gamepad.cpp` (`PollGamepads`, new sampler, change detection, hat decode, mapping detection).
   - `wasm/main.cpp` (`InputThreadFunc` deadline loop and pad-count gate, embind
     `setGamepadCount`, safe `PostToJsAsync`).
   - `wasm/moonlight_wasm.hpp` (input stats struct, atomics).
   - `wasm/connectionlistener.cpp` (stall counter, async warnings).
   - `wasm/platform/gamepad.js`, `wasm/platform/index.js` (`showStreamMode`, `showAppsMode`,
     `gamepadinputchanged` guard, connect and disconnect hooks).
4. Pacer: `wasm/wasmplayer.cpp` `VidDecSubmitDecodeUnit`, plus overlay additions in
   `FormatVideoStats`. The stats buffer `s_StatString` is resized to 1000 bytes at
   `wasmplayer.cpp` 339; raise it to 2048 for the extra lines.
5. Vibe features: `wasm/static/js/utils.js`, `wasm/platform/index.js`, `wasm/index.html`, locale
   file.
6. Look: restyle the host list, app list, settings and dialogs with the Vibe Window Android
   design tokens (`wasm/static/css/style.css`), sized for a 10-foot UI.
7. Docs and release: `tv/README.md`, `tv/RELEASE_NOTES.md`. Bump `tv/VERSION` and
   `res/config.xml` together for each release.

## 11. Acceptance criteria

Build and release:

- CI on the work branch builds `VibeWindowTV-<v>.wgt`. Its `config.xml` has package `VibeWinTV1`
  and version `<v>`. Its signature files are present. The `.sha256` matches. The release
  `tv-v<v>` is created once, and re-running the workflow does not duplicate it.
- The `.wgt` installs with Apps2Samsung "Custom WGT File" on a Tizen 7+ TV, and with
  `tizen install` on a Tizen 5.5 to 6.5 TV in Developer Mode. It installs next to an existing
  Moonlight.

Back to quit:

- On the host list, one Back press (focus on a host card, the top bar, or a host's menu icon, and
  also right after moving the pointer) opens "Exit Vibe Window?" with Cancel focused.
- Back or Enter on Cancel closes it. Left then Enter on Exit closes the app. It is gone from the
  task view and starts fresh next time.
- On the loading screen, Back also opens the dialog.
- On inner screens (apps grid, settings, other dialogs), Back goes up one level and never exits.
- During a stream with the default "Ask", Back opens Cancel / Stop streaming / Exit app with Cancel focused; with "Send Esc to PC" it sends Esc as upstream. RED and the gamepad combo still stop the stream.
- Gamepad B behaves like Back.

Controller input (with stats overlay on, Yellow key or Select+LB+RB+X):

- One controller, game idle: "poll interval avg/max" is at most 5/8 ms on Tizen 6.0+ over
  60 seconds.
- Under UI load (stats on, Web Audio backend): the max stays at or below 12 ms. The baseline
  build shows larger spikes: record both.
- "Controller packets sent" is 0/s with the controller untouched (no stick drift beyond
  1 LSB), and follows real input otherwise.
- "Input send stalls (>10 ms)" is 0 per minute on a stable network.
- "Gamepad sample age avg" is at most 4 ms on Tizen 6.0+ (expected about 8 ms on Tizen 5.5,
  where the browser polls every 16 ms).
- A 2.4 GHz dongle controller in X-input mode shows "standard". Every button, both sticks,
  both triggers and the D-pad reach the host (check with a gamepad tester on the PC).
- In D-input mode, the overlay and toast say "non-standard", and the D-pad works if it is a hat.
- End-to-end check: film the controller and the TV with a 240 fps phone camera while the PC runs
  a full-screen gamepad tester. Take 20 presses each with the baseline (upstream v1.19.0) and
  Vibe Window TV, same settings and Game Mode on. The median press-to-photon of Vibe Window TV is not worse, and
  is expected to be lower under load. Report both medians in the PR.

Video:

- With frame pacing on, "Average frame queue delay" is at most 2 ms on stable Wi-Fi. After a
  forced 0.5 s network stall (for example clumsy on the host, or disconnecting Wi-Fi briefly), it
  is back to at most 2 ms within 3 s. Upstream stays high for a long time (about 0.5 ms
  recovered per second).
- "Rendering frame rate" equals "Incoming frame rate".

Vibe:

- With "Show only the app window" on (default), launch and resume requests carry `windowOnly=1`.
  The Vibepollo host log shows "Client requested window-only capture". Off sends `windowOnly=0`.
- A fresh install defaults to 1920x1080 at 60 FPS on every panel.

## 12. Risks

- **Signing (biggest risk).** Generic-signed packages do not install on Tizen 7+. The user must
  re-sign with a Samsung certificate tied to the TV's DUID (Apps2Samsung plus a Samsung
  account). Each CI build has a new author certificate, and Apps2Samsung uses its own, so
  switching install methods or builds can need an uninstall, which loses pairing. The new
  package id means a first pairing with the PC is needed anyway. Developer Mode must point to
  the IP of the PC that installs, and it resets if the TV is restored. Samsung certificates can
  expire and need renewal in the installer.
- **SDK downloads.** The build depends on `download.tizen.org` and a `developer.samsung.com` file
  URL. If Samsung moves them, CI breaks. Mitigation: keep the built image as a workflow artifact
  or cache, and pin the SHA256 of both downloads in the Dockerfile.
- **No local compile or device here.** Every C++ change is only compiled in CI, and every latency
  number needs a real TV. Land the changes in separate commits (back key, input, pacer) so a
  regression can be reverted alone.
- **Gamepad timestamp time base.** `Gamepad.timestamp` may not share `performance.now()`'s
  origin on every Tizen version. The "sample age" line falls back to `n/a`. The poll interval and
  the camera test do not depend on it.
- **Send-on-change.** If a host ever needs periodic state refresh, a held button could be
  dropped after a reconnect. Required mitigation (not optional): resend the full state after every arrival event and
  every 1 s while any button or stick is non-neutral.
- **Pacer change.** It could reduce smoothness on some models. Frame pacing can still be turned
  off, and the commit can be reverted alone.
- **Upstream drift.** brightcraft is releasing every few days. Import newer upstream releases
  into `tv/` before a release when they matter, and keep our changes small.
- **Tizen 5.5** has a browser-side 16 ms gamepad sampling floor that the app cannot remove.
  Document plugging the dongle into the PC as the low-latency option.

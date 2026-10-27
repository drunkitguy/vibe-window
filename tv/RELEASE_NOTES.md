# Vibe Window TV 1.0.0

Samsung Smart TV (Tizen 5.5 and newer) client of Vibe Window. With the Vibepollo host it streams only the launched app's window, never the Windows desktop; install the latest `window-host-v*` MSI from https://github.com/drunkitguy/apollo2-vibe/releases on the PC. Sunshine, Apollo and GameStream hosts also work and stream the full display.

## What's Changed:

- Back on the remote opens "Exit Vibe Window?" with Cancel focused, from the computer list, its top bar and loading screens
- During a stream, Back asks: Cancel, Stop streaming or Exit app (or sends Esc to the PC, see Input Settings)
- Lower and steadier gamepad input latency: one browser call per poll, a 4 ms schedule, and controller state sent only when it changes
- New Adaptive frame pacing (default) that never holds a frame longer than one frame time; the upstream pacer stays as Legacy
- "Show only the app window" for Vibepollo hosts, on by default
- 1920x1080 at 60 FPS and 20 Mbps on first run
- The Vibe Window look: black background, flat cards and a blue focus ring
- Gamepad, poll interval, sample age, packet rate and send stall lines in the stats overlay

**Full Changelog**: https://github.com/drunkitguy/vibe-window/commits/tv-v1.0.0

## Downloads

- `VibeWindowTV-1.0.0.wgt`: the standard build. Use this one.
- `VibeWindowTV-ForceGM-1.0.0.wgt`: forces the TV's Game Mode through the app's metadata, only for TVs where the in-app "Game mode" setting has no effect (Tizen 9 and newer). If streaming does not start with it, use the standard build.

Both have the same package id: **install only one of them**. Check a download with its `.sha256` file.

## Install

1. **Developer Mode:** on the TV open Apps, type `1 2 3 4 5` with the remote, turn Developer mode ON, enter the IP address of the computer that runs the installer as "Host PC IP", then hold the power button for 5 seconds to restart the TV. "DEVELOP MODE" now shows in Apps. The TV's IP address is under Settings, General, Network, Network Status, IP Settings.
2. **Uninstall the old Moonlight first,** and any earlier Vibe Window TV build. Vibe Window TV has its own package id (`VibeWinTV1`) and does not replace Moonlight, but a TV refuses a package with the same id and a different signature, and both apps would keep their own background preview service.
3. **Apps2Samsung (recommended for every Tizen version, required for Tizen 7.0 and newer, 2023+ TVs):** in [Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung) choose "Custom WGT File", select the `.wgt`, pick the TV and sign in with a Samsung account when asked. It re-signs the package with a certificate for your TV.
4. **Or, Tizen 5.5 to 6.5 only (2020 to 2022 TVs):** with the Tizen Studio CLI run `sdb connect <TV_IP>`, `sdb devices`, then `tizen install -n VibeWindowTV-1.0.0.wgt -t <device_id>`.

**Updating:** install the newer `.wgt` the same way. Each CI build has a new author certificate and Apps2Samsung uses your own, so if the TV reports a signature mismatch, uninstall Vibe Window TV and install again. Uninstalling removes the saved computers, so pair with the PC again afterwards.

## Tips for low latency

- Keep **Game mode** on (Video Settings).
- Put 2.4 GHz controllers and dongles in **X-input** mode. In D-input mode the app shows "Controller layout not recognized".
- **Tizen 5.5** TVs read gamepads only every 16 ms. If the PC is within range, plug the dongle into the PC instead.
- **120 Hz:** 90 and 120 FPS are shown at 60 Hz on most Samsung TVs, a Tizen limit.
- Use **HEVC** when the GPU supports it. Bitrate starting points at 60 FPS: 10 Mbps at 720p, 20 Mbps at 1080p, 40 Mbps at 1440p, 80 Mbps at 4K (half that at 30 FPS). Many TV Ethernet ports run at 100 Mbit/s.

Source: GPL-3.0, based on brightcraft/moonlight-tizen v1.19.0 and Moonlight. See `tv/README.md` in this repository.

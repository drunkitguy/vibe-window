# Vibe Window TV

Vibe Window TV is the Samsung Smart TV (Tizen) client of Vibe Window. It streams games and apps
from a Windows PC running the Vibepollo host, and with that host it streams only the launched
app's window instead of the whole desktop. It also works with Sunshine, Apollo and NVIDIA
GameStream hosts, which stream the full display.

It is based on [brightcraft/moonlight-tizen](https://github.com/brightcraft/moonlight-tizen)
v1.19.0, a port of [Moonlight ChromeOS](https://github.com/moonlight-stream/moonlight-chrome).
The upstream README is kept as [README.upstream.md](README.upstream.md), and the design notes
are in [docs/tizen-tv-plan.md](../docs/tizen-tv-plan.md).

## What is different from upstream

- **Back on the remote quits, with a confirmation.** On the computer list (including its top bar
  and a computer's menu icon, and on loading screens), one press of Back opens
  "Exit Vibe Window?" with Cancel focused. Inner screens still go back one level. During a
  stream, the "Back button during stream" setting (Input Settings) chooses between Ask (Cancel,
  Stop streaming or Exit app, Cancel focused) and Send Esc to PC. RED, Ctrl+Alt+Shift+Q and
  Select+Start+LB+RB still stop the stream. The Exit key is left to the TV.
- **Lower gamepad input latency.** The stream's input thread reads all gamepads with one call to
  the browser per poll (upstream needed two plus one per slot, all blocking), polls every 4 ms
  on a fixed schedule, skips polling while no gamepad is connected, and only sends controller
  state when it changes (plus the full state after a controller arrives and once a second while
  anything is held). The menu gamepad poller is stopped while streaming.
- **Adaptive frame pacing (default).** It evens out network jitter but never holds a frame longer
  than one frame time and never holds IDR frames, and it recovers within two seconds after a
  hiccup. Upstream's pacer is still available as Legacy; Off shows every frame as it arrives.
- **Window-only capture.** "Show only the app window" (Host Settings, on by default) asks a
  Vibepollo host to capture only the launched app's window, on launch and on resume.
- **TV defaults:** 1920x1080 at 60 FPS with 20 Mbps on first run.
- **The Vibe Window look:** black background, flat dark cards, a blue focus ring and text sized
  for viewing from the couch.
- **Overlay statistics** (Yellow key, or Select+LB+RB+X on a gamepad) add the gamepad type and
  mapping, poll interval, sample age, controller packets per second and send stalls.

## Install

You need a Windows PC on the same network as the TV for the installer, and the TV's IP address
(Settings, General, Network, Network Status, IP Settings).

### 1. Turn on Developer Mode on the TV

1. Open Apps (or the Apps panel) on the TV.
2. Type `1 2 3 4 5` with the remote (or the on-screen number pad).
3. Turn Developer mode ON and enter the IP address of the computer that will run the installer
   as "Host PC IP".
4. Hold the TV's power button for at least 5 seconds to restart it fully.
5. Open Apps again and check that "DEVELOP MODE" is shown.

### 2. Download

From the [releases](https://github.com/drunkitguy/vibe-window/releases), take the newest
`tv-v*` release and download **one** of:

- `VibeWindowTV-<version>.wgt`: the standard build. Use this one.
- `VibeWindowTV-ForceGM-<version>.wgt`: forces the TV's Game Mode through the app's metadata.
  Only for TVs where the in-app "Game mode" setting has no effect (Tizen 9 and newer restrict
  it). If streaming does not start with it, go back to the standard build.

Both builds have the same package id, so **install only one of them**. The `.sha256` files let
you check the download.

### 3. Uninstall the old Moonlight first

Vibe Window TV has its own package id (`VibeWinTV1`), so it does not replace an installed
Moonlight. Still uninstall Moonlight (and any earlier Vibe Window TV test build) first:

- A TV refuses to install a package over an installed one with the same id but a different
  signature. Every CI build is signed with a new certificate, and Apps2Samsung signs with your
  own, so a mismatch is common when you switch builds or installers.
- Both apps would keep their own background Smart Hub preview service and their own pairing.

Uninstalling deletes the app's saved computers, so you pair again after the next install.

### 4a. Apps2Samsung (recommended for every Tizen version, required for Tizen 7 and newer)

TVs from 2023 on (Tizen 7.0+) only install packages signed with a Samsung certificate for that
TV. [Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung) (Windows, macOS, Linux,
Android) does that for you:

1. Start Apps2Samsung and let it find the TV (or enter its IP address).
2. Choose "Custom WGT File" and select the `.wgt` you downloaded.
3. Sign in with a Samsung account when asked. Apps2Samsung keeps a certificate for the TV and
   re-signs and installs the package.

### 4b. Tizen Studio CLI (Tizen 5.5 to 6.5 only, 2020 to 2022 models)

These TVs also accept the package as built. With the Tizen Studio command line tools (from a
local Tizen Studio, or the Docker image that `tv/Dockerfile` builds):

```
sdb connect <TV_IP>
sdb devices
tizen install -n VibeWindowTV-<version>.wgt -t <device_id>
```

`<device_id>` is the name `sdb devices` shows for the TV.

### Updating

Install the newer `.wgt` the same way. If the TV reports a signature or certificate mismatch,
uninstall Vibe Window TV first and install again; you then pair with the PC again.

## Getting the lowest latency

- **Game Mode:** keep "Game mode" on in Video Settings (the default on Tizen 6.0 to 8.0). It
  switches the TV to its low-latency picture processing, usually the biggest display delay.
- **2.4 GHz controllers and dongles:** switch the controller or dongle to **X-input** mode. The
  TV then sees a standard Xbox-style pad, and every button, both sticks, both triggers and the
  D-pad map correctly. In D-input or HID mode the app shows "Controller layout not recognized"
  once; the overlay shows "non-standard mapping", and a D-pad reported as a hat still works.
- **Tizen 5.5 (2020 TVs):** the TV's browser reads gamepads only every 16 ms (8 ms on average),
  before the app sees anything. If the PC is within range, plug the dongle into the PC instead:
  input then skips the TV and the network.
- **Network:** a wired PC and a 5 GHz Wi-Fi TV, or Ethernet on both. Many TV Ethernet ports run
  at 100 Mbit/s, which limits the bitrate.
- **120 Hz:** 90 and 120 FPS streams are shown at 60 Hz on most Samsung TVs. That is a Tizen
  limit; 60 FPS gives the same picture with less bandwidth.

### Recommended settings

Use **HEVC** (Video codec) when the PC's GPU supports it: it needs less bitrate than H.264 for the
same picture. Starting points for the bitrate:

| Resolution | 30 FPS | 60 FPS |
|---|---|---|
| 1280x720 | 5 Mbps | 10 Mbps |
| 1920x1080 | 10 Mbps | 20 Mbps |
| 2560x1440 | 20 Mbps | 40 Mbps |
| 3840x2160 | 40 Mbps | 80 Mbps |

Lower it if the overlay shows dropped frames or the "Slow connection" warning appears; HEVC
usually looks the same at about two thirds of these values.

## Building

The GitHub Actions workflow `.github/workflows/tv-release.yml` builds both packages with
`tv/Dockerfile` (Tizen Studio 6.1 CLI and Samsung's Emscripten 1.39.4.7) and publishes them to a
`tv-v<version>` release. To build locally with Docker and BuildKit:

```
DOCKER_BUILDKIT=1 docker build --build-arg BUILD_TYPE=release -t vibe-window-tv tv
docker create --name vwtv vibe-window-tv
docker cp vwtv:/home/moonlight/Moonlight.wgt VibeWindowTV.wgt
```

The version is in `tv/VERSION` and must match `version` in `tv/res/config.xml`.

## License and credits

Vibe Window TV is free software under the GNU General Public License v3.0 (see [LICENSE](LICENSE)).
It is built on the work of the Moonlight Tizen project by brightcraft and its contributors,
Moonlight ChromeOS and moonlight-common-c by the Moonlight project, Samsung's WASM port of
Moonlight for Tizen, and the forks merged into it (including OneLiberty/moonlight-chrome-tizen).
The source of every release is this repository at the release's tag.

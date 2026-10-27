# TotK dual-screen companion: plan

Owner: `manager` agent. Packets flow planner -> creator -> critic. Status: `todo`, `doing`, `done`, `blocked: user`.

## Goal

The Legend of Zelda: Tears of the Kingdom (`0100F2C0115B6000`) on a dual-screen handheld (AYN Thor): game on the top screen, a live touch companion (map, gear, abilities, inventory) on the bottom screen. Built as an Eden Duo `.dsmod.zip` package, modelled on the Breath of the Wild companion.

## Facts that shape the plan

- Eden Duo (igawa6/eden-duo, GPL-3.0) is the runtime. Companions are separate packages (igawa6/eden-duo-companions, GPL-3.0). We write a package, not a new emulator.
- The BotW companion is a native C++ module (~29k lines incl. tests), a guest helper (AArch64) installed by a load plan, and a manifest. It reads live memory and is gated to one build id. TotK needs every offset re-derived.
- Eden Duo runs companions on Android, and on desktop through `eden-cli --aux-window` (second SDL window).
- Native modules load only on non-Windows: `StageModule` in `src/core/mods/mod_module.cpp` is `#ifndef _WIN32` (dlopen of a `.so`). A Windows host cannot run a native-module companion without porting that loader.
- apollo2-vibe streams one display per session. Two screens from the host needs either two sessions (two virtual displays) or one composite stream split on the client.

## Host path (decision for the user)

| Path | Where the game runs | New work outside the companion |
|---|---|---|
| A. On device | Eden Duo on the Thor | None |
| B. Linux host | eden-cli on a Linux PC, streamed | Dual-display streaming (H2, H3) |
| C. Windows host | eden-cli on Windows, streamed | Windows module loader (H1) + H2 + H3 |

Recommendation: build the companion first (packets 1-8 are identical for every path), test on path A, then do H1-H3 if host streaming is still wanted.

## Packets

| # | Packet | Depends | Status |
|---|---|---|---|
| 0 | Pick host path A/B/C and the exact TotK version to target | - | blocked: user |
| 1 | Dump exefs/romfs with `eden-cli --dump-romfs`, record build id (`xxd -s 0x40 -l 32 -p main`) | 0 | blocked: user |
| 2 | Package skeleton: `packages/TearsOfTheKingdom/dualscreen/manifest.json`, `<BUILD16>.json`, module `native/modules/0100F2C0115B6000.cpp` that gates on build id and shows the notice page; builds on Linux; unit test for the gate | 1 | todo |
| 3 | State discovery: player position/heading, hearts, stamina, rupees, time, weather, temperature, area (Surface/Sky/Depths), equipped weapon/bow/shield/arrows with durability | 1 | blocked: user |
| 4 | Ready page: port BotW Ready (round map, status, equipped gear) | 2, 3 | todo |
| 5 | Abilities page: Ultrahand, Fuse, Ascend, Recall, Autobuild (replaces BotW runes) | 3 | todo |
| 6 | Inventory page + actions: categories in game order, equip, eat; Zonai devices; guest helper load plan for the patched call sites | 3 | todo |
| 7 | Map: Surface, Sky and Depths layers from the player's romfs; pins; travel to activated shrines/lightroots | 3 | todo |
| 8 | Headless test: `eden-cli --aux-window` with scripted `.btn` input and `.shot` screenshots, two clean boots | 4 | todo |
| 9 | Device test on the Thor; release `.dsmod.zip` | 8 | blocked: user |

Host track (only for path B or C):

| # | Packet | Depends | Status |
|---|---|---|---|
| H1 | Eden Duo fork: load native modules on Windows (`LoadLibraryW` staging in `mod_module.cpp`) | 0 = C | todo |
| H2 | apollo2-vibe: stream the aux window to the client as a second display (second virtual display + second session, or composite) | 0 = B/C | todo |
| H3 | Client (artemis-apollo2): show the second stream on the Thor's bottom screen, route touch back | H2 | todo |

## Open questions

- Which TotK update does the user own (1.4.x)? The companion targets exactly one build.
- Path A, B or C?

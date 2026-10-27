# TotK dual-screen companion: plan

Owner: `manager` agent. Packets flow planner -> coder -> critic (loop until `VERDICT: SATISFIED`).
Status values: `todo`, `doing`, `done`, `blocked: user`, `blocked: repo`.
Every claim cites a source from the [Sources](#sources) table as `[S<n>]`. `UNVERIFIED` marks a
claim no source proves. `UNKNOWN: <method>` marks a value nobody has found yet; code keeps it at 0
and fails closed.

## Goal

The Legend of Zelda: Tears of the Kingdom (title `0100F2C0115B6000`) runs in Eden Duo
(`eden-cli`) on the user's **Windows PC** and is streamed by **apollo2-vibe** to the **AYN Thor**:
the game on the Thor's top screen, a live touch companion (status, gear, abilities, inventory,
map) on the bottom screen, with bottom-screen touches going back to the companion. The
companion is an Eden Duo `.dsmod.zip` package with a native module, built like the Breath of the
Wild companion [S2]. It never ships game art, text, fonts, maps, dumps, keys or firmware
[S3 PORTING_A_GAME §4].

Host path decided by the user: **C (Windows host)**. That makes the host track (H1-H3) required.

Build order: first the pieces that can be built and tested in this Linux container without the
game (Windows module loading, the TotK skeleton), then the Windows and client work the user must
run, then the game-dependent companion pages, smallest first.

## Facts that shape the plan

1. **Runtime.** Eden Duo (GPL-3.0) is the runtime; companions are separate packages in
   igawa6/eden-duo-companions (GPL-3.0) [S1][S2]. Clones read for this plan: eden-duo `v1.3.2.2`
   (`5fa93ed4`, runtime 22 per `src/core/mods/mod_runtime.h`), companions `383d2d47`.
   Eden Duo publishes only Android APKs [S1 README]: there is no Windows eden-cli build to
   download, so the user needs a Windows build of a patched fork (H1c).
2. **Windows cannot load native modules today** [S1 `src/core/mods/mod_module.cpp`]:
   - `Platform()` (lines 38-50) returns empty on Windows, so `Load` throws "native dual-screen
     modules are unsupported on this platform" before staging;
   - `StageModule` (lines 122-158) is `#ifndef _WIN32` (mkdtemp, open/write, read-only file) and
     throws on Windows;
   - the error path calls `dlerror()` only off Windows (line 254); `Common::DynamicLibrary::Open`
     already uses `LoadLibraryA` on Windows [S1 `src/common/dynamic_library.cpp:85-87`];
   - `~GameModule` and the stale-dir sweep use `remove_all`, which fails on a read-only file on
     Windows unless the attribute is cleared first (`UNVERIFIED`, check in H1a's test).
   Other `_WIN32` gaps found by grepping `src/core/mods`, `src/yuzu_cmd/dsmod_harness.*` and
   `emu_window_sdl3_aux.*`:
   - `dsmod_harness.cpp:1006-1008` calls `std::signal(SIGUSR1/SIGUSR2)` and `getpid()` without a
     guard. MinGW's `signal.h` has no `SIGUSR1` (checked: compile error), MSVC neither. So
     `eden-cli` with the Duo harness does not compile on Windows as is. The `.shot` and `.btn`
     file triggers do not need signals [S3 PORTING_A_GAME §6].
   - `mod_clock.cpp` already has `_WIN32` branches; `mod_host_values` uses
     `Common::GetPowerStatus` (portable); the aux window is plain SDL3 with mouse and finger
     events matched by window id (`emu_window_sdl3_aux.cpp:45-64`); no other guard found.
   - The loader's Catch2 tests build the fake module only for Linux x86-64
     (`src/tests/core/mods/duo_tests.cmake:37`) and hard-code `linux-x86_64`
     (`src/tests/core/mods/module.cpp:66`).
   - Eden reads gamepads with `SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1`
     (`src/input_common/drivers/sdl_driver.cpp:668`), so a tap that focuses the aux window should
     not cut controller input (`UNVERIFIED` on Windows; checked in H1c).
3. **How Eden builds on Windows** [S1 `docs/Build.md`, `docs/Caveats.md`]: MSVC with Ninja
   (preferred) or Visual Studio 2022, clang-cl, or MSYS2 `MINGW64`. No documented Linux-to-Windows
   cross build of the whole emulator.
4. **Module DLL toolchain.** The module ABI is plain C: fixed-width integers, pointers, function
   pointers, borrowed strings; nothing allocated on one side is freed on the other; callbacks
   must not throw [S1 `dsmod_module_abi.h`; S3 MODULE_GUIDE §9]. On x64 Windows there is one
   calling convention for C functions [S22], so a module DLL built with MinGW-w64 GCC
   (`-static-libgcc -static-libstdc++ -static`) loads into an MSVC-built or MinGW-built eden-cli.
   Checked in this container (MinGW-w64 GCC 13 + wine 9 from Ubuntu noble):
   - the companions SDK compiles with `-Wall -Wextra -Werror` under MinGW (its
     `__attribute__((visibility))` export macro is accepted);
   - without a `.def` file MinGW exports every global symbol; with a `.def` listing
     `eden_dsmod_get_module` it exports only that;
   - a PE DLL named `0100F2C0115B6000.so` loads with `LoadLibraryExW` under wine and returns a
     valid module table. `LoadLibrary` uses a given file extension as is [S22b], so the package
     path rule `modules/<platform>/<TITLEID>.so` (loader and builder) can stay unchanged.
   - MSVC or clang-cl would not accept the SDK's `__attribute__` macro: module DLLs use MinGW.
5. **apollo2-vibe streams one captured display per host process** [S21]. Video capture is a
   process-wide singleton (`capture_thread_async`, `capture_thread_sync` in
   `/home/user/apollo2-vibe/src/video.cpp:1565-1566`), so two concurrent sessions see the same
   display. It creates a virtual display at the client-requested mode
   (`src/nvhttp.cpp:2003-2013`, `createVirtualDisplay(..., width, height, fps, ...)` in
   `src/platform/windows/virtual_display.h:156`), and a per-client `display_mode` overrides it
   (`src/nvhttp.cpp:2016-2025`). `virtual_display_mode` defaults to `per_client`;
   `virtual_display_layout = exclusive` leaves the virtual display as the only active monitor
   [`docs/configuration.md` "virtual_display_mode", "virtual_display_layout"]. Touch from the
   client is passed through onto the captured display's area (`src/input.cpp:137`, `:1071`).
6. **Moonlight clients hold one stream per process.** moonlight-common-c keeps the connection in
   globals and `LiStartConnection` is "not thread-safe" [S23]. Touch is sent as
   `LiSendTouchEvent` with x/y normalized over the whole video area [S23].
7. **Thor screens**: top 6" 1920x1080 120 Hz, bottom 3.92" 1240x1080 60 Hz [S24]. The BotW
   companion canvas is 1240x1080 [S2 BotW `manifest.json`].
8. **Artemis already drives a second Android display**: its `Game.java` uses `DisplayManager`,
   `isOnExternalDisplay()` and an `ExternalDisplayControlActivity` [S25]. The user's client is
   their own Vibe Window APK (Moonlight-Android fork, tag `v1.4.10`), merged into this repo's
   plan branch `claude/practical-mccarthy-f7wf2f` by `4128b20`; it keeps that code (see H3).
9. **Module ABI is headers only** [S1 `src/core/mods/`]: the BotW module builds with
   `g++ ... -I$EDEN_SOURCE_ROOT/src` and nothing else [S2 `tools/botw/build_modules.sh`]. The
   companions' shared CMake needs fmt/nlohmann_json/zstd from `$EDEN_SOURCE_ROOT/.cache/cpm`,
   which exists only after Eden Duo was configured once [S2 `native/README.md`]; our clone has
   none. This repo therefore gets its own small CMake that needs only the headers; zstd (CPM) is
   needed later for the map.
10. **Three build gates** [S3 MODULE_GUIDE §3; S1 `mod_module.cpp:213-230`, `:263-276`]: the
    loader's `module.build_ids` prefix check, the module's `supports_build`, and `create`
    comparing all 32 bytes. On failure the host publishes `module_ready = 0`,
    `module_error = 1`, `module_error_message` and the pages still render
    [S1 `mod_module_host.cpp` RunGameModule]: that is the notice page.
11. **Desktop runs the JIT, not NCE.** Desktop x86-64 always uses Dynarmic, so guest `call`/
    `sequence` actions work there; on Android NCE they do not [S3 PORTING_A_GAME §5]. Path C
    can use guest calls for actions; the BotW-style load-plan helper stays the portable option.
12. **BotW companion shape** [S2 BotW README]: one translation unit + `botw_*.h` (~22k lines),
    59 `botw_*_test.cpp` host tests (~6.9k lines), data file `{}`, a format-2 load plan with an
    AArch64 guest helper gated to 1.6.0, reads guarded by `is_mapped`, bounds, vtable checks and a
    second read.
13. **No TotK companion exists** in either clone. The GAFT page [S20] is unreachable from this
    container and web search found nothing about it (`UNVERIFIED` whether it lists TotK).

## Host architecture (path C)

```
Windows PC                                                   AYN Thor
eden-cli (patched fork, H1)                                  Vibe Window APK (H3)  
  main window  borderless at (0,0)    1920x1080  ┐            decode once (1920x2160)
  aux window   borderless at (0,1080) 1240x1080  ├ one virtual   ├ crop (0,0,1920,1080)    -> top screen
  TotK module 0100F2C0115B6000.so (PE DLL)       │ display        └ crop (0,1080,1240,1080) -> bottom screen
apollo2-vibe: per-client mode 1920x2160, exclusive ┘ 1920x2160     touch on bottom screen ->
  layout, app = eden-cli ... (H2)                                   LiSendTouchEvent(x/1920, (1080+y)/2160)
  touch passthrough -> Windows touch at that point -> aux window (SDL finger events)
```

Decision: **one composite stream** (stacked 1920x2160), not two sessions.
- Two sessions would need two apollo2-vibe processes (capture is a singleton, fact 5) on
  different ports with two virtual displays managed by two display helpers, and two client
  processes (one connection per process, fact 6), plus two encoders, two decoders and two
  unsynchronized streams.
- The composite needs: two window rects in eden-cli (H1b), host configuration only in
  apollo2-vibe (H2), and a split renderer + touch mapping in the client (H3).
- Cost: twice the pixels to encode; the bottom region is mostly static. Whether the virtual
  display driver accepts 1920x2160 is `UNVERIFIED` (H2 test). Fallback if it does not: 1920x1080
  top + 1240x1080 bottom side by side (3160x1080), same client split.

## TotK versions and build ids

Main NSO build id = 32 bytes at offset 0x40 of `main`; the data file is named by its first 16
hex characters [S3 PORTING_A_GAME §1.3; S3 PACKAGE_FORMAT §1].

| Version | Update version | Catalog date [S4] | Build id, first 20 bytes [S5][S6][S7] |
|---|---|---|---|
| 1.0.0 | 0 | base | `082CE09B06E33A12` (rest UNKNOWN) |
| 1.1.0 | 131072 | 2023-05-04 | `D5AD6AC71EF53E3E52417C1B81DBC9B4142AA3B3` |
| 1.1.1 | 196608 | 2023-05-20 | `168DD518D925C7A327677286E72FEDA833314919` |
| 1.1.2 | 262144 | 2023-05-26 | `9A10ED9435C06733DA597D8094D9000AB5D3EE60` |
| 1.2.0 | 327680 | 2023-07-14 | `6F32C68DD3BC7D77AA714B80E92A096A737CDA77` |
| 1.2.1 | 393216 | 2023-08-23 | `9B4E43650501A4D4489B4BBFDB740F26AF3CF850` |
| 1.4.0 | 524288 | 2025-06-10 | `6265F94D606242CE730EF721A8037DDA8E4BFC63` |
| 1.4.1 | 589824 | 2025-06-12 | `965EAB9CEB8EB867F747DA772022C95065C9B927` |
| 1.4.2 | 655360 | 2025-07-03 | `5CB42B1CF25469FB0635FD046453D843C18BC8AB` |
| 1.4.3 | 720896 | 2026-02-17 | `277178B7DBA1B6D4949A84778D4ABC58B31B34F5` |

- **Current update: 1.4.3** (v720896, 2026-02-17); switch-cheats-db marks it `latest` [S5];
  news and ZeldaWiki list 1.4.3 after 1.4.2 [S8]. Nothing newer was found. There is no 1.3.x.
- Three sources agree on the mapping: [S5] (16 hex), [S6] (40 hex, 1.1.0-1.4.3), [S7] (16 hex
  with version names).
- The 64-hex id is the 20 bytes above plus 12 zero bytes: `UNVERIFIED` (BotW's is 16 bytes plus
  zeros [S2]). Confirm with `xxd -s 0x40 -l 32 -p main` on the user's dump (T2).
- 1.4.x moved code a lot: one function sits at `0xFBB374` in 1.2.1 and `0x9F1510` in 1.4.3
  [S7]. Offsets do not carry over between 1.2.1 and 1.4.x.

**Target build (decision D2, user).** One build only. Default for T1: **1.2.1**, which has by far
the most public leads (next section). If the user plays 1.4.3, T2 switches the constant and every
lead below must be found again.

## Public TotK research (leads, never facts)

Every lead is confirmed from code and live validation before use [S3 PORTING_A_GAME §2.1, §3].

| Source | License | What it gives | Use |
|---|---|---|---|
| switch-cheats-db `cheats/0100F2C0115B6000.json` [S9] | none found; authors credited in file | 1.2.1: 111 cheats incl. pointer chains. Rupees `[[main+0x4721B98]+0xD8]+0x28EA4` (cheat 9); hearts chain from `main+0x46AF308` `+0x480 +0x228 +0x2F8` (cheat 3); stamina via `main+0x4721B98` and `main+0x46AF308` (cheat 4); motion via `main+0x471DCB0 +0xC8 +0x178 +0x30`, float `+0xFC` (cheat 17); battery, durability, arrows, pouch sizes, Korok map. 1.0.0-1.1.2: ASM hooks for HP/stamina/quantity. No 1.4.x entries | Anchors for T3 (1.2.1) |
| MyNXCheats `277178B7DBA1B6D4.txt` [S10] | none found | 1.4.3 cheat list: Inf. Health, Stamina, Battery, Horse Stamina, Perfect Thermometer, Gloom, Items x999, durability, movement. Code types not inspected (`UNVERIFIED`) | Anchors for T3 if target is 1.4.3 |
| afiaka87/totk-mods `Totk121Offsets.hpp` [S7] | MIT | 1.2.1: SceneModule `main+0x04728538`; GameDataMgr holder `main+0x0462E3D8` (double deref), `GameDataGetInt` `main+0x010CD5BC`, `GameDataSetInt` `main+0x00B51B08`, int store `+200`; resident actors (scene `+0x1E8`, components `+0x58`, index 13, list `+0x28`, stride `0x70`); actor name `+0x218`, position Vec3f `+0x2B4`, rotation `+0x2C0`, velocity `+0x320` | Best lead set for 1.2.1 |
| TkSharp `TkPatch.cs` [S6] | not determined | Full 20-byte build ids 1.1.0-1.4.3 | Build ids |
| TotkMods/Research `Formats/Sav` [S11], dt-12345 gmd_scripts `gamedata.md` [S12] | not determined | GameData flags hashed with murmur3-32; full list in romfs `GameData/GameDataList.Product.110.byml.zs`; 33 types; structs such as `PlayerStatus` | Read flag names from the player's romfs at runtime; never ship the list |
| zeldamods/objmap-totk [S13] | GPL-3.0 | Layers Sky / Surface (`Ground`) / Depths; MainField = Sky + Surface, MinusField = Depths; map unit col `(x+5000)/1000`, row `(z+4000)/1000` | Map layer model; romfs tile source UNKNOWN |

Romfs formats (`.zs` zstd with a `ZsDic.pack.zs` dictionary pack, BYML, SARC, BNTX) are handled by
many TotK tools [S6][S12]; details `UNVERIFIED` until read from the user's romfs. zstd dictionary
decoding is in the zstd sources the companions already compile (`zstd_ddict.c`) [S2 CMakeLists].

## BotW feature table: reuse, adapt, drop

Source: [S2] BotW README and `native/modules/botw_*.h`.

| BotW feature (files) | TotK | Notes |
|---|---|---|
| Build gate, guarded reads, torn-read rejection (`botw_pouch.h`) | Reuse pattern | Use `dsmod_sdk::ReadGuest<MissingIsMapped::Reject>` and `InMain` |
| Fake host fixture (`botw_test_fixture.h`) | Reuse pattern | `totk_test_host.h` |
| UI context, title/loading/pause pages (`botw_context`, `botw_states`, `page_binds`) | Adapt | TotK scene names UNKNOWN |
| HUD hidden detection (`botw_hud`) | Adapt | |
| Ready page: hearts, stamina, rupees, time, temperature, weather, location (`botw_ready*`, `botw_environment`, `botw_temperature_*`) | Adapt | Plus gloom and battery |
| Equipped weapon/bow/shield/arrows + durability (`botw_player_equipment`, `botw_gear`, `botw_item_status`) | Adapt | Fuse: base item + fused material |
| Runes (`botw_runes`, `botw_world_runes`) | Drop | Abilities page instead |
| Champions (`botw_champions`) | Drop | Sage vows are a TotK extension |
| Round minimap from the native HUD transform (`botw_map_view`) | Adapt | |
| Full map: tiles, labels, regions, pins, saved icons, moving and death markers (`botw_map*`) | Adapt | Three layers |
| Koroks, trackers, DLC locations (`botw_map_koroks`, `botw_tracker_*`) | Drop (v1) | Tables generated from 1.6.0 data |
| Shrine compass, sensor (`botw_shrine_compass`, `botw_sensor`) | Adapt (later) | Shrines + lightroots |
| Quests (`botw_quests`) | Adapt (later) | |
| Inventory read (`botw_pouch`, `botw_inventory`) | Adapt | Storage UNKNOWN (GameData arrays is a lead [S11][S12]) |
| Equip/eat/remove via guest helper (`botw_bridge`, `*_actions`, `native/helper/botw`) | Adapt (late) | On Windows guest `call` actions also work (fact 11) |
| Hold-to-cook, recipes, heal advisor (`botw_hold*`, `botw_recipes*`, `botw_heal_advisor`) | Adapt (later) | Recipe data from the player's romfs |
| Travel (`botw_warp*`, `warp-ui-hook.S`) | Adapt (last) | Shrines, lightroots, Skyview towers |
| Text helpers (`botw_msbt_reflow`, `botw_text_measure`, `botw_detail_*`) | Reuse | Game-independent |
| Press flash, event hold, recent gear (`botw_press`, `botw_event_hold`, `botw_recent_gear`) | Reuse | Game-independent |
| Layout animations, fx curves, drawn state (`botw_layout_fx`, `botw_fx_curves`, `botw_drawn`) | Drop | BotW-specific |
| Generated 1.6.0 metadata headers (armor, weapon, consume, status, descriptions) | Drop | Read from romfs at runtime |

TotK-only: abilities (Ultrahand, Fuse, Ascend, Recall, Autobuild, Camera, Travel Medallion),
Zonai devices and battery [S9], Sky/Surface/Depths [S13], gloom, lightroots, Sage companions,
schematics.

## Work packets

Doable in this Linux container now (no game, no Windows, no Thor):

| # | Packet | Depends | Status |
|---|---|---|---|
| 0 | Decisions: D1 host path = **C** (user, done); D2 target version | - | D1 done; D2 blocked: user |
| H1a | Eden Duo: native modules on Windows (loader, harness compile fix, tests), as patches | - | done: `host/eden-duo/0001-0003` + `host/eden-duo-companions/0001`; loader Catch2 tests pass standalone; full eden-duo build blocked (proxy 403 on CPM fmt download), real-Windows checks in H1c; critic SATISFIED round 3 |
| T1 | TotK package skeleton: build gate, fail-closed reader, tests; Linux `.so` and Windows DLL; `.dsmod.zip` | H1a (builder patch) | done: `2c482b6` (+ plan `911aed8`); gcc/clang/MinGW builds and ctest pass (MinGW under wine), single export, package + wine probe pass; critic SATISFIED round 3 |
| H1b | eden-cli composite layout: `--window-rect`, `--aux-rect`, borderless, aux not focusable | H1a | done: `afe952e`, patch 0004 adds `--window-rect`/`--aux-rect` (borderless, hidden until placed), non-focusable aux, 640x360 main floor, 32767 bound, ledger rows; parser tests pass natively and under wine; SDL and harness code compile-checked only in H1c; critic SATISFIED round 3 |

Needs the user, a Windows PC, the Thor or another repo:

| # | Packet | Depends | Status |
|---|---|---|---|
| H1c | Windows build of the patched fork (MSVC Ninja per [S1 docs/Build.md], `EDEN_DSMOD_BUILD_DEV_TOOLS=ON`), smoke run: TotK package installed, notice page in the aux window, `.shot` screenshots, a tap on the aux window keeps controller input | H1a, H1b, T1 | blocked: user |
| H2 | apollo2-vibe window-host (`claude/clever-clarke-k7hhhj`): verify the 1920x2160 window-only composite with no host code; H2b (`dualScreen=1` pin) only if a check fails | H1c, H3 | blocked: user |
| H3 | Vibe Window client (`/home/user/vibe-window` `app/`, plan branch): "Dual screen" setting, split renderer, bottom-screen touch | H1b | todo |
| T2 | Pin the build: user's `xxd` output replaces `kTargetBuild`; data file renamed; T1 tests rerun | T1, D2, dump | blocked: user |
| T3 | State discovery on the user's build (Windows dev-tools eden-cli, gdbstub): position + layer, hearts, stamina, rupees, time, weather, temperature, equipped gear with durability; sentinel test, second boot [S3 PORTING_A_GAME §2-3] | T2, H1c | blocked: user |
| T4 | Ready page v1 = smallest working companion: status + position + equipped gear, read-only | T3 | todo |
| T5 | End-to-end on the Thor through apollo2-vibe; sample cost in us | T4, H2, H3 | blocked: user |
| T6 | Map v1: Sky/Surface/Depths tiles from the player's romfs (zstd + CPM cache), player marker, layer switch | T3 | todo |
| T7 | Abilities page, read-only | T3 | todo |
| T8 | Inventory read: categories in game order, Zonai devices | T3 | todo |
| T9 | Release: `windows-x86_64` module (plus `android-arm64-v8a` only if path A is wanted) | T5 | todo |
| T10+ | Extensions, one packet each: actions (guest call on Windows, or load-plan helper), map pins, travel, compass, quests, recipes | T9 | todo |

### H1a (coder, first)

Goal: a patch series that lets eden-cli load native modules on Windows, testable here.

Where: `host/eden-duo/000N-*.patch` (git format-patch) against eden-duo `v1.3.2.2` (`5fa93ed4`),
and `host/eden-duo-companions/0001-*.patch` against companions `383d2d47`. The user moves them to a
fork later (H1c). Clones: `E=/tmp/claude-0/-home-user/6540dfaa-428d-5b44-8a41-bf2bc5812bad/scratchpad/ext/eden-duo`,
`C=.../ext/eden-duo-companions`; apply in fresh copies, never in those read-only clones.
Tools: `apt-get install -y g++-mingw-w64-x86-64-posix wine64` (both install from Ubuntu noble;
wine is `/usr/lib/wine/wine64`).

Changes:
1. Move module staging into `src/core/mods/mod_module_stage.{h,cpp}` with only std + POSIX/Win32
   dependencies, so it compiles and tests without the emulator:
   - `Platform()`: add `windows-x86_64` for `_WIN32 && (_M_X64 || __x86_64__)` (MSVC defines
     `_M_X64`, not `__x86_64__`).
   - `std::filesystem::path StageModule(bytes, title, root)` returns the new staging dir. Windows:
     dir `module-<random hex>` under `root` via `CreateDirectoryW`, retried on
     `ERROR_ALREADY_EXISTS` up to a fixed count, then fail; write `<TITLE>.so` with
     `CreateFileW(..., CREATE_NEW, ...)`, then `FILE_ATTRIBUTE_READONLY`. POSIX keeps today's
     `mkdtemp` + `O_EXCL` behaviour.
   - `void* OpenStaged(const std::filesystem::path&, std::string& error)`: Windows
     `LoadLibraryExW(path.c_str(), ...)` with `GetLastError()` text; elsewhere `dlopen` with
     `dlerror()`. `mod_module.cpp` wraps the handle in the existing
     `Common::DynamicLibrary(void*)` constructor [S1 `src/common/dynamic_library.h:24`] instead of
     `library.Open(filename.string().c_str())` (`mod_module.cpp:253`, which goes through
     `LoadLibraryA` and breaks non-ASCII paths).
   - `RemoveStaged(dir)`: clear the read-only attribute, then `remove_all`; used by `~GameModule`
     (after `library.Close()`).
   - `SweepStale(root)`: the stale-dir sweep moved out of `SetModuleCacheDirectory`
     (`mod_module.cpp:161-180`), same rule (only `module-*` dirs older than 10 minutes), built on
     `RemoveStaged`.
2. `dsmod_harness.cpp`: register the SIGUSR1/SIGUSR2 handlers and log the pid only
   `#ifdef SIGUSR1`; the `.shot`/`.btn` files keep working everywhere.
3. Loader tests:
   - `src/tests/core/mods/duo_tests.cmake`: build the fake modules on Windows too.
   - `src/tests/core/mods/module.cpp`: take the platform name from `Platform()` at line 50 (VFS
     dir name) and line 66 (manifest JSON), both hard-coded `linux-x86_64` today.
   - `src/tests/core/mods/fake_module.cpp`: `#ifdef _WIN32 __declspec(dllexport)` on
     `eden_dsmod_get_module` and `eden_dsmod_get_data_extensions` (MSVC would export nothing).
4. Companions `tools/build_dualscreen_package.py`: add `windows-x86_64` to `PLATFORMS`. The
   `.so` suffix and `modules/<platform>/<TITLEID>.so` path stay (fact 4).

Acceptance:
1. `git apply --check` of every patch on a fresh `git worktree`/copy of each clone at the pinned
   commit; `git am` applies the series in order.
2. A standalone test `host/eden-duo/test/stage_test.cpp` (built from the patched
   `mod_module_stage.cpp` + eden's patched `src/tests/core/mods/fake_module.cpp` as the module)
   passes:
   - natively with g++ (Linux branch);
   - built with `x86_64-w64-mingw32-g++-posix -static` and run with
     `WINEDEBUG=-all /usr/lib/wine/wine64 stage_test.exe` (Windows branch).
   Both runs use an ASCII cache root and a non-ASCII one (e.g. `cache-é`). It checks:
   `Platform()` is `linux-x86_64` / `windows-x86_64`; `StageModule` returns a new private dir
   holding a read-only `<TITLE>.so` with the exact bytes; a second stage returns a different dir;
   `OpenStaged` loads the staged file and `eden_dsmod_get_module` returns the fake table; a bad
   path gives null and a non-empty error; after unload `RemoveStaged` leaves nothing;
   `SweepStale` removes only `module-*` dirs older than 10 minutes.
3. MinGW `-fsyntax-only` of the patched `dsmod_harness.cpp` is not required (it needs the whole
   tree); instead: `grep -n "SIGUSR" ` shows every use inside `#ifdef SIGUSR1`.
4. Best effort, time-boxed to 45 minutes: configure and build eden-duo on Linux with the patches
   (`docs/Build.md`, `-DYUZU_TESTS=ON`), then run `tests "[dsmod][module]"`. The docs ask for
   Ubuntu 26.04 dependencies and this container is 24.04 [S1 `docs/Deps.md`]; do not install a
   26.04 toolchain. Stop at the first configure or build error and report it (H1c covers it on
   Windows).
5. The patched builder packs a `windows-x86_64` module and still refuses an unknown platform.

### T1 (coder, after H1a)

Scope: builds and tests in this container with no game dump. All game offsets `UNKNOWN` (0).

Learned in H1a: downloads from github.com (CPM, FetchContent) get 403 from the proxy, so T1 uses
only std and the three header-only SDK headers in `$E/src/core/mods` (`dsmod_module_abi.h`,
`dsmod_module_extensions.h`, `modules/dsmod_module_sdk.h`): no CPM, no FetchContent, no Catch2,
no network; tests are plain `assert` programs. wine needs `LC_ALL=C.UTF-8` (and
`WINEDEBUG=-all`) wherever it runs, including the CTest emulator and the acceptance 7 probe.

Files:
- `.gitignore`: `build/`, `dist/`.
- `native/CMakeLists.txt`: cmake >= 3.22; fail clearly if `EDEN_SOURCE_ROOT` is not absolute or
  the three SDK headers are missing (same check as [S2] `native/modules/CMakeLists.txt` lines 28-35). Target
  `dsmod-totk`: SHARED, C++20, `PREFIX ""`, `OUTPUT_NAME 0100F2C0115B6000`, hidden visibility,
  `-Wall -Wextra -Werror`. ELF: version script exporting only `eden_dsmod_get_module`,
  `-Wl,--no-undefined`. Windows (MinGW): `native/modules/0100F2C0115B6000.def` with the same
  single export, `-static-libgcc -static-libstdc++ -static`. With `BUILD_TESTING`: one executable
  + CTest test per `modules/totk_*_test.cpp`, asserts kept (`-UNDEBUG`); when cross-compiling,
  tests run through `CMAKE_CROSSCOMPILING_EMULATOR` (wine). No fmt, json or zstd.
- `native/mingw-w64.cmake`: toolchain file (`CMAKE_SYSTEM_NAME Windows`,
  `x86_64-w64-mingw32-gcc-posix`/`g++-posix`, emulator
  `/usr/bin/env;LC_ALL=C.UTF-8;WINEDEBUG=-all;/usr/lib/wine/wine64`).
- `native/modules/totk_build.h`: `kTitleId = 0x0100F2C0115B6000`; `kTargetBuild` = 64 upper-case
  hex, set to 1.2.1 `9B4E43650501A4D4489B4BBFDB740F26AF3CF850` + 24 zeros (`UNVERIFIED`, T2
  confirms); exact-match helpers for the hex string and the 32 raw bytes.
- `native/modules/totk_offsets.h`: struct of named offsets for the status domain (player/GameData
  root, hearts, max hearts, stamina, max stamina, rupees), every default `0` with
  `// UNKNOWN: <how to find>` naming the lead from this plan.
- `native/modules/totk_status.h`: `ReadStatus(const EdenDsmodHostApi&, const Offsets&)`. Invalid
  without any host call when a needed offset is 0; otherwise `dsmod_sdk::ReadGuest<MissingIsMapped::Reject>` with
  `InMain` and overflow checks, two reads, mismatch rejected.
- `native/modules/0100F2C0115B6000.cpp`: module table (ABI version/hash, exact `struct_size`, title
  id, capabilities `0`). `supports_build` exact match. `create` checks
  `dsmod_sdk::HostAbiMatches`, title id and the 32 bytes, else null. `sample` publishes
  `totk.ready = 1`, `totk.status.valid` (0 now), status values only when valid. `tick` does
  nothing. Every callback catches all exceptions. Export with `DSMOD_SDK_EXPORT_MODULE`.
- `native/modules/totk_test_host.h`: fake host (map-backed memory, counters for `is_mapped`,
  `read_memory`, `write_memory`, recorded publishes).
- `native/modules/totk_build_test.cpp`, `totk_status_test.cpp`, `totk_module_test.cpp`.
- `packages/TearsOfTheKingdom/dualscreen/manifest.json`: `format 1`, `title_id`, `name`,
  `min_runtime 22`, `requires_module true`, canvas 1240x1080, page `main`: notice (rect + label
  "Companion unavailable" + label `bind_text: module_error_message` giving the reason: wrong build, module error, checksum or platform) hidden when `totk.ready` is 1
  (`hide_bind`/`hide_eq` [S3 PACKAGE_FORMAT §3.5; Dread manifest]); placeholder Ready label with
  `need_bind: totk.ready`. No game art, no `module` block.
- `packages/TearsOfTheKingdom/dualscreen/9B4E43650501A4D4.json`: `{}`.

Acceptance (critic reruns all; `$E`, `$C` as in H1a, `$CP` = a copy of `$C` with the H1a builder
patch applied):
1. `cmake -S native -B build/gcc -G Ninja -DEDEN_SOURCE_ROOT=$E -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON && cmake --build build/gcc` exits 0.
2. Same with `-DCMAKE_CXX_COMPILER=clang++ -B build/clang`, and with
   `-DCMAKE_TOOLCHAIN_FILE=$PWD/native/mingw-w64.cmake -B build/win`: each exits 0.
3. `ctest --output-on-failure` in all three build dirs: 100% pass, at least 3 tests each (the
   `build/win` tests run under wine).
4. `nm -D --defined-only build/gcc/0100F2C0115B6000.so` prints exactly `eden_dsmod_get_module`;
   `x86_64-w64-mingw32-objdump -p build/win/0100F2C0115B6000.dll` exports exactly that name and
   imports no `libstdc++`/`libgcc`/`libwinpthread` DLL.
5. Tests cover at least:
   - `eden_dsmod_get_module`: wrong version or hash gives null; correct gives
     `struct_size == sizeof(EdenDsmodModuleApi)`, title id, five non-null callbacks,
     capabilities 0.
   - `supports_build`: true only for `kTargetBuild`; false for null, `""`, the 16-hex prefix,
     lower case, one changed nibble, the 1.4.3 id, BotW's id.
   - `create`: null for null host, wrong ABI hash, short `struct_size`, wrong title, wrong build
     bytes; non-null for the target; `destroy` frees it.
   - `sample`/`tick` with default offsets: zero `is_mapped`, `read_memory`, `write_memory` calls;
     publishes `totk.ready = 1`, `totk.status.valid = 0`, no other `totk.` value. Null instance
     or host does not crash. A throwing publish callback does not escape.
   - `ReadStatus` with test-only synthetic offsets over fake memory: reads the values; invalid
     when unmapped, when `main_base + offset` overflows or leaves `main`, when a pointer is null
     or misaligned, when the second read differs.
6. Package: strip both modules (`llvm-strip --strip-all -o dist/mod/linux/0100F2C0115B6000.so ...`,
   `x86_64-w64-mingw32-strip --strip-all -o dist/mod/win/0100F2C0115B6000.so build/win/0100F2C0115B6000.dll`), then
   `python3 -I $CP/tools/build_dualscreen_package.py --package packages/TearsOfTheKingdom --output dist --version 0.1.0 --min-runtime 22 --module linux-x86_64=dist/mod/linux/0100F2C0115B6000.so --module windows-x86_64=dist/mod/win/0100F2C0115B6000.so --build-id <kTargetBuild>`
   exits 0; `unzip -l dist/0100F2C0115B6000.dsmod.zip` lists exactly `package.json`,
   `dualscreen/manifest.json`, `dualscreen/9B4E43650501A4D4.json` and the two modules.
7. A wine probe (`LoadLibraryExW` on the packaged `windows-x86_64/0100F2C0115B6000.so`, then
   `eden_dsmod_get_module`) returns the table with title `0100F2C0115B6000`.
8. `--build-id` equals `kTargetBuild`; the data file name equals its first 16 characters.
9. Every offset in `totk_offsets.h` is `0` and has an `UNKNOWN:` comment.
10. `git status --porcelain` shows no `build/`, `dist/`, `*.so`, `*.dll`, `*.zip`; `packages/`
    holds only the two JSON files.

Not runnable here (say so in the report): Windows eden-cli, headless boot (no game), Thor.

### H1b (coder, after H1a)

Patch `src/yuzu_cmd` so one 1920x2160 virtual display can hold both windows:
- `--window-rect x,y,w,h` (main window) and `--aux-rect x,y,w,h` (aux window): borderless,
  positioned and sized with SDL3, and created **without** `SDL_WINDOW_RESIZABLE` (both are
  resizable today). Reason: SDL3 gives every resizable window `WS_THICKFRAME | WS_MAXIMIZEBOX`
  [S26], and the window-host tracker resizes such a target to fill the whole capture display
  (H2 finding 4). Without the flags nothing changes.
- With `--aux-rect`, create the aux window with `SDL_WINDOW_NOT_FOCUSABLE`, so taps never move
  keyboard focus away from the game window.
- Help text lists both flags.
Where and how (learned in H1a/T1): `host/eden-duo/0004-*.patch` (more if needed, numbered on),
`git am` on top of 0001-0003 in a fresh copy of `$E` at `5fa93ed`. There is no full eden-duo build
here (the proxy 403s CPM's fmt) and no SDL3 headers (Ubuntu noble has none), so:
- the rect parser lives in its own std-only file `src/yuzu_cmd/window_rect.{h,cpp}` (added to
  `duo_cmd.cmake`), called from the existing `getopt_long` table in `dsmod_harness.cpp`;
- SDL changes stay minimal: the flags passed to the four `SDL_CreateWindow` calls
  (`emu_window_sdl3_{gl,vk,null,aux}.cpp`) plus one position/size call; they are only
  compile-checked in H1c.
Acceptance:
1. `git am` of 0001-0004+ on a fresh copy succeeds; 0001-0003 are unchanged.
2. Standalone assert test `host/eden-duo/test/window_rect_test.cpp`, built like `stage_test`
   with g++ and with MinGW, run natively and under `LC_ALL=C.UTF-8 WINEDEBUG=-all` wine: valid
   rects parse; missing fields, extra fields, non-numbers, negative x/y, zero or negative w/h,
   values beyond `int` are rejected with a message.
3. `grep` of the patched tree: `SDL_WINDOW_RESIZABLE` is dropped and `SDL_WINDOW_BORDERLESS` set
   only when the matching rect flag is given; `SDL_WINDOW_NOT_FOCUSABLE` only on the aux window
   with `--aux-rect`; without the flags every create call passes the same flags as today.
4. Help text lists both flags. The windows are built and checked on real Windows in H1c.
Fallback if the patch is refused: a PowerShell script that finds the windows by title (`Eden - Screen 2 (DSMod aux)`) and sets style and
position.

### H1c (user on Windows, after H1a, H1b, T1)

Some H1a checks only mean something on real Windows (wine does not enforce them): the owner-only
staging DACL (`host/eden-duo/test/stage_test.cpp:165`) and cleanup of the staging dir after a
failed load. On the user's Windows build of the patched fork:
1. Build `host/eden-duo/test` with the Windows toolchain (MSYS2 `MINGW64` g++ with the Windows
   lines of `run.sh`: `fake.dll`, `stage_test.exe`, `planted.dll`, `importer.dll`, all in one
   folder) and run `stage_test.exe fake.dll importer.dll` with `planted.dll` next to it, and
   `window_rect_test.exe`. Must pass.
2. Run `tests.exe "[dsmod][module]"` from the MSVC build (`-DYUZU_TESTS=ON`). Must pass.
3. Smoke run as in the H1c row: TotK package installed, notice page in the aux window, `.shot`
   screenshots, a tap on the aux window keeps controller input. With
   `--window-rect 0,0,1920,1080 --aux-rect 0,1080,1240,1080`: both windows sit at those rects,
   borderless and not resizable (no maximize), and the aux window never takes focus.
4. Confirm the call-site minimums: `--window-rect 0,0,639,360` and `0,0,640,359` are refused with
   a message, `0,0,640,360` starts; `--aux-rect 0,0,0,1` is refused, `0,0,1,1` starts.
5. Run `tools/duo/duo-touched-ledger.sh --check` in the patched tree. Must pass.
Send logs and screenshots back.

Decision point: if a failed load leaves its `module-*` staging dir behind on real Windows,
desktop eden-cli needs a `SweepStale` call at startup (today only Android calls
`SetModuleCacheDirectory`). That becomes its own follow-up packet; H1c does not fix it.

### H2 (host: apollo2-vibe window-host, verify first)

Repo: `/home/user/apollo2-vibe`, branch `claude/clever-clarke-k7hhhj` (window-host 1.3.2,
`43408b8`) [S21b]. The client sends `windowOnly=1`; the host captures the app's window with WGC in
a helper process and paints everything else black. Findings from that code:
1. **The aux window joins the captured set.** With `window_only_secondary_windows` on (default,
   `src/config.cpp:955`), the tracker's app window set holds the target's process
   (`window_target.cpp` `update_window_set` -> `build_app_pids(target_pid, ...)`), and the helper
   gives every visible, top-level, non-transparent window of those processes that touches the
   capture display its own WGC layer, up to 16 (`tools/sunshine_wgc_capture.cpp:3180-3190`;
   `window_capture_policy.h` `is_secondary_window`, line 367). eden-cli's aux window is a visible
   unowned top-level window of the same process, so it becomes a layer.
2. **Position.** The target's client area and each layer are copied at their true desktop
   position relative to the capture display; every other pixel is black
   (`tools/sunshine_wgc_capture.cpp:1193-1196`, `CopySubresourceRegion` at `dst_x/dst_y`). The
   frame is exactly the desktop layout of the two windows.
3. **Target choice.** Foreground eligible window first, then the current target, then the
   largest client area (`window_capture_policy.h` `choose_target`). The 1920x1080 main window
   wins over the 1240x1080 aux window unless the aux window becomes foreground, and a target
   change restarts capture (`display_wgc.cpp` `window_target_needs_reinit`). H1b's
   `SDL_WINDOW_NOT_FOCUSABLE` becomes `WS_EX_NOACTIVATE` on Windows [S26], so a tap never makes the
   aux window foreground. H1b's flags are needed.
4. **Placement fight.** The tracker places each new target (`place_step`, `plan_placement`):
   left alone if its client area covers the display; if resizable (`WS_THICKFRAME` +
   `WS_MAXIMIZEBOX`, unowned) resized to fill the whole display; if fixed-size, moved only when
   its client area is not already inside the display. eden-cli creates its main window
   `SDL_WINDOW_RESIZABLE` (`emu_window_sdl3_vk.cpp:81-83`), which would stretch the game to
   1920x2160. With H1b's non-resizable main window at `0,0,1920,1080` it is inside the display and
   left alone. The dialog mover (`place_secondary_windows`) moves only captioned windows that are
   owned or foreground, so the never-foreground aux window is not moved. H1b found that SDL
   3.4.8 gives borderless windows `WS_POPUP | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX`, so both
   windows are captioned but not resizable: the main window is fixed-size and inside the display
   (left alone), the aux window is unowned, never foreground (`WS_EX_NOACTIVATE`) and fixed-size
   inside the display, so it is never moved, and `choose_among`'s `dialog_like` rule keeps it
   from becoming the target. Verified by reading (H1b critic); H2's checks confirm it live.

So no host code is needed if H1b holds. Setup on the PC:
1. Install the window-host MSI built from that branch.
2. App "TotK duo": `eden-cli.exe --aux-window --window-rect 0,0,1920,1080 --aux-rect 0,1080,1240,1080 -g <TotK path>`.
3. Keep `virtual_display_mode = per_client` and `virtual_display_layout = exclusive` (the virtual
   display is alone, origin 0,0). The client's Dual screen setting (H3) requests
   `mode=1920x2160x60` with `virtualDisplay=1` and `windowOnly=1`; the host creates the virtual
   display at the requested mode (`src/nvhttp.cpp:2003-2013`). Whether the virtual display driver
   accepts 1920x2160 is `UNVERIFIED`.
4. Turn on taskbar auto-hide on the PC. The virtual display is the only display, so the taskbar
   (~48 px at 100 %) sits along its bottom edge, over the aux window's bottom strip. The stream
   does not show it, but apollo injects touch at screen coordinates
   (`InjectSyntheticPointerInput`), so taps there would hit the always-on-top taskbar and could
   take focus from the game. Fallback if auto-hide still lets bottom-edge taps reveal it: mode
   `1920x2240x60`, leaving an 80 px band below the aux window that the client never shows or
   touches (a constant change in H3's `DualScreenLayout`).

Checks (user, Windows + Thor, after H1c and H3):
- the host log shows "Client requested window-only capture", eden-cli's main window as the
  target, and "app window set has 1 process(es)";
- the virtual display is 1920x2160;
- the stream shows the game on top and the companion bottom-left, black elsewhere;
- the target never changes after taps on the companion;
- a touch in the bottom area moves the companion, one in the top area does not;
- controller input reaches the game after a tap below;
- taps within 50 px of the bottom edge (y 2110-2159) reach the companion: it reacts, no taskbar
  appears, and controller input still reaches the game afterwards.

**H2b (code, only if a check fails)**, same repo and branch: a launch argument `dualScreen=1`,
parsed next to `windowOnly` (`src/nvhttp.cpp:2114`) into the launch session and passed to the
tracker as `pinned`. A pinned tracker never calls `place_step` or focus for its target, and keeps
the largest window of the app as target even when a smaller app window is foreground.
Acceptance: new cases in `tests/unit/test_window_capture_policy.cpp` for the pinned choice;
without the argument behaviour is unchanged and the existing tests pass. The client sends the
argument only once H2b exists.

### H3 (client: Vibe Window APK)

Repo: `/home/user/vibe-window` `app/` (Moonlight-Android fork, tag `v1.4.10`, app id
`app.vibewindow.client`, package `com.limelight`), merged into the plan branch
`claude/practical-mccarthy-f7wf2f` by `4128b20` (user decision). H3 code is committed on the plan
branch, no new branch. What exists there [S27]:
- `windowOnly` and `virtualDisplay` are sent on `/launch` and `/resume` with
  `mode=<W>x<H>x<FPS>` from the negotiated size (`NvHTTP.java:885-897`); default 1920x1080@120.
- Second-display support: `ServerHelper.getSecondaryDisplay` (first non-default display) and
  "Full external display" mode, which starts `Game` on that display with
  `ExternalDisplayControlActivity` on the other one (`ServerHelper.java:64-131`).
- A GL path from the decoder's `SurfaceTexture` (`utils/Stereo3DRenderer.java`, wired in
  `ui/StreamContainer.java`), the model for the split.
- Direct touch through `conn.sendTouchEvent` with normalized coordinates (`Game.java:2768-2801`).
- No per-app settings, so the switch is global.

Changes:
1. Setting **"Dual screen"** (global, default off, shown only when a second display exists).
   When on: request `1920x2160x60` (overriding width, height and fps the way full external
   display mode does in `Game.onCreate`), keep `windowOnly=1` and `virtualDisplay=1`, and turn off
   the modes that fight it (full external display, stereo 3D, PiP).
2. Split renderer: MediaCodec decodes into one `SurfaceTexture`; one GL context draws the top
   1920x1080 crop to the main-display surface and the bottom 1240x1080 crop (origin `0,1080`) to a
   `SurfaceView` inside a `Presentation` on the second display, 1:1, no scaling. Which display is
   the top one is decided by size (1920x1080 vs 1240x1080), not assumed.
3. Touch: bottom-display touches always go out as direct touch with `x = tx / 1920` and
   `y = (1080 + ty) / 2160` (normalized over the whole video [S23]); top-display touches keep the
   user's touch mode with `y` scaled into the top half. Pointer ids from the two displays never
   collide.
4. The pure parts (mode override, crop rects, coordinate mapping, pointer ids) live in one class
   `com.limelight.utils.DualScreenLayout` with no `android.*` import, Java 11, and every size in
   one place, so the side-by-side fallback (3160x1080, bottom crop at `1920,0`) is a constant
   change. Its test `app/src/test/java/com/limelight/utils/DualScreenLayoutTest.java` is plain
   JUnit 4 (no Robolectric).

What this container can do (checked): no Android SDK or NDK (dl.google.com is refused by the
proxy, so no `sdkmanager` and no `android.jar`), so `./gradlew` cannot build the app here; JDK 21,
`/opt/gradle-8.14.3/lib/junit-4.13.2.jar` and `hamcrest-core-1.3.jar` exist; the
moonlight-common-c submodule is not checked out (reachable with git, not needed for H3).

Acceptance (coder, here):
1. `javac --release 11 -cp <junit>:<hamcrest> -d build/h3` of `DualScreenLayout.java` and its test,
   then `java -cp build/h3:<junit>:<hamcrest> org.junit.runner.JUnitCore
   com.limelight.utils.DualScreenLayoutTest`: OK. Tests cover: the four corners and centre of each
   display map to the expected normalized x/y; top/bottom pointer ids never collide; the mode is
   `1920x2160x60` when on and the negotiated values unchanged when off; the crop rects are
   `0,0,1920,1080` and `0,1080,1240,1080`.
2. The Android changes (setting, `Game`/`NvHTTP` wiring, renderer, `Presentation`) are reviewed by
   reading only; with the setting off every touched code path is skipped by one `if`. `git diff`
   touches nothing outside `app/` and `docs/PLAN.md` status.
3. Not runnable here, say so: `./gradlew assembleDebug testDebugUnitTest`, Robolectric, the Thor.

Acceptance (user): `./gradlew assembleDebug testDebugUnitTest` passes on their machine (or CI);
with the setting off, launch arguments and rendering are unchanged. On the Thor (user): both screens at native size, tap error under 2 px on the
companion, no added frame drops at 60 fps.

## What needs the user

| Item | For | What exactly |
|---|---|---|
| D2 target version | T1 constant, T2-T3 | Which TotK update they run. Default 1.2.1 (most leads), else 1.4.3 (current) |
| eden-duo fork + Windows build | H1c | A fork repo for the H1 patches, and either a Windows build on their PC (MSVC + Ninja, `tools/windows/load-msvc-env.ps1` [S1 docs/Build.md]) or a GitHub Actions Windows runner in the fork |
| Windows tests | H1c, H2 | Install the window-host MSI from `apollo2-vibe` branch `claude/clever-clarke-k7hhhj`, add the "TotK duo" app (H2), run the H1c and H2 checks; send host logs and `.shot` screenshots |
| Vibe Window APK | H3 | Install the H3 client build on the Thor and turn on Settings -> "Dual screen" (keep "Show only the app window" on) |
| Build id | T2 | `xxd -s 0x40 -l 32 -p main` from `eden-cli --dump-romfs` of base + update [S3 PORTING_A_GAME §1] |
| Dump + running game for RE | T3 | Discovery runs on their Windows PC (dev-tools eden-cli, gdbstub), or they provide exefs/romfs and keys to this container outside the repo. Nothing from it is committed |
| Test saves | T3, T5 | Isolated profile copies: Great Sky Island; Surface with Ultrahand/Fuse/Ascend/Recall; late game with Autobuild and Depths |
| Thor | H2, H3, T5 | Stream "TotK duo" with Dual screen on; check both screens and touch |

## Host path (decided: C)

| Path | Where the game runs | New work outside the companion |
|---|---|---|
| A. On device | Eden Duo on the Thor | None |
| B. Linux host | eden-cli on a Linux PC, streamed | H1b, H2, H3 |
| **C. Windows host (chosen)** | eden-cli on Windows, streamed by apollo2-vibe | H1a, H1b, H1c, H2, H3 |

Path A stays the fallback: the same package works there with an `android-arm64-v8a` module.

## Risks

- **Wrong build** reads garbage or corrupts a save: three gates, read-only until T10, every write
  guarded by expected bytes [S3 PORTING_A_GAME §5].
- **Upstream Windows support of Duo code** may have more gaps than the grep found (only a
  Windows build proves it): H1c.
- **Virtual display mode 1920x2160** may be refused by the driver: side-by-side 3160x1080
  fallback (same client split).
- **Touch on Windows**: injected touch may reach the aux window as mouse events, or SDL may route
  it to the focused window; H2 checks it, `SDL_WINDOW_NOT_FOCUSABLE` limits focus theft.
- **Latency**: the client split adds a GPU pass; the composite doubles encoded pixels.
- **1.4.x has few leads**: only ASM cheats [S10] and code offsets [S6][S7].
- **Performance**: module `sample` must not allocate, do file I/O or log per frame; measure
  [S3 MODULE_GUIDE §9].
- **Licensing**: Eden Duo, the companions, objmap-totk and Artemis are GPL-3.0
  [S1][S2][S13][S25]; this package is GPL-3.0-or-later. totk-mods is MIT [S7]. Cheat files and
  TkSharp have no license found: numbers as leads only, no copied code.

## Sources

| # | URL | What it proves |
|---|---|---|
| S1 | https://github.com/igawa6/eden-duo (clone `v1.3.2.2`, `5fa93ed4`) | Module ABI/SDK headers, loader gates, Windows gaps, harness, aux window, Windows build docs, runtime 22 |
| S2 | https://github.com/igawa6/eden-duo-companions (clone `383d2d47`) | BotW package, module, tests, load plan, build script, CMake, package builder |
| S3 | S2 `docs/` (PORTING_A_GAME, MODULE_GUIDE, PACKAGE_FORMAT, ARCHITECTURE) | Porting method, build-id rules, package format, NCE, headless testing |
| S4 | https://raw.githubusercontent.com/blawar/titledb/master/versions.json | TotK update versions and catalog dates |
| S5 | https://github.com/HamletDuFromage/switch-cheats-db `versions/0100F2C0115B6000.json` | Version -> 16-hex build id; 720896 is latest |
| S6 | https://github.com/TKMM-Team/TkSharp `TkSharp.Core/TkPatch.cs` | Full 20-byte build ids 1.1.0-1.4.3 |
| S7 | https://github.com/afiaka87/totk-mods (`0b4ec43e`, MIT) | Version names per build id; 1.2.1 offsets |
| S8 | https://mp1st.com/title-updates-and-patches/the-legend-of-zelda-tears-of-the-kingdom-update-1-4-2-released-for-switch-2-issues ; https://zeldawiki.wiki/wiki/The_Legend_of_Zelda:_Tears_of_the_Kingdom/Version_History (search result; unreachable here) | 1.4.2 on 2025-07-03; 1.4.3 on 2026-02-17 |
| S9 | https://github.com/HamletDuFromage/switch-cheats-db `cheats/0100F2C0115B6000.json` | Cheat chains 1.0.0-1.2.1; none for 1.4.x |
| S10 | https://github.com/Arch9SK7/MyNXCheats `.../0100F2C0115B6000/cheats/277178B7DBA1B6D4.txt` | 1.4.3 cheat list |
| S11 | https://github.com/TotkMods/Research `Formats/Sav/Readme.md` | Save and GameData type layout |
| S12 | https://github.com/dt-12345/gmd_scripts `gamedata.md` | GameData flags, murmur3 hashes, `GameDataList.Product.110.byml.zs` |
| S13 | https://github.com/zeldamods/objmap-totk (GPL-3.0) | Sky/Surface/Depths layers, map unit naming |
| S20 | https://andreyvelsk.github.io/GAFT/project/eden-ds/ | Unreachable from this container (DNS fails); contents UNVERIFIED |
| S21 | /home/user/apollo2-vibe (`0906d00`) | Capture singleton, virtual display per client mode, per-client display mode override, touch passthrough, layout options |
| S22 | https://learn.microsoft.com/en-us/cpp/build/x64-calling-convention | One x64 calling convention on Windows |
| S22b | https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-loadlibraryw | `.dll` is appended only when the name has no extension |
| S23 | https://github.com/moonlight-stream/moonlight-common-c `src/Limelight.h`, `src/Connection.c` | One connection per process (globals, "not thread-safe"); `LiSendTouchEvent` normalized coordinates |
| S24 | https://www.androidauthority.com/ayn-thor-screen-size-3589297 ; https://droix.net/product/ayn-thor/ | Thor screens 1920x1080 and 1240x1080 |
| S21b | /home/user/apollo2-vibe branch `claude/clever-clarke-k7hhhj` (`43408b8`) | Window-only tracker, target choice, placement, secondary-window layers |
| S26 | https://github.com/libsdl-org/SDL `src/video/windows/SDL_windowswindow.c` (release-3.4.8) | `SDL_WINDOW_NOT_FOCUSABLE` -> `WS_EX_NOACTIVATE`; resizable -> `WS_THICKFRAME`, `WS_MAXIMIZEBOX` |
| S27 | /home/user/vibe-window `app/` (tag `v1.4.10`, merged by `4128b20`) | Vibe Window client: launch args, secondary display, GL renderer, touch |
| S25 | https://github.com/ClassicOldSong/moonlight-android (branch `moonlight-noir`, GPL-3.0) `app/src/main/java/com/limelight/Game.java` | Artemis external-display handling |

The MinGW and wine results come from spikes in the planner's scratch dir
(`/tmp/claude-0/-home-user/6540dfaa-428d-5b44-8a41-bf2bc5812bad/scratchpad/spike`), not from a
web source.

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

## Next steps for the user

Everything below is blocked on you, in this order. Send logs, `.shot` files and screenshots back.
1. **H1c, eden-cli on Windows.** Fork igawa6/eden-duo at `v1.3.2.2`, then
   `git am host/eden-duo/000*.patch`. Run `tools/windows/load-msvc-env.ps1`, then
   `cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DYUZU_TESTS=ON -DEDEN_DSMOD_BUILD_DEV_TOOLS=ON`
   and `cmake --build build`. Then run the five checks in §H1c (stage and rect tests,
   `tests.exe "[dsmod][module]"`, smoke run, rect minimums, `tools/duo/duo-touched-ledger.sh --check`).
2. **H2, host.** Install the apollo2-vibe window-host MSI from branch `claude/clever-clarke-k7hhhj`.
   Turn on Windows taskbar auto-hide (Settings > Personalization > Taskbar > Automatically hide).
   Keep `virtual_display_mode = per_client` and `virtual_display_layout = exclusive`. Add the app
   "TotK duo": `eden-cli.exe --aux-window --window-rect 0,0,1920,1080 --aux-rect 0,1080,1240,1080 -g <TotK path>`.
3. **H3, client.** Build `./gradlew :app:testNonRoot_gameDebugUnitTest :app:assembleNonRoot_gameRelease`
   (JDK 17, Android SDK). CI (`.github/workflows/release.yml`) runs only on pushes to `main` or
   `claude/clever-clarke-k7hhhj`, not this plan branch, so push the commits there to get a CI build.
   Install the APK on the Thor and turn on Settings > "Dual screen".
4. **H2 + H3 checks on the Thor.** Run every §H2 check: 1920x2160 display, the right target,
   bottom taps move the companion, taps within 50 px of the bottom edge, controller input after
   taps. Also check two fingers on each screen at once. Then the §H3 checks: both screens at
   native size, tap error under 2 px, no added frame drops at 60 fps.
5. **T2.** You play **1.2.0** (D2, answered). Send `xxd -s 0x40 -l 32 -p main` from
   `eden-cli --dump-romfs` of base + update, so T2 confirms the 1.2.0 build id T2a sets.
6. **Approve vendoring `stb_truetype.h` (T3a').** TotK's fonts are CFF outlines (`.bfotf`), so
   the module rasterizes them. The file is `native/modules/third_party/stb_truetype.h` v1.26
   (public domain) from the companions clone [S2], copied to `native/third_party/`; the ACNH module already does the same with it.
   The session's classifier refused compiling outside code once (zstd, T3a), so the coder asks you
   first. The zstd vendoring you approved is **no longer needed in the module** (patch 0009 below
   decodes zstd in Eden Duo, which already links zstd); the coder uses those files only to build
   patch 0009's standalone test, outside the repo.
7. **T8a (after T3a' and H1c): texture dump.** Use the CI-built eden-cli (it now applies
   `host/eden-duo/0001-0009`). Install the package, create a file
   `<Eden data dir>/dualscreen/user/0100F2C0115B6000/totk-dump` containing `1`, start TotK 1.2.0
   once, load a save, quit. Send every log line with `totk dump`. Then, in the dev-tools build's
   console, run the `imgdump` lines the coder lists in §T8a and send the PNGs back (they stay out
   of the repo). This names the game's UI textures for 1.2.0, which no public source lists.
8. **T8 (after T4v-T7 are coded here).** Install the package on the PC, load a save, and send the
   `.shot` pictures and `DSMod` log lines listed in §T8. This is the first time the restyled pages
   are seen with your game's textures, font, icons and map tiles, and values from your save.
9. **T3 (later, memory).** Provide the dumps (exefs/romfs, kept outside the repo) or run discovery
   on the PC, plus the test saves listed in "What needs the user". Not needed for T3a'-T7.

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
   none. This repo therefore gets its own small CMake that needs only the headers; zstd was to be
   vendored in T3a (superseded 2026-11-02: zstd moves into Eden Duo, patch 0009, §R2).
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
14. **The runtime gives a module read-only save access (no memory needed).** Runtime 22 offers
    the save extension `eden_dsmod_get_save_extensions`: `read_save_file(relative_path, offset,
    out, size)` reads inside the running title's own account save directory only, ≤64 MiB per
    call, `out == NULL` returns the size, and there is no write path
    [S1 `dsmod_module_extensions.h:140-180`, `mod_module_services.cpp:73-244`; S3 MODULE_GUIDE
    §2.3]. No shipped module uses it yet [S3]. TotK keeps most GameData in `slot_0N/progress.sav`
    (N = 0-5) with `caption.sav` beside it [S29 `zelda-totk.js:11`, `:1114`]; the format is
    documented (magic `0x01020304`, entries from 0x20, typed sections, murmur3-32 key hashes)
    [S11][S28]. So Gear, Inventory, Abilities and the map's player position can show "as of the
    last save" with no offset at all. Live values still need T3.
15. **The runtime does not decode zstd.** `grep -i zstd src/core/mods` finds nothing [S1]; its
    romfs sources resolve SARC, BNTX, BFFNT, `.lzs`, `.bctex`, `.dds`, `.png` and yaz0
    (`mod_nx_assets.cpp:162-270`, `mod_yaz0.h`) [S3 PACKAGE_FORMAT §3.11]. Almost every TotK
    romfs file is zstd with a dictionary from `Pack/ZsDic.pack.zs` [S28][S30], so
    `romfs:/...zs#member` sources and the manifest `msbt` key cannot read TotK files. The module
    must read them through `read_romfs`, decompress, and hand pictures over through `load_image`
    (base extension, RGBA8 ≤4096², ≤16 MiB) and text through published values [S1
    `dsmod_module_extensions.h:70-88`; S3 MODULE_GUIDE §1.4, §2.1]. The host lends its ASTC
    decoder (`EdenDsmodHostExtensions::decode_astc`) [S1 `dsmod_module_extensions.h:45-64`].
    (2026-11-02: decision changed, §R2: patch 0009 adds zstd to the runtime.)
    The Wonder module already does zstd -> SARC -> BNTX -> deswizzle -> ASTC/BC -> RGBA
    (`native/modules/wonder_assets.cpp`, GPL-3.0, 540 lines) [S2]; old T3a adapted it. Superseded: patch 0009 decodes zstd in the runtime and the
    runtime's own BNTX decoder does the rest; the module keeps names and the font (§R2, T3a').
16. **The manifest `msbt` text source only loads labels the manifest names statically**
    (`mod_nx_runtime.cpp:2326-2379` collects `text_src` / `text_map` labels) [S1], so item names
    that change with the save are decoded by the module and published as text (`bind_text`),
    the way BotW publishes `botw.inventory.*` names [S2 BotW manifest].
17. **Page binds never fire on the first value.** `DrivePageBinds` captures the first valid read
    as a silent baseline [S1 `mod_pages.cpp:52-68`], so a page bind cannot move from a notice
    page to the companion at start; the notice stays an overlay on every page (T1 pattern).

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

**Target build (decision D2, user: 1.2.0).** One build only. T1 assumed 1.2.1; the user plays
**1.2.0** (update v327680). T2a switches `kTargetBuild` to the 1.2.0 id
`6F32C68DD3BC7D77AA714B80E92A096A737CDA77` + 24 zeros (`UNVERIFIED` zero tail, T2 confirms with
the user's `xxd`). Two sources agree on the 20 bytes: TkSharp `NSO_BINARY_ID_120 =
"6f32c68dd3bc7d77aa714b80e92a096a737cda77"` [S6 `TkPatch.cs:16`] and switch-cheats-db's
`6F32C68DD3BC7D77` key [S5][S9].
- Consequence for T3 (memory) only: 1.2.0 has the fewest leads. switch-cheats-db holds 30 cheats
  for `6F32C68DD3BC7D77`, all render-resolution, contrast and saturation (no pointer chains),
  against 111 for 1.2.1 [S9]; totk-mods' offsets are 1.2.1 [S7]; lotuskit covers 1.0.0, 1.1.0 and
  1.2.1 [S28 §Sources]. Decision **D2b (user, before T3)**: stay on 1.2.0 and find every offset
  from scratch, or update to 1.2.1 (most leads) or 1.4.3 (current, live GameData layout known
  [S28 §3]). T4-T7 read the save and the romfs, which do not depend on the build, so D2b does not
  block them.

## Public TotK research (leads, never facts)

Every lead is confirmed from code and live validation before use [S3 PORTING_A_GAME §2.1, §3].

| Source | License | What it gives | Use |
|---|---|---|---|
| switch-cheats-db `cheats/0100F2C0115B6000.json` [S9] | none found; authors credited in file | 1.2.1: 111 cheats incl. pointer chains. Rupees `[[main+0x4721B98]+0xD8]+0x28EA4` (cheat 9); hearts chain from `main+0x46AF308` `+0x480 +0x228 +0x2F8` (cheat 3); stamina via `main+0x4721B98` and `main+0x46AF308` (cheat 4); motion via `main+0x471DCB0 +0xC8 +0x178 +0x30`, float `+0xFC` (cheat 17); battery, durability, arrows, pouch sizes, Korok map. 1.0.0-1.1.2: ASM hooks for HP/stamina/quantity. No 1.4.x entries | Anchors for T3 (1.2.1) |
| MyNXCheats `277178B7DBA1B6D4.txt` [S10] | none found | 1.4.3 cheat list: Inf. Health, Stamina, Battery, Horse Stamina, Perfect Thermometer, Gloom, Items x999, durability, movement. Code types not inspected (`UNVERIFIED`) | Anchors for T3 if target is 1.4.3 |
| afiaka87/totk-mods `Totk121Offsets.hpp` [S7] | MIT | 1.2.1: SceneModule `main+0x04728538`; GameDataMgr holder `main+0x0462E3D8` (double deref), `GameDataGetInt` `main+0x010CD5BC`, `GameDataSetInt` `main+0x00B51B08`, int store `+200`; resident actors (scene `+0x1E8`, components `+0x58`, index 13, list `+0x28`, stride `0x70`); actor name `+0x218`, position Vec3f `+0x2B4`, rotation `+0x2C0`, velocity `+0x320` | Best lead set for 1.2.1 |
| TkSharp `TkPatch.cs` [S6] | not determined | Full 20-byte build ids 1.1.0-1.4.3 | Build ids |
| TotkMods/Research `Formats/Sav` [S11], dt-12345 gmd_scripts `gamedata.md` [S12] | not determined | GameData flags hashed with murmur3-32; full list in romfs `GameData/GameDataList.Product.110.byml.zs`; 33 types; structs such as `PlayerStatus` | Read flag names from the player's romfs at runtime; never ship the list |
| zeldamods/objmap-totk [S13] | GPL-3.0 | Layers Sky / Surface (`Ground`) / Depths; MainField = Sky + Surface, MinusField = Depths; map unit col `(x+5000)/1000`, row `(z+4000)/1000`; item names from `USen.Product.100/ActorMsg/PouchContent` labels `<Actor>_Name` (`tools/make_names_list.py:22-40`) | Map layer model; MSBT label scheme |
| kitcatkode/totk-android `android/BOTTOM_SCREEN.md`, `runtime/bottom_inventory.c` [S28] | MIT | Found on 1.4.3 with a file-open log: map tiles `UI/Map/MainField/<L>_<cc>-<rr>_<lv>[_<reveal>].bntx.zs` (L = `G` surface, `S` sky, `U` Depths; 12x10 tiles, tile (c,r) covers x -6000+1000c..+1000, z -5000+1000r..+1000; level 02 = 300x300 ASTC 8x8, 01 ~750 px, 00 3000 px ASTC 12x12; reveal letters `T`/`F` per region, `_A`/`_B` variants); icons `UI/Tex/Icon/<item id>.bntx.zs` (256x256 ASTC 4x4 sRGB); names `Mals/USen.Product.<n>.sarc.zs` (n = 142 on 1.4.3) `ActorMsg/PouchContent.msbt` `<id>_Name` / `<id>_Caption`; `ZsDic.pack.zs` = plain-zstd SARC with `zs.zsdic`, `pack.zsdic`, `bcett.byml.zsdic`; Depths tiles are channel masks to tint; pouch keys; newest save = newest `slot_NN/progress.sav`; equip through the game's quick menu with readback | Romfs paths for T3a, save layout for T3s. All from 1.4.3: `UNVERIFIED` on 1.2.0 |
| marcrobledo/savegame-editors `zelda-totk/` [S29] | MIT | GameData key names with their murmur3 hashes: `PlayerStatus.MaxLife` 0xfbe01da1, `.CurrentRupee` 0xa77921d7, `.MaxStamina` 0xf9212c74, `.MaxEnergy` 0xafd01d68, `.SavePos` 0xc884818d (Vector3F), `.Life` 0x31ab5580; pouch structs `Pouch.{Weapon,Bow,Shield,Armor,Arrow,Material,Food,SpecialParts,KeyItem}.Content.*` (`zelda-totk.class.pouch.js:454-518`); `Pouch.Arrow.EquipIndex`; abilities `IsGet.Obj_{UltraHand,OneTouchBond,Tooreroof,ReverseRecorder,AutoBuilder}` + `IsGetAnyway.*`, `Obj_Camera`, `Obj_Warp` (`zelda-totk.class.item.js:168-175`, `zelda-totk.js:491-504`, `:1686-1689`); newest slot from `caption.sav` date hashes (`zelda-totk.js:1105-1150`) | Key names for T3s |

Checked in this container: a 20-line murmur3_x86_32 (seed 0) gives exactly the hashes [S28] and
[S29] publish for `Pouch.Material.Content.Name` (0xd96ebf12), `Pouch.Weapon.Content.Name`
(0x65efd0be), `PlayerStatus.MaxLife`, `.CurrentRupee`, `.MaxStamina`, `.SavePos`,
`.Life` and `MapData.IconData.StampData.Type` (planner scratch `mm3.py`).

Romfs formats: `.zs` = zstd whose frame header names a dictionary from `Pack/ZsDic.pack.zs`
(itself plain zstd around a SARC of `*.zsdic`) [S28][S30]; inside, SARC, BNTX, MSBT, BYML. The
paths above come from 1.4.3; on 1.2.0 they are `UNVERIFIED` until the T8 log lists them.

## BotW feature table: reuse, adapt, drop

Source: [S2] BotW README and `native/modules/botw_*.h`.

| BotW feature (files) | TotK | Notes |
|---|---|---|
| Build gate, guarded reads, torn-read rejection (`botw_pouch.h`) | Reuse pattern | Use `dsmod_sdk::ReadGuest<MissingIsMapped::Reject>` and `InMain` |
| Fake host fixture (`botw_test_fixture.h`) | Reuse pattern | `totk_test_host.h` |
| UI context, title/loading/pause pages (`botw_context`, `botw_states`, `page_binds`) | Adapt (T3+) | TotK scene names UNKNOWN; T4-T7 have no scene reader, so the pages show save data at all times |
| Page tracking for lazy publishing (P5R `ui_open` + `page_binds` [S3 MODULE_GUIDE §7.4]; BotW `botw.state.page` binds) | Reuse | T4: tab bar = module action `totk.ui.page`, `page_binds` on `totk.ui.page` |
| HUD hidden detection (`botw_hud`) | Adapt (T3+) | |
| Ready page: hearts, stamina, rupees, time, temperature, weather, location (`botw_ready*`, `botw_environment`, `botw_temperature_*`) | Adapt | T4 v1 = status strip on the Gear page from the save (hearts, max hearts, max stamina, rupees, max battery); live stamina, time, weather, temperature, gloom after T3 |
| Equipped weapon/bow/shield/arrows + durability (`botw_player_equipment`, `botw_gear`, `botw_item_status`) | Adapt | T4 from the save: `Pouch.<P>.EquipIndex[0]` into `Pouch.<P>.Content.*` [S28][S29]; Fuse = `Content.Combined.Name/Life` [S29]. Armor equip key UNKNOWN |
| Runes (`botw_runes`, `botw_world_runes`) | Drop | T5 Abilities page instead (owned flags from the save) |
| Champions (`botw_champions`) | Drop | Sage vows are a TotK extension |
| Round minimap from the native HUD transform (`botw_map_view`) | Adapt (T3+) | Needs a live camera/position reader |
| Full map: tiles as 120 world-space `overlays` with `src_bind` + `src_detail_bind` (BotW manifest `map` page), labels, regions, pins, saved icons, moving and death markers (`botw_map*`) | Adapt | T7: the same 120-overlay pattern; tile pictures are module images; one layer at a time. Labels, pins, stamps later |
| Item icons as `romfs:` sources in `sprite_map` (`romfs:/UI/StockItem/<id>.sbitemico#<id>`, 1278 entries) [S2 BotW manifest] | Adapt | TotK icons are zstd (fact 15): `romfs:/UI/Tex/Icon/<id>.bntx.zs` paths published by the module, decoded by the runtime with patch 0009 (T3a', §R2) |
| Koroks, trackers, DLC locations (`botw_map_koroks`, `botw_tracker_*`) | Drop (v1) | Tables generated from 1.6.0 data |
| Shrine compass, sensor (`botw_shrine_compass`, `botw_sensor`) | Adapt (later) | Shrines + lightroots |
| Quests (`botw_quests`) | Adapt (later) | |
| Inventory read (`botw_pouch`, `botw_inventory`) | Adapt | T6 from the save (T3s); live GameData after T3 (layout found on 1.4.3 [S28 §3]) |
| Inventory grid: `repeat` 420, 5 columns, `repeat_dx` 148, `repeat_row_dy` 160, `draggable`, `payload` `{i}`, `on_hold`, scroll region, `drop_action` [S2 BotW manifest `inventory` page] | Reuse pattern | T6 |
| Equip/eat/remove via guest helper (`botw_bridge`, `*_actions`, `native/helper/botw`) | Adapt (late) | T4-T7 wire the UI to module actions that are refused (fail closed). Later: guest `call` on Windows (fact 11), or the game's quick menu with readback [S28 §5 step H] |
| Hold-to-cook, recipes, heal advisor (`botw_hold*`, `botw_recipes*`, `botw_heal_advisor`) | Adapt (later) | Recipe data from the player's romfs |
| Travel (`botw_warp*`, `warp-ui-hook.S`) | Adapt (last) | Shrines, lightroots, Skyview towers |
| Text helpers (`botw_msbt_reflow`, `botw_text_measure`, `botw_detail_*`) | Reuse (later) | Game-independent. T3a decodes MSBT itself (`totk_msbt.h`, adapted from the runtime's `mod_msbt.cpp`) |
| zstd -> SARC -> BNTX -> RGBA (`wonder_assets.cpp`, Wonder module, not BotW) [S2] | Superseded | Patch 0009 + the runtime's decoders (T3a'); plan B only |
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
| 0 | Decisions: D1 host path = **C** (user, done); D2 target version = **1.2.0** (user, done); D2b = **stay on 1.2.0** (user, done); zstd vendoring = **approved** (user; now only for patch 0009's test); stb_truetype vendoring = **approved** (user); redesign defaults = **accepted** (user) | - | done |
| H1a | Eden Duo: native modules on Windows (loader, harness compile fix, tests), as patches | - | done: `host/eden-duo/0001-0003` + `host/eden-duo-companions/0001`; loader Catch2 tests pass standalone; full eden-duo build blocked (proxy 403 on CPM fmt download), real-Windows checks in H1c; critic SATISFIED round 3 |
| T1 | TotK package skeleton: build gate, fail-closed reader, tests; Linux `.so` and Windows DLL; `.dsmod.zip` | H1a (builder patch) | done: `2c482b6` (+ plan `911aed8`); gcc/clang/MinGW builds and ctest pass (MinGW under wine), single export, package + wine probe pass; critic SATISFIED round 3 |
| H1b | eden-cli composite layout: `--window-rect`, `--aux-rect`, borderless, aux not focusable | H1a | done: `afe952e`, patch 0004 adds `--window-rect`/`--aux-rect` (borderless, hidden until placed), non-focusable aux, 640x360 main floor, 32767 bound, ledger rows; parser tests pass natively and under wine; SDL and harness code compile-checked only in H1c; critic SATISFIED round 3 |
| T2a | Retarget the skeleton to 1.2.0 (`kTargetBuild`, data file name, tests) | T1 | done: `d175a18`; 1.2.0 build id and data file; critic SATISFIED (with T3s) |
| T3a'.1 | Eden Duo patch 0009 (zstd + `ZsDic` in the runtime's romfs reader, §T3a' A) + icon keys as `romfs:` paths (§T3a' B2) | T4 | done: 64a646e, critic SATISFIED round 1, Windows CI run 41379288233 green (198 cases); real romfs untested (T8a) |
| T3a'.2 | Item names from MSBT (B1) + the one-shot texture dump for T8a (B5) | T3a'.1 | doing |
| T3a'.3 | Game font through the module (`.bfotf` + stb_truetype, B4) | T3a'.1 (stb_truetype approved by the user) | todo |
| T3s | Save reader: newest `slot_0N`, `progress.sav` GameData by murmur3 hash, domain snapshots (status, gear, pouches, abilities, position); save extension export; module worker thread | T2a | done: `6edf1ef`, `f4b1527`, `b535fca`; two-read stability gate, unknowns published as absent, gives up after 8 reads; 3 toolchains + wine + TSan/ASan + fuzz pass; real saves untested (T8); critic SATISFIED round 3 |
| T4 | Shell + Gear page, tab bar, notice overlay, base extensions, manifest generator + checker | T3s | done: `3be5876`..`7e9ab94`; shell, tab bar, notice, base extensions + action table (refusals logged once), Gear page with 4 cards and 3 states, generator + checker with runtime-faithful visibility tests; critic SATISFIED round 3 (7 -> 1 -> 0 findings). Its look is superseded by T4v |
| T4r | Render tool `tools/totk/render/` moved from the critic's harness + fixtures (§T4v item 4) | T4 | todo |
| T4v | Restyle (the reference look): game font, TotK textures, header page tabs, home page = Gear + status right column + ability bar (inventory side framed, filled by T6) | T3a'.1, T4r (T8a names the textures; starts with the `UNVERIFIED` table) | todo |
| T6 | Inventory side of the home page: 8 category icon tabs, icon grid, detail pane with Equipped badge, hold and drag to a refused use | T4v | todo |
| T5 | Abilities page: 7 large tiles with the game's icons, owned/locked, battery, refused select | T4v | todo |
| T7 | Map page: Surface/Sky/Depths tiles as `romfs:` paths, the game's layer icons and player icon, pan/zoom | T4v, T3a' | todo |

Order after the redesign (user, 2026-11-02): **T3a' -> T4v -> T6 -> T5 -> T7** here; T8a as soon
as H1c's CI build with patch 0009 runs on the PC; T4v's `TEX` follow-up after T8a.

Needs the user, a Windows PC, the Thor or another repo:

| # | Packet | Depends | Status |
|---|---|---|---|
| H1c | Windows build of the patched fork (MSVC Ninja per [S1 docs/Build.md], `EDEN_DSMOD_BUILD_DEV_TOOLS=ON`), smoke run: TotK package installed, notice page in the aux window, `.shot` screenshots, a tap on the aux window keeps controller input | H1a, H1b, T1 | blocked: user (CI build in progress: `.github/workflows/eden-duo-windows.yml`, `5d72e9c`, `15c60e8`) |
| H2 | apollo2-vibe window-host (`claude/clever-clarke-k7hhhj`): verify the 1920x2160 window-only composite with no host code; H2b (`dualScreen=1` pin) only if a check fails | H1c, H3 | blocked: user |
| H3 | Vibe Window client (`/home/user/vibe-window` `app/`, plan branch): "Dual screen" setting, split renderer, bottom-screen touch | H1b | done: `2d21128`, global Dual screen setting in `app/`; stream split onto the Thor's second display through a `Presentation` using the decoder's transform matrix; pointer ids 0-4 top, 5-9 bottom (host touch max 10); setting-off path behind one `if`; `DualScreenLayoutTest` 13 tests pass + android-all type-check; Gradle, Robolectric and the Thor untested; critic SATISFIED round 4 |
| T2 | Pin the build: user's `xxd` output confirms or replaces T2a's `kTargetBuild`; data file renamed if needed; T1 tests rerun | T2a, dump | blocked: user |
| T8a | Texture dump on the PC: `totk dump` log lines (archive versions, texture list, layout panes, Mals members) and `imgdump` PNGs; turns the `UNVERIFIED` texture table into names | T3a', H1c | blocked: user |
| T8 | First real run on the PC (romfs paths, save keys, pictures, sample cost), then end-to-end on the Thor through apollo2-vibe | T4v-T7, T8a, H1c (H2, H3 for the Thor part) | blocked: user |
| T3 | Memory discovery on the user's build (Windows dev-tools eden-cli, gdbstub): live GameData object (replaces the save as source), player position + layer, current stamina, current ability, scene/menu state; sentinel test, second boot [S3 PORTING_A_GAME §2-3] | T2, H1c, D2b | blocked: user |
| T3L | Live 3D Link figure on the companion (user wants it later): hook TotK's pause-menu Link render to a second target, or `mirror` the menu while paused as a stopgap; deferred by the user until memory work | T3 | later (user) |
| T9 | Release: `windows-x86_64` module (plus `android-arm64-v8a` only if path A is wanted) | T8 | todo |
| T10+ | Extensions, one packet each: live readers behind the T4-T7 keys (T3), actions (guest call on Windows, quick-menu steering with readback, or load-plan helper), map reveal from Skyview Towers, stamps and pins, travel, compass, quests, recipes, item-name language | T9 | todo |

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

## Visual redesign: the reference look (user, 2026-11-02)

User feedback on T4's first render (built-in pixel font, flat boxes, coloured placeholder squares):
"i don't like how that looks. how do we get it to be more like this", with a photo of the Eden Duo
BotW companion on a Thor bottom screen [S36]: page tabs on top (Map / Inventory / Quests with L/R
hints), category tabs with icons, a grid of the game's item icons with a selected cell, a detail
pane (name, description, an "Equipped" badge), a right column with heart and stamina bars, stat
chips, equipped gear icons around a Link figure, and a bottom row of rune icons with counts; the
game's font and dark Sheikah-slate styling. Then: "rather than us design assets, is there a way we
can just leverage the existing totk asset files". So the redesign reads **TotK's own UI textures,
icons, map tiles and font from the player's romfs at runtime** and ships none of them; code-drawn
pictures only where no game texture exists (solid fills), declared in `ASSETS.provenance.json`.

### R1. Where the reference look comes from

What the BotW package does [S2 BotW `manifest.json`, `ASSETS.provenance.json`, README]:
| Piece | Source in BotW |
|---|---|
| Font | The game's BFFNT: `font` = `font_atlas` = `romfs:/Font/Font_US.sbfarc#Normal_00.bffnt`, decoded by the runtime |
| Item icons | `sprite_map`: item id -> `romfs:/UI/StockItem/<id>.sbitemico#<id>` |
| Frames, panels, gradients | 532 `romfs:` image sources, mostly `romfs:/Pack/Bootup.pack#Layout/Common.sblarc#timg/__Combined.bntx#<texture>^<x>`, plus `composites` that assemble several of those pieces into one picture (e.g. `state-native-frame`, 10 layers) |
| Text | `msbt` aliases (`Bootup_USen.pack#Message/...msbt`), `text_src` labels |
| Buttons, rings, gauges | 283 code-drawn PNGs in `assets/` (204 `file:` uses): command bars, stamina rings, gauge arcs, masks, 1x1 solid fills, each declared in `ASSETS.provenance.json` |
| Layout, cells, bars, highlight frames | Manifest widgets (`rect`, `image` with `fill`/`slice`/`tint`, `bar`, `value`, select/drag keys) |
| Link figure | **None.** No widget, key or asset in the BotW 1.0.0 package draws Link (searched the manifest for `Link`, `Player`, `figure`, `model`, `bfres`, `doll`: only horse and item names). The photo's figure is not in the clone `383d2d47` (`UNVERIFIED` where it comes from) |

Rendered with the critic's harness and placeholder images (BotW's ready and inventory pages,
gates opened so every widget shows; scratch `planner-render/botw-*.png`), BotW looks as bare as
T4: flat dark rects, the built-in font and same-coloured blocks wherever a game texture goes. Its
look is the game's textures and font, not widget styling. The runtime offers only filled rects
with a frame, capsule buttons (`pill`), bars and images; no rounded rects or gradients. Images can
be 9-sliced (`fill: slice`), tiled, mirrored (`flip_x/y`), cropped (`src_rect`), tinted (`tint`,
`tint_bind`), faded and composited [S1 `mod_types_page.h:162-178,315-317,509-520`]. Single-channel
BNTX masks keep their channel map, so a white mask tints to any colour [S1
`mod_nx_assets.cpp:469,527-534`].

### R2. TotK 1.2.0: game font, icons and UI textures

Facts:
- **UI textures** live in `UI/LayoutArchive/Common.Product.<n>.Nin_NX_NVN.blarc.zs`: zstd (with a
  `ZsDic` dictionary) around a SARC holding `blyt/<Layout>.bflyt`, `anim/*.bflan` and
  `timg/__Combined.bntx` [S33][S32 `make_button_icons.py:410-413`]. `n` is 100, 110 or 140 by game
  version; 1.2.1 ships only `Common.Product.110` [S32 `docs/v1.2.1-port.md:39`]; the 1.2.0 value
  is `UNVERIFIED` (expected 110; T8a). Layout names known from public layout edits:
  `PauseMenuBG_00`, `PauseMenuOverlay_00`, `AppMenuBG_00`, `AppMenuOverlay_00`, `AppMap_00`,
  `AppAlbum_00`, `PlayerStatus_00`, `ShortCutBG_00`, `SystemActionGuide_00`, `PaCommonBtnTitle_00`,
  `PaRecipeCardList_00` [S32 `ultrawide_layout.py:20-81`][S34 `ui_aspect.c:108-122`]. Texture names
  known: the button keys `Nt_KeyTexA_00`, `Nt_KeyTexB_00`, `Nt_KeyTexX_00`, `Nt_KeyTexY_00`,
  `Nt_KeyTexL_00`, `Nt_KeyTexZL_00`, `Nt_KeyTexLSt_00/01` (R8 masks, 72x72 up to 1.2.1) [S32
  `make_button_icons.py:414-418`]; map pins are the textures starting `MapIcon` [S33]. **No public
  source lists the pause-menu, category-tab, heart, stamina, rupee, battery or ability textures**:
  their names are `UNKNOWN: T8a dump`. BNTX texture names carry a `^<x>` suffix and the runtime
  matches the full name [S1 `mod_nx_assets.cpp:241-250`; S2 BotW `...#Logo2D_00^d`].
- **Item icons**: `UI/Tex/Icon/<item id>.bntx.zs`, 256x256 ASTC 4x4 sRGB [S28 §2] (1.4.3).
- **Font**: `Font/Font.Nin_NX_NVN.bfarc.zs` (other regions `Font_KRko`, `Font_CNzh`, `Font_TWzh`),
  a zstd SARC of `scft/*.bfotf` faces: text faces `scft/nintendo_NTLG-DB_DH_002.bfotf` and
  `scft/ninP_RodinNTLG-B_DH_003_subset.bfotf`, button pictograms `scft/External.bfotf`
  (U+E040-U+E068). A `.bfotf` is an OpenType (CFF) font with an 8-byte header, XOR-ed per word with
  a key: word 0 XOR `0x18029a7f` gives the key, word 1 XOR the key is the big-endian size. The
  archive is byte-identical in 1.0.0 and 1.2.1 [S32 `make_button_icons.py:4-12,32-33,72-74`], so
  1.2.0 almost surely has it too (`UNVERIFIED`, T8a). Which text face the pause menu uses is
  `UNVERIFIED`; v1 uses NTLG-DB.
- The runtime decodes BFFNT, MFNT and a few others, not OpenType [S1 `mod_assets.cpp:351-430`,
  `mod_nx_runtime.cpp:1027-1037`]. A module can supply the font: the font extension's
  `decode_font` gets the bytes of the manifest's `font` source and reports glyphs; the atlas is any
  image source, e.g. `module:` through `load_image` [S1 `dsmod_module_extensions.h:100-140`]. ACNH
  does exactly this for its `.bfttf`/`.bfotf` faces with stb_truetype (`acnh_font.cpp`, 700 lines,
  GPL-3.0; `font: file:acnh_font.txt`, `font_atlas: module:acnh/font/ui:40?p={p}`) [S2]. Glyph
  size: a label's cap height is `text_scale * 5` px, scaled from the atlas's line height [S1
  `mod_ui_text.cpp:274-281`].
- The runtime's romfs reader resolves SARC members, Yaz0, BFRES, BNTX (R8, BC1-BC7, ASTC, with
  channel maps) and BFFNT inside `NxAssetState::Locate`, but not zstd [S1
  `mod_nx_runtime.cpp:657-760`, `mod_nx_assets.cpp:83-125`]. Eden Duo already links zstd into
  core (`target_link_libraries(core PUBLIC ... zstd::zstd)`) [S1 `src/core/CMakeLists.txt:1225`].
  A module's `read_romfs` with a `#member` path goes through the same `Locate` [S1
  `mod_module_host.cpp:406-445`, `mod_nx_runtime.cpp:1750-1774`].

**Decision (planner): textures through a runtime patch, the font through the module.**
- *Font*: must be the module either way. A runtime zstd patch would still hand the runtime a CFF
  font it cannot rasterize. The module reads the `.bfotf`, removes the XOR, rasterizes with
  stb_truetype into one atlas, and answers `decode_font` + `load_image` (ACNH's pattern).
- *Textures, icons, map tiles, MSBT*: **patch 0009** adds zstd + `ZsDic` to `Locate` (next to its
  Yaz0 branch; about 100 lines with a pure helper) and lets a `.zs` file count as an NX source.
  Then the manifest names TotK textures exactly as BotW names its own (`romfs:/UI/LayoutArchive/
  Common.Product.110.Nin_NX_NVN.blarc.zs#timg/__Combined.bntx#<name>`), with composites, 9-slice,
  tint, preload and the runtime's caches, and every BNTX format the game uses is decoded by code
  that already exists. The module-only alternative (old T3a) needs vendored zstd, SARC, BNTX
  deswizzle, host ASTC **and** new BC1/BC3/BC4/BC5/BC7 and R8 decoders in the module (layout
  textures are BC and R8, not only ASTC [S32 `make_button_icons.py:411`][S28 §2 format list]),
  cannot feed composites, and duplicates what the runtime has. The patch is smaller and decodes
  every format correctly. Cost: the art needs the patched eden-cli, which path C already requires
  (patches 0001-0008, CI applies `000*.patch`); an unpatched runtime shows the pages without game
  art (fallbacks only, no notice: cut). If path A (stock Android Eden Duo) is ever
  wanted without the patch, old T3a's module pipeline is plan B behind the same published keys.
- *Item names and captions*: still published by the module (names change with the save, fact 16);
  the module reads `PouchContent.msbt` with `read_romfs("romfs:Mals/USen.Product.<n>.sarc.zs#
  ActorMsg/PouchContent.msbt")`, which patch 0009 decompresses, so the module needs no zstd.

**Naming textures reliably.** Names come from the game, not guesses: T8a's one-shot dump logs the
`Common.Product.<n>` archive found, its member list, every `__Combined.bntx` texture (name with
suffix, size, format, channel map) and, for the layouts whose names start `Pause`, `Pouch`, `App`,
`PlayerStatus`, `ShortCut`, `Map`, every picture and window pane (name, translate, size, material
texture names, material white/black colours). The generator keeps one table `TEX` (role -> exact
texture name) marked `UNVERIFIED` until T8a fills it. The pane list says which texture a game
screen uses where (e.g. the pouch cell, the cursor, the heart pieces), and the material colours
give the tint for R8/BC4 masks.
**BFLYT positions** are worth copying as reference values only: proportions (cell pitch, insets,
icon-to-cell ratio) scaled from the game's 1920x1080 grid to 1240x1080. No layout file is shipped
or parsed at runtime for placement; numbers go into the generator by hand with a comment naming the
layout and pane.
**Without the module dump**, the user can list names on the PC from `eden-cli --dump-romfs`, then
`zstd -d -D zs.zsdic` on the `.blarc.zs` and Switch Toolbox on `timg/__Combined.bntx` [S33]; the
module dump is preferred (one run, exact 1.2.0 files, also gives pane rects and colours).

### R3. The Link figure

Not achievable as in the photo. BotW's package has no figure (R1), and TotK's pause menu draws
Link as a live 3D model (BFRES with skeleton, skinning and the equipped armor); the runtime only
extracts textures from BFRES [S1 `mod_nx_runtime.cpp:735-750`, `mod_bfres.h`] and renders no
models. Rendering it is **out of scope**. Fallback in T4v: the right column's centre plate shows
the equipped weapon's icon at 256 px (its native size) with the fused material's badge and name,
inside a panel built from the pause menu's own textures. If T8a's texture list has a 2D Link
picture (any name containing `Link` or `Player` at 256 px or more), the plate uses it instead
(`UNKNOWN: T8a`). Armor icons around the plate (the photo's left column) come back when an armor
equip key is verified (T8).

### R4. Reusing BotW's code-drawn assets

Allowed: BotW's `assets/*.png` are GPL-3.0 [S2 LICENSE] and so is this package; a copy keeps the
licence, gets an `ASSETS.provenance.json` entry naming the source file, the companions commit
`383d2d47` and "Copyright 2026 Eden DSMod fork / igawa6, GPL-3.0", and the README's credits line.
Used: **none**. They are BotW-styled (Sheikah-blue command bars, BotW stamina rings, BotW gauge
arcs) and the user asked for TotK's own art. Our only code-drawn pictures stay generated by
`gen_manifest.py`: `map-blank.png` (1x1 transparent) and, if a fill needs a picture, 1x1 solids
(BotW's `solid-*.png` idea, our own bytes).

### R5. Layout spec (canvas 1240x1080; all packets T4v-T7)

**Pages.** Three, switched by header tabs in the photo's order: **Map | Inventory | Abilities**.
Inventory is the home page and holds Gear and status (right column) and the ability bar (bottom),
like the photo; the old Gear page becomes that right column. `totk.ui.page`: **0 home, 1 abilities,
2 map** (was 0 gear, 1 abilities, 2 inventory, 3 map; `kActionArgs[kActPage]` becomes 3). The
module publishes the inventory tab's lists on page 0 and the map tiles on page 2.

**Tokens** (fallback colours drawn by widgets under or instead of game textures; masks are tinted
with the BFLYT material colours from T8a, these values until then):
| Token | ARGB | Use |
|---|---|---|
| `bg` | `FF0A0E0D` | manifest background |
| `panel` | `E6121815` | panels under textures |
| `edge` | `FF3E4A36` | 2 px panel frames (olive) |
| `text` | `FFECE5C8` | cream text |
| `dim` | `FF8F907F` | captions, inactive tabs |
| `value` | `FFE9C46A` | numbers |
| `select` | `FF6FF0C0` | selection and active tab (Zonai green) |
| `heart` | `FFE0464B` | heart bar |
| `stamina` | `FF74D14C` | stamina bar |
| `locked` | `66FFFFFF` | tint for not-owned ability icons |
Colour choices follow S28's TotK look notes (near-black panels, olive edges, cream text, gold
values, Zonai green selection) [S28 BOTTOM_SCREEN "Look"]; exact values `UNVERIFIED` until T8a.

**Font.** The game font (T3a'): one atlas at cap 48 px. Text scales: 4 (20 px) captions and counts,
5 (25 px) body, 6 (30 px) names and tab labels, 8 (40 px) big values. `L`/`R` hints are the
External pictograms U+E044/U+E045 in the same atlas.

**Every page.**
- Header `[0,0,1240,76]` `panel` + TotK header texture `TEX.header` (9-slice; `UNKNOWN: T8a`, from
  `PauseMenuBG_00`/`AppMenuBG_00`). Tabs: Map `[140,8,300,60]`, Inventory `[470,8,300,60]`,
  Abilities `[800,8,300,60]`, each `button` (module `totk.ui.page` k), label scale 6 centred; the
  current one: `TEX.tab_on` (or a `select` 3 px capsule frame) and `text`, others `dim`. `L` label
  `[40,14,48,48]` and `R` `[1152,14,48,48]` (U+E044/U+E045, scale 8): taps go to the left and right
  neighbour page (static per page; no new action); `on_swipe_left`/`right` on the header do the same.
- Source line `[8,1052,600,24]` scale 4 `dim` ("From last save" / "Live"), as T4.
- *(Art notice cut, see §T3a' cut list.)*
- T1 notice overlay as T4 (full canvas, `input_block`).

**Home page** (`home`; T4v draws the right column, the bottom bar and the left side's frames and
empty state; T6 fills the left side).
Left side, inventory (x 0-632):
- Category tabs `[8,84,624,72]`: 8 `button`s 78x72 at x = 8 + 78k (game order, as the data model):
  icon `[x+11,92,56,56]` `TEX.cat_<k>` (`UNKNOWN: T8a`; fallback a scale 4 short label WPN BOW SHD
  ARM MAT FOOD ZON KEY); current tab: underline `rect [x+8,152,62,4]` `select` and full tint, others
  tint `B3FFFFFF`; count `value` `totk.inv.tab{k}.count` scale 4 at `[x+70,90,0,20]` right-aligned.
  Selected category name `[16,162,400,28]` scale 5 `text`.
- Grid: scroll region `inv_grid [8,194,624,520]`, `cols 5`, `row_h 124`, `count_bind
  totk.inv.count`, `reset_bind totk.inv.tab`. Cell `[12+124c,198+124r,116,116]`: slot texture
  `TEX.slot` (9-slice; fallback `panel` + `edge` frame), icon `[x+4,y+4,108,108]` `src_bind
  totk.inv.{i}.icon`, count/durability `value` on a shade `[x+56,y+88,56,24]` scale 4 `value`,
  equipped mark `TEX.equipped` `[x+92,y+4,20,20]` (fallback 14 px `select` square) `need_bind
  totk.inv.{i}.equipped`, fuse badge `[x+4,y+4,40,40]` `src_bind .fuse_icon`. `select_group inv`,
  `payload "{i}"`, `draggable`, `on_tap inv_select`, `highlight_src TEX.cursor` (fallback
  `highlight_color select`, `frame 4`). `on_swipe_left` -> `inv_tab_next`.
- Detail pane `[8,722,624,206]` (`TEX.detail` 9-slice or `panel`+`edge`): icon `[20,736,128,128]`
  `src_bind totk.inv.sel.icon` on `TEX.slot`, `on_hold inv_use` (refused); name `[164,738,340,34]`
  scale 6 `text`, `max_lines 1`, `fit_text`; Equipped badge `[512,738,108,32]` (`TEX.badge` or a
  `select` capsule, "Equipped" scale 4) `need_bind totk.inv.sel.equipped`; value line
  `[164,778,440,26]` scale 5 `value` ("Durability 42" / "x17" / hearts for food by `.kind`); fuse
  and effect `[164,808,440,24]` scale 4 `dim`; caption `[164,836,456,84]` scale 4 `text`, `wrap_width
  456`, `max_lines 3`. The pane is the drop zone: `accept_group inv`, `drop_action inv_use`
  (refused).
- Empty states in the grid area: "No save data yet" (`!totk.inv.valid`), "Nothing here" (count 0).

Right column, Gear and status (x 640-1232):
- Status panel `[640,84,592,120]`: heart icon `[656,96,44,44]` `TEX.heart` (`UNKNOWN: T8a`, from
  `PlayerStatus_00`; fallback a `heart` rect 28x28); `bar [712,104,360,22]` `bind
  totk.status.hearts`, `max_bind totk.status.max_hearts`, colour `heart`, `fill_image
  TEX.bar` if T8a finds one; `value [1220,98,0,32]` right-aligned scale 5 "h / max" (`max_sep "/"`).
  Stamina icon `[656,150,44,44]` `TEX.stamina`; `bar [712,158,360,22]` bind and `max_bind`
  `totk.status.max_stamina` (the save has no current stamina: the bar is full) colour `stamina`;
  `value [1220,152,0,32]` "Max <n>". Units as published (hearts `UNVERIFIED`, T8).
- Stat chips `[640,212,592,64]`, three of 192 x 64 at x 640, 840, 1040: Rupees (`TEX.rupee`
  icon 40 px, `value totk.status.rupees` `group`), Battery (`TEX.battery`, `totk.status.max_battery`
  `div 100`, `UNVERIFIED` unit), Arrows (`src_bind totk.gear.arrow.icon`, `totk.gear.arrow.count`).
  Icons fall back to a scale 4 caption.
- Equipment panel `[640,284,592,644]` (`TEX.equip_panel` or `panel`+`edge`):
  - Slot cards x 1092, y 300 / 470 / 640, each 128x160: caption scale 4 `dim` (WEAPON / BOW /
    SHIELD) `[1156,300,0,22]` centred; slot `[1100,324,112,112]` `TEX.slot` + icon `src_bind
    totk.gear.<s>.icon`; fuse badge `[1172,324,40,40]` `need_bind .fuse`; durability `value .life`
    `[1156,440,0,24]` centred scale 4 `value`. Three states from `.present` exactly as T4 (1 item, 0
    "None", absent nothing). `on_hold` -> `gear_swap_k` (refused).
  - Centre plate `[800,300,272,612]`: `TEX.plate` (`UNKNOWN`), the equipped weapon icon
    `[808,420,256,256]`, its fuse badge `[1000,420,72,72]`, name `[936,700,0,60]` centred scale 5
    `wrap_width 256 max_lines 2`, effect `[936,768,0,26]` scale 4 `value` (R3: no 3D Link).
  - Left column x 652: the arrows card is in the stat chips, so the column shows the bow's and the
    shield's effects as two lines `[652,300,140,...]` scale 4 `value` while present; the armor cards
    (head, upper, lower) are reserved here for the packet that verifies an armor equip key.
- Ability bar `[0,940,1240,108]` (`TEX.bar_bg` or `panel`): 7 slots 172 wide at x = 18 + 172k:
  icon `[x+54,946,64,64]` `TEX.ability_<a>` (`UNKNOWN: T8a`; first candidate
  `romfs:/UI/Tex/Icon/Obj_<X>.bntx.zs` with the save's `Obj_X` names, fact data model), drawn twice:
  full tint `need_bind totk.ability.<a>.owned`, `locked` tint `hide_bind` the same; short name
  `[x+86,1014,0,22]` centred scale 4 (`text` / `dim`); `on_tap ability_select_k` (refused). No
  current-ability highlight (live only).

**Abilities page** (`abilities`, T5): tiles 290x400, row 1 at y 92 x = 16 + 306k (k 0-3: Ultrahand,
Fuse, Ascend, Recall), row 2 at y 508 x = 169 + 306k (Autobuild, Camera, Travel Medallion). Each:
`TEX.tile` 9-slice (or `panel`+`edge`), icon `[x+65,y+40,160,160]` (owned / `locked` tint as the
bar), name `[x+145,y+228,0,34]` centred scale 6, "Locked" scale 4 `dim` when not owned, `on_tap`
refused. Battery strip `[16,924,1208,72]`: `TEX.battery` icon, `value totk.status.max_battery`,
"Current ability: needs a live reader" scale 4 `dim`.

**Map page** (`map`, T7): `map` widget `[0,76,1240,972]` as old T7 (area, overlays, pan/zoom);
`marker_src TEX.player_pin` (a `MapIcon...` texture, `UNKNOWN: T8a`; fallback the runtime pin);
layer pills on the right as in the game's map, top to bottom Sky, Surface, Depths at
`[1100,96+80j,124,64]` with `TEX.layer_<L>` icons (`UNKNOWN`, from `AppMap_00`; fallback scale 5
labels), current one `select` frame; Centre `button [1100,968,124,64]` `view_reset`; position
`[16,1052,...]` replaces the source line text: "Last save x <x>  z <z>" scale 4 `dim`.

**Interactions** (all pages): header tap, L/R tap, header swipe; category tap; grid tap selects,
swipe left = next category, hold on an item or the detail icon = refused use, drag an item onto the
detail pane = refused use; hold a gear card = refused swap; tap an ability = refused; map pan,
pinch, layer pills, Centre. Haptics as T4 (`tap click`, `drop confirm`, `refused reject`).

**Asset list** (all read at runtime; none shipped). `C` = `romfs:/UI/LayoutArchive/Common.Product.
<n>.Nin_NX_NVN.blarc.zs#timg/__Combined.bntx#` (n `UNVERIFIED`, 110 expected):
| Role (`TEX.*`) | Source | Status |
|---|---|---|
| font | `Font/Font.Nin_NX_NVN.bfarc.zs#scft/nintendo_NTLG-DB_DH_002.bfotf` + `#scft/External.bfotf` via `module:totk:font/body:48` | path known [S32]; 1.2.0 `UNVERIFIED` |
| item, fuse, arrow icons | `romfs:/UI/Tex/Icon/<id>.bntx.zs` (module publishes the path) | known on 1.4.3 [S28] |
| map tiles | `romfs:/UI/Map/MainField/<L>_<cc>-<rr>_<lv><suffix>.bntx.zs` (module probes and publishes) | known on 1.4.3 [S28] |
| L/R hints | External font U+E044/U+E045 (atlas) | [S32] |
| header, tab_on, slot, cursor, detail, badge, equipped, equip_panel, plate, bar, bar_bg, tile | `C<name>` from the `PauseMenuBG_00`/`AppMenuBG_00`/pouch layouts | `UNKNOWN: T8a` |
| cat_0..7 (pouch category icons) | `C<name>` | `UNKNOWN: T8a` |
| heart, stamina, battery, rupee | `C<name>` from `PlayerStatus_00` | `UNKNOWN: T8a` |
| ability_<a> | `romfs:/UI/Tex/Icon/Obj_<X>.bntx.zs` or `C<name>` | `UNKNOWN: T8a` |
| player_pin, layer_<L> | `C MapIcon...` / `AppMap_00` textures | prefix known [S33]; names `UNKNOWN: T8a` |
| solids, map-blank | generated by `gen_manifest.py` | ours |
Every game texture sits on a widget-drawn fallback (rect, frame or label) so a missing texture
leaves a readable page; the generator emits both from one helper.

## Companion pages without memory offsets (T2a-T8)

User direction (2026-11-01): "don't worry about memory locations right now. do the maps, gear,
abilities and inventory." So T4-T7 are built from two sources that need no offset:
1. **The last save** (`progress.sav`), read through the runtime's read-only save extension
   (fact 14, T3s). Every page says "From last save" while this is the source.
2. **The player's romfs** for item names, captions, icons and map tiles (fact 15, T3a). Nothing
   from the game is shipped.
Live values (current hearts and stamina, the equipped item right now, Link's position, the
current ability) come from **live reader functions** with `UNKNOWN` offsets (0) that return
"invalid" without any host call, as T1's `ReadStatus` does. The module prefers a valid live value
over the save; with every offset 0 it always uses the save. T3 later fills the offsets behind the
same published keys, so no page changes.

### Shared rules

- **Files.** One translation unit plus headers, as T1: `0100F2C0115B6000.cpp` includes
  `totk_*.h`; each header has a `totk_*_test.cpp`. The CMake glob already builds and registers
  every `totk_*_test.cpp` (`native/CMakeLists.txt`).
- **Threads.** One module worker `std::thread`, started in `create`, stopped (flag +
  `std::condition_variable`) and joined in `destroy`; it loads the dictionaries and the name table,
  then polls the save every 2 s. Precedent: the SMO module runs its own `std::thread` with a copy
  of the host struct [S2 `native/modules/0100000000010000.cpp:155-180`]. `read_romfs` already runs
  on two threads (asset worker and tick) [S1 `dsmod_module_extensions.h:80-87`]; the save API
  locks its own mutex [S1 `mod_module_services.cpp:227`]. Calling them from a module thread is
  `UNVERIFIED` beyond that precedent; T8 watches for it.
- **`sample` never blocks or allocates.** It copies one `std::shared_ptr<const Snapshot>` under a
  mutex held only for the copy, and publishes from prebuilt key strings. The project's rules ask for cheap
  `sample` calls and lazy publishing [S3 MODULE_GUIDE §6]; this plan's Risks already require no
  allocation, file I/O or per-frame logging. The snapshot
  is cleared every tick, so every value is published every `sample` [S3 MODULE_GUIDE §1.2].
- **Lazy publishing** [S3 MODULE_GUIDE §7.4]: the tab bar sends module action `totk.ui.page`, the
  module publishes `totk.ui.page`, and four `page_binds` follow it. Heavy lists (one inventory
  tab, the map's tile keys) are published only while their page is open. From T4v there are three
  pages and three `page_binds`.
- **Fail closed per domain.** Each domain has `totk.<domain>.valid`; a page shows its empty state
  ("No save data yet" / "Not in this save") when it is 0 or missing. Pictures that fail to load
  show nothing (`load_image` returns false; the host retries 4 times, then gives up
  [S3 MODULE_GUIDE §1.4]).
- **No game data in the repo.** Only generated JSON, generated PNGs (1x1 transparent, 1x1
  solids), `totk_font.txt` and the vendored `stb_truetype.h`. Tests build every SARC, BNTX, BFLYT,
  MSBT, zstd frame and save they read in memory; font tests use a system TTF. Texture names and
  numbers copied from the T8a dump are names and values, not game data.

### Preview mode (decided: no)

No package in the companions repo has a demo or sample-state mode (searched `preview`, `demo`,
`sample state`, `fixture` across `docs/`, `tools/`, `packages/`, `native/` [S2]). With the save
reader (T3s) the pages show the user's real data without any offset, and a canned state on the
Thor could be mistaken for the game. The tests already drive every key with synthetic saves. Not
worth the code. Revisit only if T8 shows the save cannot be read on the user's setup.

### Actions (all packets): UI wired, game actions refused

Every action in the manifest is `page`, `view_reset` or `module` (checked by CA7). The module's
`on_action` (base extension [S1 `dsmod_module_extensions.h:75-79`]) handles two kinds:

| Action | Argument | Result |
|---|---|---|
| `totk.ui.page` | 0 home (inventory + gear + status), 1 abilities, 2 map (from T4v; T4 used 0 gear, 1 abilities, 2 inventory, 3 map) | Accepted; publishes `totk.ui.page` |
| `totk.inv.tab` | 0-7 | Accepted; selection resets to 0 |
| `totk.inv.tab_next` | ignored | Accepted; wraps 7 -> 0 (swipe left) |
| `totk.inv.select` | `$payload` = slot index | Accepted if `< totk.inv.count` |
| `totk.map.layer` | 0 surface, 1 sky, 2 depths | Accepted |
| `totk.gear.swap` | 0-3 (weapon, bow, shield, arrow) | **Refused** |
| `totk.inv.use` | ignored (uses the selection: equip for gear tabs, eat for food, hold for materials) | **Refused** |
| `totk.ability.select` | 0-6 | **Refused** |

UI actions touch only module state. Refused actions return false, so the runtime plays the
`refused` haptic and nothing after them runs [S3 PACKAGE_FORMAT §3.13]; the module logs one line
per action name per session ("totk: <action> disabled: no verified offsets"). An argument out of
range is refused too. No refused action reads or writes guest memory or queues a guest call. Later
packets (T10+) turn them on only after T3 finds offsets and with readback (equip through the
game's quick menu worked on 1.4.3 [S28 §5 step H]).

### Data model (published keys)

Text values are `publish_text`, numbers `publish_i64`, positions `publish_f64`. `<s>` = `weapon`,
`bow`, `shield`, `arrow`, `head`, `upper`, `lower`; `<a>` = `ultrahand`, `fuse`, `ascend`,
`recall`, `autobuild`, `camera`, `travel`; `{i}` = 0..`totk.inv.count`-1. A key is absent when not
known; the manifest gates on `.valid` and `need_bind`.

| Key | Type | Source (save key [S29] unless noted) |
|---|---|---|
| `totk.ready` | int 1 | Module loaded (T1) |
| `totk.ui.page`, `totk.inv.tab`, `totk.inv.sel`, `totk.map.layer` | int | Module UI state (actions above) |
| `totk.names.valid` | int | 1 once the PouchContent name table loaded (T3a) |
| `totk.status.valid`, `.source` | int | 1 valid; source 1 save, 2 live (T1 key kept) |
| `totk.status.hearts`, `.max_hearts`, `.max_stamina`, `.rupees`, `.max_battery` | int | `PlayerStatus.Life`, `.MaxLife`, `.MaxStamina`, `.CurrentRupee`, `.MaxEnergy` (float, published rounded x100). Units as the save stores them, `UNVERIFIED` (T8 compares with the game's HUD) |
| `totk.status.stamina` | int | Live only (T1 `ReadStatus`, offsets UNKNOWN); not in the save list |
| `totk.gear.valid`, `.source` | int | |
| `totk.gear.<s>.present` | int | 1 if equipped. weapon/bow/shield/arrow: `Pouch.<P>.EquipIndex[0]` ≥ 0 [S28 `bottom_inventory.c:39,123-124`; S29 `zelda-totk.js:468`]. head/upper/lower: `UNKNOWN` save key (candidate `Pouch.Armor.EquipIndex`, `UNVERIFIED`): published only if found |
| `totk.gear.<s>.id`, `.name`, `.icon` | text | `Pouch.<P>.Content.Name[idx]`; name from T3a' (falls back to the id); icon = `romfs:/UI/Tex/Icon/<id>.bntx.zs` from T3a' (T4: `module:totk:icon/<id>`) |
| `totk.gear.<s>.life` | int | `Content.Life[idx]` (weapon, bow, shield) |
| `totk.gear.arrow.count` | int | `Pouch.Arrow.Content.StockNum[idx]` |
| `totk.gear.<s>.fuse`, `.fuse_name`, `.fuse_icon` | int, text, text | `Content.Combined.Name[idx]` non-empty (weapon, shield) |
| `totk.gear.<s>.effect` | text | `Content.Effect.Type[idx]` enum hash -> short English label from the enum names in [S29 `pouch.js:457,468,475`]; empty for `None` |
| `totk.ability.valid`, `.source` | int | |
| `totk.ability.<a>.owned` | int | `IsGet.Obj_X` and `IsGetAnyway.Obj_X` both true [S29 `zelda-totk.js:1686-1689`], X = `UltraHand`, `OneTouchBond`, `Tooreroof`, `ReverseRecorder`, `AutoBuilder`, `Camera`, `Warp` [S29 `zelda-totk.class.item.js:168-175`, `zelda-totk.js:491-504`] |
| `totk.ability.<a>.name` | text | `Obj_X_Name` in PouchContent if present (`UNVERIFIED`), else not published (manifest shows its own short label) |
| `totk.ability.current` | int | Live only, offset UNKNOWN: never published in T5 |
| `totk.inv.valid`, `.source`, `.count` | int | Current tab only |
| `totk.inv.tab{k}.count` | int | k = 0-7, for the tab chips |
| `totk.inv.{i}.icon`, `.name` | text | Item `i` of the current tab |
| `totk.inv.sel.equipped` | int | The selected slot equals its pouch's `EquipIndex[0]` (T4v; the detail pane's Equipped badge) |
| `totk.inv.{i}.value`, `.kind` | int | kind 1 durability (`Life`), 2 count (`StockNum`), 0 none (armor) |
| `totk.inv.{i}.equipped` | int | Slot equals that pouch's `EquipIndex[0]` |
| `totk.inv.{i}.fuse_icon` | text | Fused material's icon |
| `totk.inv.sel.icon`, `.name`, `.caption`, `.fuse_name`, `.effect` | text | Selected item; caption = `<id>_Caption` [S28 §4] |
| `totk.inv.sel.value`, `.kind`, `.hearts` | int | `.hearts` = food `LifeRecover` [S29 `pouch.js:502`] |
| `totk.map.valid`, `.source` | int | |
| `totk.map.x`, `totk.map.y` | f64 | `PlayerStatus.SavePos` x and -z (the map widget's y points up [S3 PACKAGE_FORMAT §3.3 `@map_view_*`]; TotK z points south [S28 §1]) |
| `totk.map.tile.{n}`, `totk.map.tile.{n}.d` | text | n = 0..119 (n = r*12 + c): `romfs:/UI/Map/MainField/<L>_<cc>-<rr>_02<suffix>.bntx.zs` and the `_01` file for the selected layer (T7) |

Tabs in game order (user's list): 0 Weapons (`Pouch.Weapon`), 1 Bows (`Pouch.Bow`, then
`Pouch.Arrow` appended: `UNVERIFIED` that the game shows arrows there), 2 Shields, 3 Armor,
4 Materials, 5 Food, 6 Zonai Devices (`Pouch.SpecialParts`), 7 Key Items (`Pouch.KeyItem`)
[S29 `pouch.js:454-518`]. Items keep save array order; empty names are skipped [S28 §3]. At most
400 items per tab (`kMaxItems`; the publish count is bounded).

### Common acceptance (every packet T2a-T7; critic reruns all)

`$E`, `$C`, `$CP` as in T1.
- CA1. The three builds of T1 acceptance 1-2 (gcc, clang, MinGW toolchain file) exit 0.
- CA2. `ctest --output-on-failure` in all three build dirs: 100 % pass (`build/win` under wine with
  `LC_ALL=C.UTF-8 WINEDEBUG=-all`). Test count grows with each packet.
- CA3. Exports: `nm -D --defined-only build/gcc/0100F2C0115B6000.so` prints exactly the module's
  getters (T2a: `eden_dsmod_get_module`; from T3s also `eden_dsmod_get_save_extensions`; from T4
  also `eden_dsmod_get_extensions`; from T3a' also `eden_dsmod_get_font_extensions`); `x86_64-w64-mingw32-objdump -p` of the DLL exports the same
  names and imports no `libstdc++`/`libgcc`/`libwinpthread`/`libzstd` DLL.
- CA4. From T4: `python3 -I tools/totk/gen_manifest.py --check` exits 0 (the committed
  `manifest.json`, `assets/map-blank.png` and `ASSETS.provenance.json` equal the generator's
  output) and `python3 -I tools/totk/check_manifest.py --eden $E` exits 0 (checks in T4).
- CA5. Package: T1 acceptance 6 with `--build-id <kTargetBuild>`; `unzip -l` lists exactly
  `package.json`, `dualscreen/manifest.json`, `dualscreen/6F32C68DD3BC7D77.json`, the two
  modules, and from T4 `dualscreen/ASSETS.provenance.json` and `dualscreen/assets/map-blank.png`,
  from T3a' `dualscreen/totk_font.txt`.
- CA6. Wine probe (extend T1's): `LoadLibraryExW` of the packaged `windows-x86_64` module, then
  each exported getter with the right version and hash returns a table whose `version`,
  `struct_size` and `abi_hash` match the SDK headers; a wrong hash returns null.
- CA7. Fail closed: (a) `grep -nE "write_memory|queue_guest_call|write_batch"` over
  `native/modules/*.{h,cpp}` minus `*_test.cpp` and `totk_test_host.h` finds no call (only struct
  field names in comments are allowed, none expected); (b) the manifest has no action with kind
  `write`, `slot_write`, `call`, `sequence` or `button`; (c) `totk_module_test` runs a full
  fixture (save + romfs + every action in the table above, valid and out-of-range arguments) and
  asserts `write_calls == 0`, `read_calls == 0` and `is_mapped_calls == 0` with default offsets;
  (d) every refused action returns false.
- CA8. Every field in `totk_offsets.h` is `0` with an `UNKNOWN:` comment (T1 acceptance 9).
- CA9. `git status --porcelain` shows no build output; `packages/` holds only generated files;
  no file over 64 KiB is added except `native/third_party/stb_truetype.h` (T3a'); no zstd source
  in the repo (patch 0009's test fetches it into its build dir).
- CA10. `totk_module_test` counts `operator new` (as it already does) and asserts zero allocations
  across 1000 `sample` calls on a full fixture snapshot with each page selected.

Not runnable here (say so in every report): eden-cli with the package, real romfs, real saves,
the Thor. Those are T8.

### T2a (coder, first): retarget to 1.2.0

Files: `native/modules/totk_build.h` (`kTargetBuild` =
`6F32C68DD3BC7D77AA714B80E92A096A737CDA77000000000000000000000000`, comment: 1.2.0, sources [S6
`TkPatch.cs:16`][S5], zero tail `UNVERIFIED` until T2), `totk_build_test.cpp` (the 1.2.1 id is now
a "false" case next to 1.4.3 and BotW), `totk_offsets.h` (each lead comment says "1.2.1 lead; no
1.2.0 lead exists [S9]"), `git mv packages/TearsOfTheKingdom/dualscreen/9B4E43650501A4D4.json
6F32C68DD3BC7D77.json`.
Acceptance: CA1-CA3, CA5, CA6, CA8, CA9; `grep -rn 9B4E4365 native packages` finds only the
1.2.1 "false" case in `totk_build_test.cpp`.

### T3a' (coder, now; replaces T3a): game assets first

Split (manager, 2026-11-02, packet size): **T3a'.1** = A + B2; **T3a'.2** = B1 + B5;
**T3a'.3** = B4 (after the stb_truetype approval). Each is its own critic loop.

Cut (ponytail rules, user 2026-11-02; not needed for the BotW-like look from TotK's files):
`totk.assets.valid`, its probe and the art notice (path C always runs the patched eden-cli, and
every texture already falls back harmlessly); typed keys in `totk_keys.h` and the checker (a
checker improvement, not part of the look); the render fixture `noart`. Plan B stays a note only.

Goal: real icons, item names and the game font before any more page work (user, 2026-11-02).
Decision and evidence: §R2. Old T3a's module pipeline (vendored zstd, SARC, BNTX, ASTC in the
module) is **not built**; it stays plan B (summary at the end of this packet).

**A. Eden Duo patch `host/eden-duo/0009-DSMod-read-zstd-romfs-files-with-ZsDic.patch`** (on top of
0001-0008, `git format-patch` like them, CI applies `000*.patch`):
1. New pure files `src/core/mods/mod_zstd.h/.cpp` (namespace `Core::Mods::Zstd`, no runtime, no
   logging; precedent `mod_yaz0.h`): `class Dicts { bool Add(std::span<const u8> zsdic); bool
   Decode(std::span<const u8> frame, u64 cap, std::vector<u8>& out, std::string& error) const; }`.
   `Add`: `ZSTD_createDDict`, keyed by `ZSTD_getDictID_fromDDict` (id 0 refused). `Decode`: magic
   `28 B5 2F FD`; `ZSTD_getFrameContentSize` must be known and ≤ `cap`; dictionary id from
   `ZSTD_getDictID_fromFrame` (0 = none, else must be known: "unknown zstd dictionary <id>");
   `ZSTD_decompress_usingDDict` / `ZSTD_decompress`; output size must equal the content size.
   DDicts freed in the destructor; copy disabled.
2. `mod_nx_runtime.cpp`, `NxAssetState::Locate`: after the Yaz0 branch, a zstd branch of the same
   shape (`ReadAll`, decode with `MaxWholeRead` as cap, `VectorVfsFile`, `RememberExpanded`). A frame
   that names a dictionary loads the dictionaries once per source prefix: `Locate("<prefix>:Pack/
   ZsDic.pack.zs")` (plain zstd, no dictionary) -> `NX::ParseSarc` -> `Dicts::Add` for every member
   ending `.zsdic` (`zs.zsdic`, `pack.zsdic`, `bcett.byml.zsdic` [S28 §2][S30]); stored in
   `NxAssetState` under `io_mutex`, cleared by `ResetNxAssets`; a failed load is remembered (one
   warning) until the next reset. No recursion beyond that one plain frame.
3. `IsNxAssetSource`: test the file name without a trailing `.zs` (so `X.bntx.zs` is an NX
   source; `X.blarc.zs#...` already is, it has a `#`).
4. `src/core/mods/duo_core.cmake`: add `mod_zstd.cpp`; core already links `zstd::zstd` [S1
   `src/core/CMakeLists.txt:1225`]. Ledger row in `tools/duo/duo-touched-ledger.sh` data as the
   other patches.
5. Test `host/eden-duo/test/zstd_test.cpp` (std-only asserts, built by `run.sh` like
   `stage_test`, natively and with MinGW under wine) against `$E/src/core/mods/mod_zstd.cpp` and
   the zstd 1.5.6 single-file library from the user-approved PyPI sdist (hashes in plan B below),
   unpacked into the build dir, never committed (`run.sh` takes `ZSTD_DIR`). Cases: two
   dictionaries made with `ZDICT_finalizeDictionary` and explicit ids pick the right one; a plain
   frame; unknown id; truncated frame; unknown content size; size over the cap; a dictionary with
   id 0 refused. The `Locate` hook itself is compiled only in CI (H1c); the critic reviews it by
   reading.

**B. Module** (`0100F2C0115B6000.cpp`, new headers each with a test):
1. `totk_msbt.h`: LBL1 + TXT2, UTF-16LE -> UTF-8 with surrogates, `0x0E` tags stripped; adapted
   from the runtime's pure `mod_msbt.cpp` (GPL-3.0) [S1 `mod_msbt.h:1-50`]. Names: probe
   `Mals/USen.Product.<n>.sarc.zs` with raw size queries (no `#`) for n = 200 down to 100, first hit
   wins (142 on 1.4.3 [S28 §2]; 1.2.0 `UNVERIFIED`), then `read_romfs("romfs:Mals/USen.Product.
   <n>.sarc.zs#ActorMsg/PouchContent.msbt")` (size query, cap 64 MiB, one read). Labels `<id>_Name`,
   `<id>_Caption`. `totk.names.valid` 1 once loaded; names replace ids in `.name` keys; captions feed
   `totk.inv.sel.caption` (T6). English only.
2. Picture keys become `romfs:` paths: `totk.gear.<s>.icon`, `.fuse_icon`, `totk.inv.{i}.icon`,
   `.fuse_icon`, `totk.inv.sel.icon` = `romfs:/UI/Tex/Icon/<id>.bntx.zs`, `<id>` checked against
   `[A-Za-z0-9_]{1,63}` (else not published). Built once per snapshot on the worker thread (no
   allocation in `sample`, CA10). `load_image` keeps refusing every key except the font atlas.
3. *(cut: `totk.assets.valid`, see the cut list above.)*
4. `totk_font.h` + export `eden_dsmod_get_font_extensions` (by hand, version/hash checks as the
   other getters; `.def` and version script): spec bytes from the package file
   `dualscreen/totk_font.txt` = `body:48`; `decode_font` reads `Font/Font.Nin_NX_NVN.bfarc.zs#scft/
   nintendo_NTLG-DB_DH_002.bfotf` and `#scft/External.bfotf` through the `read_romfs` saved in
   `create` (decode_font gets no host [S2 `010055D009F78000.cpp:151`]), removes the XOR (key = word
   0 ^ `0x18029a7f`, size = word 1 ^ key, big-endian, size ≤ file − 8 [S32]), rasterizes with
   stb_truetype at cap 48 px: U+0020-U+017F, U+2013/2014/2018-201D/2026 from the text face and
   U+E040-U+E068 from External, shelf-packed into one atlas ≤ 2048x2048 (RGB `FF`, A = coverage);
   reports a dense glyph run U+0020..U+E068 (missing slots are empty glyphs advancing like a space,
   as ACNH [S2 `acnh_font.h`]); returns false while the romfs is not readable (the host retries 30
   times [S1 `mod_assets.cpp:393-405`]). `load_image("module:totk:font/body:48")` returns that
   atlas. Adapted from `acnh_font.cpp` (GPL-3.0, keep its copyright line); stb_truetype v1.26
   vendored to `native/third_party/stb_truetype.h` **after the user approves it** (step 6), compiled
   in one TU with ACNH's pragmas. Manifest: `font: file:totk_font.txt`, `font_atlas:
   module:totk:font/body:48` (CA5 lists the new package file).
5. T8a dump (`totk_dump.h`): only when `read_romfs("user:totk-dump", size query) > 0` (the runtime's
   player-supplied source [S1 `mod_assets.cpp:204-206`]); once per session on the worker; lines
   prefixed `totk dump`, ≤ 200 chars each: the `Common.Product.<n>` probe (n 100-200, raw size
   query) and the Mals n; the blarc's SARC member names; every texture of `timg/__Combined.bntx`
   (name with suffix, width, height, format, channel map; BRTI fields as §plan B); for every
   `blyt/*.bflyt` whose name starts `Pause`, `Pouch`, `App`, `PlayerStatus`, `ShortCut` or `Map`,
   each `pic1`/`wnd1` pane (name, translate, size, its material's texture names and white/black
   colours) via a small BFLYT walker adapted from `acnh_lyt.cpp` (GPL-3.0) [S2]; the Mals archive's
   member names under `LayoutMsg/`. A failure logs one line and stops the dump.

Tests: `totk_msbt_test` (labels, tags, a surrogate pair, bad sizes); `totk_font_test` (XOR header
round trip with a synthetic key; bad key/size refused; spec parsing; an atlas built from a system
TTF found at test time, e.g. DejaVu Sans, skipped with a message when none exists: glyph run dense,
missing slots empty, atlas within bounds; `decode_font` returns false and calls the sink zero times
when `read_romfs` misses); `totk_dump_test` (BNTX name list and a synthetic BFLYT with one pic1 and
its material; no dump without the user file); `totk_module_test` (icon paths and id validation,
names from a fake Mals member, the font getter's
version/hash checks, CA7 still zero memory calls). Extend `totk_test_host.h`'s fake `read_romfs` to
answer `#member` paths from an in-memory map.
Acceptance per part:
- **T3a'.1**: the A.5 patch test passing natively and under wine; `git am` of 0001-0009 onto a clean
  `v1.3.2.2` clone succeeds and 0001-0008 are byte-identical; the ledger check
  (`tools/duo/duo-touched-ledger.sh --check`) passes in that tree; the `eden-duo-windows` CI run on
  the pushed branch builds (link to the run in the report; the `Locate` hook compiles only there);
  module: CA1-CA10 (three getters), icon path tests (valid ids give `romfs:/UI/Tex/Icon/<id>.bntx.zs`,
  bad ids publish nothing), CA10 still zero allocations.
- **T3a'.2**: CA1-CA10, `totk_msbt_test`, `totk_dump_test`, names in `totk_module_test`.
- **T3a'.3**: CA1-CA10 with **four** getters (CA3), CA5 with `totk_font.txt`, `totk_font_test`.

Plan B (only if the user wants an unpatched runtime): old T3a in the module (vendored zstd,
`ZsDic`, SARC from `wonder_assets.cpp:355-410`, BNTX block-linear unswizzle from
`wonder_assets.cpp:62-300`, ASTC via the host's `decode_astc`, BRTI +0x1C format, +0x24 width,
+0x28 height, +0x34 block height log2 [S28 §2]) **plus** BC1-BC7 and R8 decoders for layout
textures, serving `module:totk:<...>` keys; the module would then publish `module:` keys instead of
`romfs:` paths and the generator's `TEX` table would switch prefix. Composites would not be
available for those textures.
The approved zstd source (still used by patch 0009's test): PyPI `zstandard-0.23.0.tar.gz` (sha256
`b2d8c62d08e7255f68f7a740bae85b3c9b8e5466baa9cbf7f57f1cde0ac6bc09`; `zstd/zstd.c` sha256
`3ef459f74b63efd8bf59bea100f260541a9873b34deac0e373381c3d09acb9a1`, `zstd/zstd.h`
`4fab9cf39160cd15fbd9fbc2fd637ee300002fa70e307f85e8a7e45c1e057906`) [S31], fetched with
`pip download zstandard==0.23.0 --no-binary :all: --no-deps`.

### T3s (coder, after T2a): save reader

New headers:
1. `totk_gamedata.h` — `constexpr uint32_t Murmur3(std::string_view)` (x86_32, seed 0) with
   `static_assert`s on the published vectors [S28 §3][S29] (list above). `ParseGameData(bytes)`:
   magic `0x01020304` at 0, data offset at 8; entries `{u32 hash, u32 value}` from 0x20 up to the
   data offset; an entry with hash 0 starts a section whose type is its value [S11]. Types read:
   Bool 0x00, Int 0x02, IntArray 0x03, Float 0x04, Enum 0x06 (value = hash of the name), EnumArray
   0x07, Vector3 0x0A (offset -> 3 floats), String64Array 0x11 (offset -> u32 count, then 64-byte
   strings [S28 §3]), UInt 0x14; others skipped. Arrays: count then data at the offset [S11].
   Every offset and `count * size` is bounds- and overflow-checked; a String64 without a NUL in
   its 64 bytes is rejected. Result: hash -> (type, value or span), lookups typed.
2. `totk_save.h` — `NewestSlot(save_api)`: for N = 0-5 read `slot_0N/caption.sav` (≤ 0x1C0
   bytes) and take year/month/day/hour/minute from hashes `0x9811A3F7`, `0xDFD840D3`,
   `0xBD46F485`, `0x23F3D75E`, `0x27853BF7` and the autosave flag `0x25F03CAA`, scanning
   0x28..0x1C0 in steps of 8 [S29 `zelda-totk.js:1036-1056`, `:1120-1131`]; newest wins, a manual
   save beats an autosave on a tie [S29 `:1144-1149`]. Then `progress.sav`: size query, one read,
   size must match, parse. Builds an immutable `Snapshot` (status, gear, 8 tabs, abilities,
   position) per the data model; keys built once with `Murmur3`.
3. No live reader stubs (user: "don't worry about memory locations"). T1's `ReadStatus` stays the
   only live reader; T3 adds the others behind the same keys. Leads for T3: GameData object
   1.4.3 `main+0x3ad66b0`, vtable `main+0x39403b0` [S28 §3]; resident actors 1.2.1 [S7].
4. `0100F2C0115B6000.cpp` — `eden_dsmod_get_save_extensions` defined by hand (no SDK macro
   [S3 MODULE_GUIDE §1.5]), `configure` copies the struct [S1 `dsmod_module_extensions.h:170-176`];
   the module worker thread (Shared rules: Threads) starts in `create`, is joined in `destroy`
   (test: create/destroy 100 times, no hang, no leak), and polls captions every 2 s and re-reads `progress.sav` only when the newest
   (slot, date) changes; a failed read keeps the previous snapshot. `sample` publishes status,
   gear, abilities, and the current page's lists.

Tests (`totk_gamedata_test`, `totk_save_test`, extend `totk_module_test`): a test-only save
builder writes every used type; lookups by key; bounds (data offset past the end, offset past the
end, `count * 64` overflow, unknown type section, missing key -> absent); caption choice (newest,
tie -> manual, unreadable slot skipped, no slots -> nothing published); a progress size change
between the size query and the read keeps the old snapshot; gear from `EquipIndex` (-1 and out of
range -> not present); fuse and effect labels; abilities need both flags; position published as
(x, -z).
Acceptance: CA1-CA3 (two getters), CA5-CA10, the tests above. No zstd, no romfs reads.

### T4 (done, `3be5876`..`7e9ab94`; look superseded by T4v): shell, Gear page, generator and checker

Result: shell, tab bar, notice overlay, base extensions + action table (refusals logged once), Gear
page with 4 cards and 3 states, manifest generator + checker with runtime-faithful visibility
tests; critic SATISFIED after 3 rounds (7 -> 1 -> 0 findings). The user rejected its look (§Visual
redesign); T4v restyles it. The text below is the packet as built.


Base extensions move here from T3a: export `eden_dsmod_get_extensions`
(`DSMOD_SDK_EXPORT_EXTENSIONS`, version script and `.def`), `capabilities` =
`EDEN_DSMOD_CAP_EXTENSIONS` [S1 `dsmod_module_extensions.h:31,44`], `configure` stores the host
extensions, `on_action` implements the action table (UI actions accepted, game actions refused),
`load_image` returns false (T3a fills it). Without T3a, names show the item id and icons stay
blank; `totk.names.valid` is not published.

What T3s publishes (read from the code, `0100F2C0115B6000.cpp` `PublishGear`/`PublishInventory`):
gear slots are only `weapon`, `bow`, `shield`, `arrow`; `.present` is 1/0 when `EquipIndex` was
read and **absent** otherwise; `.name` = id; `.effect` only for weapon/bow/shield; no armor equip
keys exist (armor equip key `UNKNOWN`), and food has no effect key. Unknown values are absent,
never 0.

New `native/modules/totk_keys.h`: the single list of fixed keys, key families (`<s>`, `<a>`,
`{i}`) and module action names. The module builds its key strings and checks action names from
it (replacing the literals now in `0100F2C0115B6000.cpp`), and the checker parses it.

**Generator** `tools/totk/gen_manifest.py` (Python std-lib, run with `python3 -I`, like
`tools/echoes-of-wisdom/gen_manifest.py` [S2]): writes `manifest.json` (stable key order, 1-space
indent), `assets/map-blank.png` (1x1 transparent, std-lib `zlib` + `struct`, as BotW's generated
`map-transparent.png` [S2 `ASSETS.provenance.json`]) and `ASSETS.provenance.json`; `--check`
compares instead of writing. Manifest: `format 1`, `min_runtime 22`, `requires_module true`,
canvas 1240x1080, the built-in font (no `font` key), `haptics` `{enabled, respect_system, tap
click, drop confirm, refused reject}` (kinds and strengths per [S3 PACKAGE_FORMAT §3.9]); no `module` block (the
builder adds it, T1).

**Checker** `tools/totk/check_manifest.py --eden $E` (std-lib): fails when
- an object key (not starting with `_`) does not appear as a `"key"` string literal in any
  `$E/src/core/mods/*.cpp` (every widget, action, map and settings key in use is a literal in
  `mod_manifest.cpp`, e.g. `src_detail_bind` `:1053`, `on_hold` `:1705`, `drop_action` `:1826`);
- an action kind is not `page`, `module` or `view_reset`; a `module` action name is not in the
  module's action table (parsed from `native/modules/totk_keys.h`);
- an `on_tap`/`on_hold`/`on_swipe_*`/`drop_action`/`enforce` names an undefined action, a `page`
  action or page bind names an undefined page, a `scroll` names an undefined region;
- a `rect` is not 4 numbers or leaves the canvas, `hide_eq` is not an integer (a string throws
  [S3 PACKAGE_FORMAT §3.5]);
- a bind starting `totk.` is not a key or `{i}` family listed in `native/modules/totk_keys.h`
  (the header the module publishes through), and a listed key is bound by no page (warning only);
- the file is over 4 MiB [S3 PACKAGE_FORMAT §1].

**Every page** (generator adds them): tab bar `[0,968,1240,112]` with four buttons 310 px wide
(Gear, Abilities, Inventory, Map), each a `module` action `totk.ui.page` with its index, the
current page's button drawn highlighted; the source line `[24,920,1192,40]` (`text_bind`
`totk.<domain>.source`, `text_map` {1: "From last save", 2: "Live"}); on top, the T1 notice
(full-canvas `rect` with `input_block`, "Companion unavailable", `module_error_message`), hidden
while `totk.ready` is 1. `page_binds`: four entries `{point totk.ui.page, equals k, when_equal
{page, transition fade, duration_ms 130, easing ease_in_out}}` (BotW's values [S2]). The first
page is `gear`; the T1 page `main` goes away.

**Gear page** (`gear`):
- Status strip `[0,0,1240,120]`: `value` widgets for hearts / max hearts (`max_bind`,
  `max_sep " / "`), max stamina, rupees (`group`), max battery, each with a short text label;
  `need_bind totk.status.valid`.
- Equipped row, four cards at x = 24 + 300k, y 140, 284x430 (weapon, bow, shield, arrows; height
  430 so the effect line sits inside the card, manager decision after T4 critic round 1), each
  with a slot caption label above the card (WEAPON / BOW / SHIELD / ARROWS) so a "None" card
  still names its slot:
  `image` `[x+62,160,160,160]` `src_bind totk.gear.<s>.icon`; name `label` `[x+12,330,260,90]`
  `bind_text .name`, `wrap_width 260`, `max_lines 2`; `value` `.life` (or `.count`)
  `[x+12,430,260,40]`; fuse badge `image` `[x+190,160,64,64]` `src_bind .fuse_icon`,
  `need_bind .fuse`, fuse name `[x+12,476,260,36]`; effect text `[x+12,520,260,36]` inside the
  card. Centre-aligned labels anchor at x = the centre (the runtime centres on rect x). Three states from
  `.present`: 1 the item widgets; 0 a "None" label; absent nothing (use the hide/need rules of
  [S3 PACKAGE_FORMAT §3.5] as written for an absent key, and say which in the report). Each card
  `on_hold` -> `gear_swap_k` (`module` `totk.gear.swap` k, refused).
- No armor row in T4: no armor equip key is known, so it would always be empty. It comes back as
  its own packet once T8 verifies an armor equip key on a real save.
- Empty state "No save data yet" `need_bind !totk.gear.valid`.
Tests: `totk_module_test`: `totk.ui.page` accepted for 0-3 and refused otherwise, gear keys from a
fixture save, `totk.gear.swap` refused; CA10 with page 0; every key published on a full fixture
(each page selected) matches an entry of `totk_keys.h`. The checker covers the manifest (CA4)
and has its own test `tools/totk/test_check_manifest.py` (std-lib `unittest`): one mutated copy
per rule above (unknown object key, bad action kind, unknown module action, rect off canvas,
string `hide_eq`, `totk.` bind not in `totk_keys.h`) must fail, the committed manifest passes.
Acceptance: CA1-CA10 (three getters), and `python3 -I -m unittest discover -s tools/totk` passes.

### T4v (coder, after T3a'): restyle the shell and build the home page's Gear side

Goal: the §R5 look on the shell and the home page's right column and ability bar, and the left
side's frames and empty states (T6 fills the grid and detail pane). Starts with the generator's
`TEX` table `UNVERIFIED` (names T8a has not confirmed yet are emitted as written and miss
harmlessly over their fallbacks); after T8a, a short follow-up commit fills `TEX` and the tints.

Carried over from T4 (critic rounds 1-3, keep them): centre- and right-aligned labels anchor at the
rect's x (the runtime centres on rect x); the checker's rules and their mutation tests; the three
gear card states from `.present` (1 item, 0 "None", absent nothing) and their visibility tests;
the tab bar's page binds; the notice overlay; refused actions logged once; the empty state.

Changes:
1. Module: `totk.ui.page` 0 home, 1 abilities, 2 map (`kActionArgs[kActPage]` = 3); lists
   published for page 0 (inventory) and 2 (map); `totk.inv.sel.equipped` (new: the selected slot
   equals the pouch's `EquipIndex[0]`) in `totk_keys.h`. Gear and status
   publish on every page (the header is everywhere; cheap, fixed keys).
2. *(Typed keys cut, see §T3a' cut list.)*
3. Generator: tokens (§R5 table), the font keys, `totk_font.txt`, header with three tabs and L/R,
   source line, home page right column (status panel, stat chips, equipment panel with
   the three slot cards and the centre plate) and ability bar, the left side's frames, category tab
   frames and empty states, a `TEX` table and one helper that emits a game texture over its
   fallback. Pages `home`, `abilities` (frame and empty state until T5), `map` (until T7).
4. **(Packet T4r, before the rest of T4v.)** Render tool `tools/totk/render/` (moved from the critic's scratch harness [S37], GPL-3.0,
   credit the critic run): `render.sh $E <out dir>` builds `render_page.cpp` against `$E`'s
   `mod_ui*.cpp` like `host/eden-duo/test/run.sh`; `render_page <manifest> <page id> <state.json>
   <out.png> [--assets DIR]`. State comes from a JSON file of ints, floats and texts (no TotK
   state hard-coded, so BotW pages render too); derived values as the harness does today. Every
   widget key the TotK manifest uses is mapped to the runtime's `Widget` field (`fill`, `slice`,
   `tint`, `src_rect`, `flip_*`, `pill`, `frame`, `border`, `bar`, `max_bind`, `repeat*`, `scroll`
   rows, `highlight_*`, `text_*`); an unmapped key fails the run (so the tool cannot silently drift
   from the manifest). Images: `file:` loads the package PNG; others load `DIR/<sanitized key>.png`
   when `--assets` has it (the user's T8a `imgdump` PNGs, kept outside the repo), else a labelled
   placeholder (grey checker with the texture name's last component). The game font: when
   `--assets` holds `font-atlas.png` + `font-metrics.json` (written by a `totk_font_test` run on a
   system TTF, or later from the real atlas), use it; else the built-in font.
   Fixtures `tools/totk/render/states/*.json`: `full`, `mixed`, `none`, `nosave`, `notready`.

Tests: `totk_module_test` page indices and `totk.inv.sel.equipped`; the generator `--check`.
T4r alone: the render tool runs on the current T4 manifest and BotW's `ready` page, and fails on
an unmapped widget key (one test manifest).
Acceptance: CA1-CA10, `python3 -I -m unittest discover -s tools/totk`, and renders: the home page
in every fixture state, the abilities and map frames, and BotW's `ready` and `inventory` pages
rendered by the same tool with the same placeholder rules (state: every `botw.*` gate the page
needs set to 1), placed side by side in the report (scratch PNGs, not committed). The critic
compares structure: header tabs, grid density, right column, bottom bar, text sizes, empty space
(no region over 200x200 px without content in the `full` state), every game texture over a
fallback (render with an empty `--assets`: the page still reads). Not checkable here: the real
textures and font (T8a/T8).

### T8a (user, after T3a' and H1c): name the game's UI textures

1. CI-built eden-cli with patches 0001-0009 (`.github/workflows/eden-duo-windows.yml`), dev tools
   on. Package installed as §T8 step 1. Create `<Eden data dir>/dualscreen/user/0100F2C0115B6000/
   totk-dump` containing `1` [S1 `mod_user_source.h`].
2. Start TotK 1.2.0, load a save, wait 10 s, quit. Send every log line containing `totk dump`,
   `totk` and `DSMod` (font, Mals n, misses).
3. In the dev-tools console, `imgdump <src> <out.png>` [S3 PORTING_A_GAME §6 table] for the textures
   the coder picks from the dump (one line each, listed in the coder's T8a request), plus
   `imgdump module:totk:font/body:48 font-atlas.png`. Send the PNGs; they are used only in the
   scratch render (`--assets`), never committed.
4. Delete the `totk-dump` file.
Result: the coder fills `TEX`, the tints (BFLYT material colours), `Common.Product.<n>`, the Mals n
and any BFLYT reference values, in one commit; the critic re-renders with `--assets`.

### T6 (coder, after T4v): the home page's inventory side

§R5 "Home page, left side": category tabs with `TEX.cat_<k>`, the grid (BotW's grid pattern [S2
BotW manifest `inventory`]: template `image` `repeat 400`, `repeat_bind totk.inv.count`,
`repeat_cols 5`, `repeat_dx 124`, `repeat_row_dy 124`, `scroll inv_grid`), the detail pane with
the Equipped badge, caption (`totk.inv.sel.caption`, new text key from T3a's names) and the
refused use by hold and drop. Module unchanged except `.caption`.
Tests: `totk_module_test`: each tab's published count and order from a fixture save with all nine
pouches (arrows after bows), equipped flags and `sel.equipped`, `totk.inv.select` bounds, tab change
resets the selection, `tab_next` wraps, only the current tab's `{i}` keys are published (page 0
only), 400-item cap. Acceptance: CA1-CA10 and renders (`full` with 40 items in a tab, an empty tab,
`nosave`) beside BotW's `inventory` render.

### T5 (coder, after T4v): Abilities page

§R5 "Abilities page": seven tiles with `TEX.ability_<a>` icons (owned / locked tint), names,
"Locked", refused select, battery strip. Ability icon path: the T8a dump decides between
`UI/Tex/Icon/Obj_<X>.bntx.zs` and a layout texture.
Tests: `totk_module_test` owned flags for all seven from a fixture save; select refused for 0-6 and
out of range. Acceptance: CA1-CA10 and renders (all owned, none owned, `nosave`).

### T7 (coder, after T4v and T3a'): Map page

§R5 "Map page". Data and keys as before, with tile keys as `romfs:` paths:
- Area `MainField` in `map.areas`: `min [-6000,-5000]`, `max [6000,5000]`, `image
  file:assets/map-blank.png`, `clamp_view true`, dark background (BotW's area shape [S2]); bounds
  from the tile grid (12x10 tiles of 1000 units from x -6000, z -5000 [S28 §2]).
- `map` widget `[0,76,1240,972]`, `pan_zoom true`, `marker_x_bind totk.map.x`, `marker_y_bind
  totk.map.y`, `marker_src TEX.player_pin` (fallback: no `marker_src`, the runtime's pin). 120
  `overlays`, tile (c, r) -> `x0 = -6000 + 1000c`, `x1 = x0 + 1000`, `y1 = 5000 - 1000r`, `y0 = y1 -
  1000`, `src_bind totk.map.tile.{n}`, `src_detail_bind totk.map.tile.{n}.d`, `detail_threshold 360`
  (level 02 is 300 px [S28 §2]) [S1 `mod_manifest.cpp:1045-1066`].
- Module: `totk.map.tile.{n}` = `romfs:/UI/Map/MainField/<L>_<cc>-<rr>_02<suffix>.bntx.zs` and `.d`
  the `_01` file, for the selected layer only while page 2 is open; the suffix found by raw size
  queries in the order reveal `""`, `_T`, `_TT`, `_TTT`, `_TTTT` x variant `""`, `_A_A`, `_A`
  (fully revealed only in v1), cached per tile; misses logged once with the path. Depths (`U`)
  tiles are channel masks [S28 §2]: drawn with `tint` `FF8A8F99` in v1 (`UNVERIFIED` look; a real
  recolour needs plan B's module decode).
- Layer pills (Sky, Surface, Depths top to bottom, `totk.map.layer` 1, 0, 2), Centre
  (`view_reset`), position line; marker on every layer (no layer in the save).
Tests: `totk_module_test`: tile keys for each layer equal the expected strings for (0,0), (11,9),
(5,4); none published off page 2; position (x, -z); the suffix probe order with a fake
`read_romfs`. Acceptance: CA1-CA10 and renders (each layer pill selected, `nosave`).

### T8 (user, after T4v-T7, T8a and H1c): first real run

Only the user can run these. On the Windows PC with the CI-built patched eden-cli:
1. Unzip `dist/0100F2C0115B6000.dsmod.zip` into `<Eden data dir>/load/0100F2C0115B6000/TotKDuo/`
   so `dualscreen/manifest.json` is directly inside [S3 README "Desktop"].
2. `eden-cli.exe --aux-window --aux-rect 0,1080,1240,1080 --screenshot-prefix C:\duo\p -g <TotK
   1.2.0>`; load a save. For each page tap its tab, then touch `C:\duo\p.shot`
   [S3 PORTING_A_GAME §6]; send `p-screen2.png` for the home page (each category tab, an item
   selected), Abilities and Map (each layer, zoomed out and zoomed in).
3. Send every log line containing `totk` and `DSMod` (missing romfs paths, Mals n, dictionary
   ids, refused actions) and the `DSMod snapshot` lines.
4. Compare with the game: hearts / max hearts / rupees / battery units, equipped weapon, bow,
   shield and arrows, one item count per tab, owned abilities; save in game and check the
   companion follows within ~2 s.
5. Report average `sample` µs if the dev-tools build prints it [S3 MODULE_GUIDE §6].
6. Then H2/H3 on the Thor: the same pages on the bottom screen, taps, the swipe, the hold on the
   detail icon and a drag to the Use zone (both must play the refused haptic and change nothing).
What the user's romfs is needed for: the font, UI textures, icons, names, captions and map tiles.
Without it, without patch 0009, or when a path differs on 1.2.0, the pages still show ids,
numbers and the widget-drawn fallbacks in the built-in font.

## What needs the user

| Item | For | What exactly |
|---|---|---|
| D2 target version | T2a, T2 | Answered: 1.2.0 |
| D2b | T3 only | Answered: stay on 1.2.0 |
| Approve vendoring zstd | T3a | Answered: approved. No longer vendored in the module (§R2); the same files build patch 0009's test outside the repo (hashes in §T3a') |
| Approve vendoring stb_truetype | T3a' | `native/modules/third_party/stb_truetype.h` v1.26 (public domain) from the companions clone, for the game font (§R2) |
| T8a dump | T8a, T4v | The `totk-dump` file, one start of TotK 1.2.0 with the CI-built eden-cli (patches 0001-0009), the `totk dump` log lines and the `imgdump` PNGs the coder lists |
| Reference look decisions | T4v | Accepted by the user: pages Map / Inventory (home) / Abilities with Gear in the home page's right column; no 3D Link figure (§R3), the equipped weapon at 256 px instead; English only |
| T8 run | T8 | Package installed in the CI-built eden-cli, one save loaded, `.shot` pictures per page, `totk`/`DSMod` log lines, values compared with the game |
| eden-duo fork + Windows build | H1c | A fork repo for the H1 patches, and either a Windows build on their PC (MSVC + Ninja, `tools/windows/load-msvc-env.ps1` [S1 docs/Build.md]) or a GitHub Actions Windows runner in the fork |
| Windows tests | H1c, H2 | Install the window-host MSI from `apollo2-vibe` branch `claude/clever-clarke-k7hhhj`, add the "TotK duo" app (H2), run the H1c and H2 checks; send host logs and `.shot` screenshots |
| Vibe Window APK | H3 | Install the H3 client build on the Thor and turn on Settings -> "Dual screen" (keep "Show only the app window" on) |
| Build id | T2 | `xxd -s 0x40 -l 32 -p main` from `eden-cli --dump-romfs` of base + update [S3 PORTING_A_GAME §1] |
| Dump + running game for RE | T3 | Discovery runs on their Windows PC (dev-tools eden-cli, gdbstub), or they provide exefs/romfs and keys to this container outside the repo. Nothing from it is committed |
| Test saves | T3, T8 | Isolated profile copies: Great Sky Island; Surface with Ultrahand/Fuse/Ascend/Recall; late game with Autobuild and Depths |
| Thor | H2, H3, T8 | Stream "TotK duo" with Dual screen on; check both screens and touch |

## Host path (decided: C)

| Path | Where the game runs | New work outside the companion |
|---|---|---|
| A. On device | Eden Duo on the Thor | None |
| B. Linux host | eden-cli on a Linux PC, streamed | H1b, H2, H3 |
| **C. Windows host (chosen)** | eden-cli on Windows, streamed by apollo2-vibe | H1a, H1b, H1c, H2, H3 |

Path A stays the fallback: the same package works there with an `android-arm64-v8a` module.

## Risks

- **Wrong build** reads garbage or corrupts a save: three gates, read-only until T10, every write
  guarded by expected bytes [S3 PORTING_A_GAME §5]. The save extension has no write path at all
  [S1 `dsmod_module_extensions.h:140-148`].
- **Romfs paths and save keys come from 1.4.3 research** [S28][S29]; 1.2.0 may name files
  differently (Mals version, tile suffixes, icon folder). Every miss is logged with the path, and
  pages degrade to ids and empty picture slots; T8 corrects the tables.
- **Save lag and slot choice.** The pages show the last save, not the live game; two saves in the
  same minute tie on the caption date (manual beats auto, then the lower slot), so the wrong one
  of two same-minute saves can show until the next save.
- **Texture names and archive versions on 1.2.0** are `UNKNOWN` until T8a (no public list).
  Until then T4v's game textures miss and the fallbacks show; the T8a dump and `imgdump` fix
  them in one commit.
- **Patch 0009 is a runtime change.** The art needs the patched eden-cli; a stock Eden Duo
  shows the fallbacks and the "needs patch 0009" line. Plan B (module decoders) exists.
- **Font face and coverage.** The menu face is `UNVERIFIED` (NTLG-DB chosen); characters
  outside the atlas set fall back through the runtime's substitutions.
- **Spoilers.** The map shows fully revealed tiles in v1 (no reveal state yet).
- **Text coverage.** The built-in font may lack some characters in item names (e.g. `é`); the
  runtime draws them anyway and logs it [S1 `mod_nx_runtime.cpp:2438`].
- **Publish cost.** Up to ~400 items x 5 keys on the inventory page and 240 tile keys on the map
  page per tick; lazy publishing bounds it, CA10 forbids allocation, T8 measures µs.
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
| S28 | https://github.com/kitcatkode/totk-android (MIT) `android/BOTTOM_SCREEN.md`, `runtime/bottom_inventory.c`, `runtime/bottom_assets.c` (raw HEAD, read 2026-11-01) | TotK 1.4.3 romfs paths for map tiles, icons, Mals; ZsDic contents; tile grid and levels; Depths masks; GameData keys and live layout on 1.4.3; murmur3 vectors; equip through the quick menu |
| S29 | https://github.com/marcrobledo/savegame-editors (MIT) `zelda-totk/zelda-totk.js`, `zelda-totk.class.pouch.js`, `zelda-totk.class.item.js` | GameData key names and hashes, pouch structs, ability flags, `slot_0N`, caption date hashes |
| S30 | GitHub code search "ZsDic.pack.zs" / "bcett.byml.zsdic" (2026-11-01): KillzXGaming/Switch-Toolbox `Switch_Toolbox_Library/Compression/Formats/Zstb.cs`, dt-12345/asb `zstd.py`, valentderah/totk-pc-recomp `runtime/camera_speed.c` (result snippets only; files not opened) | `Pack/ZsDic.pack.zs` is plain zstd around a SARC of dictionaries `zs.zsdic`, `pack.zsdic`, `bcett.byml.zsdic` |
| S31 | https://pypi.org/project/zstandard/0.23.0/ sdist `zstandard-0.23.0.tar.gz` | zstd 1.5.6 single-file library (`zstd/zstd.c`, BSD or GPLv2), hashes in §T3a' |
| S32 | https://github.com/ly05010419/MacNativeGames (MIT) `games/totk/scripts/make_button_icons.py`, `ultrawide_layout.py`, `layout_view.py`, `docs/v1.2.1-port.md` (raw HEAD, read 2026-11-02) | TotK font archive `Font/Font.Nin_NX_NVN.bfarc.zs` and `.bfotf` faces + XOR header; `UI/LayoutArchive/Common.Product.<n>.Nin_NX_NVN.blarc.zs` with `timg/__Combined.bntx`; HUD key texture names; layout names; 1.2.1 has only `Common.Product.110` |
| S33 | https://github.com/zeldadungeon/maps `tools/README.md` (raw HEAD) | TotK map pins are `MapIcon*` textures in `Common.Product.100...blarc.zs` `timg/__Combined.bntx`; decompress with `zs.zsdic` |
| S34 | https://github.com/kuraeow/ultratears (GPL-3.0) `src/host/ui_aspect.c` (raw HEAD) | TotK layout names in `Common.Product.140` (full-screen menu backgrounds, AppMap, PlayerStatus) |
| S36 | User's photo `images/1.png` (2026-11-02) | The reference look: Eden Duo BotW companion on a Thor bottom screen |
| S37 | Critic's render harness `scratchpad/critic-t4/render/harness.cpp` (T4 rounds) | Renders a manifest page with the runtime's own `RenderPage`; basis of T4v's render tool |

The MinGW and wine results come from spikes in the planner's scratch dir
(`/tmp/claude-0/-home-user/6540dfaa-428d-5b44-8a41-bf2bc5812bad/scratchpad/spike`), not from a
web source. The BotW placeholder renders in §R1 are in `scratchpad/planner-render/`.

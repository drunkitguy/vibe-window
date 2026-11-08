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
   `git am host/eden-duo/00*.patch`. Run `tools/windows/load-msvc-env.ps1`, then
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
5. **Update to 1.2.1 (D2b, user said yes), only after T2b is in CI.** Live data needs 1.2.1: the
   pointer chains found are for 1.2.1 ([S9][S7][S38], §L2); 1.2.0 has none [S9]. You need the **1.2.1 update file (v393216)** itself, not the latest: a console update
   installs the newest one, 1.4.3 (v720896) [S5] (`UNVERIFIED` whether Nintendo still serves
   v393216), and with 1.4.3 none of these chains apply (fact: offsets moved [S7]; 1.4.3 has only the
   GameData layout [S28 §3], so status and position would need new scans). Then back up your
   saves and install it in Eden (§T8c steps 1-2). Before T2b's package (CI "TotK companion") is
   built, the companion on 1.2.1 shows only the notice page.
6. **T8a on 1.2.1 if you said yes in step 5, else 1.2.0 (after T2b and H1c): texture dump.** Use the CI-built eden-cli (it applies
   `host/eden-duo/0001-0011`) and the CI-built companion package. Install the package, create the
   `totk-dump` file, start TotK (1.2.1 if you said yes in step 5, else 1.2.0) once, load a save, wait 2 minutes, then quit. The log should
   then contain `totk dump done` (or `failed`), as §T8a explains.
   - Send every log line with `totk`, `DSMod` or `build_id=`. Eden's `Querying NSO patch existence for
     build_id=<hex>, name=main` line (every boot, trailing zeros trimmed [S1 `src/core/file_sys/patch_manager.cpp:473-475`];
     `Patching NSO` appears only with an exefs patch or `dump_nso` [S1 `src/core/loader/nso.cpp:221`])
     confirms the build id (T2) even when the package is refused. Romfs names may differ from 1.2.0 (Mals n, `Common.Product` n), and
     the dump records them.
   - Then write the `imgdump` lines the coder lists into the `EDEN_DSMOD_CMD` file and send the PNGs
     back (they stay out of the repo).
   Exact PowerShell steps are in §T8a.
7. **T8c (after T3a and T3b are coded here): check live values.** Console `chain` reads, a few
   in-game changes against the HUD, then send the log (§T8c).
8. **T8 (after T4v-T7): first real run.** Install the package on the PC, load a save, and send the
   `.shot` pictures and `DSMod` log lines listed in §T8.
9. **T8d (after T3c is in CI): GameData check and ability scan.** Install the newest package, then
   the title-screen and in-game inventory checks and the ability scan (§T8d).

Decision D3 (2026-11-07, user): "Stay 1.2.1, go for all four" (travel, equip, use item, ability
select). You were told: "abilities via injected ability-wheel input; travel by moving the Travel
Medallion's point then confirming on the map (if Eden crashes before 'put back', the medallion's
old spot is lost); equip and eat need reverse engineering of 1.2.1 functions with sessions on the
user's PC". The plan adds: while the medallion is moved, the companion shows its old spot on
the Map page and logs it as an error line, which Eden writes to disk at once, so it survives an
Eden crash with no setting (§A2 "Log on disk"). After a crash, read it back right away, before
eden-cli runs again: every eden-cli run (a game launch, `--install`, a launch that fails, `--help`)
rotates the log, and the second run deletes the crashed session's lines (§A2 "After a crash").
A later companion move does not hide the old spot: the step follows the chain of moves back to it.
So:
10. **A1 wheel look (after A1 is in CI).** First turn on `flush_line` once, so the log can be read
    while eden-cli runs (§A2 "Log on disk").
    Start the game as §T8a step 3's first window does: `cd C:\duo; $d = "$env:APPDATA\eden";
    $env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"; .\eden-cli.exe --aux-window -g <TotK 1.2.1>`, and
    load a save. Hold L and note which ability sits at each of the 8 stick directions. In a second
    window (`cd C:\duo; $d = "$env:APPDATA\eden"`) fix any wrong direction as §A1 "Wheel
    directions" says, then `Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt reload` and check
    `Select-String -Path "$d\log\eden_log.txt" -Pattern 'DSMod reload'` prints `DSMod reloaded: ...`
    (`reload` answers only in the log, not in `dsmod-cmd.txt.out` [S1 `mod_manifest.cpp:2826-2864,3003`];
    a `reload skipped` line says why; a broken edit gives `manifest.json does not parse`). Then tap each of the six tiles 10 times
    and write down for each tap what the game did; also tap once in a menu, once in a dialog and
    once while a loading screen shows (that tap must do nothing).
    Watch for the two input risks of §A1 Limits: a tap that uses the current ability instead of
    selecting (the wheel did not open), and a tap that only turns the camera. Send the corrected
    six values and the notes.
    If the Abilities page shows "Wheel locked: game loading or not readable" after the save has
    loaded, every tap is refused (§A1): stop, run `Select-String -Path "$d\log\eden_log.txt"
    -Pattern 'totk loading' | ForEach-Object Line`, send the `totk loading: unknown (...)` line it
    prints, and do step 11 first.
11. **T8c additions (after A1 and A2 are in CI).** The loading-gate, medallion and entry-10 reads
    of §A3 "T8c additions", in their own session: `flush_line` on and the `totk-re` file created
    before the launch (both as that section says), start as §T8c step 3, load a save that has the
    Travel Medallion, make a manual save in game, then run them.
12. **A2 first run (after the coder fills A2's fields from step 11).** The old spot is written to
    the log at once as an error line (§A2 "Log on disk"); it is also on the Map page while the
    medallion is moved. Do not send `reload` to the console while the medallion is moved: it
    restarts the module, which then forgets the old spot (§A2 Restore); if you did, quit eden-cli
    and run §A2 "After a crash" as after a close. If Eden crashes or closes
    while the medallion is moved, run §A2 "After a crash" right after it, before you run eden-cli
    again for anything (every run, even `--install` or a launch that fails, rotates the log; the
    second run deletes the line). Exception: a crash or close of the recovery session that step
    starts, before its hand-placed save, does not re-run the step (make no companion move in it); continue it from "walk to the old spot"
    (§A2 "After a crash"). Move the medallion to one
    shrine on the Surface, one in the Sky, one lightroot in the Depths and one tower, travel on the
    game's map each time, write down whether Link lands standing (or mid-air, falling, inside
    rock), then tap "Put back" (§A2 acceptance).
13. **A3 sessions (after A3 step 1 is in CI).** Watchpoint and spy runs for equip and eat (§A3).

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

**Target build (decision D2, user: 1.2.0; extended by D2b below).** One build only until T2b. T1 assumed 1.2.1; the user plays
**1.2.0** (update v327680). T2a switches `kTargetBuild` to the 1.2.0 id
`6F32C68DD3BC7D77AA714B80E92A096A737CDA77` + 24 zeros (`UNVERIFIED` zero tail, T2 confirms with
the user's `xxd`). Two sources agree on the 20 bytes: TkSharp `NSO_BINARY_ID_120 =
"6f32c68dd3bc7d77aa714b80e92a096a737cda77"` [S6 `TkPatch.cs:16`] and switch-cheats-db's
`6F32C68DD3BC7D77` key [S5][S9].
- Consequence for T3 (memory) only: 1.2.0 has the fewest leads. switch-cheats-db holds 30 cheats
  for `6F32C68DD3BC7D77`, all render-resolution, contrast and saturation (no pointer chains),
  against 111 for 1.2.1 [S9]; totk-mods' offsets are 1.2.1 [S7]; lotuskit covers 1.0.0, 1.1.0 and
  1.2.1 [S38].

**Decision D2b (2026-11-02, user): live data on 1.2.1.** The user asked "I want live
data. What do we need to do". The user chose to update TotK to **1.2.1** (update v393216, build `9B4E43650501A4D4489B4BBFDB740F26AF3CF850` [S6]; 16-hex
prefix also in [S5] and [S7 `phantom-foothold/src/program/GameProfiles.hpp:20`]; zero tail
`UNVERIFIED`, T2). The package keeps serving 1.2.0 (save and romfs pages, no memory reads) and adds
1.2.1 with live readers (T2b). Live values replace the save where valid; the companion stays
read-only (CA7; D3 amends: §Game actions on 1.2.1). Plan: §Live data on 1.2.1.

## Public TotK research (leads, never facts)

Every lead is confirmed from code and live validation before use [S3 PORTING_A_GAME §2.1, §3].

| Source | License | What it gives | Use |
|---|---|---|---|
| switch-cheats-db `cheats/0100F2C0115B6000.json` [S9] | none found; authors credited in file | 1.2.1: 111 cheats incl. pointer chains. Rupees `[[main+0x4721B98]+0xD8]+0x28EA4` (cheat 9); hearts chain from `main+0x46AF308` `+0x480 +0x228 +0x2F8` (cheat 3); stamina via `main+0x4721B98` and `main+0x46AF308` (cheat 4); motion via `main+0x471DCB0 +0xC8 +0x178 +0x30`, float `+0xFC` (cheat 17); battery, durability, arrows, pouch sizes, Korok map. 1.0.0-1.1.2: ASM hooks for HP/stamina/quantity. No 1.4.x entries. Value chains decoded in §L2 | Anchors for T3a-T3c (1.2.1, D2b) |
| Eiffel2018 1.2.1 set (mirror xenocard/Eiffel2018 [S40]) decoded with Atmosphere's opcode table [S39] | none found | Author of the [S9] 1.2.1 value cheats (codes identical); widths and hop counts per chain (§L2) | §L2 |
| Arch9SK7 MyNXCheats `9B4E43650501A4D4.txt` [S41] | none found | 1.2.1: mostly code patches; time-of-day code site `main+0xB3920C`, temperature `main+0xB7D13C`/`+0xB7D208`; no data address | Gaps only |
| aquacluck/totk-lotuskit [S38] | not determined | 1.2.1 symbols: `gmd::GameDataMgr::sInstance` `main+0x04721B98`, `SceneModule::sInstance` `main+0x04728538`; actor struct (name +0x218, position +0x2B4); GameData flag list with types and hashes (`PlayerStatus.CurrentSpecialPower` Enum 0xf6e7eacd, `MapData.CurrentLayer` Enum 0x1294b632) | §L2, T3c, T3d |
| lingxup-hash/switch-native-mac 1.2.1 cheat test [S42] | not determined | A third party read the Eiffel 1.2.1 hearts, stamina, weapon and rupee chains read-only (zero writes) on 1.2.1 in a Mac native port, not Eden | Supporting only |
| MyNXCheats `277178B7DBA1B6D4.txt` [S10] | none found | 1.4.3 cheat list: Inf. Health, Stamina, Battery, Horse Stamina, Perfect Thermometer, Gloom, Items x999, durability, movement. Code types not inspected (`UNVERIFIED`) | Anchors for T3 if target is 1.4.3 |
| afiaka87/totk-mods `Totk121Offsets.hpp` [S7] | MIT | 1.2.1: SceneModule `main+0x04728538`; GameDataMgr holder `main+0x0462E3D8` (double deref), `GameDataGetInt` `main+0x010CD5BC`, `GameDataSetInt` `main+0x00B51B08`, int store `+200`; resident actors (scene `+0x1E8`, components `+0x58`, index 13, list `+0x28`, stride `0x70`); actor name `+0x218`, position Vec3f `+0x2B4`, rotation `+0x2C0`, velocity `+0x320`; actor component registry `+0x228` (`+0x220` on 1.0.0, `self-recall/src/program/main.cpp`); GameData write queues and loading flag (`botw-abilities/src/champions/GameData.cpp:48,64-74`) | Best lead set for 1.2.1 |
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
| Equip/eat/remove via guest helper (`botw_bridge`, `*_actions`, `native/helper/botw`) | Adapt (late) | T4-T7 wire the UI to module actions that are refused (fail closed). A3 (D3): the same load-plan helper pattern, once the 1.2.1 functions are found |
| Hold-to-cook, recipes, heal advisor (`botw_hold*`, `botw_recipes*`, `botw_heal_advisor`) | Adapt (later) | Recipe data from the player's romfs |
| Travel (`botw_warp*`, `warp-ui-hook.S`) | Adapt (A2) | Shrines, lightroots, Skyview towers as map markers; the trigger moves the Travel Medallion's point instead of calling a warp function (§A2) |
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
| 0 | Decisions: D1 host path = **C** (user, done); D2 target version = **1.2.0** (user, done); D2b = **live data** (user, 2026-11-02); **update to 1.2.1** (user, 2026-11-02; needs the user's own v393216 file, Next steps 5); zstd vendoring = **approved** (user; now only for patch 0009's test); stb_truetype vendoring = **approved** (user); redesign defaults = **accepted** (user); D3 = **stay on 1.2.1, all four actions** (travel, equip, use item, ability select; user, 2026-11-07; §Game actions) | - | done |
| H1a | Eden Duo: native modules on Windows (loader, harness compile fix, tests), as patches | - | done: `host/eden-duo/0001-0003` + `host/eden-duo-companions/0001`; loader Catch2 tests pass standalone; full eden-duo build blocked (proxy 403 on CPM fmt download), real-Windows checks in H1c; critic SATISFIED round 3 |
| T1 | TotK package skeleton: build gate, fail-closed reader, tests; Linux `.so` and Windows DLL; `.dsmod.zip` | H1a (builder patch) | done: `2c482b6` (+ plan `911aed8`); gcc/clang/MinGW builds and ctest pass (MinGW under wine), single export, package + wine probe pass; critic SATISFIED round 3 |
| H1b | eden-cli composite layout: `--window-rect`, `--aux-rect`, borderless, aux not focusable | H1a | done: `afe952e`, patch 0004 adds `--window-rect`/`--aux-rect` (borderless, hidden until placed), non-focusable aux, 640x360 main floor, 32767 bound, ledger rows; parser tests pass natively and under wine; SDL and harness code compile-checked only in H1c; critic SATISFIED round 3 |
| T2a | Retarget the skeleton to 1.2.0 (`kTargetBuild`, data file name, tests) | T1 | done: `d175a18`; 1.2.0 build id and data file; critic SATISFIED (with T3s) |
| T3a'.1 | Eden Duo patch 0009 (zstd + `ZsDic` in the runtime's romfs reader, §T3a' A) + icon keys as `romfs:` paths (§T3a' B2) | T4 | done: 64a646e, critic SATISFIED round 1, Windows CI run 41379288233 green (198 cases); real romfs untested (T8a) |
| T3a'.2 | Item names from MSBT (B1) + the one-shot texture dump for T8a (B5) | T3a'.1 | done: 2bdcb00 (patches 0010-0011), critic SATISFIED round 4, Eden CI 41396378620 + companion CI green; real romfs untested (T8a) |
| T3a'.3 | Game font through the module (`.bfotf` + stb_truetype, B4) | T3a'.1 (stb_truetype approved by the user) | done: 671bb6f, critic SATISFIED round 2; real base: font untested (T8a) |
| T3s | Save reader: newest `slot_0N`, `progress.sav` GameData by murmur3 hash, domain snapshots (status, gear, pouches, abilities, position); save extension export; module worker thread | T2a | done: `6edf1ef`, `f4b1527`, `b535fca`; two-read stability gate, unknowns published as absent, gives up after 8 reads; 3 toolchains + wine + TSan/ASan + fuzz pass; real saves untested (T8); critic SATISFIED round 3 |
| T4 | Shell + Gear page, tab bar, notice overlay, base extensions, manifest generator + checker | T3s | done: `3be5876`..`7e9ab94`; shell, tab bar, notice, base extensions + action table (refusals logged once), Gear page with 4 cards and 3 states, generator + checker with runtime-faithful visibility tests; critic SATISFIED round 3 (7 -> 1 -> 0 findings). Its look is superseded by T4v |
| T4r | Render tool `tools/totk/render/` moved from the critic's harness + fixtures (§T4v item 4) | T4 | done: 05aec2d + dcd41fc (with patch 0012 for romfs: icon binds), critic SATISFIED; no game-font rendering (built-in font only) |
| T4v | Restyle (the reference look): game font, TotK textures, header page tabs, home page = Gear + status right column + ability bar (inventory side framed, filled by T6) | T3a'.1, T4r (T8a names the textures; starts with the `UNVERIFIED` table) | done: 81e0055 + 8426a73, critic SATISFIED round 2; TEX roles None until T8a (fallbacks only) |
| T6 | Inventory side of the home page: 8 category icon tabs, icon grid, detail pane with Equipped badge, hold and drag to a refused use | T4v | done: f59ee90 + 3e11daa + 1a10f15, critic SATISFIED round 3; touch feel untested (T8) |
| T5 | Abilities page: 7 large tiles with the game's icons, owned/locked, battery, refused select | T4v | done: c6e99f3, critic SATISFIED round 1; ability icon paths UNVERIFIED (T8a) |
| T7 | Map page: Surface/Sky/Depths tiles as `romfs:` paths, the game's layer icons and player icon, pan/zoom | T4v, T3a' | done: 7d17331 + 3ad54b0, critic SATISFIED round 2 |
| T2b | One package for 1.2.0 (save only) and 1.2.1 (live): two-row build table, both ids in `module.build_ids`, `data.json`, CI | T2a, D2b | done: 9bfe3a7, critic SATISFIED round 1 |
| T3a | Live status on 1.2.1: chain walker in `ReadStatus`, hearts, stamina, rupees, battery; save fallback | T2b | done: dc01a3d..6ce1927, critic SATISFIED round 4; chains UNVERIFIED until T8c |
| T3b | Live position and layer: player actor chain with name check, map marker, layer follows Link | T3a | done: c3119bd..63585db, critic SATISFIED round 3; chains UNVERIFIED until T8c |
| T3c | Live inventory and equipped items from the GameData tables, through the existing save parser; index self-check | T3a, A1 (gate chains, `ReadLoading`), A2 (probe, `int_table`, `kMaxTable`), T8c | coded (critic pending): 681df6a..8a71fa9; `totk_live.h`, `totk_live_test.cpp`; chains UNVERIFIED until T8c, String64Array layout and indices until T8d |
| T3d | Current ability, one chain from T8d's scan | T8d | blocked: user |
| A1 | Ability select through the game's wheel: six `button` actions `L+RStick(x,y)` behind `owned && !totk.loading`; `totk.loading` from the GameData loading gate; Travel Medallion tile refused with a reason (§A1) | T3a (chain walker), D3 | todo |
| A2 | Travel: (a) read-only destination markers on the Map page from the player's romfs and save; (b) the Travel Medallion trigger, one 4-op expect-checked `write_batch`, build-gated to 1.2.1, refusing until T8c fills its `UNKNOWN` fields; tap-to-restore (§A2) | A1 (`totk.loading`), T3b, D3 | todo |

Order after the redesign (user, 2026-11-02): **T3a' -> T4v -> T6 -> T5 -> T7** here; T8a as soon
as H1c's CI build with patch 0009 runs on the PC; T4v's `TEX` follow-up after T8a.
Live data (D2b): **T2b -> T3a -> T3b -> T8c (user) -> T3c (after A1 and A2, whose code it reuses) -> T8d (user) -> T3d**. T3a-T3d are the
live packets; the asset packet is T3a' (the old asset T3a is superseded).
Game actions (D3): **A1 -> A2 -> T8c with the §A3 additions (user) -> A2 fields filled -> A3
step 1 (coder) -> A3 sessions (user) -> A3 helper**.

Needs the user, a Windows PC, the Thor or another repo:

| # | Packet | Depends | Status |
|---|---|---|---|
| H1c | Windows build of the patched fork (MSVC Ninja per [S1 docs/Build.md], `EDEN_DSMOD_BUILD_DEV_TOOLS=ON`), smoke run: TotK package installed, notice page in the aux window, `.shot` screenshots, a tap on the aux window keeps controller input | H1a, H1b, T1 | blocked: user (CI build in progress: `.github/workflows/eden-duo-windows.yml`, `5d72e9c`, `15c60e8`) |
| H2 | apollo2-vibe window-host (`claude/clever-clarke-k7hhhj`): verify the 1920x2160 window-only composite with no host code; H2b (`dualScreen=1` pin) only if a check fails | H1c, H3 | blocked: user |
| H3 | Vibe Window client (`/home/user/vibe-window` `app/`, plan branch): "Dual screen" setting, split renderer, bottom-screen touch | H1b | done: `2d21128`, global Dual screen setting in `app/`; stream split onto the Thor's second display through a `Presentation` using the decoder's transform matrix; pointer ids 0-4 top, 5-9 bottom (host touch max 10); setting-off path behind one `if`; `DualScreenLayoutTest` 13 tests pass + android-all type-check; Gradle, Robolectric and the Thor untested; critic SATISFIED round 4 |
| T2 | Pin the builds: the `DSMod module: loaded ... build <hex>` log line [S1 `mod_module.cpp:311`] (T8a, T8c; printed only when the id matched). If the package is refused (`dual-screen module does not support this game build`, no id [S1 `mod_module.cpp:229-230,316`]), the fallback is Eden's `Querying NSO patch existence for build_id=<hex>, name=main` line, printed on every NSO load with trailing zeros trimmed [S1 `src/core/file_sys/patch_manager.cpp:473-475`] (not `Patching NSO`, which needs an exefs patch or `dump_nso` [S1 `src/core/loader/nso.cpp:221`]) (1.2.1 with a zero tail shows `9B4E43650501A4D4489B4BBFDB740F26AF3CF85`; any extra hex means the tail is not zero), or `xxd -s 0x40 -l 32 -p main` confirms or replaces T2b's 1.2.1 id (and T2a's 1.2.0 id if the user still runs it); T1 tests rerun | T2b, dump | blocked: user |
| T8a | Texture dump on the PC: `totk dump` log lines (archive versions, texture list, layout panes, Mals members) and `imgdump` PNGs; turns the `UNVERIFIED` texture table into names | T3a', H1c | blocked: user |
| T8 | First real run on the PC (romfs paths, save keys, pictures, sample cost), then end-to-end on the Thor through apollo2-vibe | T4v-T7, T8a, H1c (H2, H3 for the Thor part) | blocked: user |
| T8c | Check live values on the PC (1.2.1): dev-console `chain` reads of every §L2 chain, in-game changes against the HUD, log and profile lines | T3a, T3b, H1c | blocked: user |
| T8d | GameData index check lines (T3c) and the ability change scan (finds T3d's address) | T3c, H1c | blocked: user |
| A3 | Equip and use item on 1.2.1: user-run watchpoint and spy sessions on the PC, match AurelioB/totk-android's 1.4.3 equip/eat functions to 1.2.1, then a BotW-style load-plan helper with expected bytes that hold with UltraCam installed (§A3); also lists the T8c additions A1 and A2 need | A2 (GameData hash lookup), H1c, D3; D4 before the plan file is committed | step 1 done (coder); steps 2-4 blocked: user |
| T3L | Live 3D Link figure on the companion: dropped by the user (2026-11-02), since the Link in the reference photo is the game's own pause menu, not a companion widget | - | dropped (user) |
| T9 | Release: `windows-x86_64` module (plus `android-arm64-v8a` only if path A is wanted) | T8 | todo |
| T10+ | Extensions, one packet each: more live readers (time, weather, temperature, scene/menu state: no lead yet, §L2), map reveal from Skyview Towers, stamps and pins, compass, quests, recipes, item-name language (game actions moved to A1-A3) | T9 | todo |

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
  (patches 0001-0008, CI applies `00*.patch`); an unpatched runtime shows the pages without game
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
refused. Battery strip `[16,924,1208,72]`: `TEX.battery` icon, `value totk.status.max_battery`.

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
over the save; with every offset 0 it always uses the save. T3a-T3d fill the 1.2.1 chains behind
the same published keys, so no page changes (§Live data on 1.2.1).

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

### Actions (all packets): UI wired, game actions refused except A1 and A2

Every action in the manifest is `page`, `view_reset` or `module` (checked by CA7), plus, from A1, exactly
the six `button` actions `wheel_<a>` of §A1, each behind its `enabled_bind`. From A2 the module
accepts exactly two game actions, `totk.travel.go` and `totk.travel.restore` (§A2), each behind
its fail-closed checks. Every other game action stays refused (decision D3 amends the read-only
rule only this far). The module's
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
| `totk.ability.select` | 0-6 | **Refused**. From A1 the manifest sends it only for 6 (Travel Medallion); the log line gives the reason (§A1) |
| `totk.travel.select` | `$payload` = destination slot (A2) | Accepted if 0 ≤ arg < `totk.travel.count` (s64, like `kActInvSelect`'s `arg < 0` check); UI state only |
| `totk.travel.go` | ignored (A2) | **Game action**: queued only if every on_action check of §A2 passes, else refused; the write happens in the next `tick` after every precondition of §A2 passes |
| `totk.travel.restore` | ignored (A2) | **Game action**: queued only while the module holds a moved medallion, else refused |

The six A1 actions are not module actions: they are manifest `button` actions (§A1), so the
runtime itself refuses them when their `enabled_bind` is 0 or missing
[S1 `mod_actions.cpp:367-384`].

UI actions touch only module state. Refused actions return false, so the runtime plays the
`refused` haptic and nothing after them runs [S3 PACKAGE_FORMAT §3.13]; the module logs one line
per action name per session ("totk: <action> disabled: no verified offsets"). An argument out of
range is refused too. No refused action reads or writes guest memory or queues a guest call.
Equip and use (`totk.gear.swap`, `totk.inv.use`) stay refused until A3 finds and checks their
1.2.1 functions.

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
| `totk.status.valid`, `.source` | int | 1 valid; source 1 save, 2 live (T1 key kept). Every `.source` below is 2 while its domain is live (T3a-T3c) |
| `totk.status.hearts`, `.max_hearts`, `.max_stamina`, `.rupees`, `.max_battery` | int | `PlayerStatus.Life`, `.MaxLife`, `.MaxStamina`, `.CurrentRupee`, `.MaxEnergy` (float, published rounded x100). Units as the save stores them, `UNVERIFIED` (T8 compares with the game's HUD) |
| `totk.status.stamina` | int | Live only (T3a, 1.2.1; f32 rounded); not in the save list |
| `totk.status.battery` | int | Live only (T3a, 1.2.1; f32 x100 rounded, as `.max_battery`) |
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
| `totk.ability.current` | int | Live only, `UNKNOWN` until T8d's scan; published from T3d |
| `totk.inv.valid`, `.source`, `.count` | int | Current tab only |
| `totk.inv.tab{k}.count` | int | k = 0-7, for the tab chips |
| `totk.inv.{i}.icon` | text | Item `i` of the current tab (names only for the selection, `totk.inv.sel.name`; `{i}.name` dropped in T6 as unbound) |
| `totk.inv.sel.equipped` | int | The selected slot equals its pouch's `EquipIndex[0]` (T4v; the detail pane's Equipped badge) |
| `totk.inv.{i}.value`, `.kind` | int | kind 1 durability (`Life`), 2 count (`StockNum`), 0 none (armor) |
| `totk.inv.{i}.equipped` | int | Slot equals that pouch's `EquipIndex[0]` |
| `totk.inv.{i}.fuse_icon` | text | Fused material's icon |
| `totk.inv.sel.icon`, `.name`, `.caption`, `.fuse_name`, `.effect` | text | Selected item; caption = `<id>_Caption` [S28 §4] |
| `totk.inv.sel.value`, `.kind`, `.hearts` | int | `.hearts` = food `LifeRecover` [S29 `pouch.js:502`] |
| `totk.map.valid`, `.source` | int | |
| `totk.map.x`, `totk.map.y` | f64 | `PlayerStatus.SavePos` x and -z (the map widget's y points up [S3 PACKAGE_FORMAT §3.3 `@map_view_*`]; TotK z points south [S28 §1]) |
| `totk.loading` | int | Live only (A1): 0 after 60 clear samples of the GameData loading gate, else 1; an unknown gate (1.2.0, a wrong chain) is 1 |
| `totk.travel.count` | int | A2: destinations in the list (≤ `kMaxDest` 320), from romfs `LocationArea` + save unlock state; Map page only |
| `totk.travel.{i}.x`, `.y` | f64 | A2: destination `i` (here 0..`totk.travel.count`-1), x and -z as `totk.map.x/.y` |
| `totk.travel.{i}.kind`, `.dim` | int | A2: 0 shrine, 1 lightroot, 2 tower, -1 not on the selected layer; dim 1 unless open |
| `totk.travel.icon` | text | A2: exactly `file:assets/travel-dot.png` (the package-file image key; a bare path draws no marker [S1 `mod_assets.cpp:185`, `mod_ui_map_widget.cpp:1638-1645`]) |
| `totk.travel.sel.valid`, `.open` | int | A2: a destination is selected; it is open (1), locked (0) or unknown (-1) |
| `totk.travel.sel.name`, `totk.travel.msg` | text | A2: the selected name (`Location.msbt`, else the label); the last result line |
| `totk.travel.moved` | int | A2: 1 while the medallion is moved |
| `totk.travel.old.x`, `.y`, `.z` | f64 | A2: the medallion's old spot while moved |
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
  also `eden_dsmod_get_extensions`; from T3a' also `eden_dsmod_get_font_extensions`; from A2 also
  `eden_dsmod_get_write_extensions`); `x86_64-w64-mingw32-objdump -p` of the DLL exports the same
  names and imports no `libstdc++`/`libgcc`/`libwinpthread`/`libzstd` DLL.
- CA4. From T4: `python3 -I tools/totk/gen_manifest.py --check` exits 0 (the committed
  `manifest.json`, `assets/map-blank.png`, from A2 `assets/travel-dot.png`, and
  `ASSETS.provenance.json` equal the generator's output) and `python3 -I tools/totk/check_manifest.py --eden $E` exits 0 (checks in T4).
- CA5. Package: T1 acceptance 6 with `--build-id <kTargetBuild>`; `unzip -l` lists exactly
  `package.json`, `dualscreen/manifest.json`, `dualscreen/6F32C68DD3BC7D77.json` (from T2b
  `dualscreen/data.json`), the two
  modules, and from T4 `dualscreen/ASSETS.provenance.json` and `dualscreen/assets/map-blank.png`,
  from T3a' `dualscreen/totk_font.txt`, from A2 `dualscreen/assets/travel-dot.png`.
- CA6. Wine probe (extend T1's): `LoadLibraryExW` of the packaged `windows-x86_64` module, then
  each exported getter with the right version and hash returns a table whose `version`,
  `struct_size` and `abi_hash` match the SDK headers; a wrong hash returns null.
- CA7. Fail closed (amended by D3 for exactly A1 and A2): (a) `grep -nE
  "write_memory|queue_guest_call|write_batch"` over `native/modules/*.{h,cpp}` minus `*_test.cpp`
  and `totk_test_host.h` finds no `write_memory` and no `queue_guest_call` call; from A2,
  `grep -o "write_batch("` over the same files finds exactly one match, the call in `SendBatch` in
  `totk_travel.h` (used by go and restore); any other `write_batch` match is without `(` (the
  null check in `on_action`); (b) the manifest has no action with kind `write`, `slot_write`,
  `call` or `sequence`; from A1 its only `button` actions are the six `wheel_<a>` of §A1, each with
  `enabled_bind` `ui.wheel.<a>`, whose derived `expr` is exactly
  `totk.ability.<a>.owned && !totk.loading`, and a chord of the form `L+RStick(x,y)`
  (`check_manifest.py` enforces all three); (c) `totk_module_test` runs a full
  fixture (save + romfs + every action in the table above, valid and out-of-range arguments,
  -1 among them for every slot action, `totk.travel.select` included) and
  asserts `write_calls == 0`, `read_calls == 0` and `is_mapped_calls == 0` with default offsets
  (from T2b: zero memory calls on the 1.2.0 build; on 1.2.1 `write_calls == 0` and every read goes
  through `ReadGuest<MissingIsMapped::Reject>`); from A2 the fake host also counts `batch_calls`,
  which is 0 in every fixture except the §A2 tests that expect exactly one 4-op batch per accepted
  go or restore, and 0 with `kOffsets121` as committed until T8c fills its `UNKNOWN` fields;
  (d) every refused action returns false; `totk.gear.swap`, `totk.inv.use` and
  `totk.ability.select` stay refused for every argument.
- CA8. Every field in `totk_offsets.h` is `0` with an `UNKNOWN:` comment (T1 acceptance 9). From
  T3a: `Offsets{}` (1.2.0) stays all empty; every hop in `kOffsets121` cites its source (`[S9]
  cheat N`, [S7], [S38]) and carries `UNVERIFIED until T8c` until T8c passes that field. The
  layout constants in `totk_offsets.h` follow the same rule: from A2 `kTableCount`,
  `kTableSize`, `kTableToValues`, `kIntArrayEntry`, `kIntArrayLen`, `kIntArrayData` (§A2), from
  A3 `kQueue*` (`UNVERIFIED until A3 step 2`), from T3c `kTableToBlock`, `kBlockBytes` and
  `kS64*` (`UNVERIFIED until T8d`); the probe and the entry reads use only these names, never a
  literal (`totk_live.h` has no hex literal).
- CA9. `git status --porcelain` shows no build output; `packages/` holds only generated files;
  no file over 64 KiB is added except `native/third_party/stb_truetype.h` (T3a') and the generated
  `manifest.json` (checked by `gen_manifest.py --check`, so its size is not hand-maintained); no zstd source
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
0001-0008, `git format-patch` like them, CI applies `00*.patch`):
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
   `dualscreen/totk_font.txt` = `body:48`; `decode_font` reads `base:Font/Font.Nin_NX_NVN.bfarc.zs#scft/
   nintendo_NTLG-DB_DH_002.bfotf` and `#scft/External.bfotf` (shipped base romfs only, gated on
   `EDEN_DSMOD_CAP_SOURCE_BASE`: stb_truetype does not bounds-check table contents, so a LayeredFS
   font mod must never reach it; font mods do not show in the companion) through the `read_romfs` saved in
   `create` (decode_font gets no host [S2 `010055D009F78000.cpp:151`]), removes the XOR (bytes 0-3 are
   `7F 9A 02 18` XOR key, i.e. S32's little-endian `0x18029a7f`; the size is word 1 ^ key read
   big-endian; size ≤ file − 8 [S32]); cap height = the top of 'H' (no fallback: no 'H', no atlas), rasterizes with
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

PowerShell on the Windows PC. Start every PowerShell window with `cd C:\duo` and
`$d = "$env:APPDATA\eden"`: `$d` is the Eden data dir, or use `C:\duo\user` instead when that
folder exists (portable) [S1 `src/common/fs/path_util.cpp:115-118`]. The log is
`$d\log\eden_log.txt` [S1 `src/common/logging.cpp:403`, `fs_paths.h:42`]; it is written in 4 KiB
chunks and completed when eden-cli quits (`flush_line` is off by default) [S1
`src/common/logging.cpp:276-279`, `src/common/settings.h:927`], so read it after quitting. eden-cli
keeps the window it was started from; where a step says "in a second window", open another one.
D2b: run this on **1.2.1** if you said yes in Next steps 5 (after T2b's package; read "1.2.0" below as 1.2.1), else on 1.2.0 as written; on 1.2.1 Mals and
`Common.Product` numbers may differ from 1.2.0 (1.2.1 has only `Common.Product.110` [S32]).
Keys and the TotK 1.2.0 update are set up as for H1c's smoke run: keys in `$d\keys\`, the update
installed once with `.\eden-cli.exe --install <TotK 1.2.0 update.nsp>` [S1
`src/yuzu_cmd/dsmod_harness.cpp:232,831`].
1. From this branch's GitHub Actions runs (signed in to GitHub, which artifact downloads need)
   download `eden-duo-windows-x64` (workflow "Eden Duo Windows": eden-cli with patches 0001-0011;
   dev tools are on by default, `EDEN_DSMOD_BUILD_DEV_TOOLS`) and `totk-companion` (workflow "TotK
   companion"). Unzip both into `C:\duo`; `totk-companion.zip` holds `0100F2C0115B6000.dsmod.zip`,
   which stays zipped. Then:
   ```
   cd C:\duo; $d = "$env:APPDATA\eden"
   Expand-Archive -Force 0100F2C0115B6000.dsmod.zip "$d\load\0100F2C0115B6000\TotKDuo"
   New-Item -Force -ItemType File "$d\dualscreen\user\0100F2C0115B6000\totk-dump" -Value 1
   ```
   `dualscreen\manifest.json` must end up directly in `TotKDuo` (§T8 step 1; any folder name
   works). The `totk-dump` file is read through the runtime's `user:` source, `$d\dualscreen\user\
   <TITLEID>\` [S1 `mod_user_source.cpp:22-28`]; any non-empty content turns the dump on.
2. `.\eden-cli.exe -g <TotK 1.2.0>`, load a save, wait 2 minutes, quit eden-cli (quitting completes
   the log; the next start rewrites it). In the same window, check that the dump finished:
   `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk dump (done|failed)'` prints a line.
   Then save what to send:
   `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk|DSMod|build_id=' | ForEach-Object Line | Set-Content -Encoding utf8 totk-log.txt`.
   Send `C:\duo\totk-log.txt` (the dump, the Mals n, the item-name line, missing romfs paths).
3. Texture PNGs. The dev console is a file [S1 `mod_console.cpp:72-90`]: the runtime reads one
   command per write and empties the file, and mirrors its answers to `<file>.out`. It reads
   plain 8-bit text: PowerShell 5.1's `>` writes UTF-16, which it cannot parse, so always use
   `Set-Content -Encoding ascii`. Save the coder's list as `C:\duo\imgdump.txt` (one
   `imgdump <src> <out.png>` per line; `<out.png>` has no spaces and lands in `C:\duo`). In the
   first window: `$env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"; .\eden-cli.exe -g <TotK 1.2.0>`,
   and load a save. In a second window (`cd C:\duo` first):
   ```
   $cmd = "C:\duo\dsmod-cmd.txt"; $end = 'DSMod imgdump (ok|write-failed|FAILED|usage)'
   foreach ($line in Get-Content imgdump.txt) {
     if (-not $line.Trim()) { continue }
     $n = @(Select-String -Path "$cmd.out" -Pattern $end -ErrorAction SilentlyContinue).Count
     Set-Content -Encoding ascii $cmd $line
     $give_up = (Get-Date).AddSeconds(90)
     while (@(Select-String -Path "$cmd.out" -Pattern $end -ErrorAction SilentlyContinue).Count -le $n) {
       if ((Get-Date) -gt $give_up) { "no answer: $line"; break }
       Start-Sleep 1
     }
   }
   ```
   One line by hand is `Set-Content -Encoding ascii $cmd 'imgdump <src> <out.png>'` (single quotes
   keep `$`, `#` and `^` as typed). A blank line gets no answer at all (the runtime skips it [S1
   `mod_console.cpp:296-300`]); a line without an output path gets `DSMod imgdump usage: ...` [S1
   `mod_nx_runtime.cpp:2247`]. Every other command ends in one of: `DSMod imgdump ok '<src>' WxH ->
   <out.png>` (written), `DSMod imgdump write-failed ...` (decoded, but the PNG path could not be
   written), or `DSMod imgdump queued '<src>'` (still decoding) followed later by `ok`,
   `write-failed` or `FAILED '<src>' (not decodable|timeout)` (60 s) [S1
   `mod_nx_runtime.cpp:2171-2197`, `2236-2280`, line numbers with 0001-0011]. After T3a'.3 also
   `imgdump module:totk:font/body:48 font-atlas.png`. Quit eden-cli, then send the PNGs and
   `C:\duo\dsmod-cmd.txt.out`; the PNGs are used only in the scratch render (`--assets`), never
   committed.
4. `Remove-Item "$d\dualscreen\user\0100F2C0115B6000\totk-dump"`.
Result: the coder fills `TEX`, the tints (BFLYT material colours), `Common.Product.<n>`, the Mals n
and any BFLYT reference values, in one commit; the critic re-renders with `--assets`.

### T6 (coder, after T4v): the home page's inventory side

§R5 "Home page, left side": category tabs with `TEX.cat_<k>`, the grid (BotW's grid pattern [S2
BotW manifest `inventory`]: template `image` `repeat 400`, `repeat_bind totk.inv.count`,
`repeat_cols 5`, `repeat_dx 124`, `repeat_row_dy 124`, `scroll inv_grid`), the detail pane with
the Equipped badge, caption (`totk.inv.sel.caption`, new text key from T3a's names) and the
refused use by hold and drop. Module unchanged except `.caption`, empty optional text lines published as "", and `{i}.name` no longer published.
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
  (fully revealed only in v1), cached per tile; one log line per layer: found/120 and the first missing tile. Depths (`U`)
  tiles are channel masks [S28 §2]: untinted, since runtime overlays have no tint (`opacity`
  only); the Depths masks show raw until plan B's recolour.
- Layer pills (Sky, Surface, Depths top to bottom, `totk.map.layer` 1, 0, 2), Centre
  (`view_reset`), position line; marker on every layer (no layer in the save).
Tests: `totk_module_test`: tile keys for each layer equal the expected strings for (0,0), (11,9),
(5,4); none published off page 2; position (x, -z); the suffix probe order with a fake
`read_romfs`. Acceptance: CA1-CA10 and renders (each layer pill selected, `nosave`).

### T8 (user, after T4v-T7, T8a and H1c): first real run

Only the user can run these. On the Windows PC with the CI-built patched eden-cli (D2b: on 1.2.1 if you said yes in
Next steps 5, else 1.2.0; on 1.2.1 the pages say "Live" where T3a-T3c read memory):
1. Unzip `dist/0100F2C0115B6000.dsmod.zip` into `<Eden data dir>/load/0100F2C0115B6000/TotKDuo/`
   so `dualscreen/manifest.json` is directly inside [S3 README "Desktop"].
2. `eden-cli.exe --aux-window --aux-rect 0,1080,1240,1080 --screenshot-prefix C:\duo\p -g <TotK
   1.2.1, or 1.2.0 per Next steps 5>`; load a save. For each page tap its tab, then touch `C:\duo\p.shot`
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

## Live data on 1.2.1 (D2b: T2b, T3a-T3d, T8c, T8d)

Decision D2b (2026-11-02): the user wants live data and chose 1.2.1 (T2b-T3c are coded and tested on fakes before the user updates). A valid live value replaces the save value
behind the same key; when a chain fails, that domain falls back to the save (fail closed per
domain). Read-only: the module calls only `is_mapped` and `read_memory`, never `write_memory` or a
guest call (CA7; A2's one `write_batch` is the only exception, D3) [S1 `dsmod_module_abi.h:166-193`]. No game data, dumps or console output are
committed.

### L1. What the runtime gives

- `main_base`/`main_size` describe the game's main NSO (`load_base + module_start`) and are reset
  before every call, so every `main+X` lead below is `main_base + X` [S1 `src/core/loader/nso.cpp:405-410`,
  `mod_module_host.cpp:197-201,547-548`; S3 ARCHITECTURE]. The dev console's `chain` command uses
  the same base (`main_region_begin`) and the same hop rule as §L2 [S1 `mod_module_host.cpp:201`,
  `mod_console.cpp:730-757`], so T8c checks each chain without the module.
- `read_memory` checks `is_mapped` first and refuses address 0, size 0, wrap, more than 64 MiB and
  other processes [S1 `mod_module_host.cpp:29,211-221`, `mod_state.cpp:53-58`]. Reads are safe
  from any thread [S1 `mod_module_host.cpp:446-451`]. `queue_guest_call` is null, so the game's
  getters (e.g. `GameDataGetInt` [S7]) cannot be called: the module walks memory itself
  [S1 `dsmod_module_abi.h:189-193`].
- `sample` runs at 60 Hz on the tick thread, which competes with the game [S3 MODULE_GUIDE §1.2,
  ARCHITECTURE §5]. `EDEN_DSMOD_PROFILE=1` logs `DSMod profile (CPU, 5s): sample=... avg=...us`
  [S1 `mod_module_host.cpp:36,80`].
- One package serves both builds. The loader prefix-matches the running build against
  `module.build_ids` (1-256 entries), then calls `supports_build` and `create`
  [S1 `mod_module.cpp:221-230,272-274`]. Pages and the data file load on any build; the data file
  is `<BUILD16>.json`, then lower case, then `data.json` [S1 `mod_manifest.cpp:2669-2672,2794`].
  The builder repeats `--build-id` and needs no data file per id [S2
  `tools/build_dualscreen_package.py:212-217,281-286,343`]. Shipped precedent: Wonder accepts old
  builds for a page and reads memory on one build only [S2 `010015100B514000.cpp:40-52,325-328,
  1344-1352`]; MK8D picks a per-build profile in `create` [S2 `mk8d_reader.cpp:434-442`].
- Per-build data addresses are fine behind the exact 32-byte build gate (BotW reads `main+0x2CA6D50`
  the same way [S2 `botw_pouch.h:46-48,104-107`]); MODULE_GUIDE's accessor rule [S3 §7.2] guards
  against updates, which the gate already refuses (`shortcut: no accessor decode, add it if a
  third build is ever targeted`).

### L2. 1.2.1 leads (each `UNVERIFIED` until T8c)

Chain notation `T {A, B, C}`: address = `main+A`; then for each next hop, address = u64 at address
\+ hop; read `T` at the last address. This is exactly the console's `chain A B C` [S1
`mod_console.cpp:730-757`]. Decoded from Eiffel2018's 1.2.1 set [S40] (same codes in [S9] key
`9B4E43650501A4D4`) with Atmosphere's opcode table [S39]: every pointer hop is a 64-bit type-5
load; value widths come from the store opcodes. The tails are the same on 1.0.0, 1.1.0 and 1.2.1;
only the heads move (player `0x45D6750`/`0x46B1BA8`/`0x46AF308`; GameData `0x4646C60`/`0x47242B8`/
`0x4721B98`), and the registry offset is 0x220 on 1.0.0 and 0x228 later, as totk-mods' self-recall
has it [S9][S7].

| Value | Chain | Type, unit | Source |
|---|---|---|---|
| player name check | `{0x46AF308, 0x480, 0x218, 0x0}` | 7 bytes `Player\0`, any alignment (a `char*` from `sead::SafeString::cstr()`; [S28 `runtime/world_survey.c:92-94`] and [S38 `src/program/util/actor.cpp:189`] read it without an alignment check) | actor `[[main+0x46AF308]+0x480]` [S9 cheats 1-7]; name pointer at actor+0x218 [S7 `Totk121Offsets.hpp`][S28 `runtime/game_version.h:78-84`][S38 `src/program/structs/engineActor.hpp:128,197-210`: `BaseProc` is 0x218 bytes on 1.2.1, then `mActorName` (one `const char*` [S38 `subrepo/sead/include/prim/seadSafeString.h:145`]) at +0x218, `mComponents` data at +0x228, `mPosition` at +0x2B4] |
| hearts | `u32 {0x46AF308, 0x480, 0x228, 0x2F8, 0x1830, 0x8}` | quarter hearts | cheat 3 copies max into it [S9] |
| max hearts | `u32 {0x46AF308, 0x480, 0x228, 0x2F8, 0x1838, 0x8}` | quarter hearts: cheat 1a writes 0xA0 (40 hearts), 1b 0xC (3) | [S9] |
| stamina | `f32 {0x46AF308, 0x480, 0x228, 0x3A8, 0x13FC}` | per wheel `UNVERIFIED` | cheat 4 [S9] |
| max stamina | `f32 {0x4721B98, 0x198, 0x5BC}` | 3000.0 = "3 rings" | cheat 2 [S9] |
| rupees | `u32 {0x4721B98, 0xD8, 0x28EA4}` | rupees | cheat 9 [S9] |
| battery | `f32 {0x4728538, 0x1E8, 0x58, 0xF8, 0x5C}` | per cell `UNVERIFIED` | cheat 28 [S9]; prefix = SceneModule +0x1E8 +0x58 [S7][S38 `syms/exking/engine/hacks.py:19`] |
| max battery | `f32 {0x4721B98, 0x198, 0x594}` | cheat 29 writes 48000.0 | [S9] |
| position | `f32[3] {0x46AF308, 0x480, 0x2B4}` | world x, y, z | actor +0x2B4 [S7][S28 `game_version.h:83`][S38 `src/program/structs/engineActor.hpp:210`]; combining the cheat actor with this layout is `UNVERIFIED` |
| GameDataMgr | `[main+0x4721B98]` | | lotuskit `gmd::GameDataMgr::sInstance` [S38 `syms/exking/gmd/GameDataMgr.py:4-8`]; totk-mods uses `[[main+0x462E3D8]]` [S7 `runtime-support/include/totk/engine/Totk121Offsets.hpp:30-33`]; same object `UNVERIFIED` |

GameData layout on 1.2.1 (T3c):
- One block per value type at mgr + 8 + type*0x60 (save type numbers), values pointer at
  block+0x10. Every cheat fits: Int 0xD8, IntArray 0x138, Float 0x198, String64Array 0x678 [S9].
  The planner computed this from the offsets; the same grid is in [S28 `runtime/gamedata_live.c:4-7`] (1.4.3).
- Hash table of {u32 hash, u32 index} at block+0x18, count +0x20, size +0x24; slot = hash % size,
  next slot on a clash, hash 0 = empty. Seen on 1.4.3 only [S28 `gamedata_live.c:36-46`]:
  `UNVERIFIED` on 1.2.1.
- IntArray entry: 0x40 bytes, u32 length +0x20, int32 array pointer +0x28. String64Array entry:
  0x90 bytes, length +0x70, element array +0x78; element = 0x50 bytes {char* text, u32 capacity,
  char[64] text at +0xC} [S28 `gamedata_live.c:10,24-27`]. The 0x40/+0x28, 0x90/+0x78 and 0x50
  sizes fit every 1.2.1 pouch cheat [S9 cheats 8, 10-13, 32, 42-58]; element size 4 and text at
  +0xC are 1.4.3 only, so T3c's structural check tests the element layout (pointer at the element equals element +
  0xC; vtables InMain).
- Self-check from the cheat offsets ((offset - 0x78) / 0x90; the planner matched cheat names to
  keys): String64Array indices Armor 9, Bow 12, KeyItem 15, Material 16, Shield 18,
  SpecialParts 19, Weapon 22.
- Writes are queued and flushed once a frame [S7 `botw-abilities/src/champions/GameData.cpp:48`;
  S28 BOTTOM_SCREEN §5 H], so read twice. Loading gate, exactly as totk-mods [S7
  `botw-abilities/src/champions/GameData.cpp:64-73`]: `u32 n = [mgr+0xCC0]`, need 1 ≤ n ≤ 16;
  `file = u64 [mgr+0xCC8]`, need non-null; `loading = u8 [file+0x78] & 0x02` (mask 0x02; a null file also counts
  as loading). totk-mods applies it to the manager from the holder, `[[main+0x462E3D8]]` [S7
  `GameData.cpp:137-138`, `Totk121Offsets.hpp:30-31` "DOUBLE dereference"], so T3c requires
  `[[main+0x462E3D8]] == [main+0x4721B98]` before using the gate.
- Unknown: single Int/Float entry stride, Bool, Enum and EnumArray layouts.

No lead in any 1.2.1 source read ([S9][S40][S41][S7][S38]): current ability, Ultrahand/Fuse/
Autobuild state, a live layer flag, time of day, weather, temperature (code sites only,
`main+0xB3920C`, `main+0xB7D13C` [S41]), equipped armor. A third party read the hearts, stamina,
weapon and rupee chains read-only on 1.2.1 in a Mac native port [S42]; that supports the chains
but is not a check in Eden.

### T2b (coder, first): one package for 1.2.0 and 1.2.1

- `totk_build.h`: `kTargetBuild` becomes `kBuild120` (unchanged) and `kBuild121` =
  `9B4E43650501A4D4489B4BBFDB740F26AF3CF850` + 24 zeros (zero tail `UNVERIFIED`, T2).
  `IsSupportedBuild(hex)` and `IsSupportedBuild(bytes)` match either exactly; `Is121(bytes)`.
- `0100F2C0115B6000.cpp`: `SupportsBuild` and `Create` use `IsSupportedBuild`; `Create` sets
  `in->offsets = Is121(host->build_id) ? kOffsets121 : Offsets{}` (`kOffsets121` is empty until
  T3a). On 1.2.0 nothing changes: save and romfs pages, zero memory calls.
- `git mv packages/TearsOfTheKingdom/dualscreen/6F32C68DD3BC7D77.json data.json` (`{}`, found on
  any build [S1 `mod_manifest.cpp:2669-2672`]; the module ignores its config).
- `.github/workflows/totk-companion.yml` Package step: read both 64-hex ids from `totk_build.h`,
  require exactly two, and pass `--build-id` once for each.
- Tests: `totk_build_test` (1.2.1 now true; 1.4.3, BotW and 1.2.1 with one byte changed false);
  `totk_module_test` (supports_build and create accept both ids; 1.2.0 full fixture: zero memory calls).
Acceptance: CA1-CA10 (CA5 with `dualscreen/data.json`); `unzip -p dist/0100F2C0115B6000.dsmod.zip
package.json` lists both ids in `module.build_ids`.

### T3a (coder, after T2b): live status

1. `totk_offsets.h`: chains per field instead of one root:
   `struct Chain { std::uint8_t n = 0; std::array<std::uint64_t, 6> hop{}; };` (n 0 = UNKNOWN).
   `Walk` starts at a base: address = base + hop[0], then for each next hop address = u64 at
   address + hop. `Offsets` holds `actor` (base main: `{0x46AF308, 0x480, 0x0}`, gives the actor
   pointer); actor-relative tails `name {0x218, 0x0}`, `hearts {0x228, 0x2F8, 0x1830, 0x8}`,
   `max_hearts {0x228, 0x2F8, 0x1838, 0x8}`, `stamina {0x228, 0x3A8, 0x13FC}` (T3b adds
   `position {0x2B4}`); main-relative `max_stamina, rupees, battery, max_battery` as in §L2.
   `inline constexpr Offsets kOffsets121{...}`, each hop commented with its source and
   `UNVERIFIED until T8c`. Each tail is the §L2 chain with its `{0x46AF308, 0x480}` head cut off, so
   the console `chain` lines of §T8c still check them.
2. `totk_status.h`: one `Walk(h, base, chain)` returning the final address or 0. For a main-relative
   chain base is `main_base` and the first slot must be `InMain`. Alignment applies to the address
   being read, not to a pointer value: every u64 slot read needs `slot % 8 == 0`; the caller's final
   u32/f32 read (and the 12-byte position) needs `addr % 4 == 0`; the name bytes need none. Every
   pointer loaded must be non-null and ≥ 0x10000 (BotW's floor [S2 `botw_pouch.h:23-27`]); additions
   are overflow-checked; every read goes through the existing `ReadGuest<MissingIsMapped::Reject>`.
   `ReadStatusOnce` walks the actor once, checks its name (7 bytes `Player\0` at the pointer from
   the `name` tail), then reads hearts, max hearts and stamina through the tails from that one
   actor pointer, so the name check guards those values; then the main-relative fields.
   `ReadStatus` keeps its two reads, compared bitwise.
   `Status`: hearts, max_hearts and rupees u32; stamina, max_stamina, battery and max_battery f32.
   The battery pair is optional (absent if its chains fail, e.g. before the game gives a battery);
   the other five are all or nothing.
   Plausibility bounds (fail closed; from the cheat values [S9], `UNVERIFIED`): 12 ≤ max_hearts ≤
   160, hearts ≤ 2*max_hearts, rupees ≤ 999999, 0 < max_stamina ≤ 3000, 0 ≤ stamina ≤
   2*max_stamina, 0 < max_battery ≤ 48000, 0 ≤ battery ≤ max_battery, all floats finite
   (`shortcut: bonus hearts and bonus stamina caps UNKNOWN, tighten after T8c`).
3. `0100F2C0115B6000.cpp` `PublishStatus`: same shape; source 2 when live. A torn or failed
   sample does not flip to the save: `Instance` keeps the last valid live `Status` and a miss
   counter (plain fields, no allocation; tick thread only, like `page`). On a valid sample store it
   and reset the counter; on a mismatch or failure republish the stored one with source 2; only after
   `kLiveGrace` = 30 consecutive misses (0.5 s at 60 Hz) publish the save with source 1
   (`shortcut: N samples grace, tune after T8c`). Stamina published as
   `lround`, battery and max battery as `llround(x*100)` (as the save's `.max_battery`); new key
   `totk.status.battery` in `totk_keys.h`. One log line the first time live is valid and the first
   time it falls back after the grace: `totk live status: hearts=H/M stamina=S/MS rupees=R
   battery=B/MB | save: hearts=... max_hearts=... max_stamina=... rupees=...` (T8c compares units).
4. `totk_save.h`: lotuskit types `PlayerStatus.MaxStamina` as Float [S38 `GMDHash_InfoList.mjs`],
   and cheat 2 writes a float [S9]. When Int and UInt miss, read Float, rounded (one line).
   `totk_save_test`: a save with `PlayerStatus.MaxStamina` as Float 2000.0 publishes
   `max_stamina` 2000.
5. Generator: when `totk.status.stamina` exists, the stamina bar shows it over `max_stamina` (else
   the full bar as now); when `totk.status.battery` exists, the battery chip shows it over the max.
Tests: `totk_status_test` on `totk_test_host.h` fake memory:
- a full fake 1.2.1 layout gives valid with the exact values;
- the same layout with `"Player\0"` at an address with `addr % 8 == 1` is still valid;
- each of these gives invalid: an unmapped hop, a u64 slot at an address not 8-aligned, a u32 or
  f32 at an address not 4-aligned, a null or < 0x10000 pointer,
  a first slot outside main, a name other than `Player`, a torn read (`corrupt_read`), each bound broken,
  main_base + hop[0] wrapping past 2^64, a loaded pointer + hop wrapping to a mapped low address
  (only the overflow check rejects it; both kept from today's `totk_status_test.cpp:62-65,91-93`);
- a missing battery chain gives the five values without battery;
- `Offsets{}` makes zero host calls.
`totk_module_test`:
- a 1.2.1 fixture with memory publishes source 2 and the live values;
- a 1.2.1 fixture with empty memory publishes the save values with source 1;
- a valid live layout, then one sample with `corrupt_read` set: source stays 2 with the previous
  values; then `kLiveGrace` failing samples: source 1 with the save values;
- `write_calls == 0` everywhere, and CA10 still sees zero allocations.
Acceptance: CA1-CA10 (CA7(c) and CA8 as amended); a `full` render with live stamina.

### T3b (coder, after T3a): live position and layer

- `kOffsets121.position` = actor tail `{0x2B4}`, 12 bytes, 4-aligned (§L2 row `{0x46AF308, 0x480,
  0x2B4}`). `ReadPosition` in `totk_status.h` reuses T3a's single actor walk and name check, reads
  twice and compares, and applies bounds: x in
  [-6000, 6000] and z in [-5000, 5000] (T7's map area [S28 §2]), y in [-1500, 3500]
  [S28 `runtime/world_survey.c:53-56`], all finite.
- Layer: y < -100 is Depths, y > 1000 is Sky, else Surface [S28 `runtime/bottom_map.c:109`]
  (`shortcut: height threshold, upgrade if MapData.CurrentLayer (Enum 0x1294b632 [S38]) proves to follow Link`).
- Module: `totk.map.x`/`.y` = (x, -z) with `totk.map.source` 2. When Link's layer changes,
  `totk.map.layer` follows it; the pills still switch it by hand. Same grace as T3a: `Instance`
  keeps the last valid live position and its own miss counter; a torn or failed sample republishes
  it with source 2, and only `kLiveGrace` consecutive misses fall back to the save's `SavePos`.
  Read in `sample` like the status (`shortcut: tick-thread reads, move to the worker if T8c's
  profile line shows the cost`).
- Live position does not need a save (a new game before its first save): `Sample` publishes
  `totk.map.valid` 1, `.source`, `.x`/`.y` and the map-page tile keys from the live or save
  position **before** the `!snap` return (today `totk.map.valid` = `snap && snap->position` and the
  tiles sit inside `if (snap->position)` [`0100F2C0115B6000.cpp:492-515`]).
- If T8c shows the cheat actor is not `Player`, switch the head to the resident-actor walk with the
  same name check: SceneModule `main+0x4728538` +0x1E8, components +0x58, index 13, list +0x28,
  stride 0x70, +8, +0x40 (three sources [S7][S28 `world_survey.c:78-100`]
  [S38 `src/program/util/actor.cpp:166-193`]). Do not build it unless needed.
Tests: position and layer from fake memory (each threshold side; out of bounds falls back to the
save); the layer follows only on a change; source per domain; live memory with no save gives
`totk.map.valid` 1 with `totk.map.source` 2; one `corrupt_read` sample keeps source 2 and the
previous position.
Acceptance: CA1-CA10; map renders with a live marker on each layer.

### T3c (coder, after A1, A2 and after T8c passes T3a): live inventory and equipped items

Reuse the save path. The live reader writes a `progress.sav`-format buffer, and the existing
`GameData::Parse` + `BuildSnapshot` turn it into tabs and gear. There is no new snapshot code, and
the parser's bounds checks apply to live data too.
1. Move the test save writer (`totk_test_host.h`, "progress.sav writer") into `totk_gamedata.h`
   unchanged but for `Words` made public (the EnumArray copy); the tests keep using it.
2. `totk_live.h`, run on the existing 2 s worker poll (`shortcut: 2 s lag, poll faster if T8d finds it
   laggy`), right after the save poll, with the worker's own copy of the offsets (taken in
   `create`), and only once a save is shown (it gives the effects; `shortcut: a new game shows no
   inventory until its first save`). The worker reads through the create host (reads are safe
   from any thread [S1 `mod_module_host.cpp:446-451`]; its `main_base` is the session's):
   - every address and offset below is an `Offsets` field (`totk_offsets.h`), never a literal in
     `totk_live.h`. Reuse A1's four chains `gmd_mgr {0x4721B98, 0x0}`, `gmd_holder {0x462E3D8,
     0x0, 0x0}`, `gmd_count {0x4721B98, 0xCC0}` and `gmd_loading {0x4721B98, 0xCC8, 0x78}`, and A2's
     mgr-relative table offsets in the `v3a_table` form (the block's hash table; values at -8,
     count at +8, size at +0xC): A2's `int_table` = 0x140 for IntArray (type 3; `UNVERIFIED until
     T8c`, as in §A2) and one new `s64a_table` = 8 + 0x11*0x60 + 0x18 = 0x680 for String64Array
     (type 0x11; its §L2 source, `UNVERIFIED until T8d`), and `pouch_index` (the seven §L2 indices in
     `Pouch` order, 0 for Arrow and Food: not checked). The block is read from table −
     `kTableToBlock` (0x18), `kBlockBytes` (0x28); String64Array `kS64aEntry` 0x90, `kS64aLen` 0x70,
     `kS64aData` 0x78, `kS64Element` 0x50, `kS64Text` 0xC (§L2, `UNVERIFIED until T8d`). All are empty or 0 in `Offsets{}` and set only in
     `kOffsets121`. When `int_table` or `s64a_table` is 0 the poll is skipped (zero host calls)
     and the save stays the source (an empty gate chain reads as gate unknown with no memory
     read, A1's `ReadLoading`), so 1.2.0 and unknown builds never read these addresses (CA8);
   - the gate is A1's `ReadLoading` (mgr == holder, 1 ≤ n ≤ 16, file walk, mask 0x02 clear):
     0 reads on, 1 skips the poll (keeps the last snapshot), -1 stays on the save (logged once
     per session: `totk live gamedata: gate unknown`);
   - for each IntArray and String64Array key `ReadPouch` reads (`Pouch.*.Content.Name`,
     `.Combined.Name`, `.Life`, `.StockNum`, `.LifeRecover`, `.EquipIndex` [S29]), look the hash up
     with A2's probe in its type's table (§L2);
   - bounds: count ≤ 0x10000, count ≤ size ≤ the probe's cap 0x10000 (§A2; S28 allows size up to
     0x40000 on 1.4.3 [S28 `gamedata_live.c:38-46`]), index < count,
     text NUL within 64 bytes; an array longer than `kMaxItems` (400, `totk_save.h:23`) is not
     rejected: read min(length, `kMaxItems`) elements of every parallel array of that pouch, as
     the save path publishes at most `kMaxItems` [`totk_save.h:268`] (the real 1.2.1 lengths are
     `UNKNOWN: the ok line's lengths in T8d's totk-live.txt`);
   - read the arrays into the buffer, twice, and compare.
3. Self-check every poll, before publishing: the seven `Pouch.*.Content.Name` indices must
   equal §L2's, else log `totk live gamedata: index check FAILED <key> <got>` (`<got>` the index or
   the probe's failure text) and stay on the save for the rest of the session (no more reads).
   Then the structural check, every poll, with one extra u64 read at mgr+0 and block and entry each read from +0 in one bulk read
   (block 0x28 bytes, entry its full size) so their vtables come with the fields (no 1.2.1 vtable
   address needed): u64 `[mgr]`, `[block]` and `[entry]` of each key used must be InMain (they
   are vtables; S28 `gamedata_live.c` checks a vtable on the manager, every type block and every
   value: "every vtable checked, so a game that moved anything gives "none"" [S28
   `runtime/gamedata_live.c:11`]); and for every String64 element read, the u64 at the element
   must equal element + 0xC (S28 gives the element as "a pointer to its text, u32 capacity, the
   64-byte text at +0xc" [`gamedata_live.c:10`]; that the pointer targets its own +0xC is
   `UNVERIFIED until T8d`, and if wrong the check fails safe with the log line below). A content check cannot do this: a text 4 bytes off still reads
   as an id suffix (`on_Sword_001`), and an empty text tests nothing. Any failure logs
   `totk live gamedata: index check FAILED layout` once and drops the live inventory for that poll
   (the next passing poll shows it again; a probe failure other than "hash missing" counts as
   layout, a missing key is absent as in the save path). On a pass, log `totk live gamedata: ok lengths=W,B,S,A,R,M,F,Z,K` once (T8d step 1): each
   pouch's `Content.Name` length as the game holds it, before the `kMaxItems` cut (0 when missing),
   in `Pouch` order, so T8d decides the 400-element shortcut.
4. Module: tabs and gear come from the live snapshot (`totk.inv.source` and `totk.gear.source` 2)
   only while T3a's live status is valid (its player actor walk with the `Player` name check,
   within `kLiveGrace`), else from the save: the title screen's GameData may hold empty pouches
   that pass every check above.
   Abilities stay from the save (Bool layout UNKNOWN). Effect labels (EnumArray layout UNKNOWN) are
   copied from the save's item at the same pouch index when the ids match, else absent.
   `totk.inv.select` checks the shown tab, by the same rule.
Tests: a fake GameDataMgr with hash tables in `totk_test_host.h` (`PutGameData`; its memory is
locked and the worker's calls are counted apart from the tick thread's, so the existing exact
read counts and `corrupt_read` stay deterministic; `corrupt_worker_read` tears a worker read):
- two pouches give the same tabs as the matching save;
- each of these keeps the save: index check failed, hash missing, text without
  NUL, loading bit set, `[[main+gmd_holder]]` different from `[main+gmd_mgr]`, element text and
  its self-pointer at +0x10 with zeros at +0xC (layout check), a block vtable outside main;
- a pouch array of 401 names publishes 400 items, as the save path;
- `Offsets{}` with a fake GameDataMgr in memory makes zero host calls and publishes the save;
- a torn second pass keeps the previous snapshot;
- an empty IntArray and String64Array (data pointer 0) give ok and match the save;
- the IntArray values below 0x10000, values wrapping past 2^64 onto mapped memory, an entry, a
  block or the manager not 8-aligned (everything else readable) give layout;
- names loaded while the gate reads loading rename the live inventory shown;
- a live GameData with the actor's name or chain broken publishes source 1 and the save's tabs
  (the chain after the status's grace), and `totk.inv.select` checks the save's tab.
Acceptance: CA1-CA10; a live fixture and its matching save fixture publish identical keys apart from
`totk.status.*` and `totk.map.*` (T3a/T3b's live values, which differ by design), with inv/gear source 2.

### T3d (after T8d): current ability

`UNKNOWN: current ability; find it with T8d's change scan`. Lead: GameData Enum
`PlayerStatus.CurrentSpecialPower` (0xf6e7eacd) [S38 `GMDHash_InfoList.mjs`]; under the §L2 grid
its block's values pointer is mgr+0x258 (`UNVERIFIED`), and whether it changes live is `UNVERIFIED`.
After T8d: one chain in `kOffsets121` and the value-to-ability table T8d measured, published as
`totk.ability.current` (0-6, key already listed); the Abilities page highlights that tile. Nothing
is coded before T8d.

### T8c (user, PC, after T3a and T3b are in CI): check live values

PowerShell, as §T8a: `cd C:\duo; $d = "$env:APPDATA\eden"`. Read-only throughout: no cheats, no
`writeb`.
1. **Before updating**, back up the saves (the 1.2.0 to 1.2.1 save change is `UNVERIFIED`):
   `Copy-Item -Recurse "$d\nand\user\save" C:\duo\save-backup-120` [S1 `src/common/fs/fs_paths.h:24`,
   `src/core/file_sys/savedata_factory.cpp:32,150-152`].
2. Only after T2b's package is in CI and you said yes to D2b: `.\eden-cli.exe --install <TotK
   1.2.1 update.nsp>` (your own v393216 file, not 1.4.3; Next steps 5); then download only
   the newest `totk-companion` artifact (workflow "TotK companion"; eden-cli stays as it is) and
   run `Expand-Archive -Force "$env:USERPROFILE\Downloads\totk-companion.zip" C:\duo` (`-Force`
   replaces §T8a's older `0100F2C0115B6000.dsmod.zip`, which stays zipped), then run
   `Expand-Archive -Force 0100F2C0115B6000.dsmod.zip "$d\load\0100F2C0115B6000\TotKDuo"` and
   `Remove-Item -ErrorAction SilentlyContinue "$d\dualscreen\user\0100F2C0115B6000\totk-dump"`
   (the texture dump stays off: it blocks live reads while it runs).
3. `$env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"; $env:EDEN_DSMOD_PROFILE = "1"; .\eden-cli.exe
   --aux-window -g <TotK>`. Load a save and stand still on the Surface.
4. Save these lines as `C:\duo\chains.txt` (name, hearts, max hearts, stamina, max stamina,
   rupees, battery, max battery, position, GameDataMgr twice):
   ```
   chain 46AF308 0x480 0x218 0x0
   chain 46AF308 0x480 0x228 0x2F8 0x1830 0x8
   chain 46AF308 0x480 0x228 0x2F8 0x1838 0x8
   chain 46AF308 0x480 0x228 0x3A8 0x13FC
   chain 4721B98 0x198 0x5BC
   chain 4721B98 0xD8 0x28EA4
   chain 4728538 0x1E8 0x58 0xF8 0x5C
   chain 4721B98 0x198 0x594
   chain 46AF308 0x480 0x2B4
   chain 4721B98
   chain 462E3D8 0x0
   ```
   In a second window: `foreach ($l in Get-Content C:\duo\chains.txt) { Set-Content -Encoding ascii
   C:\duo\dsmod-cmd.txt $l; Start-Sleep 2 }`. Each line answers `DSMod chain ...` in
   `C:\duo\dsmod-cmd.txt.out` (`chain` is a direct read and cannot freeze [S1 `mod_console.cpp:730-732`]).
   `@dst` prints four s32 words, so floats (stamina, battery, position) show as their bit patterns;
   the coder decodes them, nothing to convert by hand [S1 `mod_console.cpp:752-756`].
5. Run step 4 again after each of these, writing down what the HUD shows:
   (a) take one hit;
   (b) sprint until the wheel drops;
   (c) spend or pick up rupees;
   (d) use a Zonai device;
   (e) walk about 50 m;
   (f) stand on a sky island, then in the Depths.
   Each time, check that the home page's source line says "Live" and that the values and the map
   marker follow the HUD.
6. Quit, then send:
   - `C:\duo\dsmod-cmd.txt.out`;
   - `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk|DSMod|build_id=' | ForEach-Object Line | Set-Content -Encoding utf8 totk-log.txt`
     (it holds `DSMod module: loaded ... build <hex>` and `Querying NSO patch existence for build_id=<hex>, name=main`
     for T2, the `totk live status` lines and the
     `DSMod profile` lines);
   - your HUD notes from step 5;
   - one `.shot` of the home page (§T8 step 2).
Result: the coder drops `UNVERIFIED until T8c` from each field that followed the HUD, fixes the
units and bounds (quarter hearts, stamina per wheel, battery per cell), and removes any failing
chain; that domain stays on the save.
Once A1 and A2 are in CI, run §A3 "T8c additions" (Next steps 11) in their own session.

### T8d (user, PC, after T3c is in CI): GameData check and ability scan

1. Live inventory (T3c), PowerShell as §T8c. First download only the newest
   `totk-companion` artifact (the CI build that has T3c; workflow "TotK companion"; eden-cli stays
   as it is) and run `Expand-Archive -Force "$env:USERPROFILE\Downloads\totk-companion.zip" C:\duo`
   (`-Force` replaces the older `0100F2C0115B6000.dsmod.zip`, which stays zipped), then run
   `Expand-Archive -Force 0100F2C0115B6000.dsmod.zip "$d\load\0100F2C0115B6000\TotKDuo"` and
   `Remove-Item -ErrorAction SilentlyContinue "$d\dualscreen\user\0100F2C0115B6000\totk-dump"`
   (the texture dump stays off: it blocks live reads while it runs):
   (a) then run only T8c step 3's command line (`$env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt";
       $env:EDEN_DSMOD_PROFILE = "1"; .\eden-cli.exe --aux-window -g <TotK>`) and do not load a
       save yet; on the title screen wait 5 s, then write down the home page's source line ("Live"
       or "From last save") and the item count of each tab;
   (b) load a save that has at least one material, two weapons, and an equipped weapon, bow and
       shield; on the companion's home page write down one material's count (Materials tab) and
       the weapon, bow and shield gear cards;
   (c) in the game pick up one more of that material, then equip another weapon;
   (d) within 5 s (the live read runs every 2 s) write down that material's count, the three gear
       cards and the source line on the home page;
   (e) quit, then `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk live gamedata|totk loading' |
       ForEach-Object Line | Set-Content -Encoding utf8 totk-live.txt`, and send `totk-live.txt`
       and your notes from (a), (b) and (d).
   Result: with `ok lengths=...` in the file and (d) showing the new count, the new weapon and
   "Live", the coder drops `UNVERIFIED until T8d` from `s64a_table`, `pouch_index`,
   `kTableToBlock`, `kBlockBytes` and `kS64*`; a length above 400 means reading more than
   `kMaxItems`; an `index check FAILED` line or a wrong (d) leaves the inventory on the save.
   "Live" in (a) means T3a's player actor walk reads valid on the title screen (the live
   inventory shows only while it is valid): the coder fixes that chain. `gate unknown` together
   with a `totk loading: unknown (<cause>)` line anywhere in the file (the tick thread logs it
   first) leaves the inventory on the save; the coder fixes the chain the cause names.
2. Ability scan (for T3d), one console line at a time:
   - `chain 4721B98 0x258`, and note the last pointer `P`;
   - `cscan P 65536` (256 KiB of ints: the index of `PlayerStatus.CurrentSpecialPower` and the
     Enum entry stride are unknown, so scan wide once; `clist` prints the first 60 candidates
     [S1 `mod_console.cpp:547-618`]);
   - select another ability, `cnarrow diff`, wait 5 s, `cnarrow same`;
   - do that select/diff/same round three times, then `clist`.
   If `clist` lists nothing, repeat with the actor from `chain 46AF308 0x480` (its last pointer `A`:
   `cscan A 2048`) [S1 `mod_console.cpp:547-618`].
   Send `dsmod-cmd.txt.out` and the ability selected at each step.

## Game actions on 1.2.1 (D3: A1, A2, A3)

Decision D3 (2026-11-07, user): "Stay 1.2.1, go for all four" (travel, equip, use item, ability
select). The research (five tracks, synthesis and three critic rounds, planner scratch
2026-11-07) ranked the routes; this section keeps the smallest route per action. Every TotK
address below is a third-party lead, `UNVERIFIED` on the user's main until T8c. Rejected routes:
raw teleport or state writes (actor position, `forceSetMatrix` main+0x6BA86C [S38][S7]; hearts,
item counts, `EquipIndex`): no area streaming, no buffs, and the values can reach the save;
driving the + menu with injected input: Drop is one cursor step away and the menu state has no
1.2.1 lead (research menuinput track; `UNVERIFIED` in game); retargeting
to 1.4.3: the user chose 1.2.1.

### G1. What the runtime gives (facts)

- **`button` actions** press one chord (buttons and stick vectors) on the handheld and player-1
  ports for `frames` ticks (min 2), then release every token of it on the same tick (the
  countdown and release are in `UpdateHeldButtons`); `repeat` and `gap` re-press the same chord
  [S1 `mod_actions.cpp:197-241,300-341,620-689`, `mod_manifest.cpp:2212-2246`].
  `L` is a button token [S1 `mod_actions.cpp:53`]; `RStick(x,y)` takes two finite numbers, clamped
  to the unit disk, `RY+` means y = +1 [S1 `mod_input_tokens.h:35-90`]. The chord is a static
  manifest string: the data file cannot change actions (it merges `points`, `derived`, `map`,
  `sprite_map`, `metadata_anchor`, `symbols`, `spies` and `patches`, no `actions` [S1
  `mod_manifest.cpp:2669-2790`]).
- **`enabled_bind`** takes one value and fails closed: a missing point or unpublished derived
  value refuses the action whatever its `!` [S1 `mod_actions.cpp:367-384`]. A derived `expr`
  (runtime 17) has `&&`, `||`, `!` with short-circuit; a name that is not published makes the
  result missing [S1 `mod_expr.h:4-24`].
- **Modules cannot press buttons or call guest code**: no input call in the base ABI,
  `queue_guest_call` is null [S1 `dsmod_module_abi.h:166-202`]. `on_action` gets no host struct;
  the runtime runs `sample`, the module `tick`, then the taps' actions, so a module checks memory
  in the next `tick` [S1 `mod_runtime.cpp:9-14`, `mod_actions.cpp:539`] (as the module's own
  comments already cite).
- **`write_batch`** (write extension v1): at most 32 ops of at most 64 bytes; every op is checked
  for bounds and mapping, then, with the guest's threads stopped, every `expect` is compared before
  the first store, and the stores run in op order; false means nothing was applied, or a later op's
  store failed after earlier ones [S1 `dsmod_module_extensions.h:182-223`,
  `mod_module_services.cpp:255-300`].
- **No module file write.** The base ABI has none, the `user:` source is read-only [S1
  `dsmod_module_abi.h:62-63`], the save extension is read-only [S1 `dsmod_module_extensions.h:143-148`],
  and `persist_flags` stores only manifest flag actions [S1 `mod_persist.h:6-9`]. Module memory and
  the Eden log are the only places a module can keep a value.
- **Real and injected input share one rule**: a press from a new source takes the button; a
  release from another source is ignored only while that source still holds it [S1
  `src/hid_core/frontend/emulated_controller.cpp:763-781`]. A player holding L during a tap gets
  an unpredictable result.
- **Exefs patches come first.** Eden applies exefs patches (IPS, pchtxt) to the code before the
  load plan, then checks the plan's expected bytes against the patched image and skips the plan
  on a mismatch ("the executable's bytes differ ... or an ExeFS patch"); the game starts without
  it [S1 `src/core/loader/nso.cpp:219-293`]. Patching copies only the sections back, never the
  NSO header, so the build id the gates see is the original [S1 `nso.cpp:222-230`]. This covers
  `PatchNSO` only (log `Patching NSO for name=main` and `Applying IPS patch` / `Applying IPSwitch
  patch` [S1 `patch_manager.cpp:432,448,454`]). A mod that ships its own `subsdk*` (an
  exlaunch-style hook, loaded as a layered exefs file [S1 `patch_manager.cpp:44-45,358`]) changes
  main's code at run time, after this check.
- **Spies** are declared in the data file (`symbols` + `spies`) [S1 `mod_manifest.cpp:2721-2790`],
  armed from the tick loop, fire once each, and log `caught <name> at main+X lr=... x0..x7 d0`;
  Dynarmic only [S1 `mod_guest_bridge.cpp:631-683`, `mod_runtime.cpp:234-243`]. `eden-cli --debug
  <port>` (parsed by the harness, which takes the port [S1 `src/yuzu_cmd/dsmod_harness.cpp:814,844,983`];
  `launch_params.cpp:29`'s `--debug` takes none and is not eden-cli's) turns on the gdbstub [S1
  `src/core/launch_params.cpp:172-175`], which supports write watchpoints (`Z2`) [S1
  `src/core/debugger/gdbstub.cpp:326`]. With the gdbstub on, the guest is suspended at boot until
  a debugger continues ("GDB stub enabled; suspending guest process until a debugger continues
  execution") [S1 `src/core/hle/kernel/k_process.cpp:1021-1027`]. Every new connection pauses the game until the debugger sends `c` [S1
  `src/core/debugger/debugger.cpp:159-167`], and the stub takes a new connection after each close
  (`AsyncAccept` re-arms after every accept [S1 `debugger.cpp:49-56`]). eden-cli saves its whole config
  when start-up ends, after the command line was applied [S1 `src/yuzu_cmd/yuzu.cpp:202,207`,
  `sdl_config.cpp:58-62`, `frontend_common/config.cpp:515-535,604-613`], so a `--debug` run most
  likely writes `use_gdbstub=true` into the config it used (code reading, `UNVERIFIED` by a run):
  A3 runs it on a copied config (`-c`, [S1 `dsmod_harness.cpp:846-847`]). Other gotchas are in
  [S3 PORTING_A_GAME §2.3].
- **Map markers from published keys**: `dynamic_markers` (`count`, `x`/`y`/`kind` with `{i}`,
  `hide_when_kind`, `dim_bind`, `icon_src_bind`) [S1 `mod_manifest.cpp:1183-1262`]; a slot draws
  only with a picture [S1 `mod_ui_map_widget.cpp:1625-1657`]; `on_marker_tap` runs a named action
  with the slot index as `$payload` [S1 `mod_input.cpp:1361-1401`].

### G2. The user's exefs and romfs mods

The user's log shows `!!!!TOTK Optimizer` (UltraCam 3.0.0, patches main code) and `4kTextures`
in `load/0100F2C0115B6000`.
- A1 and A2 patch no code; A2 writes one data value. Exefs patches overwrite bytes in place and
  never resize or move a segment [S1 `src/core/file_sys/ips_layer.cpp:55-100`], and they leave the
  build id alone (G1), so the build gate and the §L2 chains are unaffected. That UltraCam
  writes no data word A1 or A2 reads is `UNVERIFIED` (no lead says it does).
- How UltraCam changes main is `UNVERIFIED`: IPS or pchtxt files in its `exefs` (applied before
  the load-plan check, G1) or a `subsdk*` that hooks main at run time (after it). The user sends,
  with A3 step 2, the `Patching NSO|Applying IP|LayeredExeFS` log lines and the list
  `Get-ChildItem -Recurse "$d\load\0100F2C0115B6000\!!!!TOTK Optimizer\exefs" | ForEach-Object Name`.
  If it is IPS or pchtxt, A3's load plan is checked against the UltraCam-patched code (G1) and a
  changed UltraCam version fails closed (plan skipped, logged). Either way, §A3 step 3's live
  hexdump comparison, taken after boot with and without the mod, is the guard that covers run-time
  hooks too, and it is required: a site or checked prologue that differs is not used, and A3's
  module re-checks its 64-byte hashes on the live image before it enables an action (§A3 step 4).
- `4kTextures` replaces textures; A2 reads BYML and MSBT through `romfs:`. That it replaces
  neither is `UNVERIFIED`; a miss fails soft (A2).
- UltraCam changes the frame rate (settings `UNVERIFIED`). A1 holds its chord for runtime ticks
  (60 Hz [S3 MODULE_GUIDE §1.2]), so the game frames that see it vary: calibrated in Next
  steps 10.

### A1 (coder, now): ability select through the game's wheel

Route: research option A. Hold L and point the right stick at the slot: one `button` chord per
ability, no memory write, no guest call. The game's own wheel makes the change.

**Wheel directions.** The source of truth is one table in `tools/totk/gen_manifest.py`:
```
# shortcut: forum order (clockwise from the top: Ascend, Camera, Ultrahand, Autobuild, Fuse,
# Amiibo, Recall, Map), UNVERIFIED on 1.2.1; replace after the user's look (Next steps 10).
WHEEL = {"ascend": (0, 1), "camera": (0.71, 0.71), "ultrahand": (1, 0),
         "autobuild": (0.71, -0.71), "fuse": (0, -1), "recall": (-1, 0)}
WHEEL_FRAMES = 20  # shortcut: hold time UNVERIFIED (research: 15-30 ticks), tune after the look
```
The order is from forum posts only (research menuinput track; `UNVERIFIED`), and that the
virtual pad's +y is "up" in TotK is `UNVERIFIED`. The user corrects it after one look without a
rebuild: in the installed `<Eden data>\load\0100F2C0115B6000\TotKDuo\dualscreen\manifest.json`
search `"wheel_`, change the six `RStick(x,y)` values (`notepad
"$d\load\0100F2C0115B6000\TotKDuo\dualscreen\manifest.json"`, save as "UTF-8", not "UTF-8 with
BOM"; a BOM is `UNVERIFIED` with the runtime's JSON parser), then, with eden-cli started with
`$env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"` (Next steps 10), run `Set-Content -Encoding ascii
C:\duo\dsmod-cmd.txt reload` (`reload` re-reads the manifest [S1 `mod_console.cpp:426-427`] and
logs `DSMod reloaded: ...` or `DSMod reload skipped: ...` in the log only [S1
`mod_manifest.cpp:2826-2864,3003`]); only the module
libraries carry a checked hash [S2 `tools/build_dualscreen_package.py:227-236`; S1
`mod_module.cpp:169`]. The user sends the six values; the coder updates `WHEEL`.

**Manifest** (generator, regenerated `manifest.json`):
- Six actions `wheel_<a>`, `<a>` in `WHEEL`: `{"kind": "button", "button": "L+RStick(<x>,<y>)",
  "frames": WHEEL_FRAMES, "enabled_bind": "ui.wheel.<a>"}`.
- Six derived values `{"name": "ui.wheel.<a>", "expr": "totk.ability.<a>.owned && !totk.loading"}`.
  Not owned gives 0; a missing owned flag or missing `totk.loading` gives missing; both refuse.
- Home ability bar and Abilities page: tiles 0-5 tap `wheel_<a>` (today `ability_select_<k>`;
  delete those six actions). Tile 6 (Travel Medallion, `totk.ability.travel.owned`) keeps
  `ability_select_6`.
- Abilities page: one dim line "Wheel locked: game loading or not readable" at
  `[1220, 1052, 0, 24]` right-aligned (the footer right of the source line, where nothing else
  is drawn; amended after A1 round 1: `[620, 900, 0, 22]` crossed the row-2 tile borders),
  `need_bind totk.loading` (shown while `totk.loading` is 1, which includes
  an unknown gate: 1.2.0 or a wrong chain).
- `check_manifest.py`: `button` is allowed only for the names in `WHEEL` and only with the gate,
  expr and chord form of CA7(b); every other rule unchanged.

**Module** (`totk_offsets.h`, `totk_status.h`, `0100F2C0115B6000.cpp`, `totk_keys.h`):
- `Offsets` gains four chains, empty in `Offsets{}`, set in `kOffsets121`, each `UNVERIFIED until
  T8c` with its source: `gmd_mgr {0x4721B98, 0x0}` (gives mgr [S38 `GameDataMgr.py:4-8`]),
  `gmd_holder {0x462E3D8, 0x0, 0x0}` (gives `[[main+0x462E3D8]]` [S7 `Totk121Offsets.hpp:30-33`]),
  `gmd_count {0x4721B98, 0xCC0}` (u32 n) and `gmd_loading {0x4721B98, 0xCC8, 0x78}` (u8; the
  `0xCC8` hop is the file pointer, so a null file fails the walk) [S7
  `botw-abilities/src/champions/GameData.cpp:64-73`]. T3c reuses them.
- `ReadLoading(h, base, offsets)` in `totk_status.h`, on T3a's `Walk` and pointer rules: returns
  0 when mgr == holder, 1 ≤ n ≤ 16, the file walk succeeds and `byte & 0x02` is clear; 1 when
  mask 0x02 is set; -1 (unknown) on any other outcome, including every empty chain (zero host
  calls).
- `sample` publishes `totk.loading` (new key in `totk_keys.h`): 0 only after `kLoadSettle` = 60
  consecutive samples of 0 (`shortcut: 1 s settle after loading, tune after T8c`), else 1.
  **Unknown counts as loading.** Justification: `!totk.loading` is a permit for A1's input and
  A2's write. When the gate cannot be read the module cannot tell a boot, title or load screen
  from play, so the safe side is to close the permit. The cost: on 1.2.0, or if a chain is wrong,
  the wheel taps refuse; the log says why. One log line per change of the published value
  (`totk loading: 1 -> 0`), and once per session each unknown cause kind (`totk loading:
  unknown (managers differ | count unreadable | count n | file walk)`, at most 4 lines; amended
  after A1 round 1: the boot's `managers differ`, logged before the game makes GameDataMgr, must
  not hide a later wrong count or file hop); never per frame. `ReadLoading` reports the cause
  as an enum plus `n`, so an unknown frame formats nothing.
- `totk.ability.select` keeps refusing every argument (the manifest sends only 6). Its log line
  becomes `totk: totk.ability.select disabled: the Travel Medallion is placed in the + menu and
  used from the game's map; the companion's medallion move (Map page) waits for T8c`.

**Limits** (for the coder's report; no UI):
- The owned flag comes from the last save (`.source` 1, §Data model), so an ability gained since
  then is refused, and one the game disables for now (`PlayerStatus.IsForceDisableUseSpecialPower`
  0x7152c6f4 [S38 `GMDHash_InfoList.mjs:19626`]) still gets the input.
- Only loading is gated. What L+RStick does in menus, the map, dialogs, shops, while building or
  riding is `UNVERIFIED`. Lead for a wider gate: lotuskit's `engine::module::PauseMgr`
  `mContext.mPauseMask` [S38 `syms/exking/engine/module/PauseMgr.py:4-40`]; instance and fields
  `UNKNOWN: spy processRequests (main+0x7ee454) or requestPause (main+0x102361c) and read x0`
  (both `UNVERIFIED`; not `initialize`, which runs before spies are armed [S1
  `mod_runtime.cpp:661,771`]).
- Two input risks, both `UNVERIFIED` (checked in Next steps 10): if the hold is too short or the
  wheel does not open, the L press uses the currently selected ability (research menuinput track:
  "a later tap of L reuses the last selected ability"); a stick deflection without the wheel turns
  the camera.
- L and the stick release on the same tick (G1). Whether the game still commits the highlighted
  slot then is `UNVERIFIED`. If Next steps 10 shows it does not: `shortcut:` note in the
  generator, upgrade to module gates plus two `enforce` button actions (L held longer than the
  stick) [S3 PACKAGE_FORMAT §3.9].
- No readback until T3d (`PlayerStatus.CurrentSpecialPower` 0xf6e7eacd [S38]).

**Tests** (one per new piece of logic):
- `totk_status_test`: `ReadLoading` on fake memory gives 0 for a full layout; 1 with byte 0x02
  (mask 0x02 set) and 0 with byte 0x04 (another bit);
  -1 for n = 0, n = 17, a null file pointer, holder != mgr, an unmapped hop; `Offsets{}` makes zero
  host calls.
- `totk_module_test`: `totk.loading` is 1 for 59 clear samples and 0 at the 60th; one loading or
  unknown sample puts it back to 1 and restarts the count; a 1.2.0 fixture publishes 1 with zero
  memory calls; `totk.ability.select` 0-6 and out of range all return false; 1000 samples in
  runs of 100 (gate 0, then 1, alternating) log exactly the `totk loading:` lines of the
  published changes, exactly 10 (5 `1 -> 0`, each at its run's 60th sample, and 5 `0 -> 1`; the
  first published 1 logs nothing); 1000 samples alternating unknown causes (holder != mgr, then n = 0) log
  exactly one `totk loading: unknown (managers differ)` and one `totk loading: unknown (count 0)`
  line; sampling from boot (no gate laid out), then n = 17, a null file pointer, an unreadable
  count and n = 20 logs one line per kind in that order (`count 20` logs nothing); CA10 on a
  readable gate: zero allocations in 1000 samples settled at 0 and in 1000 at 1 after the change
  line (`totk_loading_test`).
- `test_check_manifest.py`: the generated manifest passes; each of these fails: a seventh `button`
  action, a `wheel_` action without `enabled_bind`, a gate expr other than the CA7(b) form, a
  chord that is not `L+RStick(x,y)`.
Acceptance: CA1-CA10 as amended; renders of the Abilities page with `totk.loading` 0 and 1; the
critic runs the runtime's chord parser (`InputTokens::ParseChord`) on the six chords. On the PC
(Next steps 10): each of the six taps selects its ability 10 times out of 10 when owned, the
refused haptic while loading, and one try each in a menu and in a dialog with what happened
written down.

### A2 (coder, now): travel

**(a) Destination list (read-only).** Built at runtime from the player's files, never shipped:
- Positions: `romfs:Banc/MainField/LocationArea/MainField.locationarea.byml.zs` and the
  `MinusField` twin; groups `Shrine`, `CheckPoint` (lightroots) and `Tower`; each entry has
  `LocationName`, `Trans[]` (one or more x, y, z) and `InstanceID[]` [S13
  `tools/make_static_list.py:96,252-253` (1.0/1.1 json); S43
  `games/totk/scripts/make_companion_assets.py:537-547`,
  `android/setup/companion/stage_companion.c:473` (1.4.3)]. The label is the `LocationName` file
  stem (`Dungeon001`, `CheckPoint001`, `Tower01`) [S43 `make_companion_assets.py:542`].
- Names: `romfs:Mals/USen.Product.<n>.sarc.zs#LocationMsg/Location.msbt`, in the Mals archive
  the module already finds, next to `ActorMsg/PouchContent.msbt`, labels as above [S43
  `stage_companion.c:203`]. `totk::msbt::LoadNames` gets the member path as a parameter
  (PouchContent stays the default call), and its two `why` strings, which name
  "PouchContent.msbt" today (`totk_msbt.h:149,153`), name the member instead.
- Unlock state from the save: `DungeonState.<label>` (Enum), `ArrivalPointState.<label>` (Enum),
  `IsActivateCannon.<label>` (Bool) [S38 `GMDHash_InfoList.mjs:154661,154254,9920`]. Which values
  mean "travelable" is `UNVERIFIED`; v1 table (`shortcut: enum names Hidden/Appear/Open/Enter/Clear
  and Close/Open from the research travel track, compared as Murmur3 of the name like every enum
  (totk_gamedata.h), confirm in Next steps 12`): DungeonState `Open`, `Enter` or `Clear`;
  ArrivalPointState `Open`; IsActivateCannon true. A key missing from the save is "unknown".
  One `Dest` per `Trans` element, kept only if that element is exactly 3 numbers (float or int
  nodes), finite and inside T3b's world bounds; any other element (2 or 4 entries, a string or
  container node, NaN, infinity, out of bounds) is dropped, and each file logs one line
  `totk travel: <path>: N Trans dropped` (trust boundary: the file is the player's romfs, which
  a romfs mod such as the user's 4kTextures may replace, and the value reaches GameData and the
  save; S43 skips `len(pos) != 3` too [S43 `make_companion_assets.py:543`]). The bounds are
  `ReadPosition`'s (`totk_status.h:141-142`), moved into one `inline bool InWorld(float x,
  float y, float z)` there that `ReadPosition`, this builder and preconditions 4-5 call (NaN and
  infinity fail its comparisons).
- Layer: MinusField = Depths; MainField points use T3b's height rule (`shortcut: altitude is not
  a layer, the ground shrine on Death Mountain is above several sky shrines [S43
  make_companion_assets.py:527-529]; upgrade by matching the Sky LocationMarker hashes in
  Banc/MainField/Sky/*.byml.zs`).
- File names are from 1.0 and 1.4.3, `UNVERIFIED` on 1.2.1. **Fail soft**: a missing or
  unparsable file or msbt gives an empty list (or ids for names) and one log line per file per
  session (`totk travel: <path> missing`), never an error page.
- Code: the module reads each file whole with `read_romfs("romfs:Banc/...byml.zs")`, which patch
  0009 already decompresses with ZsDic (host/eden-duo/0009, §R2; the module links no zstd).
  `totk_byml.h` is the BYML v2-7 parser only, copied from [S2 `native/modules/wonder_catalog.cpp`
  (BYML section and `BymlNode`), `wonder_catalog.h:29-80`] without wonder's zstd path, with its
  GPL-3.0 notice and source line (this repo is GPL-3.0); its test writer (`wonder_catalog_test.cpp:21-110`) goes into
  `totk_test_host.h`. `totk_travel.h` builds `std::vector<Dest>` {label, name, kind 0 shrine /
  1 lightroot / 2 tower, layer, x, y, z, open: 1, 0 or -1 unknown} on the worker thread, once
  (romfs) plus on every new save (unlock state), at most `kMaxDest` = 320 (152 + 120 + 15 = 287 known points [research
  travel track], plus margin for entries with several `Trans`; a longer list is cut and logged).

**UI (Map page, generator).** In area `MainField` one `dynamic_markers` entry: group `travel`,
`count` 320, `x` `totk.travel.{i}.x`, `y` `totk.travel.{i}.y`, `kind` `totk.travel.{i}.kind`,
`hide_when_kind` -1, `dim_bind` `totk.travel.{i}.dim`, `icon_src_bind` `totk.travel.icon`,
`size` 28, `selected_size` 40. The map widget gets `on_marker_tap` `travel_select` (module
`totk.travel.select`, `$payload`) and `marker_tap_groups ["travel"]` (`shortcut: the widget also runs the action on the tap that
deselects a marker, and drops its selection on an empty tap or a page change without telling the
module [S1 `mod_input.cpp:1379-1398,1407-1419,1629-1649`], so the panel keeps showing the last
tapped destination, which is the one "Move medallion here" acts on; a module-side toggle would
drift the other way after an empty tap; upgrade when the host lets a module read
`@map_sel:travel`). The picture is a generated
code-drawn dot `assets/travel-dot.png` (like `map-blank.png`; no game art). A panel
`[16, 884, 1068, 152]` over the map, shown while `totk.travel.sel.valid`: the name
(`totk.travel.sel.name`, kind word, "Locked" or "Unknown" when not open), the last result line
`totk.travel.msg`, one fixed dim line "Close the game's map and menus first" (the
module cannot see them, §A2 limits), and at its right "Move medallion here" (`travel_go`,
module `totk.travel.go`, `need_bind ui.travel.can_go`, a derived value with `expr`
`totk.travel.sel.open == 1`, so an unknown (-1) or locked (0) destination shows no button [S1
`mod_ui_widget_state.cpp:407-409`, `mod_expr.h:12`]). A second panel, shown on
`totk.travel.moved` alone (outside the `sel.valid` gate, so a cleared selection never hides the
old spot), `[16, 96, 1068, 152]` at the top of the map: "Medallion was at x X y Y z Z"
(`totk.travel.old.x/.y/.z`), `totk.travel.msg`, and at its right "Put medallion back"
(`travel_restore`, `need_bind totk.travel.moved`). It overlaps none of the selection panel
(y 884-1036), the layer pills (x ≥ 1100, y 96-320), Centre `[1100, 968, 124, 64]` or the position
line at y 1052, so both panels show at once; both come after the map widget in the page's widget
list, selection panel first, moved panel last (on top, though nothing is under it but the map).

**Keys** (`totk_keys.h`; published only while page 2 is open, prebuilt strings, CA10):
`totk.travel.count`; `totk.travel.{i}.x` and `.y` (x, -z, as `totk.map.x/.y`), `.kind` (-1 when not on
the selected layer), `.dim` (1 unless open); slots past the list are not published (a slot
without `.kind` is not drawn [S1 `mod_ui_map_widget.cpp:1627-1631`]); `totk.travel.icon` = `file:assets/travel-dot.png`
(`icon_src_bind` resolves an image key, package files take the `file:` prefix [S1 `mod_assets.cpp:185`],
as the generator's `file:assets/map-blank.png` [`tools/totk/gen_manifest.py:571`]; the bind has no
`{i}`, so every slot reads this one key [S1 `mod_ui_map_widget.cpp:1524-1530,1638-1645`]); `totk.travel.sel.valid`, `.name`,
`.open`; `totk.travel.moved`; `totk.travel.old.x`, `.y`, `.z` (f64); `totk.travel.msg` (text).
Actions: `kActionNames` gains `totk.travel.select`, `totk.travel.go` and `totk.travel.restore`
(`kActTravelSelect`, `kActTravelGo`, `kActTravelRestore` before `kActions`), each with
`kActionArgs` 0 (select takes `$payload` and checks 0 ≤ arg < `totk.travel.count` itself; go and restore
ignore it), so `check_manifest.py`'s rule that every module action is in `kActionNames` holds.

**(b) Travel Medallion trigger** (research option D, decoded from cheat #30 "Travel Medallion
Mark follows Red Mark" [S9][S40] with [S39 §5T0R10, type 9 op 9, type 0xA]: mgr =
`[main+0x4721B98]`, base = `[mgr+0x438]`, src = `[base+0x2B8]`, P = `[base+0x300]`; it copies
the 12 bytes at src to P). Facts and inferences:
- 0x438 = 8 + 0x0B*0x60 + 0x10: the Vector3Array block's values pointer under §L2's grid
  (Vector3Array is type 0x0B [S11 `Formats/Sav/Readme.md` type table]). Inference, likely.
  totk-mods names 680/872 Enum/Vector2 stores [S7 `Totk121Offsets.hpp:81-87`], which this grid
  calls EnumArray/Vector2Array (types 7 and 9); both are unused in totk-mods; the type-to-block
  mapping for 6-0x10 is `UNVERIFIED`, and step 4 of §A3 "T8c additions" (the probe) decides.
- 0x2B8 and 0x300 fit 0x48-byte entries 9 and 10 with their data pointer at +0x30, the same
  pattern as IntArray's 0x40-byte entry with its pointer at +0x28 (§L2). So the cheat most likely
  copies `MapData.IconData.MapPinData.Pos[0]` (0x5f93e70f) into
  `MapData.IconData.WarpMarkerData.Pos[0]` (0x636b4637), both Vector3Array [S38
  `GMDHash_InfoList.mjs:182492-182493`; S29 `zelda-totk.js:1511,1517`]. Inference, `UNVERIFIED`.
- The player then travels with the game's own map Travel, which streams the area as any
  medallion travel does (the game's own path; `UNVERIFIED` that it re-reads the moved point).

`kOffsets121` additions (each `UNVERIFIED until T8c`, with its source; the `UNKNOWN` ones stay 0
so the trigger refuses until T8c fills them, CA8):
```
medallion   = {4, {0x4721B98, 0x438, 0x300, 0x0}}  // [S9][S40] cheat #30: P = [[[main+0x4721B98]+0x438]+0x300]
v3a_table   = 0x440   // mgr-relative: block 0x428 + 0x18 hash table, +0x20 count, +0x24 size
                      // ([S28 gamedata_live.c:4-7,36-46], 1.4.3 only)
int_table   = 0x140   // the same for IntArray (type 3): block 0x128 + 0x18 (§L2 grid; A3, T3c)
medallion_index = 10  // inference from 0x300 = 10*0x48 + 0x30
medallion_len   = 0   // UNKNOWN: base-relative u32 live length of entry 10; T8c (§A3) reads
                      // base+0x2D0..0x31F placed and picked up; lead base+0x2F8 (IntArray keeps
                      // its length 8 bytes before its pointer, §L2)
medallion_index_placed = {}  // UNKNOWN: {set, s32 value} of WarpMarkerData.Index[0] while placed;
                      // filled instead of medallion_len if T8c's hexdump shows Index tracks it
```
GameData arrays most likely keep their schema length, and S29 lists `WarpMarkerData` as a struct
array (teleporters) [S29 `zelda-totk.js:1514-1518`], so the length word may never change; then
`WarpMarkerData.Index` is the likely signal, and T8c step 3 reads both. The coder fills exactly
one of the two.
The per-entry and per-table layout is fixed, not per build, so it goes into `totk_offsets.h` as
constants next to A3's `kQueue*`, each `UNVERIFIED until T8c` with its source: `kTableCount` 0x8
and `kTableSize` 0xC (count and size after the table pointer at `v3a_table`/`int_table`; block
+0x18/+0x20/+0x24 [S28 `gamedata_live.c:36-46`], §L2), `kTableToValues` 0x8 (the block's values
pointer sits 8 bytes before its table pointer: block+0x10 and +0x18, §L2), `kIntArrayEntry`
0x40, `kIntArrayLen` 0x20 and `kIntArrayData` 0x28 (§L2 [S28 `gamedata_live.c:8-9,24-27,49-50`]). An
IntArray entry is at `[mgr+int_table-kTableToValues] + index*kIntArrayEntry`.
`medallion` follows the module's Walk rule (`totk_offsets.h:5-6`: address = base + hop[0], then
address = u64 at address + hop), with the trailing `0x0` hop that returns the pointer itself, as
`.actor` and `gmd_mgr` do. Its hop results 1, 2 and 3 are mgr = `[main+0x4721B98]`, base =
`[mgr+0x438]` and P = `[base+0x300]`, and ops 1-3 below check exactly those three; the final
address is P. A 3-hop chain would end at base+0x300, the slot holding P, so op 4 would land on
the GameData entry's data pointer.

`on_action` checks (refused, false, `totk.travel.msg` set): build is 1.2.1 (`totk::Is121` at
create); `medallion`, `v3a_table`, `int_table` and `medallion_index` set and one of
`medallion_len` / `medallion_index_placed` set (else "waiting for T8c"); the write extension configured; a selected destination with
`open` 1; `totk.ability.travel.owned` 1 in the save; no medallion already moved ("Put the
medallion back first"); first `go` of each session refused once with "Make a manual save in
game, then tap again" (data-loss rule: a save to fall back on). Then the request is queued.

Preconditions in `Tick` (host reads), all fail closed; any failure drops the request and sets
the message:
1. A1's gate published 0 this sample, and the mgr it read equals the mgr this walk reads.
2. Walk `medallion` hop by hop keeping mgr, base and P; each slot 8-aligned, each pointer
   non-null and ≥ 0x10000, every read through `ReadGuest<MissingIsMapped::Reject>` (T3a rules).
3. Placed check: in the table at mgr+`v3a_table` (pointer, count, size), the probe for 0x636b4637
   finds index `medallion_index`, and the placed signal T8c found holds: either u32
   `[base+medallion_len]` is exactly 1 (0: not placed; > 1: several medallions, and the cheat
   writes element 0 only), or, with `medallion_index_placed` set, the probe for 0xc6dc3b56 in
   `int_table` finds the entry, its length (`kIntArrayLen`) is ≥ 1 and s32 Index[0] at its data
   pointer (`kIntArrayData`) equals the placed value. Why: save with the medallion placed, pick it up, tap:
   every other check still passes. The probe (`totk_status.h`, next to `Walk`;
   one function for any type block; A3 and T3c reuse it on `int_table`) is S28's
   [`gamedata_live.c:4-7,36-46`]: entries {u32 hash, u32 index}, slot = hash % size, next slot on a
   clash, hash 0 = empty. It refuses, with zero probe reads, a table pointer below 0x10000, count 0, count > size,
   and size above `kMaxTable` = 0x10000 (`shortcut: fixed cap so a garbage size cannot spin the
   tick thread, set from the size T8c logs`); then one `dsmod_sdk::RangeMapped(h, table, size*8)`
   (it also refuses size 0 and a wrapping range [S1 `src/core/mods/modules/dsmod_module_sdk.h:119-122`]);
   then at most size probe reads; an index ≥ count is a miss. Every refusal returns which check
   failed with the raw table pointer, count and size it read, so a size above the cap still
   reaches the log.
4. The 12 bytes at P are three floats passing `InWorld` and equal on two
   consecutive ticks (so the request spans two ticks). This only proves P holds floats.
5. The destination: Trans of the selected entry, y + 2.0 (`shortcut: Bivouac's arrival hover
   [S7 bivouac/src/program/engine/MapStampService.cpp:40], tune in Next steps 12`), and that
   value passes `InWorld` again (message "Destination out of the world", no batch): it is the
   only value op 4 writes.

**Read-only report for T8c** (does not depend on the trigger or on `medallion_len`): on 1.2.1,
only while the file `user:totk-re` exists and is non-empty (checked once per session on the worker
and handed to the tick thread as a flag, §A3 step 1; the `totk-dump` pattern, `totk_dump.h:159`), the first time in a session that `totk.loading` publishes 0 the
module runs the probe for 0x636b4637 in `v3a_table` and for `WarpMarkerData.Index` (0xc6dc3b56,
IntArray [S38 `GMDHash_InfoList.mjs:162026`; S29 `zelda-totk.js:1516`]) in `int_table`, and logs
one line per probe, once: `totk travel: v3a_table 0x636b4637 -> index N (size S)` and
`totk travel: int_table 0xc6dc3b56 -> index N (size S); data at 0x<addr>` (addr = the IntArray
entry's data pointer, at `kIntArrayData` in the entry, §A2 layout constants), each replaced on failure
by `totk travel: <table name> probe failed (<which check>; table 0x<ptr> count C size S)` with
the raw values read, so one failed probe never hides the other's result. `kMaxTable` is set from
the larger S. Host reads only, never a batch. No `totk travel:` line at all means the gate never
opened (the `totk loading: unknown (...)` line says why).

**Log on disk.** Eden writes its log in 4 KiB chunks, flushed per line only with `flush_line`
(default off) or on an error line [S1 `src/common/logging.cpp:276-292`, `src/common/settings.h:927`].
The write runs on the caller's thread [S1 `logging.cpp:426-450`] and the flush is an `fflush` [S1
`src/common/fs/file.cpp:335-345`]. The module log ABI has levels [S1
`dsmod_module_abi.h:33-36,177`] and the host maps `EDEN_DSMOD_LOG_ERROR` to `LOG_ERROR` [S1
`mod_module_host.cpp:265-283`], so the medallion state lines below (each starts `totk travel:
medallion` or is `totk travel: move not applied`) are logged at `EDEN_DSMOD_LOG_ERROR`: each is
in the file before the next statement runs, whatever `flush_line` says, and survives an Eden
crash (not a Windows crash or power loss: `fflush` is not a disk sync). Every other module line
stays at INFO. Every eden-cli run rotates the log: `SDL_AppInit` calls `Common::Log::Initialize()`
before it parses any argument [S1 `src/yuzu_cmd/yuzu.cpp:179-181,196`], which constructs the
`FileBackend` [S1 `src/common/logging.cpp:393-403`], which deletes `eden_log.txt.old.txt` and renames
`eden_log.txt` to it [S1 `src/common/logging.cpp:242-248`, `src/common/fs/fs_paths.h:42`]. So
`--install` (T8c), a launch that fails ("No ROM specified" [S1 `yuzu.cpp:209-211`]) and `--help`
count as runs, as a game launch does. A crashed session's lines are in `eden_log.txt` until
eden-cli runs again, in `eden_log.txt.old.txt` after that one run, and gone after the second
(Restore, "After a crash").
`flush_line` is needed only to read the log while eden-cli runs (Next steps 10 and 11, A3
sessions). The user turns it on once: quit eden-cli, `notepad
"$d\config\sdl2-config.ini"` (eden-cli's config, written at its first start [S1
`frontend_common/config.cpp:55-61,883-887`, `src/common/fs/path_util.cpp:114-121`]), and under
`[Miscellaneous]` set both lines `flush_line\default=false` and `flush_line=true` (with
`\default=true` Eden ignores the value [S1 `frontend_common/config.cpp:909-931`]); save, then
`Select-String -Path "$d\config\sdl2-config.ini" -Pattern '^flush_line'` must print both. The log
can then be read while eden-cli runs (it is opened deny-write, not deny-read [S1
`src/common/logging.cpp:249`, `src/common/fs/file.h:127`, `file.cpp:93-94`]).

Then, in this order: log at `EDEN_DSMOD_LOG_ERROR` `totk travel: medallion moved from x=.. y=..
z=.. to <label> x=.. y=.. z=..; if Eden closes before Put back, place it again by hand at the old
x/z` **before** the batch (so a crash during the batch still leaves the old spot in the log), then
`SendBatch`, 4 ops. Every vec3 in a `totk travel: medallion` line (from, to, now at, old spot,
back at) is printed by one `std::snprintf` format, `x=%.9g y=%.9g z=%.9g` of the floats cast to
double: 9 significant digits round-trip a float, so two printed vec3 are equal text exactly when
the floats are bitwise equal (`InWorld` already excludes NaN), which "After a crash" relies on.

| Op | Address | Size | expect | value |
|---|---|---|---|---|
| 1 | main+0x4721B98 | 8 | mgr | mgr |
| 2 | mgr+0x438 | 8 | base | base |
| 3 | base+0x300 | 8 | P | P |
| 4 | P | 12 | old vec3 | destination |

mgr, base and P are always the values this request's own walk (precondition 2) just read. Ops 1-3
stop a re-allocated manager or block from sending the write to stale mapped memory; op 4
is last, so a store failure after the checks can only have rewritten identical bytes (G1).
4 ops < 16, so no `min_runtime` change [S1 `dsmod_module_extensions.h:194-198`].
After a true return: `moved` = {old vec3, dest vec3, label, ticks 60} (no pointers: every later
read walks `medallion` again); publish `totk.travel.moved` 1 and the old x/y/z; message "Open the
map and travel to your medallion". For the next 60 ticks, each tick walks `medallion` with
precondition 2 (a failed walk skips that tick) and reads the vec3 at P:
- equal to the old vec3 (bitwise): message "The game did not keep the move (not applied)", log
  `totk travel: move not applied (medallion still at x=.. y=.. z=..)`, drop `moved` (`UNVERIFIED`
  whether the game reverts it);
- equal to `moved.dest`: nothing;
- any other value (for example the game snapping y to the ground, `UNVERIFIED`): the medallion
  did move, so keep `moved`, `totk.travel.old.*` and the panel. Once that value passes
  precondition 4 (`InWorld`, bitwise equal on two consecutive ticks), set `moved.dest` to it,
  message "The game changed the medallion; its old spot is x y z", log `totk travel: medallion
  changed by the game; now at x=.. y=.. z=..; old spot x=.. y=.. z=..` (once per accepted value:
  the next tick equals the new `moved.dest`). Restore's op 4 then expects the game's value. A value
  that never passes within the 60 ticks leaves `moved.dest` as it was (Restore then reports
  `not where`).
False from the batch: "Not applied, try again", log `totk travel: move not applied`, nothing
kept (`moved` stays clear). So a `moved from` line is closed by exactly one of `move not
applied`, `medallion back at` or `medallion already back`; it stays open while the move stands,
and also after Restore's `medallion not where the companion put it` line, the one drop that
does not close it on purpose: that line asks for the same hand placement as "After a crash".

**Restore** (`totk.travel.restore`, explicit tap only; automatic restore on arrival waits for
T8c to pass T3b's live position: `shortcut: manual restore, automate after T8c`): the same
preconditions 1-4 with a fresh walk, then the same 4-op shape with ops 1-3 expecting the mgr,
base and P that this walk read (so a GameData re-allocation by a load or the travel in between
does not block restore) and op 4 expect = `moved.dest` (the destination, or the game's value
after a `changed by the game` line), value = old.
Before the batch, if the vec3 at P equals the old vec3 (the game reverted the move after the
60-tick window, or the player put the medallion back on that spot): drop `moved`, message
"Medallion is already back", log `totk travel: medallion already back at x=.. y=.. z=..`, no batch. Else, if the vec3 at P is neither `moved.dest`
nor the old vec3 (the game changed it after the 60 ticks, or the player moved the medallion): drop `moved`, message
"The medallion is not where the companion put it; place it by hand at its old spot x y z" (kept
on screen until the next action), log `totk travel: medallion not where the companion put it;
place it by hand at its old spot x=.. y=.. z=..`, no batch. If a precondition fails: keep `moved` and
show that precondition's own message, as go does (for example "Medallion not placed: place it,
then tap again"). If the batch returns false: keep `moved`, message "Not applied, try again".
On success drop `moved`, log once `totk travel: medallion back at x=.. y=.. z=..`. In `destroy`, if
`moved` is set, log once at `EDEN_DSMOD_LOG_ERROR` `totk travel: medallion still moved; place it
by hand at its old spot x=.. y=.. z=..` (no write at shutdown). `destroy` also runs on a console
`reload` (`ReloadManifest` ends in `InitializeGameModule` [S1 `mod_manifest.cpp:3005`]; destroy
on "Shutdown or reload" [S3 MODULE_GUIDE §1.2]), and the new instance starts without `moved`.
Data loss stated plainly: the old spot lives only in module memory, on the Map page (the
`totk.travel.*` keys are published only while it is open) and in the log (G1); the log keeps it
through an Eden crash with no setting, because the state lines are error lines ("Log on disk").
A crash, a close or a console `reload` before "Put back" loses it in the game, and a save made
in between keeps the moved medallion (autosave on travel `UNVERIFIED`).

**After a crash** (or a close or `reload` while the medallion is moved; after a `reload`, quit
eden-cli first, then run the step as after a close, so the next launch rotates that session into
`.old.txt` and the Move-Item below removes it): right after it, before
eden-cli runs again for anything (a game launch, `--install`, a launch that fails, `--help`: every
run rotates the log, "Log on disk"), in PowerShell (`$d = "$env:APPDATA\eden"`), run
`Select-String -Path "$d\log\eden_log.txt.old.txt","$d\log\eden_log.txt" -Pattern
'totk travel: (medallion|move not applied)' | ForEach-Object Line | Tee-Object
C:\duo\medallion-old.txt` (older file first, so the lines are in time order; a missing file
only prints an error; the copy outside `$d\log` survives the next eden-cli runs). In
`C:\duo\medallion-old.txt`, a `medallion moved from` line is open if no closing line (`move not
applied`, `medallion back at`, `medallion already back`) comes after it and before the next
`moved from` line; `changed by the game`, `still moved` and `not where` lines close nothing. An
open line's start is the `x=.. y=.. z=..` right after `moved from`. Its end is the `x=.. y=..
z=..` right after `to <label>` (in `medallion moved from x=1 y=2 z=3 to Dungeon001 x=4 y=5 z=6`
the start is `x=1 y=2 z=3` and the end `x=4 y=5 z=6`), or, if `changed by the game` lines come
between it and the next `moved from` line, the `x=.. y=.. z=..` right after `now at` in the last
of them. Compare only these `x=.. y=.. z=..` values, character for character. Chain rule: take
the LATEST open line; while its start equals the end of an earlier open line, step back to the
latest such earlier line and repeat. The old spot is the start of the line where the chain stops.
All of these vec3 come from the same floats through the same format (§A2 "Then, in this order"), so a companion move from a spot the companion set continues the chain, and a
hand placement in between (other floats; landing on the exact same floats by hand `UNVERIFIED`
but not expected) breaks it, so the player's newest spot wins. Example: session A moves O to D1,
crash; session B, the player places it by hand at O, later at S, moves S to D2, crash: the
latest open line is S to D2, S is not D1, so the old spot is S. Had the player moved from D1
without placing by hand, that line's start D1 equals A's end, the chain steps back, and the old spot is
O. If there is an open line, start the game, load the save, walk to the old spot's x/z (the
companion's Map page shows Link's x/z, §T3b), place the medallion there by hand and make a
manual save in game. Make no companion move before that save: its line would not be in
`C:\duo\medallion-old.txt`, and the rule below does not re-run the step.
Then, while that game still runs, close the record in a second PowerShell
window (the first one runs eden-cli): `cd C:\duo; $d = "$env:APPDATA\eden"; Move-Item -Force
"$d\log\eden_log.txt.old.txt" C:\duo\eden_log-crashed.txt`. If that launch was the first eden-cli
run after the step, `.old.txt` holds exactly the crashed session (or, if the step used the
one-run safety margin, that extra run); after a recovery crash below it holds an earlier
recovery session, which made no companion move. Moving either is harmless. After that save the game's
own record holds the hand-placed spot, so the crashed session's lines are no longer needed;
without the Move-Item, a later crash or close of this session with no companion move would find
the crashed session's open line again and send the player back to its old spot over the
player's newer one. Keep `C:\duo\medallion-old.txt` until that save is made: running the step
again overwrites it (`Tee-Object` without `-Append`). If this recovery session crashes or
closes before that save, do not run the step again: `C:\duo\medallion-old.txt` already holds
the old spot, and a re-run would overwrite it from logs that may no longer hold the crashed
session (the next launch rotates it out, sooner if the safety margin was used). Start the game
again and continue from "walk to the old spot". After the Move-Item,
run the step only as after any crash or close while the
medallion is moved. The moved file keeps the crash diagnostics.
Limit: the second eden-cli run after the crash deletes those lines, so the spot is lost for good
if this step is skipped (`shortcut: the log is the only record, upgrade to a module file under
user: if this happens in practice`). Running the step at once leaves one run as safety margin.

**Limits** (for the coder's report):
- Whether the game's map or a menu is open is not gated (no 1.2.1 lead; A1's loading gate does
  not cover it). Tapping "Move medallion here" with the map open is `UNVERIFIED`: the map may
  show the old icon until it is reopened, or the game may overwrite the write. The panel's fixed
  line asks the player to close the map and menus first; the 60-tick re-read reports a revert.
- The arrival point is the LocationArea `Trans` (the map display position [S43
  `make_companion_assets.py:537-547`]) plus 2.0 in y, and may be inside geometry or mid-air;
  whether the game re-checks the point at travel time is `UNVERIFIED` (research synthesis,
  option D risks). Next steps 12 records, per kind and layer, whether Link lands standing; a bad
  arrival either tunes the `shortcut:` y offset of precondition 5 or drops that kind from the
  list (with the reason in the coder's report).
- `flush_line` cannot be checked by the module; only reading the log during a session needs it
  ("Log on disk").
- After Restore's `not where` line the `moved from` line stays open for the rest of that
  session and the next eden-cli run (its log is then `eden_log.txt.old.txt`, which the step
  reads; Move-Item above runs only after "After a crash", and a `not where` drops `moved`, so a
  normal close logs nothing and the step is not run). If the player places the medallion by
  hand at the old spot, later moves it by hand again, and Eden then crashes or closes in that
  session or the next run, "After a crash" gives the `not where` old spot, not the player's
  newer spot (`shortcut: the log is the only record, upgrade to a module file under user: if this
  happens in practice`).

**Module wiring**: `DSMOD_SDK_EXPORT_WRITE_EXTENSIONS(kWriteExtensions)` next to the export
block (`0100F2C0115B6000.cpp:794-800`; the macro is [S1 `dsmod_module_sdk.h:161-163`]), whose
`configure` copies the host struct; add `eden_dsmod_get_write_extensions` to
`native/modules/0100F2C0115B6000.def` and to the version script in `native/CMakeLists.txt:39`,
which list the exports by hand (CA3 and CA6 list it from A2); `Instance` gains the travel state (tick thread only, like
`page`); the destination list comes with the snapshot from the worker.

**Tests** (`totk_travel_test.cpp`, `totk_module_test`; the fake host gains `write_batch` that
counts `batch_calls`, checks every `expect` and applies to fake memory, and records each log
line's level next to its text (`totk_test_host.h:107-110` drops it today)):
- the BYML fixture (Shrine, CheckPoint, Tower, two Trans in one entry, MinusField) gives the
  expected `Dest` list with names and open states; a missing file gives an empty list and one
  log line; a corrupt BYML gives empty; entries with a 2-element Trans, a NaN element and an
  out-of-bounds x give no `Dest` and one `Trans dropped` line (N 3) for the file; `InWorld` on
  each bound's both sides, NaN and infinity; 321 destinations publish `totk.travel.count` 320 and log
  the cut once;
- the hash probe: a clash resolved to the next slot, a missing hash stopping after `size` probes
  in a full table, a missing hash in a table with an empty slot (hash 0) stopping at that slot
  (probe reads counted: exactly the slots up to it [S28 `gamedata_live.c:41-44`]),
  size 0 and a count above size refused; size 0x10001 (above `kMaxTable`) refused with zero
  probe reads and zero `is_mapped` calls, and the report's failure line holds the table pointer,
  the count and `size 65537`; an unmapped table range refused with zero probe reads;
- go on a full fake layout where P is not base+0x300 (P in another fake block): one batch, ops
  1-3 identity at main+0x4721B98, mgr+0x438 and base+0x300, op 4 at P (never at base+0x300),
  expect old, value destination with y + 2.0; the "medallion moved from" line comes before the
  batch and is recorded at level 3 (`EDEN_DSMOD_LOG_ERROR`);
- go to a destination whose Trans y is 3499 (in bounds, 3501 after + 2.0) gives zero batches
  and "Destination out of the world";
- with page 2 open, `totk.travel.icon` publishes exactly `file:assets/travel-dot.png`;
- go with the write extension unconfigured (null `write_batch`) is refused with its message and
  zero batches; a go batch that returns false (op 4 expect mismatch) leaves `moved` clear, sets
  "Not applied, try again" and records `totk travel: move not applied` at level 3 after the
  `medallion moved from` line; a mgr that changes between the gate's read and the walk gives
  zero batches;
- the read-only report: committed `kOffsets121` (with `medallion_len` 0) plus a fake GameDataMgr
  with both tables and `user:totk-re` = "1" gives the `totk travel: v3a_table 0x636b4637 ->
  index 10 (size S)` and `totk travel: int_table 0xc6dc3b56 -> index N (size S); data at
  0x<addr>` lines once after `totk.loading` turns 0, with `batch_calls` 0; with the int table's
  count above its size, the v3a success line plus `totk travel: int_table probe failed (count >
  size; ...)`; without the file, or with an empty one, no line and no probe reads;
- each gives zero batches and its message: 1.2.0 (zero memory calls), `medallion_len` 0 (the
  committed `kOffsets121`), length 0, length 2, hash mapped to 9, hash missing, `totk.loading` 1,
  holder != mgr, NaN or out-of-bounds old vec3, old vec3 changing between the two ticks, not
  owned, destination not open, a second go while moved, the first go of the session;
- with `medallion_len` 0 and `medallion_index_placed` {set, V}: Index[0] == V gives one batch,
  Index[0] != V and a missing 0xc6dc3b56 give zero batches and "not placed";
- restore: expect `moved.dest`, value old; a successful restore clears `moved` and logs
  "medallion back at" once at level 3; the vec3 at P holding a third value drops `moved` with the "not where
  the companion put it" message, zero batches and its log line recorded once at level 3; the vec3
  at P == old (set after 60 ticks) gives zero batches, "Medallion is already back", `moved`
  cleared and the `medallion already back` line recorded once at level 3, and `destroy` then
  logs nothing;
  a failed batch with the vec3 at P == destination keeps `moved` with "Not applied, try again";
  the placed check failing keeps `moved` with "Medallion not placed: place it, then tap again";
  after go, the fake moves the block (new base at mgr+0x438, new P at base+0x300 holding the
  destination): restore still sends one batch, ops 2-4 at the new base and P;
- after go, within 60 ticks: the vec3 at P set back to old drops `moved` with "not applied" and
  one level-3 `totk travel: move not applied` line after the `moved from` line; set
  to a third value (in bounds, held two ticks) keeps `moved`, `totk.travel.moved` 1 and
  `totk.travel.old.*` published, with "The game changed the medallion" and the `medallion
  changed by the game; now at` line (the third value) recorded once at level 3; a restore then
  sends one batch whose op 4 has expect = the third value and value = old, and logs `medallion
  back at` once at level 3; a third value seen on one tick only leaves `moved.dest` unchanged;
- the vec3 format: the `x=.. y=.. z=..` after `to <label>` in one `moved from` line and the
  `x=.. y=.. z=..` after `moved from` in a later line, for the same floats, are equal strings, and two floats one ulp apart give different strings;
- `destroy` with `moved` set logs the "place it by hand" line with the old x/y/z exactly once,
  at level 3, and makes zero batches; without `moved`, no line.
Acceptance: CA1-CA10 as amended; Map page renders with markers on each layer, a selected open
destination with "Move medallion here", a locked and an unknown one without it, and the moved state with the old coordinates and the Put back button,
once with a selection and once with none. On the PC (Next steps
12, after T8c fills the fields): one move, travel and put back for a Surface shrine, a Sky shrine,
a Depths lightroot and a tower; for each, whether Link lands standing (a bad arrival tunes the
`shortcut:` y offset or drops that kind, §A2 Limits); the map icon follows the move and is back
after "Put back"; the log holds both coordinate lines. Until then: the markers
and names show, and "Move medallion here" refuses with "waiting for T8c".

**After A2's trigger passes T8c**: tile 6 (Travel Medallion) taps `ui_page_2` (the Map page)
instead of the refused `ability_select_6`; the refusal line goes.

### A3 (user + coder, later): equip and eat on 1.2.1

No public 1.2.1 equip or eat function exists. AurelioB/totk-android calls the game's own pouch
functions on 1.4.3, device-tested: `bool equip(pouch, cat, slot, -1)` main+0x20a1128, `unequip`
0x20a9a28, `is_equipped` 0x20ac5c4, `can_use` 0x20ac080, `bool eat(pouch, cat 5|6, slot)`
0x20af148, each checked by a SHA-256 of its first 64 bytes [S43
`games/totk/runtime/companion_actions.c:62-90`], with the game's refusals (full hearts, no stock,
weapon locked) [S43 `companion_actions.c:230-262`]. Its 1.2.1 counterparts are `UNKNOWN`; the one
1.2.1 pouch lead, `PouchMgr::consumeMaterial(mgr, slot)` main+0x1A6CBB0, is only hooked by
Bivouac, never called, so what it does is `UNVERIFIED` [S7 `bivouac/src/program/runtime/
BivouacRuntime.cpp:54,425`]. Route: find the 1.2.1 functions by watching the GameData write
queue their writes go through (step 1 says why not the arrays), then call them from a BotW-style load-plan helper (research option C [S2 `native/helper/botw/`,
`tools/botw/build_helper_plan.py`]).

1. **Coder (small):**
   - **Why the watch goes on the write queue, not on the arrays.** On 1.4.3 a write watch on
     `EquipIndex` stops in the generic GameData setter called from the per-frame flush
     (main+0x3e59a4 -> 0x3e5dfc -> 0x74240), "so the caller that queued it is not on that stack"
     [S28 BOTTOM_SCREEN §5 H]; 1.2.1 queues its writes too (§L2). The push into the queue runs on
     the caller's stack. totk-mods checks room in per-type queue stores at mgr+8, +200 and +392
     before its set calls [S7 `botw-abilities/src/champions/GameData.cpp:47-50`], and at mgr+200
     and +296 before it adds to the pouch [S7 `runtime-support/include/totk/engine/KeyItemGrant.hpp:43-44`];
     a store holds its capacity at +40, the buffer pointer at +48 and the write index in the low
     20 bits of the u32 at +56 [S7 `runtime-support/include/totk/engine/Totk121Offsets.hpp:81-87`,
     `GameDataQueue.hpp:39-45`]. 296 = 8 + 3*0x60 is the IntArray block on §L2's grid (type 3),
     and `EquipIndex` and `StockNum` are IntArrays [S29], so the watch is on the u32 at
     mgr+296+56 = mgr+0x160. That a push writes this word is an inference (`UNVERIFIED`); the log
     line below gives the coder capacity and buffer to check the store first. Spying a setter
     instead does not work: the one known 1.2.1 setter, `kGameDataSetInt` main+0xB51B08 [S7
     `Totk121Offsets.hpp:33`], is the Int setter (store +200), not IntArray, and a spy fires
     once, on the first call from anywhere [S1 `mod_guest_bridge.cpp:631-683`].
   - `kOffsets121` gains `ia_queue = 0x128` (mgr-relative IntArray queue store, sources above,
     `UNVERIFIED until A3 step 2`; 0 in `Offsets{}`), and `totk_offsets.h` the store offsets
     `kQueueCapacity` 0x28, `kQueueBuffer` 0x30, `kQueueControl` 0x38 with the same source.
   - Trigger: the worker checks the file `user:totk-re` once per session, after the romfs
     answered, as it runs `dump::Run` (`0100F2C0115B6000.cpp:373-380`, `totk_dump.h:159`; §T8a),
     and hands the result to the tick thread through the mutex-guarded `re` flag that Sample
     copies to `report`, so `sample` does no file I/O [S3 MODULE_GUIDE §9]. §A2's read-only report uses the same flag. With it set, once per
     session after `totk.loading` first publishes 0, the module logs (host reads only)
     `totk re: IntArray queue control at 0x<mgr+0x160> (capacity C, buffer 0x<b>); hashes
     Pouch.Weapon.EquipIndex 0x.. Pouch.Shield.EquipIndex 0x.. Pouch.Bow.EquipIndex 0x..
     Pouch.Food.Content.StockNum 0x..` (hashes from the module's Murmur3, `totk_gamedata.h`), with
     `(store unreadable)` in place of the capacity and buffer when either read fails; the address
     is still logged. A missing line means the gate never opened (§T8c additions). Test: the fake GameDataMgr gives
     the fixture's address, capacity and buffer in one line, once; without the file, with an empty
     one or with `ia_queue` 0, no line and no reads.
   - `tools/totk/rsp_watch.py` (stdlib only), two modes. `rsp_watch.py <host:port> go`: connect
     (retry every 0.5 s for up to 60 s, since the gdbstub may not listen yet right after the
     launch; on timeout print `no gdbstub at <host:port> after 60 s; is --debug set?` and exit 1),
     send `c`, close the socket while the game runs (no reply comes until the next stop), for the
     boot suspension (G1). `rsp_watch.py <host:port> <addr> <len> <hash>`: connect, read the main
     base (`monitor info`), set one write watchpoint (`Z2,<addr>,<len>`; Eden answers `E01` for
     an unmapped range [S1 src/core/debugger/gdbstub.cpp:311-314]: exit 1 with the error message,
     no watchpoint armed), `c`. At each stop: if one
     of x0-x15 holds `<hash>` in its low 32 bits, print pc, lr, x0-x7 and 16 frames of the x29
     chain as `main+X` and finish; else print `skip pc=main+X lr=main+Y`, remove the watchpoint,
     single-step, set it again, `c`. It finishes after the match or 200 stops: remove the
     watchpoint, `c`, and close only while running (gotchas [S3 PORTING_A_GAME §2.3]); after an
     error past the `Z2` it still sends a best-effort `z2` and `c` (Eden keeps a watchpoint in its
     KProcess slots after the client is gone, so its next hit would freeze the game with no client).
     `shortcut: the hash filter assumes the push still holds the hash in a register (UNVERIFIED),
     print every stop's frames if no row matches`. `shortcut: Eden's step clears the exclusive
     monitor [S1 src/core/arm/dynarmic/arm_dynarmic_64.cpp:359-361], so a skip at an LDXR/STXR
     store repeats at one pc until 200 stops; upgrade the skip to z2, c, \x03 (Eden's interrupt),
     Z2, c if a row shows it`. Tests: a fake RSP server feeds two stops, the
     first without the hash (one `skip` line, then `z2`, `s`, `Z2`, `c` in that order) and the
     second with it (the frames printed, then `z2`, `c`, the close); in `go` mode it receives
     exactly one `c` and then the close, with no packet after `c`, also when the fake server
     starts listening 2 s late; with no server, the timeout message and exit 1 (the test patches the
     script's 60 s constant, no new option).
2. **User sessions** (PC, PowerShell as §T8a; Python 3 from python.org for `py -3`; copy
   `tools/totk/rsp_watch.py` to `C:\duo`; `flush_line` already on, §A2 "Log on disk", so the
   addresses can be read from the log during the session). Make a manual save in game, quit, then:
   ```
   cd C:\duo; $d = "$env:APPDATA\eden"
   Copy-Item -Recurse "$d\nand\user\save" "C:\duo\save-backup-re-$(Get-Date -Format yyyyMMdd-HHmm)"
   Copy-Item -Force "$d\config\sdl2-config.ini" C:\duo\re-config.ini
   New-Item -Force -ItemType File "$d\dualscreen\user\0100F2C0115B6000\totk-re" -Value 1
   Remove-Item C:\duo\dsmod-cmd.txt.out -ErrorAction SilentlyContinue
   $env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"
   .\eden-cli.exe -c C:\duo\re-config.ini --aux-window --debug 6543 -g <TotK 1.2.1>
   ```
   The copied config is the isolated profile: the `--debug` run writes `use_gdbstub=true` into
   the config it used (G1), so it lands in `re-config.ini`, never in your normal config. The saves
   are shared, hence the backup (a game frozen at a stop and then killed can lose unsaved
   progress). The guest waits at boot for the debugger (fact, G1): right after the launch, in a
   second window, `py -3 C:\duo\rsp_watch.py 127.0.0.1:6543 go` (it waits up to 60 s for the stub;
   on its `no gdbstub` message, run it again before restarting). Load the save. To **relaunch**
   (below): in the first window (the one that ran these lines; `$env:EDEN_DSMOD_CMD` is still set
   there) run only the `.\eden-cli.exe` line again, not the backup, `Copy-Item`, `New-Item` or
   `Remove-Item` lines; open a new second window, run `cd C:\duo; $d = "$env:APPDATA\eden"` and
   the `go` line, load the save, read the new `totk re:` address as below, and go on with the next
   row. Then in the second window (`cd C:\duo; $d = "$env:APPDATA\eden"`):
   `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk re:' | ForEach-Object Line` prints
   the queue address and the four hashes (the address changes every boot; it is the same for
   every row of one boot). If the line says `(store unreadable)`, still run the rows with its
   address; a row whose script ends with an `error:` line follows the error rule below, and the
   line goes in `totk-log.txt` as usual. Each row in this order: (1) in game, get to the last button press
   (for the + menu rows: menu open, cursor on the item); (2) in the second window run
   `py -3 C:\duo\rsp_watch.py 127.0.0.1:6543 <addr> 4 <hash> | Tee-Object C:\duo\watch-<row>.txt`
   with that row's hash; (3) do the action; (4) wait until the script ends; if the script prints
   no new line within 30 s of the action or of its last line, close its window, write down the row, quit eden-cli and
   relaunch as above (a closed script leaves its watchpoint armed until eden-cli exits). If the script
   ends with an `error:` line, run the `go` line if the game is frozen, check the address and hash
   you typed, and run that row once more; if it fails again, write down the row and go on. Rows: (a) equip
   another weapon from the + menu (hash `Pouch.Weapon.EquipIndex`); (b) the same from the D-pad
   quick menu (hold right, flick the stick, let go); (c) a shield (`Pouch.Shield.EquipIndex`);
   (d) a bow (`Pouch.Bow.EquipIndex`); (e) eat one food from the + menu
   (`Pouch.Food.Content.StockNum`); (f) the same with one elixir. A script that prints 200 `skip`
   lines ends by itself: write down which row. Each connection pauses the game until the script
   sends `c` (`AcceptConnection` calls `PauseEmulation` [S1 `src/core/debugger/debugger.cpp:159-167`]),
   and the stub takes a new connection after each close (`AsyncAccept` re-arms after every
   accept [S1 `debugger.cpp:49-56`]). If the game stays frozen after a script ended, run the `go`
   line once; if it freezes again, write down the row, quit eden-cli and relaunch as above. Last,
   `Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt 'chain 0'` (its bracketed address is the main
   base), quit eden-cli, and
   `Remove-Item "$d\dualscreen\user\0100F2C0115B6000\totk-re"`.
   Send: every `watch-*.txt`, `dsmod-cmd.txt.out`,
   `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk|DSMod|Patching NSO|Applying IP|LayeredExeFS' |
   ForEach-Object Line | Set-Content -Encoding utf8 totk-log.txt`, and
   `Get-ChildItem -Recurse -LiteralPath "$d\load\0100F2C0115B6000\!!!!TOTK Optimizer\exefs" | ForEach-Object Name`
   (G2). Nothing from it is committed.
3. **Coder, then user (spy and UltraCam check):** in the matched stops, the frames that every row
   shares are GameData internals (the push, the setter); the first frame past them that differs
   between rows is a candidate caller. The coder picks candidate functions there whose register
   pattern matches the 1.4.3 roles (x1 = category, x2 = slot, x3 = -1 for equip; category 5 or 6
   for eat). **If no row matched its hash, or every row's chain is the same past the internals**
   (the watch saw only the flush or one per-frame writer), there is no candidate: equip and eat
   stay refused and the coder says so in the report. A row that ended with 200 `skip` lines at
   one pc is a tool failure (the exclusive-store shortcut in step 1), not a row without a match:
   it does not count toward this decision; the coder makes the skip upgrade named there and the
   user reruns that row. One pc alone does not prove an exclusive store: every IntArray push
   shares the push code, so 200 plain pushes before the target also give 200 skips at one pc.
   So the rule applies once: after the skip upgrade, a rerun row that again ends with 200 `skip`
   lines counts as a row without a match. The one other known route is the quick-menu
   input with readback (hold the D-pad, flick the stick, read `EquipIndex` back; Thor-tested on
   1.4.3 for weapons, not bows or food [S28 BOTTOM_SCREEN §5 H]); it is not what D3 was told, so
   it needs a new user decision. Otherwise the coder sends two files: `data.json` for the installed package,
   `{"symbols": {"eq": "main+0x<X>", ...}, "spies": [{"name": "equip", "symbol": "eq", "reg": 0}, ...]}`
   (one spy per address, each fires once per session [S1 `mod_guest_bridge.cpp:631-683`]), and
   `sites.txt`, one hex main offset per line for each candidate site and function. The user saves
   both in `C:\duo` and installs the fragment over the package's `data.json` (`{}` today):
   `Copy-Item -Force C:\duo\data.json "$d\load\0100F2C0115B6000\TotKDuo\dualscreen\data.json"`.
   Run 1, as installed, without the debugger and with your normal config:
   `Remove-Item C:\duo\dsmod-cmd.txt.out -ErrorAction SilentlyContinue;
   $env:EDEN_DSMOD_CMD = "C:\duo\dsmod-cmd.txt"; .\eden-cli.exe --aux-window -g <TotK 1.2.1>`, load
   the save, repeat rows (a)-(f), then in the second window read the main base and dump each site:
   ```
   Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt 'chain 0'; Start-Sleep 2
   $m = Select-String -Path C:\duo\dsmod-cmd.txt.out -Pattern 'main\+0: \[([0-9A-F]+)\]' | Select-Object -Last 1
   $b = [Convert]::ToUInt64($m.Matches[0].Groups[1].Value, 16)
   foreach ($x in Get-Content C:\duo\sites.txt) {
     Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt ('hexdump {0:X} 0x40' -f ($b + [Convert]::ToUInt64($x, 16)))
     Start-Sleep 2
   }
   ```
   (`chain 0` prints `main+0: [<main base>]=...`, `hexdump` takes an absolute address [S1
   `mod_console.cpp:730-757,865-890`].) Quit, then `Copy-Item C:\duo\dsmod-cmd.txt.out
   C:\duo\hexdump-installed.txt` and, before run 2 rewrites the log, `Select-String -Path
   "$d\log\eden_log.txt" -Pattern 'caught' | ForEach-Object Line | Set-Content -Encoding utf8
   C:\duo\caught.txt`. Then restore the empty data file, so run 2 plants no spies (every spy is a
   breakpoint written over its site's first word until it fires [S1
   `mod_guest_bridge.cpp:606-626,665-667`]): `Set-Content -Encoding ascii
   "$d\load\0100F2C0115B6000\TotKDuo\dualscreen\data.json" '{}'`. Run 2, without UltraCam:
   `Move-Item -LiteralPath "$d\load\0100F2C0115B6000\!!!!TOTK Optimizer" C:\duo\ultracam-off`,
   the same launch, load the save (rows (a)-(f) are not needed), the same block in the second
   window (its own `chain 0`: the base can differ per boot), quit, `Copy-Item
   C:\duo\dsmod-cmd.txt.out C:\duo\hexdump-plain.txt`, and put the mod back: `Move-Item
   C:\duo\ultracam-off "$d\load\0100F2C0115B6000\!!!!TOTK Optimizer"`. Send
   `hexdump-installed.txt`, `hexdump-plain.txt` and `caught.txt`. A function whose spy has no
   `caught` line is not used: in run 1 its first word is still the breakpoint. A site or prologue
   that differs between the two runs is not used (G2).
4. **Coder (after D4):** a format-2 load plan for build 9B4E4365 only: one patched BL at a
   UI-thread call site the frames show (`UNKNOWN` until step 2; the BotW shape replaces BL sites
   and re-calls the original [S2 `native/helper/botw/warp-ui-hook.S`]), a mailbox, and an AArch64
   helper that re-validates the request on the game thread and calls the found equip or eat. The
   expected bytes are the words from the as-installed run that equal the no-UltraCam run, so the
   plan applies with or without UltraCam and is skipped (logged, game starts) on any other bytes
   (G1). The module checks the called functions' 64-byte SHA-256 (the totk-android pattern) on the
   live image before it enables the actions, and applies the game's own refusals (full hearts for
   heart-only food, no stock, weapon locked [S43 `companion_actions.c:230-262`]) plus
   `!totk.loading`. A manifest `call` on Dynarmic stays a test tool only: the frame hook is
   `UNKNOWN`, the call runs on whatever thread hits it, and it reports done even when nothing ran
   [S1 `mod_actions.cpp:691-701`]. Arrows: likely nothing to equip (one plain arrow kind [S43
   `companion_actions.c:259`]), though `Pouch.Arrow.EquipIndex` exists [S29]; armor's equip key is
   `UNKNOWN` (§Data model). CA7 gets one more amendment then, for exactly those actions.
   Acceptance then: each equip and eat confirmed by readback (T3c's live `EquipIndex` and
   `StockNum`) 10 times out of 10 on the PC, with UltraCam installed.

**T8c additions for A1 and A2** (Next steps 11). `flush_line` on (§A2 "Log on disk"). Before
the launch, turn on the read-only report: `New-Item -Force -ItemType File
"$d\dualscreen\user\0100F2C0115B6000\totk-re" -Value 1` (an empty file counts as missing,
`totk_dump_test.cpp:245`). Start as §T8c step 3 (with
`EDEN_DSMOD_PROFILE`), load a save that has the Travel Medallion (step 2 places it), make a
manual save in game, then run these in that session; T8c's steps 4-5 are not needed for them. Console lines into
`dsmod-cmd.txt` as in §T8c step 4; at the end quit, `Remove-Item
"$d\dualscreen\user\0100F2C0115B6000\totk-re"`, run `Select-String -Path "$d\log\eden_log.txt"
-Pattern 'totk loading|totk travel|DSMod' | ForEach-Object Line | Set-Content -Encoding utf8
C:\duo\totk-log.txt`, and send `C:\duo\dsmod-cmd.txt.out` and `C:\duo\totk-log.txt`. If `Select-String -Path "$d\log\eden_log.txt" -Pattern
'totk travel:' | ForEach-Object Line` prints nothing after the save loaded, the loading gate never
opened: send the `totk loading: unknown (...)` line instead, skip steps 3-4 (they need the
report), and the coder fixes the gate first (A1).
1. Loading gate: `chain 4721B98`, `chain 462E3D8 0x0` (the two must be equal), `chain 4721B98
   0xCC0` (@dst word 0 = n; words 2-3 = the file pointer) and `chain 4721B98 0xCC8 0x78` (@dst
   word 0, low byte: mask 0x02 = loading), standing still; then enter a shrine or travel natively,
   wait until the game shows the world again, and run `Select-String -Path "$d\log\eden_log.txt"
   -Pattern 'totk loading' | ForEach-Object Line`: it must print a `totk loading: 0 -> 1` line
   and then a `totk loading: 1 -> 0` line.
2. Medallion chain: stand still, place the medallion at your feet, then run `chain 46AF308 0x480
   0x2B4` (Link) and `chain 4721B98 0x438 0x300 0x0` (the medallion). That is all: `@dst` prints
   the floats as bit patterns, and the coder decodes and compares them [S1 `mod_console.cpp:751-755`].
3. Entry 10, in three states: the medallion placed, then picked up, then placed elsewhere. Save
   these lines once as `C:\duo\entry10.txt` (base+0x2D0 to base+0x31F, 16 bytes each):
   ```
   chain 4721B98 0x438 0x2D0
   chain 4721B98 0x438 0x2E0
   chain 4721B98 0x438 0x2F0
   chain 4721B98 0x438 0x300
   chain 4721B98 0x438 0x310
   ```
   In each state, in the second window: `foreach ($l in Get-Content C:\duo\entry10.txt) {
   Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt $l; Start-Sleep 2 }`, then
   `Set-Content -Encoding ascii C:\duo\dsmod-cmd.txt 'hexdump <addr> 0x10'; Start-Sleep 2` with
   `<addr>` the `data at 0x...` of the report's `totk travel: int_table` line (a heap address, new
   every boot; `Select-String -Path "$d\log\eden_log.txt" -Pattern 'totk travel:' |
   ForEach-Object Line` shows it). If that line is `totk travel: int_table probe failed (...)`,
   there is no address: skip the `hexdump` and send the failure line. The coder looks for a word
   that tracks pick-up: one in the chain words gives `medallion_len`, one in
   `WarpMarkerData.Index` gives `medallion_index_placed` (§A2). If neither tracks it, the trigger
   stays refused.
4. Hash tables: the two `totk travel:` lines, one per table. `v3a_table 0x636b4637 -> index N
   (size S)`: N must be 10; `int_table 0xc6dc3b56 -> index N (size S)`; the larger S sets
   `kMaxTable`. A `<table name> probe failed` line holds that table's raw pointer, count and size
   (§A2 precondition 3), so a size above the cap shows there too.
5. Wheel: Next steps 10, if not done yet.
Result: the coder fills `medallion_len` or `medallion_index_placed`, drops `UNVERIFIED until T8c`
from each field that passed, and the trigger becomes live in Next steps 12.

## What needs the user

| Item | For | What exactly |
|---|---|---|
| D2 target version | T2a, T2 | Answered: 1.2.0 |
| D2b | T2b, T3a-T3d, T8c | Answered (user, 2026-11-02): live data on 1.2.1. Needs the user's own v393216 update file (Next steps 5) |
| D3 | A1-A3 | Answered (user, 2026-11-07): stay on 1.2.1 and do all four actions; read-only rule CA7 amended only for A1 and A2, each behind fail-closed gates |
| D4 | A3 | Open: may the load plan commit the few instruction words its patch sites must check (BotW's plan does [S2]), or must it check SHA-256 hashes only? Asked when A3's sites are known |
| Wheel look, A2 run, A3 sessions | A1, A2, A3 | Next steps 10-13; `flush_line` on once, to read the log during a session (§A2 "Log on disk"); for A3 the UltraCam `exefs` file list and patch log lines (§G2) |
| Approve vendoring zstd | T3a | Answered: approved. No longer vendored in the module (§R2); the same files build patch 0009's test outside the repo (hashes in §T3a') |
| Approve vendoring stb_truetype | T3a' | Answered: approved; vendored as `native/third_party/stb_truetype.h` v1.26 (public domain) for the game font (§R2) |
| T8a dump | T8a, T4v | The `totk-dump` file, one start of TotK (1.2.1 if you update, else 1.2.0; Next steps 5-6) with the CI-built eden-cli (patches 0001-0011) and companion package, the `totk`/`DSMod` log lines and the `imgdump` PNGs the coder lists (PowerShell steps in §T8a) |
| Reference look decisions | T4v | Accepted by the user: pages Map / Inventory (home) / Abilities with Gear in the home page's right column; no 3D Link figure (§R3), the equipped weapon at 256 px instead; English only |
| T8 run | T8 | Package installed in the CI-built eden-cli, one save loaded, `.shot` pictures per page, `totk`/`DSMod` log lines, values compared with the game |
| eden-duo fork + Windows build | H1c | A fork repo for the H1 patches, and either a Windows build on their PC (MSVC + Ninja, `tools/windows/load-msvc-env.ps1` [S1 docs/Build.md]) or a GitHub Actions Windows runner in the fork |
| Windows tests | H1c, H2 | Install the window-host MSI from `apollo2-vibe` branch `claude/clever-clarke-k7hhhj`, add the "TotK duo" app (H2), run the H1c and H2 checks; send host logs and `.shot` screenshots |
| Vibe Window APK | H3 | Install the H3 client build on the Thor and turn on Settings -> "Dual screen" (keep "Show only the app window" on) |
| Build id | T2 | The `DSMod module: loaded ... build <hex>` log line, or when the package is refused the `Querying NSO patch existence for build_id=<hex>, name=main` line [S1 `patch_manager.cpp:473-475`] (T8a, T8c filter `totk|DSMod|build_id=`), or `xxd -s 0x40 -l 32 -p main` from `eden-cli --dump-romfs` of base + update [S3 PORTING_A_GAME §1] |
| Live checks on 1.2.1 | T8c, T8d | Save backup, the 1.2.1 update, the console `chain` lines and in-game changes of §T8c; the GameData lines and ability scan of §T8d. Nothing from it is committed |
| Test saves | T8, T8c, T8d | Isolated profile copies: Great Sky Island; Surface with Ultrahand/Fuse/Ascend/Recall; late game with Autobuild and Depths |
| Thor | H2, H3, T8 | Stream "TotK duo" with Dual screen on; check both screens and touch |

## Host path (decided: C)

| Path | Where the game runs | New work outside the companion |
|---|---|---|
| A. On device | Eden Duo on the Thor | None |
| B. Linux host | eden-cli on a Linux PC, streamed | H1b, H2, H3 |
| **C. Windows host (chosen)** | eden-cli on Windows, streamed by apollo2-vibe | H1a, H1b, H1c, H2, H3 |

Path A stays the fallback: the same package works there with an `android-arm64-v8a` module.

## Risks

- **Wrong build** reads garbage or corrupts a save: three gates, read-only except A1's input and A2's one
  batch (D3), every write guarded by expected bytes [S3 PORTING_A_GAME §5]. The save extension has no write path at all
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
- **Live chains are cheat leads, unchecked in Eden** [S9][S40]. A chain that resolves to the wrong
  object gives plausible wrong numbers. The guards are the exact build gate, the `Player` name
  check, pointer rules, plausibility bounds and the double read; T8c compares with the HUD before
  `UNVERIFIED` is dropped.
- **Live read cost on the tick thread**: about 45 pointer hops, read twice, per `sample`
  (T3a + T3b). T8c reports the `DSMod profile` line; the reads move to the worker if the cost
  shows.
- **1.2.1 update and saves**: whether 1.2.0 saves load unchanged is `UNVERIFIED`; back them up first
  (T8c step 1). The save reader has no version gate (`totk_save.h`).
- **Missing live domains**: abilities owned, effect labels, armor, time, weather, temperature and
  the current ability have no 1.2.1 lead (§L2). They stay on the save or are absent until a scan
  finds them.
- **Performance**: module `sample` must not allocate, do file I/O or log per frame; measure
  [S3 MODULE_GUIDE §9].
- **Game actions (A1-A3)**: injected input lands in whatever state the game is in; only loading
  is gated (§A1 limits). The A2 medallion move lives only in module memory, so a crash, a close
  or a console `reload` before "Put back" loses the old spot in the game: it is shown on the Map
  page, and the log keeps it through an Eden crash with no setting, as error lines (§A2 "Log
  on disk"), but only until the second eden-cli run of any kind after the crash (log rotation,
  even `--install` or a failed launch): §A2 "After a crash", run right after the crash, is the
  recovery step. The arrival point may be inside geometry or mid-air (`UNVERIFIED`, §A2 Limits). A3 code patches must match UltraCam's code, checked
  live with and without it (§G2: how UltraCam patches is `UNVERIFIED`), or they are not used.
  Details in each packet.
- **Licensing**: Eden Duo, the companions, objmap-totk and Artemis are GPL-3.0
  [S1][S2][S13][S25]; this package is GPL-3.0-or-later. totk-mods is MIT [S7]. Cheat files and
  TkSharp have no license found: numbers as leads only, no copied code.

## Sources

| # | URL | What it proves |
|---|---|---|
| S1 | https://github.com/igawa6/eden-duo (clone `v1.3.2.2`, `5fa93ed4`) | Module ABI/SDK headers, loader gates (`module.build_ids` list), data-file fallback `data.json`, guest read API, dev console `chain`/`cscan`, profile line, Windows gaps, harness, aux window, Windows build docs, runtime 22 |
| S2 | https://github.com/igawa6/eden-duo-companions (clone `383d2d47`) | BotW package, module, tests, load plan, build script, CMake, package builder (repeatable `--build-id`); Wonder and MK8D multi-build modules |
| S3 | S2 `docs/` (PORTING_A_GAME, MODULE_GUIDE, PACKAGE_FORMAT, ARCHITECTURE) | Porting method, build-id rules, package format, NCE, headless testing |
| S4 | https://raw.githubusercontent.com/blawar/titledb/master/versions.json | TotK update versions and catalog dates |
| S5 | https://github.com/HamletDuFromage/switch-cheats-db `versions/0100F2C0115B6000.json` | Version -> 16-hex build id; 720896 is latest |
| S6 | https://github.com/TKMM-Team/TkSharp `TkSharp.Core/TkPatch.cs` | Full 20-byte build ids 1.1.0-1.4.3 |
| S7 | https://github.com/afiaka87/totk-mods (`0b4ec43e`, MIT) | Bivouac 1.2.1 warp, pouch and map-hover offsets (`bivouac/src/program/engine/MapStampService.cpp`, `runtime/BivouacRuntime.cpp`); version names per build id; 1.2.1 offsets (`zonai-survey/.../Totk121Offsets.hpp`, `runtime-support/include/totk/engine/Totk121Offsets.hpp`); registry 0x220 vs 0x228 (`self-recall/src/program/main.cpp`); GameData queues and loading flag (`botw-abilities/src/champions/GameData.cpp`); 1.2.1 id (`phantom-foothold/src/program/GameProfiles.hpp:20`) |
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
| S28 | https://github.com/kitcatkode/totk-android (MIT) `android/BOTTOM_SCREEN.md`, `runtime/bottom_inventory.c`, `runtime/bottom_assets.c` (raw HEAD, read 2026-11-01); `runtime/gamedata_live.c`, `world_survey.c`, `bottom_map.c`, `game_version.h` (`837a8253`, read 2026-11-02) | TotK 1.4.3 romfs paths for map tiles, icons, Mals; ZsDic contents; tile grid and levels; Depths masks; GameData keys and live layout on 1.4.3 (type blocks, hash table, IntArray/String64Array entries); murmur3 vectors; equip through the quick menu; 1.2.1 actor name +0x218, position +0x2B4; height thresholds for the layer; position bounds |
| S29 | https://github.com/marcrobledo/savegame-editors (MIT) `zelda-totk/zelda-totk.js`, `zelda-totk.class.pouch.js`, `zelda-totk.class.item.js` | GameData key names and hashes, pouch structs, ability flags, `slot_0N`, caption date hashes |
| S30 | GitHub code search "ZsDic.pack.zs" / "bcett.byml.zsdic" (2026-11-01): KillzXGaming/Switch-Toolbox `Switch_Toolbox_Library/Compression/Formats/Zstb.cs`, dt-12345/asb `zstd.py`, valentderah/totk-pc-recomp `runtime/camera_speed.c` (result snippets only; files not opened) | `Pack/ZsDic.pack.zs` is plain zstd around a SARC of dictionaries `zs.zsdic`, `pack.zsdic`, `bcett.byml.zsdic` |
| S31 | https://pypi.org/project/zstandard/0.23.0/ sdist `zstandard-0.23.0.tar.gz` | zstd 1.5.6 single-file library (`zstd/zstd.c`, BSD or GPLv2), hashes in §T3a' |
| S32 | https://github.com/ly05010419/MacNativeGames (MIT) `games/totk/scripts/make_button_icons.py`, `ultrawide_layout.py`, `layout_view.py`, `docs/v1.2.1-port.md` (raw HEAD, read 2026-11-02) | TotK font archive `Font/Font.Nin_NX_NVN.bfarc.zs` and `.bfotf` faces + XOR header; `UI/LayoutArchive/Common.Product.<n>.Nin_NX_NVN.blarc.zs` with `timg/__Combined.bntx`; HUD key texture names; layout names; 1.2.1 has only `Common.Product.110` |
| S33 | https://github.com/zeldadungeon/maps `tools/README.md` (raw HEAD) | TotK map pins are `MapIcon*` textures in `Common.Product.100...blarc.zs` `timg/__Combined.bntx`; decompress with `zs.zsdic` |
| S34 | https://github.com/kuraeow/ultratears (GPL-3.0) `src/host/ui_aspect.c` (raw HEAD) | TotK layout names in `Common.Product.140` (full-screen menu backgrounds, AppMap, PlayerStatus) |
| S36 | User's photo `images/1.png` (2026-11-02) | The reference look: Eden Duo BotW companion on a Thor bottom screen |
| S37 | Critic's render harness `scratchpad/critic-t4/render/harness.cpp` (T4 rounds) | Renders a manifest page with the runtime's own `RenderPage`; basis of T4v's render tool |
| S38 | https://github.com/aquacluck/totk-lotuskit (`59dcfccd`) `syms/exking/gmd/GameDataMgr.py`, `syms/exking/engine/hacks.py`, `src/program/structs/engineActor.hpp`, `subrepo/sead/include/prim/seadSafeString.h`, `src/program/util/actor.cpp`, `frontend/assets/data/GMDHash_InfoList.mjs` | 1.2.1 GameDataMgr `main+0x04721B98` and SceneModule `main+0x04728538`; actor layout; GameData flag types and hashes |
| S39 | https://github.com/Atmosphere-NX/Atmosphere/blob/master/docs/features/cheats.md | Cheat opcode semantics (type 5 loads, 6/A stores and widths, 7 add, 9 move, loops) used to decode §L2 |
| S40 | https://github.com/xenocard/Eiffel2018/blob/main/0100F2C0115B6000/cheats/9B4E43650501A4D4.txt | Eiffel2018 is the author of the 1.2.1 value cheats in [S9] (identical codes) |
| S41 | https://github.com/Arch9SK7/MyNXCheats/blob/main/The%20Legend%20Of%20Zelda%20Tears%20Of%20The%20Kingdom/0100F2C0115B6000/cheats/9B4E43650501A4D4.txt | 1.2.1 code patches; time and temperature code sites only |
| S42 | https://github.com/lingxup-hash/switch-native-mac/blob/main/cheats/totk-1.2.1/王国之泪1.2.1金手指测试结果.md (2026-10-22) | Third-party read-only run of the Eiffel 1.2.1 chains (hearts, stamina, weapon, rupees readable, zero writes) in a Mac native port |
| S43 | https://github.com/AurelioB/totk-android (`3421073`, MIT) `games/totk/runtime/companion_actions.c`, `games/totk/scripts/make_companion_assets.py`, `games/totk/android/setup/companion/stage_companion.c` | 1.4.3 device-tested pouch functions (equip, unequip, eat) with 64-byte prologue SHA-256 checks and the game's refusals; travel points from `Banc/<Field>/LocationArea/<Field>.locationarea.byml.zs` groups and `LocationMsg/Location.msbt`; altitude is not a layer |

The MinGW and wine results come from spikes in the planner's scratch dir
(`/tmp/claude-0/-home-user/6540dfaa-428d-5b44-8a41-bf2bc5812bad/scratchpad/spike`), not from a
web source. The BotW placeholder renders in §R1 are in `scratchpad/planner-render/`.

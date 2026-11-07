// SPDX-License-Identifier: GPL-3.0-or-later
// Named pointer chains for the live status (docs/PLAN.md §L2, §T3a), the loading gate (§A1) and the
// Travel Medallion trigger (§A2), and the GameData table layout the A2 probe reads.
// Offsets{} (1.2.0 and any other build) is all empty: every chain UNKNOWN, so ReadStatus reads
// nothing. kOffsets121 holds the 1.2.1 leads, each UNVERIFIED until T8c checks it with the
// console's `chain` command.
// Walk rule (totk_status.h): address = base + hop[0]; then for each next hop, address = u64 at
// address + hop. The actor-relative tails are the §L2 chains with their {0x46AF308, 0x480} head
// cut off, so `chain 46AF308 0x480 <tail>` still checks each one.
#pragma once

#include <array>
#include <cstdint>

namespace totk {

struct Chain {
    std::uint8_t n = 0; // hops used; 0 = UNKNOWN
    std::array<std::uint64_t, 6> hop{};
};

struct Offsets {
    // UNKNOWN: main-relative chain to the player actor pointer (no 1.2.0 lead exists [S9]).
    Chain actor;
    // UNKNOWN: actor-relative chain to the actor name bytes ("Player\0").
    Chain name;
    // UNKNOWN: actor-relative chain to the u32 hearts (quarter hearts).
    Chain hearts;
    // UNKNOWN: actor-relative chain to the u32 max hearts.
    Chain max_hearts;
    // UNKNOWN: actor-relative chain to the f32 stamina.
    Chain stamina;
    // UNKNOWN: main-relative chain to the f32 max stamina.
    Chain max_stamina;
    // UNKNOWN: main-relative chain to the u32 rupees.
    Chain rupees;
    // UNKNOWN: main-relative chain to the f32 battery.
    Chain battery;
    // UNKNOWN: main-relative chain to the f32 max battery.
    Chain max_battery;
    // UNKNOWN: actor-relative chain to the f32[3] world position (x, y, z).
    Chain position;
    // The GameData loading gate (docs/PLAN.md §A1, totk_status.h ReadLoading).
    // UNKNOWN: main-relative chain giving the GameDataMgr pointer.
    Chain gmd_mgr;
    // UNKNOWN: main-relative chain giving the GameData holder's manager pointer.
    Chain gmd_holder;
    // UNKNOWN: main-relative chain to the u32 GameData file count.
    Chain gmd_count;
    // UNKNOWN: main-relative chain to the u8 file flags (0x02: loading).
    Chain gmd_loading;
    // The Travel Medallion trigger (docs/PLAN.md §A2, totk_travel.h).
    // UNKNOWN: main-relative chain whose hop results are mgr, base and P (the medallion's vec3).
    Chain medallion;
    // UNKNOWN: mgr-relative offset of the Vector3Array type block's hash table pointer.
    std::uint64_t v3a_table = 0;
    // UNKNOWN: mgr-relative offset of the IntArray type block's hash table pointer.
    std::uint64_t int_table = 0;
    // UNKNOWN: Vector3Array index of MapData.IconData.WarpMarkerData.Pos.
    std::uint32_t medallion_index = 0;
    // UNKNOWN: base-relative offset of the u32 live length of that entry (1 while placed).
    std::uint64_t medallion_len = 0;
    // UNKNOWN: WarpMarkerData.Index[0] while placed; used instead of medallion_len when set.
    struct Placed {
        bool set = false;
        std::int32_t value = 0;
    } medallion_index_placed;
};

// GameData type block layout (docs/PLAN.md §A2, §L2), the same on every build. A table is
// {u64 pointer, u32 count at +kTableCount, u32 size at +kTableSize} [S28 gamedata_live.c:36-46]
// (1.4.3 only). UNVERIFIED until T8c.
inline constexpr std::uint64_t kTableCount = 0x8;
// UNVERIFIED until T8c [S28 gamedata_live.c:36-46].
inline constexpr std::uint64_t kTableSize = 0xC;
// The block's values pointer sits 8 bytes before its table pointer (block +0x10, +0x18) [S28
// gamedata_live.c:4-7]. UNVERIFIED until T8c.
inline constexpr std::uint64_t kTableToValues = 0x8;
// IntArray entry size, its u32 live length and its data pointer [S28 gamedata_live.c:8-9,24-27,
// 49-50]. UNVERIFIED until T8c.
inline constexpr std::uint64_t kIntArrayEntry = 0x40;
// UNVERIFIED until T8c [S28 gamedata_live.c:49-50].
inline constexpr std::uint64_t kIntArrayLen = 0x20;
// UNVERIFIED until T8c [S28 gamedata_live.c:49-50].
inline constexpr std::uint64_t kIntArrayData = 0x28;

// 1.2.1 (decoded from Eiffel2018's set [S40], same codes in [S9] key 9B4E43650501A4D4).
inline constexpr Offsets kOffsets121{
    // [S9] cheats 1-7 head main+0x46AF308, +0x480 is the actor; +0x0 returns the pointer itself.
    // UNVERIFIED until T8c.
    .actor = {3, {0x46AF308, 0x480, 0x0}},
    // [S7 Totk121Offsets.hpp][S38 engineActor.hpp:128,197-210] mActorName at actor+0x218; +0x0
    // the char* target. UNVERIFIED until T8c.
    .name = {2, {0x218, 0x0}},
    // [S9] cheat 3: actor +0x228 +0x2F8 +0x1830 +0x8. UNVERIFIED until T8c.
    .hearts = {4, {0x228, 0x2F8, 0x1830, 0x8}},
    // [S9] cheats 1a/1b: actor +0x228 +0x2F8 +0x1838 +0x8. UNVERIFIED until T8c.
    .max_hearts = {4, {0x228, 0x2F8, 0x1838, 0x8}},
    // [S9] cheat 4: actor +0x228 +0x3A8 +0x13FC. UNVERIFIED until T8c.
    .stamina = {3, {0x228, 0x3A8, 0x13FC}},
    // [S9] cheat 2: main+0x4721B98 (GameDataMgr [S38 GameDataMgr.py:4-8]) +0x198 +0x5BC.
    // UNVERIFIED until T8c.
    .max_stamina = {3, {0x4721B98, 0x198, 0x5BC}},
    // [S9] cheat 9: main+0x4721B98 +0xD8 +0x28EA4. UNVERIFIED until T8c.
    .rupees = {3, {0x4721B98, 0xD8, 0x28EA4}},
    // [S9] cheat 28: main+0x4728538 (SceneModule [S7][S38 hacks.py:19]) +0x1E8 +0x58 +0xF8
    // +0x5C. UNVERIFIED until T8c.
    .battery = {5, {0x4728538, 0x1E8, 0x58, 0xF8, 0x5C}},
    // [S9] cheat 29: main+0x4721B98 +0x198 +0x594. UNVERIFIED until T8c.
    .max_battery = {3, {0x4721B98, 0x198, 0x594}},
    // [S7 Totk121Offsets.hpp][S28 game_version.h:83][S38 engineActor.hpp:210] mPosition at
    // actor+0x2B4 (the cheat actor with this layout is UNVERIFIED). UNVERIFIED until T8c.
    .position = {1, {0x2B4}},
    // [S38 GameDataMgr.py:4-8] main+0x4721B98 holds the GameDataMgr; +0x0 returns it.
    // UNVERIFIED until T8c.
    .gmd_mgr = {2, {0x4721B98, 0x0}},
    // [S7 Totk121Offsets.hpp:30-33] [[main+0x462E3D8]]. UNVERIFIED until T8c.
    .gmd_holder = {3, {0x462E3D8, 0x0, 0x0}},
    // [S7 botw-abilities/src/champions/GameData.cpp:64-73] mgr +0xCC0: u32 n.
    // UNVERIFIED until T8c.
    .gmd_count = {2, {0x4721B98, 0xCC0}},
    // [S7 botw-abilities/src/champions/GameData.cpp:64-73] mgr +0xCC8 is the file pointer (null
    // fails the walk), +0x78 its u8 flags. UNVERIFIED until T8c.
    .gmd_loading = {3, {0x4721B98, 0xCC8, 0x78}},
    // [S9][S40] cheat #30 "Travel Medallion Mark follows Red Mark": mgr = [main+0x4721B98], base
    // = [mgr+0x438], P = [base+0x300]; the trailing +0x0 returns P itself. UNVERIFIED until T8c.
    .medallion = {4, {0x4721B98, 0x438, 0x300, 0x0}},
    // [S28 gamedata_live.c:4-7,36-46] (1.4.3) block 0x428 (8 + 0x0B * 0x60 + 0x10 = 0x438 is the
    // values pointer of cheat #30) + 0x18. UNVERIFIED until T8c.
    .v3a_table = 0x440,
    // [S28 gamedata_live.c:4-7] IntArray (type 3) block 0x128 + 0x18 (§L2 grid).
    // UNVERIFIED until T8c.
    .int_table = 0x140,
    // [S9][S40] cheat #30's 0x300 = 10 * 0x48 + 0x30 (inference). UNVERIFIED until T8c.
    .medallion_index = 10,
    // UNKNOWN: T8c (§A3) reads base+0x2D0..0x31F placed and picked up; lead base+0x2F8 (IntArray
    // keeps its length 8 bytes before its pointer, §L2). 0: the trigger refuses.
    .medallion_len = 0,
    // UNKNOWN: filled instead of medallion_len if T8c's hexdump shows Index tracks the medallion.
    .medallion_index_placed = {},
};

} // namespace totk

// SPDX-License-Identifier: GPL-3.0-or-later
// Named pointer chains for the live status (docs/PLAN.md §L2, §T3a). Offsets{} (1.2.0 and any
// other build) is all empty: every chain UNKNOWN, so ReadStatus reads nothing. kOffsets121 holds
// the 1.2.1 leads, each UNVERIFIED until T8c checks it with the console's `chain` command.
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
};

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
};

} // namespace totk

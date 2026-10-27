// SPDX-License-Identifier: GPL-3.0-or-later
// Named offsets for the status domain. Every value is UNKNOWN until T3 confirms it on the target
// build from code and a live read; 0 means "not found" and ReadStatus then reads nothing.
// Read model (T3 may change it): root = *(main_base + game_data_root), value = *(s32*)(root + field).
#pragma once

#include <cstdint>

namespace totk {

struct Offsets {
    // UNKNOWN: main-relative pointer slot to the player/GameData root. Leads (1.2.1): totk-mods
    // GameDataMgr holder main+0x0462E3D8 (double deref); cheats rupees/stamina via main+0x4721B98.
    std::uint64_t game_data_root = 0;
    // UNKNOWN: current hearts. Lead (1.2.1): cheat 3 chain main+0x46AF308 +0x480 +0x228 +0x2F8.
    std::uint64_t hearts = 0;
    // UNKNOWN: max hearts. Lead: GameData PlayerStatus struct (TotkMods/Research, gmd_scripts).
    std::uint64_t max_hearts = 0;
    // UNKNOWN: current stamina. Lead (1.2.1): cheat 4 via main+0x4721B98 and main+0x46AF308.
    std::uint64_t stamina = 0;
    // UNKNOWN: max stamina. Lead: GameData PlayerStatus struct (TotkMods/Research, gmd_scripts).
    std::uint64_t max_stamina = 0;
    // UNKNOWN: rupees. Lead (1.2.1): cheat 9 [[main+0x4721B98]+0xD8]+0x28EA4.
    std::uint64_t rupees = 0;
};

} // namespace totk

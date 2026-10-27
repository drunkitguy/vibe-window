// SPDX-License-Identifier: GPL-3.0-or-later
// Status reader: hearts, stamina, rupees. Fails closed: any unknown offset, unmapped or
// out-of-range address, null or misaligned pointer, or a torn read gives an invalid Status.
#pragma once

#include <array>
#include <cstdint>
#include <limits>

#include "core/mods/modules/dsmod_module_sdk.h"
#include "totk_offsets.h"

namespace totk {

struct Status {
    bool valid = false;
    std::int32_t hearts = 0;
    std::int32_t max_hearts = 0;
    std::int32_t stamina = 0;
    std::int32_t max_stamina = 0;
    std::int32_t rupees = 0;
};

namespace detail {
using dsmod_sdk::MissingIsMapped;

// Value types are UNKNOWN too; s32 until T3 finds them.
inline bool ReadStatusOnce(const EdenDsmodHostApi& h, const Offsets& o,
                           std::array<std::int32_t, 5>& out) {
    constexpr auto kMax = std::numeric_limits<std::uint64_t>::max();
    if (h.main_base > kMax - o.game_data_root)
        return false;
    const std::uint64_t slot = h.main_base + o.game_data_root;
    std::uint64_t root = 0;
    if (!dsmod_sdk::InMain(h.main_base, h.main_size, slot, sizeof root) ||
        !dsmod_sdk::ReadGuest<MissingIsMapped::Reject>(h, slot, &root, sizeof root) || root == 0 ||
        root % 8 != 0)
        return false;
    const std::uint64_t fields[5] = {o.hearts, o.max_hearts, o.stamina, o.max_stamina, o.rupees};
    for (std::size_t i = 0; i < out.size(); ++i)
        if (root > kMax - fields[i] ||
            !dsmod_sdk::ReadGuest<MissingIsMapped::Reject>(h, root + fields[i], &out[i],
                                                           sizeof out[i]))
            return false;
    return true;
}
} // namespace detail

inline Status ReadStatus(const EdenDsmodHostApi& h, const Offsets& o) {
    if (!o.game_data_root || !o.hearts || !o.max_hearts || !o.stamina || !o.max_stamina ||
        !o.rupees)
        return {};
    std::array<std::int32_t, 5> first{}, second{};
    if (!detail::ReadStatusOnce(h, o, first) || !detail::ReadStatusOnce(h, o, second) ||
        first != second)
        return {};
    return {true, first[0], first[1], first[2], first[3], first[4]};
}

} // namespace totk

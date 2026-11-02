// SPDX-License-Identifier: GPL-3.0-or-later
// Live status reader: hearts, stamina, rupees and battery through the Offsets chains
// (docs/PLAN.md §T3a). Fails closed: an empty chain reads nothing; an unmapped or misaligned slot,
// a null or low pointer, an overflowing address, an actor not named "Player", a torn read or an
// implausible value gives an invalid Status. Reads only through ReadGuest<Reject>; never writes.
#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <limits>

#include "core/mods/modules/dsmod_module_sdk.h"
#include "totk_offsets.h"

namespace totk {

struct Status {
    bool valid = false;
    std::uint32_t hearts = 0, max_hearts = 0, rupees = 0;
    float stamina = 0, max_stamina = 0;
    bool has_battery = false; // the pair is optional: absent before the game gives a battery
    float battery = 0, max_battery = 0;
};

namespace detail {
using dsmod_sdk::MissingIsMapped;

inline constexpr std::uint64_t kMinPointer = 0x10000; // BotW's floor [S2 botw_pouch.h:23-27]

template <class T>
bool Read(const EdenDsmodHostApi& h, std::uint64_t at, T& out) {
    return dsmod_sdk::ReadGuest<MissingIsMapped::Reject>(h, at, &out, sizeof out);
}

// The final address of `c` from `base`, or 0. From main_base the first slot must lie in main.
inline std::uint64_t Walk(const EdenDsmodHostApi& h, std::uint64_t base, const Chain& c) {
    constexpr auto kMax = std::numeric_limits<std::uint64_t>::max();
    if (c.n == 0 || c.n > c.hop.size() || base == 0 || base > kMax - c.hop[0])
        return 0;
    std::uint64_t at = base + c.hop[0];
    if (base == h.main_base && !dsmod_sdk::InMain(h.main_base, h.main_size, at, 8))
        return 0;
    for (std::size_t i = 1; i < c.n; ++i) {
        std::uint64_t p = 0;
        if (at % 8 != 0 || !Read(h, at, p) || p < kMinPointer || p > kMax - c.hop[i])
            return 0;
        at = p + c.hop[i];
    }
    return at;
}

// A 4-byte value (u32 or f32 bits) at the end of `c`, 4-aligned.
inline bool ReadWord(const EdenDsmodHostApi& h, std::uint64_t base, const Chain& c,
                     std::uint32_t& out) {
    const std::uint64_t at = Walk(h, base, c);
    return at && at % 4 == 0 && Read(h, at, out);
}

// One pass: hearts, max hearts, stamina, max stamina, rupees, then battery and max battery;
// floats as their bits so two passes compare bitwise.
using Raw = std::array<std::uint32_t, 7>;

inline bool ReadStatusOnce(const EdenDsmodHostApi& h, const Offsets& o, Raw& r) {
    // One actor walk; its name guards the three actor-relative values read from it.
    const std::uint64_t actor = Walk(h, h.main_base, o.actor);
    const std::uint64_t name = actor ? Walk(h, actor, o.name) : 0;
    char text[7];
    if (!name || !Read(h, name, text) || std::memcmp(text, "Player", 7) != 0)
        return false;
    if (!ReadWord(h, actor, o.hearts, r[0]) || !ReadWord(h, actor, o.max_hearts, r[1]) ||
        !ReadWord(h, actor, o.stamina, r[2]) || !ReadWord(h, h.main_base, o.max_stamina, r[3]) ||
        !ReadWord(h, h.main_base, o.rupees, r[4]))
        return false;
    if (!ReadWord(h, h.main_base, o.battery, r[5]) || !ReadWord(h, h.main_base, o.max_battery, r[6]))
        r[5] = r[6] = 0;
    return true;
}

inline float Float(std::uint32_t bits) {
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}
} // namespace detail

// Two passes compared bitwise, then the plausibility bounds (from the cheat values [S9],
// UNVERIFIED). shortcut: bonus hearts and bonus stamina caps UNKNOWN, tighten after T8c.
inline Status ReadStatus(const EdenDsmodHostApi& h, const Offsets& o) {
    if (!o.actor.n || !o.name.n || !o.hearts.n || !o.max_hearts.n || !o.stamina.n ||
        !o.max_stamina.n || !o.rupees.n)
        return {};
    detail::Raw a{}, b{};
    if (!detail::ReadStatusOnce(h, o, a) || !detail::ReadStatusOnce(h, o, b) || a != b)
        return {};
    Status s{true, a[0], a[1], a[4], detail::Float(a[2]), detail::Float(a[3])};
    // The float bounds are written so NaN and infinity fail them: every float is finite.
    if (s.max_hearts < 12 || s.max_hearts > 160 || s.hearts > 2 * s.max_hearts ||
        s.rupees > 999999 || !(s.max_stamina > 0 && s.max_stamina <= 3000) ||
        !(s.stamina >= 0 && s.stamina <= 2 * s.max_stamina))
        return {};
    // A battery out of bounds drops only the battery pair (it may read 0 before the game gives
    // one).
    const float battery = detail::Float(a[5]), max_battery = detail::Float(a[6]);
    if (max_battery > 0 && max_battery <= 48000 && battery >= 0 && battery <= max_battery) {
        s.has_battery = true;
        s.battery = battery;
        s.max_battery = max_battery;
    }
    return s;
}

} // namespace totk

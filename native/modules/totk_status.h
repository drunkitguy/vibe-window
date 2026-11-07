// SPDX-License-Identifier: GPL-3.0-or-later
// Live status reader: hearts, stamina, rupees and battery through the Offsets chains
// (docs/PLAN.md §T3a), Link's position (§T3b) and the GameData loading gate (§A1). Fails closed:
// an empty chain reads nothing; an unmapped or misaligned slot, a null or low pointer, an
// overflowing address, an actor not named "Player", a torn read or an implausible value gives an
// invalid Status or Position.
// Reads only through ReadGuest<Reject>; never writes.
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

struct Position {
    bool valid = false;
    float x = 0, y = 0, z = 0; // world units; the map shows (x, -z)
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

// One actor walk, or 0 unless that actor is named "Player": the name guards every value read
// from it.
inline std::uint64_t PlayerActor(const EdenDsmodHostApi& h, const Offsets& o) {
    const std::uint64_t actor = Walk(h, h.main_base, o.actor);
    const std::uint64_t name = actor ? Walk(h, actor, o.name) : 0;
    char text[7];
    return name && Read(h, name, text) && std::memcmp(text, "Player", 7) == 0 ? actor : 0;
}

inline bool ReadStatusOnce(const EdenDsmodHostApi& h, const Offsets& o, Raw& r) {
    const std::uint64_t actor = PlayerActor(h, o);
    if (!actor || !ReadWord(h, actor, o.hearts, r[0]) || !ReadWord(h, actor, o.max_hearts, r[1]) ||
        !ReadWord(h, actor, o.stamina, r[2]) || !ReadWord(h, h.main_base, o.max_stamina, r[3]) ||
        !ReadWord(h, h.main_base, o.rupees, r[4]))
        return false;
    if (!ReadWord(h, h.main_base, o.battery, r[5]) || !ReadWord(h, h.main_base, o.max_battery, r[6]))
        r[5] = r[6] = 0;
    return true;
}

// x, y, z as their bits, one 12-byte read, 4-aligned.
using RawPosition = std::array<std::uint32_t, 3>;

inline bool ReadPositionOnce(const EdenDsmodHostApi& h, const Offsets& o, RawPosition& r) {
    const std::uint64_t actor = PlayerActor(h, o);
    const std::uint64_t at = actor ? Walk(h, actor, o.position) : 0;
    return at && at % 4 == 0 && Read(h, at, r);
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

// Two passes compared bitwise, then the bounds: x and z inside T7's map area [S28 §2], y in
// [-1500, 3500] [S28 runtime/world_survey.c:53-56]; NaN and infinity fail them.
inline Position ReadPosition(const EdenDsmodHostApi& h, const Offsets& o) {
    if (!o.actor.n || !o.name.n || !o.position.n)
        return {};
    detail::RawPosition a{}, b{};
    if (!detail::ReadPositionOnce(h, o, a) || !detail::ReadPositionOnce(h, o, b) || a != b)
        return {};
    const Position p{true, detail::Float(a[0]), detail::Float(a[1]), detail::Float(a[2])};
    if (!(p.x >= -6000 && p.x <= 6000 && p.y >= -1500 && p.y <= 3500 && p.z >= -5000 &&
          p.z <= 5000))
        return {};
    return p;
}

// Why the loading gate reads unknown; the module logs each kind once per session.
enum class Unknown : std::uint8_t { none, managers_differ, count_unreadable, count, file_walk };
inline constexpr const char* kUnknownText[] = {"", "managers differ", "count unreadable", "count",
                                               "file walk"};

// The GameData loading gate [S7 botw-abilities/src/champions/GameData.cpp:64-73]: 0 when the
// holder's manager is the GameDataMgr, its file count n is 1-16, the file walk succeeds and the
// flags' 0x02 bit is clear; 1 when that bit is set; -1 (unknown) on anything else, with `why`
// naming the cause (and `n` the count for Unknown::count). A manager or holder walk that fails
// counts as "managers differ". Any empty chain gives -1 with no host call and leaves `why` none.
inline int ReadLoading(const EdenDsmodHostApi& h, std::uint64_t base, const Offsets& o,
                       Unknown& why, std::uint32_t& n) {
    why = Unknown::none;
    if (!o.gmd_mgr.n || !o.gmd_holder.n || !o.gmd_count.n || !o.gmd_loading.n)
        return -1;
    const std::uint64_t mgr = detail::Walk(h, base, o.gmd_mgr);
    if (!mgr || mgr != detail::Walk(h, base, o.gmd_holder))
        why = Unknown::managers_differ;
    else if (!detail::ReadWord(h, base, o.gmd_count, n))
        why = Unknown::count_unreadable;
    else if (n < 1 || n > 16)
        why = Unknown::count;
    if (why != Unknown::none)
        return -1;
    const std::uint64_t file = detail::Walk(h, base, o.gmd_loading);
    std::uint8_t flags = 0;
    if (!file || !detail::Read(h, file, flags)) {
        why = Unknown::file_walk;
        return -1;
    }
    return flags & 0x02 ? 1 : 0;
}

// Link's map layer (totk.map.layer: 0 surface, 1 sky, 2 depths) from his height [S28
// runtime/bottom_map.c:109]. shortcut: height threshold, upgrade if MapData.CurrentLayer (Enum
// 0x1294b632 [S38]) proves to follow Link.
inline int Layer(float y) {
    return y < -100 ? 2 : y > 1000 ? 1 : 0;
}

} // namespace totk

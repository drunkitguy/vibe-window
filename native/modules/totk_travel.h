// SPDX-License-Identifier: GPL-3.0-or-later
// Travel (docs/PLAN.md §A2). (a) The destination list, built on the worker from the player's own
// files (nothing shipped): shrines, lightroots and towers from the romfs LocationArea BYML, names
// from Location.msbt, the unlock state from the last save. (b) The Travel Medallion move: the
// medallion walk, the placed check, the read-only report for T8c, the vec3 line format and the one
// write_batch call (go and restore). Every read goes through ReadGuest<Reject> (totk_status.h).
#pragma once

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "core/mods/dsmod_module_extensions.h"
#include "totk_byml.h"
#include "totk_bytes.h"
#include "totk_gamedata.h"
#include "totk_msbt.h"
#include "totk_offsets.h"
#include "totk_status.h"

namespace totk::travel {

// 152 shrines + 120 lightroots + 15 towers = 287 known points [research travel track], plus
// margin for entries with several Trans.
inline constexpr std::size_t kMaxDest = 320;
// shortcut: Bivouac's arrival hover [S7 bivouac/src/program/engine/MapStampService.cpp:40], tune
// in Next steps 12.
inline constexpr float kArrivalHover = 2.0f;
// MapData.IconData.WarpMarkerData.Pos (Vector3Array) and .Index (IntArray) [S38
// GMDHash_InfoList.mjs:182493,162026; S29 zelda-totk.js:1516-1517].
inline constexpr std::uint32_t kWarpPos = 0x636b4637, kWarpIndex = 0xc6dc3b56;
static_assert(Murmur3("MapData.IconData.WarpMarkerData.Pos") == kWarpPos &&
              Murmur3("MapData.IconData.WarpMarkerData.Index") == kWarpIndex);

// File names from 1.0 and 1.4.3 [S13][S43], UNVERIFIED on 1.2.1.
inline constexpr const char* kFiles[] = {
    "romfs:Banc/MainField/LocationArea/MainField.locationarea.byml.zs",
    "romfs:Banc/MinusField/LocationArea/MinusField.locationarea.byml.zs"};
inline constexpr const char* kGroups[] = {"Shrine", "CheckPoint", "Tower"}; // kind 0, 1, 2
inline constexpr const char* kLocationMsg = "LocationMsg/Location.msbt";

struct Dest {
    std::string label, name; // the LocationName stem (Dungeon001); Location.msbt's text or empty
    int kind = 0;            // 0 shrine, 1 lightroot, 2 tower
    int layer = 0;           // totk.map.layer
    float x = 0, y = 0, z = 0;
    int open = -1; // 1 travelable, 0 locked, -1 unknown (its key is not in the save)
};

// The romfs part of the list, once per session: one Dest per Trans element that is exactly three
// numbers inside the world (trust boundary: a romfs mod may replace the file, and the value
// reaches GameData and the save). Fails soft: a missing or unparsable file or msbt gives no
// entries (or labels for names) and one line; never an error page.
inline std::vector<Dest> Load(const EdenDsmodHostApi& h, int mals) {
    std::vector<Dest> out;
    for (int f = 0; f < 2; ++f) {
        std::vector<std::uint8_t> bytes;
        if (!ReadAll(h, kFiles[f], bytes)) {
            Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: %s missing", kFiles[f]);
            continue;
        }
        const auto root = byml::Parse(bytes);
        if (!root) {
            Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: %s unparsable", kFiles[f]);
            continue;
        }
        unsigned dropped = 0;
        for (int g = 0; g < 3; ++g) {
            const byml::Node* group = root->Get(kGroups[g]);
            for (std::size_t e = 0; group && group->At(e); ++e) {
                const byml::Node& entry = *group->At(e);
                const byml::Node* location = entry.Get("LocationName");
                const byml::Node* trans = entry.Get("Trans");
                const auto path = location ? location->String() : std::nullopt;
                if (!path || !trans)
                    continue;
                std::string_view stem = path->substr(path->find_last_of('/') + 1);
                stem = stem.substr(0, stem.find('.'));
                for (std::size_t t = 0; trans->At(t); ++t) {
                    const byml::Node& pos = *trans->At(t);
                    float v[3]{};
                    bool ok = pos.type == byml::Node::Type::Array && pos.Size() == 3;
                    for (int i = 0; ok && i < 3; ++i) {
                        const auto n = pos.array[i].Number();
                        ok = n.has_value();
                        v[i] = ok ? float(*n) : 0;
                    }
                    if (!ok || !totk::InWorld(v[0], v[1], v[2])) {
                        ++dropped;
                        continue;
                    }
                    // shortcut: altitude is not a layer, the ground shrine on Death Mountain is
                    // above several sky shrines [S43 make_companion_assets.py:527-529]; upgrade by
                    // matching the Sky LocationMarker hashes in Banc/MainField/Sky/*.byml.zs.
                    out.push_back(
                        {std::string(stem), {}, g, f == 1 ? 2 : Layer(v[1]), v[0], v[1], v[2]});
                }
            }
        }
        if (dropped)
            Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: %s: %u Trans dropped", kFiles[f], dropped);
    }
    if (out.empty())
        return out;
    msbt::Labels labels;
    std::string why = "no Mals archive";
    if (!mals || !msbt::LoadNames(h, mals, labels, why, kLocationMsg))
        Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: %s missing (%s)", kLocationMsg, why.c_str());
    for (auto& d : out)
        if (const auto it = labels.find(d.label); it != labels.end())
            d.name = it->second;
    if (out.size() > kMaxDest) {
        Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: %u destinations, list cut at %u",
            unsigned(out.size()), unsigned(kMaxDest));
        out.resize(kMaxDest);
    }
    return out;
}

// The unlock state from the save `g`: DungeonState.<label> (Enum), ArrivalPointState.<label>
// (Enum), IsActivateCannon.<label> (Bool) [S38 GMDHash_InfoList.mjs:154661,154254,9920]; a key
// missing from the save is unknown (-1).
// shortcut: enum names Hidden/Appear/Open/Enter/Clear and Close/Open from the research travel
// track, compared as Murmur3 of the name like every enum (totk_gamedata.h), confirm in Next
// steps 12.
inline std::vector<Dest> Apply(std::vector<Dest> list, const GameData& g) {
    static constexpr const char* kKey[] = {"DungeonState.", "ArrivalPointState.",
                                           "IsActivateCannon."};
    for (auto& d : list) {
        const std::uint32_t key = Murmur3(kKey[d.kind] + d.label);
        if (d.kind == 2) {
            const auto b = g.Bool(key);
            d.open = b ? int(*b) : -1;
        } else if (const auto e = g.Enum(key)) {
            d.open = *e == Murmur3("Open") ||
                     (d.kind == 0 && (*e == Murmur3("Enter") || *e == Murmur3("Clear")));
        } else {
            d.open = -1;
        }
    }
    return list;
}

// ---- the medallion ------------------------------------------------------------------------------

using Bits = std::array<std::uint32_t, 3>; // a vec3's floats as bits: compared bitwise

inline Bits ToBits(float x, float y, float z) {
    Bits b;
    std::memcpy(&b[0], &x, 4);
    std::memcpy(&b[1], &y, 4);
    std::memcpy(&b[2], &z, 4);
    return b;
}
inline bool InWorld(const Bits& b) {
    return totk::InWorld(detail::Float(b[0]), detail::Float(b[1]), detail::Float(b[2]));
}

// One walk of Offsets::medallion: the slot each hop read (main+0x4721B98, mgr+0x438, base+0x300)
// and what it held (mgr, base, P), the final address p (P) and the 12 bytes there.
struct Walked {
    std::array<std::uint64_t, 3> slot{}, ptr{};
    std::uint64_t p = 0;
    Bits bits{};
};

// Precondition 2 and the vec3 at P: false on any failed hop (Walk's rules: the first slot in
// main, every slot 8-aligned, every pointer >= 0x10000) or an unreadable or misaligned P.
inline bool ReadMedallion(const EdenDsmodHostApi& h, const Offsets& o, Walked& w) {
    if (o.medallion.n != 4) // three pointers, then +0: ops 1-3 check exactly those
        return false;
    std::uint64_t got[6]{};
    w.p = detail::Walk(h, h.main_base, o.medallion, got);
    if (!w.p || w.p % 4 != 0 || !detail::Read(h, w.p, w.bits))
        return false;
    w.slot[0] = h.main_base + o.medallion.hop[0];
    for (int i = 0; i < 3; ++i) {
        w.ptr[i] = got[i];
        if (i)
            w.slot[i] = got[i - 1] + o.medallion.hop[i];
    }
    return true;
}

// IntArray entry `index` of the block whose table pointer is at mgr + o.int_table: its u32 live
// length and its data pointer.
inline bool IntArray(const EdenDsmodHostApi& h, std::uint64_t mgr, const Offsets& o,
                     std::uint32_t index, std::uint32_t& len, std::uint64_t& data) {
    std::uint64_t values = 0;
    if (!detail::Read(h, mgr + o.int_table - kTableToValues, values) ||
        values < detail::kMinPointer)
        return false;
    const std::uint64_t entry = values + std::uint64_t(index) * kIntArrayEntry;
    return detail::Read(h, entry + kIntArrayLen, len) &&
           detail::Read(h, entry + kIntArrayData, data);
}

inline constexpr char kNotPlaced[] = "Medallion not placed: place it, then tap again";

// Precondition 3: the probe finds WarpMarkerData.Pos at medallion_index, and the placed signal
// T8c found holds (u32 [base + medallion_len] exactly 1, or Index[0] equals the placed value).
// Null when it holds, else the message.
inline const char* PlacedCheck(const EdenDsmodHostApi& h, const Offsets& o, const Walked& w) {
    const Probe v = FindHash(h, w.ptr[0] + o.v3a_table, kWarpPos);
    if (v.fail != ProbeFail::none || v.index != o.medallion_index)
        return "Medallion data not found (offsets wrong, T8c)";
    if (o.medallion_len) {
        std::uint32_t len = 0;
        if (!detail::Read(h, w.ptr[1] + o.medallion_len, len))
            return "Medallion data not found (offsets wrong, T8c)";
        // > 1: several medallions, and the move writes element 0 only
        return len == 1 ? nullptr : len == 0 ? kNotPlaced : "More than one medallion placed";
    }
    const Probe i = FindHash(h, w.ptr[0] + o.int_table, kWarpIndex);
    std::uint32_t len = 0;
    std::uint64_t data = 0;
    std::int32_t index = 0;
    if (i.fail != ProbeFail::none || !IntArray(h, w.ptr[0], o, i.index, len, data) || len < 1 ||
        !detail::Read(h, data, index) || index != o.medallion_index_placed.value)
        return kNotPlaced;
    return nullptr;
}

// "x=%.9g y=%.9g z=%.9g" of the floats as doubles: 9 significant digits round-trip a float, so two
// printed vec3 are the same text exactly when the floats are bitwise equal (InWorld excludes NaN).
// Every vec3 in a "totk travel: medallion" line goes through here ("After a crash" compares them).
struct Text {
    char s[96];
};
inline Text Format(const Bits& b) {
    Text t;
    std::snprintf(t.s, sizeof t.s, "x=%.9g y=%.9g z=%.9g", double(detail::Float(b[0])),
                  double(detail::Float(b[1])), double(detail::Float(b[2])));
    return t;
}

// The T8c report (no batch, no write): where the probes put WarpMarkerData.Pos and .Index, or which
// check refused, with the raw table header, one line per probe.
inline void Report(const EdenDsmodHostApi& h, std::uint64_t mgr, const Offsets& o) {
    const auto failed = [&](const char* table, const Probe& p, const char* why) {
        Say(h, EDEN_DSMOD_LOG_INFO,
            "totk travel: %s probe failed (%s; table 0x%llx count %u size %u)", table, why,
            static_cast<unsigned long long>(p.table), unsigned(p.count), unsigned(p.size));
    };
    const Probe v = FindHash(h, mgr + o.v3a_table, kWarpPos);
    if (v.fail == ProbeFail::none)
        Say(h, EDEN_DSMOD_LOG_INFO, "totk travel: v3a_table 0x%08x -> index %u (size %u)",
            unsigned(kWarpPos), unsigned(v.index), unsigned(v.size));
    else
        failed("v3a_table", v, kProbeText[unsigned(v.fail)]);
    const Probe i = FindHash(h, mgr + o.int_table, kWarpIndex);
    std::uint32_t len = 0;
    std::uint64_t data = 0;
    if (i.fail != ProbeFail::none)
        failed("int_table", i, kProbeText[unsigned(i.fail)]);
    else if (!IntArray(h, mgr, o, i.index, len, data))
        failed("int_table", i, "entry unreadable");
    else
        Say(h, EDEN_DSMOD_LOG_INFO,
            "totk travel: int_table 0x%08x -> index %u (size %u); data at 0x%llx",
            unsigned(kWarpIndex), unsigned(i.index), unsigned(i.size),
            static_cast<unsigned long long>(data));
}

// Go and restore: one batch of 4 ops. Ops 1-3 rewrite mgr, base and P over themselves (they stop a
// re-allocated manager or block from sending the write to stale memory); op 4, last, writes the
// 12 bytes at P from `expect` to `value`. The host checks every expect before the first store.
inline bool SendBatch(const EdenDsmodHostWriteApi& w, const Walked& m, const Bits& expect,
                      const Bits& value) {
    const EdenDsmodWriteOp ops[4] = {{m.slot[0], 8, 0, &m.ptr[0], &m.ptr[0]},
                                     {m.slot[1], 8, 0, &m.ptr[1], &m.ptr[1]},
                                     {m.slot[2], 8, 0, &m.ptr[2], &m.ptr[2]},
                                     {m.p, 12, 0, expect.data(), value.data()}};
    return w.write_batch(w.userdata, ops, 4) == EDEN_DSMOD_TRUE;
}

} // namespace totk::travel

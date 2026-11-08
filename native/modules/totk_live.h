// SPDX-License-Identifier: GPL-3.0-or-later
// Live inventory and equipped items on 1.2.1 (docs/PLAN.md §T3c): every IntArray and
// String64Array key ReadPouch reads (totk_save.h) is copied from the GameData tables into a
// progress.sav-format buffer (SaveBuilder), which the existing GameData::Parse and BuildSnapshot
// turn into tabs and gear, so the parser's bounds apply to live data too. Effect.Type (EnumArray,
// layout UNKNOWN) is copied from the save's item at the same pouch index when the ids match.
// Gate: A1's ReadLoading. Lookup: A2's FindHash. Checks: the index self-check (o.pouch_index),
// the manager's, each block's and each entry's vtable in main, each String64 element's pointer to
// its own text, a NUL in every text, two passes compared. Every address comes from `o` and the
// layout constants in totk_offsets.h; every read goes through ReadGuest<Reject>; never writes.
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "totk_gamedata.h"
#include "totk_offsets.h"
#include "totk_save.h"
#include "totk_status.h"

namespace totk::live {

enum class Result : std::uint8_t {
    off,          // a table offset empty: no host call (ReadLoading refuses empty chains)
    gate_unknown, // ReadLoading -1
    loading,      // ReadLoading 1
    index,        // the index self-check failed (`why`: "<key> <got>")
    layout,       // a vtable, self-pointer, NUL, probe or entry check failed
    torn,         // the two passes differ
    ok,
};

namespace detail {
using totk::detail::kMinPointer;

// The keys ReadPouch reads from an IntArray (text false) or String64Array (text true) table:
// the PouchKeys hash and the suffix that names it (for SaveBuilder and the log).
struct Key {
    std::uint32_t PouchKeys::*hash;
    const char* suffix;
    bool text;
};
inline constexpr Key kKeys[] = {{&PouchKeys::name, ".Content.Name", true},
                                {&PouchKeys::combined, ".Content.Combined.Name", true},
                                {&PouchKeys::life, ".Content.Life", false},
                                {&PouchKeys::stock, ".Content.StockNum", false},
                                {&PouchKeys::equip, ".EquipIndex", false},
                                {&PouchKeys::life_recover, ".Content.LifeRecover", false}};
// Every PouchKeys field but effect (copied apart) is here, each suffix hashes to its field.
static_assert(sizeof(PouchKeys) == sizeof(std::uint32_t) * (std::size(kKeys) + 1));
static_assert([]() consteval {
    for (std::size_t p = 0; p < std::size(kPouchNames); ++p)
        for (const Key& k : kKeys)
            if (totk::detail::Key("Pouch.", kPouchNames[p], k.suffix) != kPouchKeys[p].*k.hash)
                return false;
    return true;
}());

struct Layout {
    std::uint64_t entry, len, data, elem;
};
inline constexpr Layout kInts{kIntArrayEntry, kIntArrayLen, kIntArrayData, 4};
inline constexpr Layout kTexts{kS64aEntry, kS64aLen, kS64aData, kS64Element};
inline constexpr std::size_t kTextBytes = 64; // a String64's text
static_assert(kIntArrayEntry <= kS64aEntry && kIntArrayData + 8 <= kIntArrayEntry &&
              kS64aData + 8 <= kS64aEntry && kIntArrayLen + 4 <= kIntArrayEntry &&
              kS64aLen + 4 <= kS64aEntry && kS64Text + kTextBytes <= kS64Element);

inline bool InMain(const EdenDsmodHostApi& h, std::uint64_t vtable) {
    return dsmod_sdk::InMain(h.main_base, h.main_size, vtable, 8);
}

// The type block before the table pointer at `at`, in one read: its vtable in main and its
// values pointer.
inline bool Block(const EdenDsmodHostApi& h, std::uint64_t at, std::uint64_t& values) {
    std::array<std::uint64_t, kBlockBytes / 8> b{};
    const std::uint64_t block = at - kTableToBlock;
    if (block % 8 != 0 || !totk::detail::Read(h, block, b) || !InMain(h, b[0]))
        return false;
    values = b[(kTableToBlock - kTableToValues) / 8];
    return values >= kMinPointer;
}

// Entry `index` of `values` in one read of its full size (vtable in main), then the first
// min(length, kMaxItems) elements in one read; `data` and `len` receive the element array's
// address and length.
inline bool Array(const EdenDsmodHostApi& h, std::uint64_t values, std::uint32_t index,
                  const Layout& l, std::vector<std::uint8_t>& out, std::uint64_t& data,
                  std::uint32_t& len) {
    const std::uint64_t off = std::uint64_t(index) * l.entry; // index < kMaxTable: no overflow
    if (values > std::numeric_limits<std::uint64_t>::max() - off)
        return false;
    const std::uint64_t entry = values + off;
    std::uint8_t e[kS64aEntry];
    std::uint64_t vtable = 0;
    if (entry % 8 != 0 || !dsmod_sdk::ReadGuest<dsmod_sdk::MissingIsMapped::Reject>(h, entry, e,
                                                                                     l.entry))
        return false;
    std::memcpy(&vtable, e, 8);
    std::memcpy(&len, e + l.len, 4);
    std::memcpy(&data, e + l.data, 8);
    if (!InMain(h, vtable))
        return false;
    // shortcut: the first kMaxItems elements, as the save path publishes at most kMaxItems; the
    // real 1.2.1 lengths are UNKNOWN until T8d's `ok lengths=` line.
    out.assign(std::min<std::size_t>(len, kMaxItems) * l.elem, 0);
    return out.empty() ||
           (data >= kMinPointer && data % 4 == 0 &&
            dsmod_sdk::ReadGuest<dsmod_sdk::MissingIsMapped::Reject>(h, data, out.data(),
                                                                     out.size()));
}

// One pass over every key into `b`. ok (`why`: "lengths=" and each pouch's Content.Name length),
// index (with `why`) or layout.
inline Result Pass(const EdenDsmodHostApi& h, const Offsets& o, std::uint64_t mgr,
                   const GameData* save, SaveBuilder& b, std::string& why) {
    constexpr auto kMax = std::numeric_limits<std::uint64_t>::max();
    std::uint64_t vtable = 0, ints = 0, texts = 0;
    if (mgr % 8 != 0 || mgr > kMax - std::max(o.int_table, o.s64a_table) ||
        !totk::detail::Read(h, mgr, vtable) || !InMain(h, vtable) ||
        !Block(h, mgr + o.int_table, ints) || !Block(h, mgr + o.s64a_table, texts))
        return Result::layout;
    std::vector<std::uint8_t> raw;
    std::string lengths = "lengths=";
    for (std::size_t p = 0; p < kPouchKeys.size(); ++p) {
        std::uint32_t name_len = 0;
        std::vector<std::string> names;
        for (const Key& k : kKeys) {
            const std::string key = "Pouch." + std::string(kPouchNames[p]) + k.suffix;
            const Probe r =
                FindHash(h, mgr + (k.text ? o.s64a_table : o.int_table), kPouchKeys[p].*k.hash);
            const bool name = &k == &kKeys[0];
            if (name && o.pouch_index[p] &&
                (r.fail != ProbeFail::none || r.index != o.pouch_index[p])) {
                why = key + " " +
                      (r.fail == ProbeFail::none ? std::to_string(r.index)
                                                 : std::string(kProbeText[unsigned(r.fail)]));
                return Result::index;
            }
            if (r.fail == ProbeFail::missing)
                continue; // the save path reads the key as absent too
            std::uint64_t data = 0;
            std::uint32_t len = 0;
            if (r.fail != ProbeFail::none ||
                !Array(h, k.text ? texts : ints, r.index, k.text ? kTexts : kInts, raw, data, len))
                return Result::layout;
            if (!k.text) {
                std::vector<std::uint32_t> w(raw.size() / 4);
                if (!w.empty()) // an empty pouch: no copy from a null data()
                    std::memcpy(w.data(), raw.data(), raw.size());
                b.Words(GdType::IntArray, key, w);
                continue;
            }
            std::vector<std::string> v;
            for (std::size_t at = 0; at < raw.size(); at += kS64Element) {
                std::uint64_t self = 0;
                std::memcpy(&self, raw.data() + at, 8);
                const char* text = reinterpret_cast<const char*>(raw.data() + at + kS64Text);
                const void* nul = std::memchr(text, 0, kTextBytes);
                if (self != data + at + kS64Text || !nul)
                    return Result::layout;
                v.emplace_back(text, static_cast<const char*>(nul));
            }
            if (name) {
                names = v;
                name_len = len;
            }
            b.String64Array(key, v);
        }
        lengths += (p ? "," : "") + std::to_string(name_len);
        // Effect.Type: the save's value where the save has the same id at that index, else 0
        // (no label).
        const auto ids = save ? save->String64Array(kPouchKeys[p].name) : std::nullopt;
        const auto effects = save ? save->EnumArray(kPouchKeys[p].effect) : std::nullopt;
        if (!ids || !effects)
            continue;
        std::vector<std::uint32_t> words(names.size());
        for (std::size_t i = 0; i < names.size(); ++i)
            if (i < ids->size() && i < effects->size() && (*ids)[i] == names[i])
                words[i] = (*effects)[i];
        b.Words(GdType::EnumArray, "Pouch." + std::string(kPouchNames[p]) + ".Content.Effect.Type",
                words);
    }
    why = lengths;
    return Result::ok;
}
} // namespace detail

// The live GameData as a progress.sav-format buffer in `out` (Result::ok, `why` the
// Content.Name lengths), or why not. `save` (may be null) gives the effects.
inline Result ReadGameData(const EdenDsmodHostApi& h, const Offsets& o, const GameData* save,
                           std::vector<std::uint8_t>& out, std::string& why) {
    if (!o.int_table || !o.s64a_table)
        return Result::off;
    Unknown unknown;
    std::uint32_t n = 0;
    std::uint64_t mgr = 0;
    const int gate = ReadLoading(h, h.main_base, o, unknown, n, mgr);
    if (gate != 0)
        return gate < 0 ? Result::gate_unknown : Result::loading;
    std::vector<std::uint8_t> first;
    for (int pass = 0; pass < 2; ++pass) {
        SaveBuilder b;
        if (const Result r = detail::Pass(h, o, mgr, save, b, why); r != Result::ok)
            return r;
        (pass ? out : first) = b.Build();
    }
    return first == out ? Result::ok : Result::torn;
}

} // namespace totk::live

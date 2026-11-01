// SPDX-License-Identifier: GPL-3.0-or-later
// TotK GameData save files (progress.sav, caption.sav): murmur3 key hashes and a bounds-checked
// parser. Format [S11 TotkMods/Research Formats/Sav]: magic 0x01020304 at 0, data offset at 8,
// {u32 hash, u32 value} entries from 0x20 up to the data offset; an entry with hash 0 starts a
// section whose type is its value. Scalars hold the value inline; arrays and Vector3 hold an
// absolute file offset (arrays: u32 count, then the elements).
#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "core/mods/modules/dsmod_module_sdk.h"

namespace totk {

// MurmurHash3 x86_32, seed 0: GameData key and enum value hashes [S28 §3][S29].
constexpr std::uint32_t Murmur3(std::string_view s) {
    constexpr std::uint32_t c1 = 0xcc9e2d51, c2 = 0x1b873593;
    const auto rotl = [](std::uint32_t x, int r) { return x << r | x >> (32 - r); };
    const auto byte = [&s](std::size_t i) { return std::uint32_t(std::uint8_t(s[i])); };
    std::uint32_t h = 0;
    const std::size_t blocks = s.size() / 4;
    for (std::size_t i = 0; i < blocks; ++i) {
        std::uint32_t k = byte(4 * i) | byte(4 * i + 1) << 8 | byte(4 * i + 2) << 16 |
                          byte(4 * i + 3) << 24;
        h ^= rotl(k * c1, 15) * c2;
        h = rotl(h, 13) * 5 + 0xe6546b64;
    }
    std::uint32_t k = 0;
    for (std::size_t i = s.size() & ~std::size_t(3), shift = 0; i < s.size(); ++i, shift += 8)
        k |= byte(i) << shift;
    if (s.size() & 3)
        h ^= rotl(k * c1, 15) * c2;
    h ^= std::uint32_t(s.size());
    h ^= h >> 16;
    h *= 0x85ebca6b;
    h ^= h >> 13;
    h *= 0xc2b2ae35;
    h ^= h >> 16;
    return h;
}
// Published vectors [S28 §3][S29 zelda-totk.js:30-38]; checked against the planner's mm3.py.
static_assert(Murmur3("PlayerStatus.MaxLife") == 0xfbe01da1);
static_assert(Murmur3("PlayerStatus.CurrentRupee") == 0xa77921d7);
static_assert(Murmur3("PlayerStatus.MaxStamina") == 0xf9212c74);
static_assert(Murmur3("PlayerStatus.MaxEnergy") == 0xafd01d68);
static_assert(Murmur3("PlayerStatus.SavePos") == 0xc884818d);
static_assert(Murmur3("PlayerStatus.Life") == 0x31ab5580);
static_assert(Murmur3("Pouch.Material.Content.Name") == 0xd96ebf12);
static_assert(Murmur3("Pouch.Weapon.Content.Name") == 0x65efd0be);
static_assert(Murmur3("MapData.IconData.StampData.Type") == 0x14d7f4c4);
static_assert(Murmur3("") == 0 && Murmur3("a") != Murmur3("b")); // tail-only and empty paths

enum class GdType : std::uint32_t {
    Bool = 0x00,
    Int = 0x02,
    IntArray = 0x03,
    Float = 0x04,
    Enum = 0x06, // value = Murmur3 of the enum value's name
    EnumArray = 0x07,
    Vector3 = 0x0A,
    String64Array = 0x11,
    UInt = 0x14,
};

class GameData {
public:
    static constexpr std::uint32_t kMagic = 0x01020304;
    static constexpr std::size_t kEntriesBegin = 0x20;
    static constexpr std::size_t kMaxBytes = 64 << 20; // the save API's per-read cap [S1]

    // nullopt: bad magic, too short or long, or a data offset outside [0x20, size]. Entries of other
    // types, before the first section or with a bad offset or length are left out (absent).
    static std::optional<GameData> Parse(std::vector<std::uint8_t> bytes) {
        GameData g;
        g.bytes_ = std::move(bytes);
        const std::size_t size = g.bytes_.size();
        if (size < kEntriesBegin || size > kMaxBytes || g.U32(0) != kMagic)
            return std::nullopt;
        const std::uint32_t data_offset = g.U32(8);
        if (data_offset < kEntriesBegin || data_offset > size)
            return std::nullopt;
        std::optional<std::uint32_t> type; // none before the first section header
        for (std::size_t at = kEntriesBegin; at + 8 <= data_offset; at += 8) {
            const std::uint32_t hash = g.U32(at), value = g.U32(at + 4);
            if (hash == 0) {
                type = value;
                continue;
            }
            if (type)
                g.Add(hash, *type, value);
        }
        return g;
    }

    std::optional<bool> Bool(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::Bool);
        return e ? std::optional<bool>(e->value != 0) : std::nullopt;
    }
    std::optional<std::int32_t> Int(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::Int);
        return e ? std::optional<std::int32_t>(std::int32_t(e->value)) : std::nullopt;
    }
    std::optional<std::uint32_t> UInt(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::UInt);
        return e ? std::optional<std::uint32_t>(e->value) : std::nullopt;
    }
    std::optional<float> Float(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::Float);
        return e ? std::optional<float>(BitFloat(e->value)) : std::nullopt;
    }
    std::optional<std::uint32_t> Enum(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::Enum);
        return e ? std::optional<std::uint32_t>(e->value) : std::nullopt;
    }
    std::optional<std::array<float, 3>> Vector3(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::Vector3);
        if (!e)
            return std::nullopt;
        return std::array<float, 3>{BitFloat(U32(e->value)), BitFloat(U32(e->value + 4)),
                                    BitFloat(U32(e->value + 8))};
    }
    std::optional<std::vector<std::int32_t>> IntArray(std::uint32_t hash) const {
        return Words<std::int32_t>(Find(hash, GdType::IntArray));
    }
    std::optional<std::vector<std::uint32_t>> EnumArray(std::uint32_t hash) const {
        return Words<std::uint32_t>(Find(hash, GdType::EnumArray));
    }
    // Each string ends at its first NUL; a 64-byte string without one rejects the whole array.
    // Checked here, per lookup, so Parse stays linear in the file size.
    std::optional<std::vector<std::string>> String64Array(std::uint32_t hash) const {
        const Entry* e = Find(hash, GdType::String64Array);
        if (!e)
            return std::nullopt;
        std::vector<std::string> out(e->count);
        for (std::uint32_t i = 0; i < e->count; ++i) {
            const char* s = reinterpret_cast<const char*>(&bytes_[e->value + 64 * std::size_t(i)]);
            const void* nul = std::memchr(s, 0, 64);
            if (!nul)
                return std::nullopt;
            out[i].assign(s, static_cast<const char*>(nul));
        }
        return out;
    }

private:
    static constexpr std::uint32_t kAmbiguous = 0xFFFFFFFF;
    struct Entry {
        std::uint32_t type;
        std::uint32_t value; // the value, or the offset of the data (after an array's count)
        std::uint32_t count; // array element count
    };

    std::uint32_t U32(std::size_t at) const {
        return dsmod_sdk::Le32(&bytes_[at]);
    }
    static float BitFloat(std::uint32_t bits) {
        float f;
        std::memcpy(&f, &bits, 4);
        return f;
    }

    // Is [at, at + n * elem) inside the file? No multiplication, so no overflow.
    bool Fits(std::uint64_t at, std::uint64_t n, std::uint64_t elem) const {
        const std::uint64_t size = bytes_.size();
        return at <= size && n <= (size - at) / elem;
    }

    void Add(std::uint32_t hash, std::uint32_t type, std::uint32_t value) {
        Entry e{type, value, 0};
        switch (GdType(type)) {
        case GdType::Bool:
        case GdType::Int:
        case GdType::Float:
        case GdType::Enum:
        case GdType::UInt:
            break;
        case GdType::Vector3:
            if (!Fits(value, 3, 4))
                return;
            break;
        case GdType::IntArray:
        case GdType::EnumArray:
        case GdType::String64Array: {
            if (!Fits(value, 1, 4))
                return;
            e.count = U32(value);
            e.value = value + 4;
            const std::uint64_t elem = GdType(type) == GdType::String64Array ? 64 : 4;
            if (!Fits(e.value, e.count, elem))
                return;
            break;
        }
        default:
            return; // a type this module does not read
        }
        // Two keys with one hash (types differ, or a corrupt file): neither is trusted.
        const auto [it, inserted] = entries_.try_emplace(hash, e);
        if (!inserted)
            it->second.type = kAmbiguous;
    }

    const Entry* Find(std::uint32_t hash, GdType type) const {
        const auto it = entries_.find(hash);
        return it != entries_.end() && it->second.type == std::uint32_t(type) ? &it->second
                                                                               : nullptr;
    }

    template <class T>
    std::optional<std::vector<T>> Words(const Entry* e) const {
        if (!e)
            return std::nullopt;
        std::vector<T> out(e->count);
        for (std::uint32_t i = 0; i < e->count; ++i)
            out[i] = T(U32(e->value + 4 * std::size_t(i)));
        return out;
    }

    std::vector<std::uint8_t> bytes_;
    std::unordered_map<std::uint32_t, Entry> entries_;
};

} // namespace totk

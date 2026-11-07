// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later
// BYML v2-7 reader (either endianness), copied from the Eden Duo companions' Wonder catalog [S2
// native/modules/wonder_catalog.cpp "BYML" section and BymlNode, wonder_catalog.h:29-80], without
// its zstd path: the runtime's read_romfs already decompresses .zs files (Eden Duo patch 0009).
// Bounded and fail closed: any read out of range, an unknown node type, too deep or too many
// nodes makes the whole parse nullopt, never partial data. Used for LocationArea (§A2).
#pragma once

#include <algorithm>
#include <bit>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace totk::byml {

struct Entry;

struct Node {
    enum class Type : std::uint8_t {
        Null,
        String, // 0xA0
        Binary, // 0xA1 / 0xA2 (bytes kept in `str`)
        Bool,   // 0xD0
        Int,    // 0xD1 s32
        Float,  // 0xD2 f32
        UInt,   // 0xD3 u32
        Int64,  // 0xD4
        UInt64, // 0xD5
        Double, // 0xD6
        Array,  // 0xC0
        Dict,   // 0xC1, also 0x20/0x21 hash dicts (keys rendered as "0x<hex hash>")
    };
    Type type{Type::Null};
    std::string str;
    union {
        bool b;
        std::int64_t i;
        std::uint64_t u;
        double f;
    } num{};
    std::vector<Node> array;
    std::vector<Entry> dict; // in file order (BYML keys are sorted)

    const Node* Get(std::string_view key) const; // dict member or nullptr
    const Node* At(std::size_t index) const {    // array element or nullptr
        return type == Type::Array && index < array.size() ? &array[index] : nullptr;
    }
    std::size_t Size() const; // array/dict element count
    std::optional<std::string_view> String() const {
        if (type != Type::String)
            return std::nullopt;
        return std::string_view(str);
    }
    // Int/UInt/Int64/UInt64 (u64 > INT64_MAX fails)
    std::optional<std::int64_t> Integer() const {
        switch (type) {
        case Type::Int:
        case Type::Int64:
            return num.i;
        case Type::UInt:
        case Type::UInt64:
            if (num.u > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
                return std::nullopt;
            return static_cast<std::int64_t>(num.u);
        default:
            return std::nullopt;
        }
    }
    // any integer or float type
    std::optional<double> Number() const {
        switch (type) {
        case Type::Float:
        case Type::Double:
            return num.f;
        case Type::Int:
        case Type::Int64:
            return static_cast<double>(num.i);
        case Type::UInt:
        case Type::UInt64:
            return static_cast<double>(num.u);
        default:
            return std::nullopt;
        }
    }
};

struct Entry {
    std::string key;
    Node value;
};

inline std::size_t Node::Size() const {
    return type == Type::Array ? array.size() : type == Type::Dict ? dict.size() : 0;
}
inline const Node* Node::Get(std::string_view key) const {
    if (type != Type::Dict)
        return nullptr;
    for (const auto& e : dict)
        if (e.key == key)
            return &e.value;
    return nullptr;
}

struct Limits {
    std::size_t max_nodes = 2'000'000; // shared subtrees are expanded, so this also bounds DAGs
    std::size_t max_depth = 64;
};

namespace detail {
using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;
using s64 = std::int64_t;

// Endian-aware bounded reader. Out-of-range reads set `bad` and return 0.
struct Cursor {
    std::span<const u8> s;
    bool be{};
    bool bad{};

    bool Has(std::size_t o, std::size_t n) {
        if (o > s.size() || n > s.size() - o) {
            bad = true;
            return false;
        }
        return true;
    }
    u8 U8(std::size_t o) {
        return Has(o, 1) ? s[o] : 0;
    }
    u16 U16(std::size_t o) {
        if (!Has(o, 2))
            return 0;
        return be ? static_cast<u16>(s[o] << 8 | s[o + 1]) : static_cast<u16>(s[o] | s[o + 1] << 8);
    }
    u32 U32(std::size_t o) {
        if (!Has(o, 4))
            return 0;
        u32 v = 0;
        for (int i = 0; i < 4; ++i)
            v |= static_cast<u32>(s[o + i]) << (be ? 8 * (3 - i) : 8 * i);
        return v;
    }
    u64 U64(std::size_t o) {
        const u64 a = U32(o), b = U32(o + 4);
        return be ? (a << 32 | b) : (b << 32 | a);
    }
};

struct Parser {
    Cursor c;
    Limits limits;
    std::size_t nodes{};
    std::vector<std::string> keys;
    std::vector<std::string> strings;

    // Node header: type byte + u24 count, laid out in the file's byte order.
    bool Header(std::size_t off, u8& type, u32& count) {
        const u32 w = c.U32(off);
        if (c.bad)
            return false;
        if (c.be) {
            type = static_cast<u8>(w >> 24);
            count = w & 0xFFFFFF;
        } else {
            type = static_cast<u8>(w & 0xFF);
            count = w >> 8;
        }
        return true;
    }

    bool StringTable(u32 off, std::vector<std::string>& out) {
        if (off == 0)
            return true;
        u8 type;
        u32 count;
        if (!Header(off, type, count) || type != 0xC2)
            return false;
        if (!c.Has(off + 4, (static_cast<std::size_t>(count) + 1) * 4))
            return false;
        out.reserve(count);
        for (u32 i = 0; i < count; ++i) {
            const std::size_t at = off + static_cast<std::size_t>(c.U32(off + 4 + i * 4));
            if (at >= c.s.size())
                return false;
            const void* end = std::memchr(c.s.data() + at, 0, c.s.size() - at);
            if (!end)
                return false;
            out.emplace_back(reinterpret_cast<const char*>(c.s.data() + at),
                             static_cast<const u8*>(end) - (c.s.data() + at));
        }
        return true;
    }

    bool Value(u8 type, u32 raw, std::size_t depth, Node& out) {
        if (++nodes > limits.max_nodes)
            return false;
        using T = Node::Type;
        switch (type) {
        case 0xA0:
            if (raw >= strings.size())
                return false;
            out.type = T::String;
            out.str = strings[raw];
            return true;
        case 0xA1:
        case 0xA2: {
            const u32 size = c.U32(raw);
            const std::size_t data = raw + (type == 0xA2 ? 8u : 4u);
            if (c.bad || !c.Has(data, size))
                return false;
            out.type = T::Binary;
            out.str.assign(reinterpret_cast<const char*>(c.s.data() + data), size);
            return true;
        }
        case 0xD0:
            out.type = T::Bool;
            out.num.b = raw != 0;
            return true;
        case 0xD1:
            out.type = T::Int;
            out.num.i = static_cast<std::int32_t>(raw);
            return true;
        case 0xD2:
            out.type = T::Float;
            out.num.f = std::bit_cast<float>(raw);
            return true;
        case 0xD3:
            out.type = T::UInt;
            out.num.u = raw;
            return true;
        case 0xD4:
        case 0xD5:
        case 0xD6: {
            const u64 v = c.U64(raw);
            if (c.bad)
                return false;
            out.type = type == 0xD4 ? T::Int64 : type == 0xD5 ? T::UInt64 : T::Double;
            if (type == 0xD4)
                out.num.i = static_cast<s64>(v);
            else if (type == 0xD5)
                out.num.u = v;
            else
                out.num.f = std::bit_cast<double>(v);
            return true;
        }
        case 0xFF:
            out.type = T::Null;
            return true;
        case 0xC0:
        case 0xC1:
        case 0x20:
        case 0x21:
            return Container(raw, type, depth + 1, out);
        default:
            return false; // unknown type: fail closed
        }
    }

    bool Container(std::size_t off, u8 expect, std::size_t depth, Node& out) {
        if (depth > limits.max_depth || (off & 3) != 0)
            return false;
        u8 type;
        u32 count;
        if (!Header(off, type, count) || type != expect)
            return false;
        if (count > limits.max_nodes - std::min(nodes, limits.max_nodes))
            return false;
        using T = Node::Type;
        if (type == 0xC0) {
            const std::size_t values = (off + 4 + count + 3) & ~std::size_t{3};
            if (!c.Has(off + 4, count) || !c.Has(values, static_cast<std::size_t>(count) * 4))
                return false;
            out.type = T::Array;
            out.array.resize(count);
            for (u32 i = 0; i < count; ++i)
                if (!Value(c.s[off + 4 + i], c.U32(values + i * 4), depth, out.array[i]))
                    return false;
            return true;
        }
        if (type == 0xC1) {
            if (!c.Has(off + 4, static_cast<std::size_t>(count) * 8))
                return false;
            out.type = T::Dict;
            out.dict.resize(count);
            for (u32 i = 0; i < count; ++i) {
                const std::size_t e = off + 4 + static_cast<std::size_t>(i) * 8;
                const u32 w = c.U32(e);
                const u32 key = c.be ? w >> 8 : w & 0xFFFFFF;
                const u8 vtype = static_cast<u8>(c.be ? w & 0xFF : w >> 24);
                if (key >= keys.size())
                    return false;
                out.dict[i].key = keys[key];
                if (!Value(vtype, c.U32(e + 4), depth, out.dict[i].value))
                    return false;
            }
            return true;
        }
        // 0x20 / 0x21: hash-keyed dicts (BYML v7): count x {hash (u32|u64), value u32}, then
        // count type bytes. Keys are kept as "0x<hash>".
        const std::size_t hash_size = type == 0x20 ? 4 : 8;
        const std::size_t entry = hash_size + 4;
        const std::size_t types = off + 4 + entry * count;
        if (!c.Has(off + 4, entry * count) || !c.Has(types, count))
            return false;
        out.type = T::Dict;
        out.dict.resize(count);
        for (u32 i = 0; i < count; ++i) {
            const std::size_t e = off + 4 + entry * i;
            const u64 hash = hash_size == 4 ? c.U32(e) : c.U64(e);
            char buf[24];
            const int n =
                std::snprintf(buf, sizeof(buf), "0x%0*llx", static_cast<int>(hash_size * 2),
                              static_cast<unsigned long long>(hash));
            out.dict[i].key.assign(buf, n > 0 ? static_cast<std::size_t>(n) : 0);
            if (!Value(c.s[types + i], c.U32(e + hash_size), depth, out.dict[i].value))
                return false;
        }
        return true;
    }
};
} // namespace detail

// BYML versions 2..7, "YB" little or "BY" big endian; nullopt on anything malformed.
inline std::optional<Node> Parse(std::span<const std::uint8_t> data, Limits limits = {}) {
    if (data.size() < 16)
        return std::nullopt;
    detail::Parser p;
    p.c = detail::Cursor{data, false, false};
    p.limits = limits;
    if (data[0] == 'Y' && data[1] == 'B')
        p.c.be = false;
    else if (data[0] == 'B' && data[1] == 'Y')
        p.c.be = true;
    else
        return std::nullopt;
    const std::uint16_t version = p.c.U16(2);
    if (version < 2 || version > 7)
        return std::nullopt;
    const std::uint32_t key_table = p.c.U32(4), string_table = p.c.U32(8), root = p.c.U32(12);
    if (!p.StringTable(key_table, p.keys) || !p.StringTable(string_table, p.strings))
        return std::nullopt;
    Node out;
    if (root == 0)
        return out; // empty document
    std::uint8_t type;
    std::uint32_t count;
    if (!p.Header(root, type, count))
        return std::nullopt;
    if (type != 0xC0 && type != 0xC1 && type != 0x20 && type != 0x21)
        return std::nullopt;
    if (!p.Container(root, type, 0, out) || p.c.bad)
        return std::nullopt;
    return out;
}

} // namespace totk::byml

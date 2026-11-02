// SPDX-License-Identifier: GPL-3.0-or-later
// Item names from the game's own message file, read at runtime (nothing shipped):
// romfs:Mals/USen.Product.<n>.sarc.zs#ActorMsg/PouchContent.msbt, which Eden Duo patch 0009
// decompresses. English only. The MSBT reader (LBL1 + TXT2, UTF-16LE -> UTF-8, tags dropped) is
// adapted from Eden Duo's mod_msbt.cpp (GPL-3.0) [S1 mod_msbt.h:1-50]; little-endian UTF-16 only.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "core/mods/dsmod_module_abi.h"

namespace totk::msbt {

using Labels = std::unordered_map<std::string, std::string>; // label -> UTF-8 text

inline constexpr std::size_t kMaxFile = 64u << 20; // read_romfs's own cap [S1 mod_module_host.cpp:29]

namespace detail {
using Bytes = std::span<const std::uint8_t>;
inline std::uint32_t U16(Bytes d, std::uint64_t at) {
    return d[at] | std::uint32_t(d[at + 1]) << 8;
}
inline std::uint32_t U32(Bytes d, std::uint64_t at) {
    return U16(d, at) | U16(d, at + 2) << 16;
}
inline void Utf8(std::string& out, std::uint32_t cp) {
    if (cp < 0x80) {
        out += char(cp);
    } else if (cp < 0x800) {
        out += char(0xC0 | cp >> 6);
        out += char(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        out += char(0xE0 | cp >> 12);
        out += char(0x80 | (cp >> 6 & 0x3F));
        out += char(0x80 | (cp & 0x3F));
    } else {
        out += char(0xF0 | cp >> 18);
        out += char(0x80 | (cp >> 12 & 0x3F));
        out += char(0x80 | (cp >> 6 & 0x3F));
        out += char(0x80 | (cp & 0x3F));
    }
}
// One TXT2 entry [at, end): text up to the first NUL. Tags are dropped: 0x0E group type size
// params, 0x0F group type (closing). So are control characters other than '\n' and lone surrogates.
inline std::string Text(Bytes d, std::uint64_t at, std::uint64_t end) {
    std::string out;
    while (at + 2 <= end) {
        std::uint32_t c = U16(d, at);
        at += 2;
        if (c == 0)
            break;
        if (c == 0x0E || c == 0x0F) {
            if (at + 4 > end)
                break;
            at += 4; // group, type
            if (c == 0x0E) {
                if (at + 2 > end)
                    break;
                at += 2 + U16(d, at); // parameter size, parameters
            }
            continue;
        }
        if (c >= 0xD800 && c <= 0xDBFF && at + 2 <= end) {
            const std::uint32_t low = U16(d, at);
            if (low >= 0xDC00 && low <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
                at += 2;
            }
        }
        if ((c < 0x20 && c != '\n') || (c >= 0xD800 && c <= 0xDFFF))
            continue;
        Utf8(out, c);
    }
    return out;
}
} // namespace detail

// Every label of a little-endian UTF-16 MSBT. False (and `out` empty) when the header, a section
// or a label runs past its bounds; a text entry out of bounds only drops its label.
inline bool Parse(std::span<const std::uint8_t> d, Labels& out) {
    using detail::U16;
    using detail::U32;
    out.clear();
    if (d.size() < 0x20 || std::memcmp(d.data(), "MsgStdBn", 8) != 0 || d[8] != 0xFF ||
        d[9] != 0xFE || d[12] != 1)
        return false;
    std::vector<std::pair<std::uint32_t, std::string>> labels; // (TXT2 index, label)
    std::uint64_t txt = 0, txt_size = 0;
    std::uint64_t at = 0x20;
    for (std::uint32_t s = 0, sections = U16(d, 14); s < sections && at + 16 <= d.size(); ++s) {
        const std::uint64_t size = U32(d, at + 4), body = at + 16, end = body + size;
        if (end > d.size())
            return false;
        if (std::memcmp(d.data() + at, "LBL1", 4) == 0) {
            if (size < 4)
                return false;
            const std::uint64_t buckets = U32(d, body);
            if (4 + 8 * buckets > size)
                return false;
            for (std::uint64_t b = 0; b < buckets; ++b) {
                const std::uint64_t count = U32(d, body + 4 + 8 * b);
                std::uint64_t p = body + U32(d, body + 8 + 8 * b);
                for (std::uint64_t k = 0; k < count; ++k) {
                    if (p >= end || p + 1 + d[p] + 4 > end)
                        return false;
                    const std::uint8_t len = d[p];
                    labels.emplace_back(U32(d, p + 1 + len),
                                        std::string(reinterpret_cast<const char*>(&d[p + 1]), len));
                    p += 1 + len + 4;
                }
            }
        } else if (std::memcmp(d.data() + at, "TXT2", 4) == 0) {
            txt = body;
            txt_size = size;
        }
        at = body + ((size + 15) & ~std::uint64_t(15));
    }
    if (txt_size < 4 || labels.empty())
        return false;
    const std::uint64_t count = U32(d, txt);
    if (4 + 4 * count > txt_size)
        return false;
    for (auto& [index, name] : labels) {
        if (index >= count)
            continue;
        const std::uint64_t begin = txt + U32(d, txt + 4 + 4 * std::uint64_t(index));
        const std::uint64_t end =
            index + 1 < count ? txt + U32(d, txt + 8 + 4 * std::uint64_t(index)) : txt + txt_size;
        if (begin <= end && end <= txt + txt_size)
            out.emplace(std::move(name), detail::Text(d, begin, end));
    }
    return true;
}

// The n of the newest Mals/USen.Product.<n>.sarc.zs (raw size queries, 200 down to 100; 142 on
// 1.4.3 [S28 §2], 1.2.0 UNVERIFIED: T8a's log shows it), or 0.
inline int FindMals(const EdenDsmodHostApi& h) {
    char path[64];
    for (int n = 200; n >= 100; --n) {
        std::snprintf(path, sizeof path, "romfs:Mals/USen.Product.%d.sarc.zs", n);
        if (h.read_romfs(h.userdata, path, 0, nullptr, 0) > 0)
            return n;
    }
    return 0;
}

// PouchContent.msbt of Mals <n>: a size query, then one read. False with `why` set otherwise.
inline bool LoadNames(const EdenDsmodHostApi& h, int n, Labels& out, const char*& why) {
    char path[96];
    std::snprintf(path, sizeof path, "romfs:Mals/USen.Product.%d.sarc.zs#ActorMsg/PouchContent.msbt",
                  n);
    const std::size_t size = h.read_romfs(h.userdata, path, 0, nullptr, 0);
    if (size == 0 || size > kMaxFile) {
        why = "PouchContent.msbt unreadable (Eden Duo patch 0009 missing?)";
        return false;
    }
    std::vector<std::uint8_t> bytes(size);
    if (h.read_romfs(h.userdata, path, 0, bytes.data(), size) != size) {
        why = "PouchContent.msbt short read";
        return false;
    }
    if (!Parse(bytes, out)) {
        why = "PouchContent.msbt is not a UTF-16LE MSBT";
        return false;
    }
    return true;
}

} // namespace totk::msbt

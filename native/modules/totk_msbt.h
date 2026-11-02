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
#include "totk_bytes.h"

namespace totk::msbt {

using Labels = std::unordered_map<std::string, std::string>; // label -> UTF-8 text

namespace detail {
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
// One TXT2 entry [at, end) of `r` (the TXT2 body): text up to the first NUL. Tags are dropped:
// 0x0E group type size params, 0x0F group type (closing). So are control characters other than
// '\n' and lone surrogates.
inline std::string Text(const Reader& r, std::uint64_t at, std::uint64_t end) {
    std::string out;
    while (at + 2 <= end) {
        std::uint32_t c = r.U16(at);
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
                at += 2 + r.U16(at); // parameter size, parameters
            }
            continue;
        }
        if (c >= 0xD800 && c <= 0xDBFF && at + 2 <= end) {
            const std::uint32_t low = r.U16(at);
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
// or a label runs past its bounds, when the label table is implausible (more labels than 5-byte
// slots: overlapping buckets) or there is no label; a text entry out of bounds only drops the
// labels it bounds. Each section is read through its own Reader, so nothing reads past it.
inline bool Parse(std::span<const std::uint8_t> d, Labels& out) {
    out.clear();
    const Reader file{d};
    if (d.size() < 0x20 || std::memcmp(d.data(), "MsgStdBn", 8) != 0 || file.U16(8) != 0xFEFF ||
        d[12] != 1)
        return false;
    std::vector<std::pair<std::uint32_t, std::string>> labels; // (TXT2 index, label)
    Reader txt{{}};
    std::uint64_t at = 0x20;
    for (std::uint32_t s = 0, sections = file.U16(14); s < sections && at + 16 <= d.size(); ++s) {
        const std::uint64_t size = file.U32(at + 4);
        if (size > d.size() - at - 16)
            return false;
        const Reader sec{d.subspan(at + 16, size)};
        if (std::memcmp(d.data() + at, "LBL1", 4) == 0) {
            const std::uint64_t buckets = sec.U32(0);
            if (4 + 8 * buckets > size)
                return false;
            for (std::uint64_t b = 0; b < buckets; ++b) {
                std::uint64_t p = sec.U32(8 + 8 * b);
                for (std::uint64_t k = 0, count = sec.U32(4 + 8 * b); k < count; ++k) {
                    const std::uint32_t len = sec.U8(p);
                    if (p + 1 + len + 4 > size || labels.size() >= size / 5)
                        return false;
                    labels.emplace_back(sec.U32(p + 1 + len),
                                        std::string(reinterpret_cast<const char*>(&sec.d[p + 1]), len));
                    p += 1 + len + 4;
                }
            }
        } else if (std::memcmp(d.data() + at, "TXT2", 4) == 0) {
            txt = sec;
        }
        at += 16 + ((size + 15) & ~std::uint64_t(15));
    }
    const std::uint64_t count = txt.U32(0), txt_size = txt.d.size();
    if (txt_size < 4 || labels.empty() || 4 + 4 * count > txt_size)
        return false;
    for (auto& [index, name] : labels) {
        if (index >= count)
            continue;
        const std::uint64_t begin = txt.U32(4 + 4 * std::uint64_t(index));
        const std::uint64_t end = index + 1 < count ? txt.U32(8 + 4 * std::uint64_t(index)) : txt_size;
        if (begin <= end && end <= txt_size)
            out.emplace(std::move(name), detail::Text(txt, begin, end));
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
    std::vector<std::uint8_t> bytes;
    if (!ReadAll(h, path, bytes)) {
        why = "PouchContent.msbt unreadable (Eden Duo patch 0009 missing?)";
        return false;
    }
    if (!Parse(bytes, out)) {
        why = "PouchContent.msbt is not a UTF-16LE MSBT";
        return false;
    }
    return true;
}

} // namespace totk::msbt

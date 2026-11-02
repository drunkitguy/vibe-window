// SPDX-License-Identifier: GPL-3.0-or-later
// T8a's one-shot dump [docs/PLAN.md §T3a' B5, §T8a]: log lines "totk dump ..." that name the
// game's UI textures, layout panes and message archives, so the generator's TEX table can be
// filled from the player's real 1.2.0 files. Runs only while the player's file user:totk-dump
// exists. Reads through read_romfs only; "<archive>#" (the decoded SARC itself) needs Eden Duo
// patch 0010. A failure logs one line and ends the dump.
// Formats: SARC as Eden Duo's ParseSarc [S1 mod_nx_assets.cpp:175-235]; BNTX/BRTI fields as its
// ParseBntx [S1 mod_nx_assets.cpp:252-318]; BFLYT txl1/mat1/pane fields as acnh_lyt.cpp (GPL-3.0)
// [S2]; wnd1's content and frame table offsets are Switch-Toolbox's WND1 layout (UNVERIFIED for
// TotK: a bad offset prints "mat=?").
#pragma once

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "core/mods/dsmod_module_abi.h"

namespace totk::dump {

using Bytes = std::span<const std::uint8_t>;
inline constexpr std::size_t kMaxFile = 64u << 20; // read_romfs's own cap
inline constexpr char kCommon[] = "romfs:UI/LayoutArchive/Common.Product.%d.Nin_NX_NVN.blarc.zs";
inline constexpr char kMals[] = "romfs:Mals/USen.Product.%d.sarc.zs";
// Layouts whose panes are dumped (blyt/<prefix>*.bflyt) [docs/PLAN.md §R2].
inline constexpr std::string_view kLayouts[] = {"Pause", "Pouch",    "App",
                                                "PlayerStatus", "ShortCut", "Map"};

// Bounds-checked little-endian reads: 0 / "" outside the data.
struct R {
    Bytes d;
    std::uint32_t U8(std::uint64_t at) const {
        return at < d.size() ? d[at] : 0;
    }
    std::uint32_t U16(std::uint64_t at) const {
        return U8(at) | U8(at + 1) << 8;
    }
    std::uint32_t U32(std::uint64_t at) const {
        return U16(at) | U16(at + 2) << 16;
    }
    // A u16 table index, or ~0 (matches nothing) when it lies outside the data.
    std::uint32_t Index(std::uint64_t at) const {
        return at + 2 <= d.size() ? U16(at) : ~0u;
    }
    float F32(std::uint64_t at) const {
        const std::uint32_t v = U32(at);
        float f;
        std::memcpy(&f, &v, 4);
        return f;
    }
    std::string Str(std::uint64_t at, std::size_t max) const {
        std::string s;
        for (std::size_t i = 0; i < max && U8(at + i); ++i)
            s += char(U8(at + i));
        return s;
    }
};

// One log line of at most 200 characters.
__attribute__((format(printf, 2, 3))) inline void Line(const EdenDsmodHostApi& h,
                                                       const char* format, ...) {
    char line[201];
    va_list args;
    va_start(args, format);
    std::vsnprintf(line, sizeof line, format, args);
    va_end(args);
    h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
}

struct Member {
    std::string name;
    Bytes bytes;
};

// The named members of a little-endian SARC, or nothing when the header or file table is bad.
inline std::vector<Member> Sarc(Bytes d) {
    const R r{d};
    if (d.size() < 0x14 || std::memcmp(d.data(), "SARC", 4) != 0 || r.U16(6) != 0xFEFF)
        return {};
    const std::uint64_t sfat = r.U16(4), data = r.U32(0xC);
    if (data > d.size() || sfat + 12 > data || std::memcmp(d.data() + sfat, "SFAT", 4) != 0)
        return {};
    const std::uint64_t count = r.U16(sfat + 6), nodes = sfat + r.U16(sfat + 4);
    const std::uint64_t sfnt = nodes + 16 * count;
    if (sfnt + 8 > data || std::memcmp(d.data() + sfnt, "SFNT", 4) != 0)
        return {};
    const std::uint64_t names = sfnt + r.U16(sfnt + 4);
    std::vector<Member> out;
    for (std::uint64_t i = 0; i < count; ++i) {
        const std::uint64_t node = nodes + 16 * i;
        const std::uint32_t attr = r.U32(node + 4), begin = r.U32(node + 8), end = r.U32(node + 12);
        if (!(attr & 0x01000000u) || end < begin || data + end > d.size())
            continue;
        const std::uint64_t name = names + 4 * std::uint64_t(attr & 0x00FFFFFFu);
        if (name >= data)
            continue;
        out.push_back({r.Str(name, data - name), d.subspan(data + begin, end - begin)});
    }
    return out;
}

// One line per BNTX texture: name (with its ^ suffix), size, format ((type << 8) | data type,
// hex), channel map (0 zero, 1 one, 2-5 RGBA). False when it is not a Switch BNTX.
inline bool Textures(const EdenDsmodHostApi& h, Bytes d) {
    const R r{d};
    if (d.size() < 0x30 || std::memcmp(d.data(), "BNTX", 4) != 0 ||
        std::memcmp(d.data() + 0x20, "NX  ", 4) != 0 || r.U32(0x2C) != 0)
        return false;
    const std::uint64_t count = r.U32(0x24), table = r.U32(0x28);
    for (std::uint64_t i = 0; i < count && table + 8 * i + 8 <= d.size(); ++i) {
        const std::uint64_t info = r.U32(table + 8 * i);
        if (r.U32(table + 8 * i + 4) != 0 || info + 0x68 > d.size() ||
            std::memcmp(d.data() + info, "BRTI", 4) != 0) {
            Line(h, "totk dump tex #%u: no BRTI", unsigned(i));
            continue;
        }
        const std::uint64_t name = r.U32(info + 0x60);
        Line(h, "totk dump tex %s %ux%u fmt=%04X ch=%u%u%u%u",
             r.U32(info + 0x64) ? "?" : r.Str(name + 2, r.U16(name)).c_str(), r.U32(info + 0x24),
             r.U32(info + 0x28), r.U32(info + 0x1C), r.U8(info + 0x58), r.U8(info + 0x59),
             r.U8(info + 0x5A), r.U8(info + 0x5B));
    }
    return true;
}

// One line per pic1 and wnd1 pane (wnd1: its content material, then one line per frame): name,
// translate, size, material name, the material's texture names, white and black colour (RGBA).
// False when it is not a BFLYT.
inline bool Panes(const EdenDsmodHostApi& h, std::string_view layout, Bytes d) {
    const R r{d};
    if (d.size() < 0x14 || std::memcmp(d.data(), "FLYT", 4) != 0)
        return false;
    std::vector<std::string> textures, materials;
    const auto material = [&](std::uint32_t m) -> std::string {
        return m < materials.size() ? materials[m] : "?";
    };
    std::uint64_t s = r.U16(6);
    for (std::uint32_t n = r.U16(0x10); n > 0 && s + 8 <= d.size(); --n) {
        const std::string_view tag(reinterpret_cast<const char*>(d.data() + s), 4);
        const std::uint64_t size = r.U32(s + 4);
        if (size < 8 || s + size > d.size())
            return false;
        const R sec{d.subspan(s, size)};
        if (tag == "txl1") {
            for (std::uint32_t i = 0, count = sec.U16(8); i < count; ++i)
                textures.push_back(sec.Str(0xC + sec.U32(0xC + 4 * i), 128));
        } else if (tag == "mat1") {
            for (std::uint32_t i = 0, count = sec.U16(8); i < count; ++i) {
                const std::uint64_t mo = sec.U32(0xC + 4 * i);
                std::string text = sec.Str(mo, 0x1C) + " tex=";
                for (std::uint32_t t = 0; t < (sec.U32(mo + 0x1C) & 3); ++t) {
                    const std::uint32_t ti = sec.U16(mo + 0x2C + 4 * t);
                    text += (t ? "," : "") + (ti < textures.size() ? textures[ti] : "?");
                }
                char colours[40];
                std::snprintf(colours, sizeof colours, " white=%02X%02X%02X%02X black=%02X%02X%02X%02X",
                              sec.U8(mo + 0x28), sec.U8(mo + 0x29), sec.U8(mo + 0x2A),
                              sec.U8(mo + 0x2B), sec.U8(mo + 0x24), sec.U8(mo + 0x25),
                              sec.U8(mo + 0x26), sec.U8(mo + 0x27));
                materials.push_back(text + colours);
            }
        } else if (tag == "pic1" || tag == "wnd1") {
            const std::string name = sec.Str(0xC, 0x18);
            // pic1: material at +0x64; wnd1: content (offset at +0x68) holds it at +0x10, each
            // frame (offset table at +0x6C, count at +0x64) at +0.
            const std::uint32_t mat =
                tag == "pic1" ? sec.Index(0x64) : sec.Index(std::uint64_t(sec.U32(0x68)) + 0x10);
            Line(h, "totk dump pane %.*s %.*s %s t=%g,%g s=%gx%g mat=%s", int(layout.size()),
                 layout.data(), int(tag.size()), tag.data(), name.c_str(), sec.F32(0x2C),
                 sec.F32(0x30), sec.F32(0x4C), sec.F32(0x50), material(mat).c_str());
            for (std::uint32_t f = 0, frames = tag == "wnd1" ? sec.U8(0x64) : 0; f < frames; ++f) {
                const std::uint64_t entry = sec.U32(0x6C) + 4 * std::uint64_t(f);
                const std::uint64_t at = entry + 4 <= size ? sec.U32(entry) : size; // size: none
                Line(h, "totk dump frame %.*s %s %u mat=%s", int(layout.size()), layout.data(),
                     name.c_str(), f, material(sec.Index(at)).c_str());
            }
        }
        s += size;
    }
    return true;
}

// The whole of `path` (a size query, then one read), or false.
inline bool Read(const EdenDsmodHostApi& h, const char* path, std::vector<std::uint8_t>& out) {
    const std::size_t size = h.read_romfs(h.userdata, path, 0, nullptr, 0);
    if (size == 0 || size > kMaxFile)
        return false;
    out.resize(size);
    return h.read_romfs(h.userdata, path, 0, out.data(), size) == size;
}

inline bool IsDumpedLayout(std::string_view member) {
    if (!member.starts_with("blyt/") || !member.ends_with(".bflyt"))
        return false;
    for (const std::string_view prefix : kLayouts)
        if (member.substr(5).starts_with(prefix))
            return true;
    return false;
}

// The dump. `mals` is the message archive's n the name loader found (0: none).
inline void Run(const EdenDsmodHostApi& h, int mals) {
    if (!h.read_romfs || !h.log || h.read_romfs(h.userdata, "user:totk-dump", 0, nullptr, 0) == 0)
        return;
    char path[128];
    int common = 0;
    for (int n = 100; n <= 200; ++n) {
        std::snprintf(path, sizeof path, kCommon, n);
        if (const std::size_t size = h.read_romfs(h.userdata, path, 0, nullptr, 0)) {
            Line(h, "totk dump common %d (%u bytes)", n, unsigned(size));
            common = n;
        }
    }
    Line(h, "totk dump mals %d", mals);
    if (!common)
        return Line(h, "totk dump failed: no UI/LayoutArchive/Common.Product.<100-200>");
    std::snprintf(path, sizeof path, kCommon, common);
    std::strcat(path, "#");
    std::vector<std::uint8_t> bytes;
    if (!Read(h, path, bytes))
        return Line(h, "totk dump failed: %s unreadable (Eden Duo patch 0010 missing?)", path);
    const auto members = Sarc(bytes);
    if (members.empty())
        return Line(h, "totk dump failed: %s is not a SARC", path);
    const Member* bntx = nullptr;
    for (const Member& m : members) {
        Line(h, "totk dump member %s %u", m.name.c_str(), unsigned(m.bytes.size()));
        if (m.name == "timg/__Combined.bntx")
            bntx = &m;
    }
    if (!bntx || !Textures(h, bntx->bytes))
        return Line(h, "totk dump failed: no BNTX timg/__Combined.bntx");
    for (const Member& m : members)
        if (IsDumpedLayout(m.name) &&
            !Panes(h, std::string_view(m.name).substr(5, m.name.size() - 11), m.bytes))
            return Line(h, "totk dump failed: %s is not a BFLYT", m.name.c_str());
    if (!mals)
        return Line(h, "totk dump failed: no Mals archive");
    std::snprintf(path, sizeof path, kMals, mals);
    std::strcat(path, "#");
    std::vector<std::uint8_t> msgs;
    if (!Read(h, path, msgs))
        return Line(h, "totk dump failed: %s unreadable", path);
    const auto messages = Sarc(msgs);
    if (messages.empty())
        return Line(h, "totk dump failed: %s is not a SARC", path);
    for (const Member& m : messages)
        if (m.name.starts_with("LayoutMsg/"))
            Line(h, "totk dump msg %s", m.name.c_str());
    Line(h, "totk dump done");
}

} // namespace totk::dump

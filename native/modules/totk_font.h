// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later
// The game font from the player's romfs (nothing shipped), adapted from the ACNH module's
// acnh_font.cpp/.h in igawa6/eden-duo-companions (GPL-3.0) [S2]:
//   Font/Font.Nin_NX_NVN.bfarc.zs, a zstd SARC (Eden Duo patch 0009 reads its members), with the
//   text face scft/nintendo_NTLG-DB_DH_002.bfotf and the button pictograms scft/External.bfotf
//   (U+E040-U+E068) [S32 make_button_icons.py:4-12,72-75].
// .bfotf: an OpenType (CFF) font behind an 8-byte header, the whole file XOR-ed per word with one
//   key (all u32 big-endian here; S32 reads the same words little-endian, magic 0x18029a7f):
//   +0 0x7F9A0218 ^ key   +4 plain size ^ key   +8 font bytes ^ key (plain = the first `size`).
//   The key comes from each file's header, never hard-coded [S2 acnh_font.h][S32 :112-118].
// Atlas spec (the package's totk_font.txt and the key module:totk:font/<spec>): "body:<cap>",
//   cap = cap height in atlas px, 8..96. One atlas <= 2048x2048 of U+0020-U+017F and
//   U+2013/2014/2018-201D/2026 from the text face, U+E040-U+E068 from External, shelf-packed.
// The ABI wants one dense run: U+0020..U+E068; slots neither face renders are empty glyphs
// advancing like a space. Metrics as ACNH: line_height = cap, bearing_y = box top above the
// baseline, advance rounded; RGB 0xFF, A = coverage.
#pragma once

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

#include "core/mods/dsmod_module_abi.h"
#include "core/mods/dsmod_module_extensions.h"
#include "core/mods/modules/dsmod_module_sdk.h"
#include "totk_bytes.h"

// stb_truetype, private to this translation unit (static functions). Contraction off so the
// rasteriser's float math is identical on x86-64 and AArch64 (ACNH's pragmas).
#if defined(__clang__)
#pragma clang fp contract(off)
#endif
#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#pragma GCC diagnostic ignored "-Wsign-compare"
#pragma GCC diagnostic ignored "-Wconversion"
#pragma GCC diagnostic ignored "-Wshadow"
#pragma GCC diagnostic ignored "-Wimplicit-fallthrough"
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#pragma GCC diagnostic ignored "-Wcast-qual"
#pragma GCC diagnostic ignored "-Wunused-parameter"
#endif
#define STBTT_STATIC
#define STB_TRUETYPE_IMPLEMENTATION
#include "../third_party/stb_truetype.h"
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

namespace totk::font {

inline constexpr std::uint32_t kMagic = 0x7F9A0218;
inline constexpr std::uint32_t kMinCap = 8, kMaxCap = 96;
inline constexpr std::uint32_t kMaxAtlas = 2048;
inline constexpr std::uint32_t kFirst = 0x20, kLast = 0xE068;
inline constexpr const char* kTextPath =
    "romfs:Font/Font.Nin_NX_NVN.bfarc.zs#scft/nintendo_NTLG-DB_DH_002.bfotf";
inline constexpr const char* kExtPath = "romfs:Font/Font.Nin_NX_NVN.bfarc.zs#scft/External.bfotf";

struct Atlas {
    std::uint32_t cap = 0, width = 0, height = 0;
    std::vector<std::uint8_t> rgba;
    std::vector<EdenDsmodFontGlyph> glyphs; // kFirst..kLast
};

// .bfotf -> the plain font bytes; empty when the header is short or its size does not fit.
inline std::vector<std::uint8_t> Decode(std::span<const std::uint8_t> file) {
    if (file.size() < 8 + 12) // header + an sfnt header
        return {};
    const std::uint32_t key = dsmod_sdk::Be32(file.data()) ^ kMagic;
    const std::uint32_t size = dsmod_sdk::Be32(file.data() + 4) ^ key;
    if (size < 12 || size > file.size() - 8)
        return {};
    const std::uint8_t k[4] = {std::uint8_t(key >> 24), std::uint8_t(key >> 16),
                               std::uint8_t(key >> 8), std::uint8_t(key)};
    std::vector<std::uint8_t> out(size);
    for (std::size_t i = 0; i < size; ++i)
        out[i] = file[8 + i] ^ k[i & 3];
    return out;
}

// True for a single sfnt (TrueType or CFF) whose table directory and every table it lists lie
// inside `f`: stb_truetype itself checks no offset.
inline bool TablesInside(std::span<const std::uint8_t> f) {
    if (f.size() < 12)
        return false;
    const std::uint32_t tag = dsmod_sdk::Be32(f.data());
    const std::uint64_t tables = dsmod_sdk::Be16(f.data() + 4);
    if ((tag != 0x00010000 && tag != 0x4F54544F /* OTTO */) || tables == 0 ||
        12 + 16 * tables > f.size())
        return false;
    for (std::uint64_t t = 0; t < tables; ++t) {
        const std::uint8_t* rec = f.data() + 12 + 16 * t;
        if (std::uint64_t{dsmod_sdk::Be32(rec + 8)} + dsmod_sdk::Be32(rec + 12) > f.size())
            return false;
    }
    return true;
}

// "body:<cap>" -> cap, or nullopt.
inline std::optional<std::uint32_t> ParseSpec(std::string_view spec) {
    constexpr std::string_view prefix = "body:";
    if (!spec.starts_with(prefix) || spec.size() == prefix.size())
        return std::nullopt;
    spec.remove_prefix(prefix.size());
    std::uint32_t cap = 0;
    const auto [end, ec] = std::from_chars(spec.data(), spec.data() + spec.size(), cap);
    if (ec != std::errc{} || end != spec.data() + spec.size() || cap < kMinCap || cap > kMaxCap)
        return std::nullopt;
    return cap;
}

struct Face {
    std::vector<std::uint8_t> plain; // stbtt_fontinfo points into it: never resized after Init
    stbtt_fontinfo info{};
    float units_per_em = 0;
    float cap_units = 0; // top of 'H' in font units
};

// One face from a .bfotf. False when unreadable or malformed.
inline bool LoadFace(const EdenDsmodHostApi& h, const char* path, Face& f) {
    std::vector<std::uint8_t> file;
    if (!ReadAll(h, path, file))
        return false;
    f.plain = Decode(file);
    if (!TablesInside(f.plain) || !stbtt_InitFont(&f.info, f.plain.data(), 0))
        return false;
    f.units_per_em = std::round(1.0f / stbtt_ScaleForMappingEmToPixels(&f.info, 1.0f));
    for (const int probe : {'H', 'E', 'I'}) {
        int x0, y0, x1, y1;
        const int gi = stbtt_FindGlyphIndex(&f.info, probe);
        if (gi != 0 && stbtt_GetGlyphBox(&f.info, gi, &x0, &y0, &x1, &y1) && y1 > 0) {
            f.cap_units = float(y1);
            break;
        }
    }
    if (f.cap_units <= 0) {
        int ascent, descent, gap;
        stbtt_GetFontVMetrics(&f.info, &ascent, &descent, &gap);
        f.cap_units = float(ascent) * 0.7f;
    }
    return f.units_per_em > 0 && f.cap_units > 0; // every scale divides by these
}

// The atlas at `cap` from the player's romfs, or null while either face is not readable (the
// host retries decode_font) or when a face is malformed or the glyphs do not fit 2048x2048.
inline std::shared_ptr<const Atlas> Load(const EdenDsmodHostApi& h, std::uint32_t cap) {
    if (!h.read_romfs || cap < kMinCap || cap > kMaxCap)
        return nullptr;
    Face text, ext;
    if (!LoadFace(h, kTextPath, text) || !LoadFace(h, kExtPath, ext))
        return nullptr;
    const float scale = float(cap) / text.cap_units;
    const float ext_scale = scale * text.units_per_em / ext.units_per_em;

    struct Raster {
        std::uint32_t cp = 0, ax = 0, ay = 0;
        int w = 0, h = 0, x0 = 0, y0 = 0, advance = 0;
        std::vector<std::uint8_t> coverage;
    };
    struct Range {
        std::uint32_t first, last;
        bool ext;
    };
    constexpr Range kRanges[] = {{0x20, 0x17F, false},   {0x2013, 0x2014, false},
                                 {0x2018, 0x201D, false}, {0x2026, 0x2026, false},
                                 {0xE040, kLast, true}};
    std::vector<Raster> rasters; // in code point order
    for (const Range& range : kRanges) {
        const Face& f = range.ext ? ext : text;
        const float s = range.ext ? ext_scale : scale;
        for (std::uint32_t cp = range.first; cp <= range.last; ++cp) {
            const int gi = stbtt_FindGlyphIndex(&f.info, int(cp));
            if (gi == 0)
                continue;
            Raster r;
            r.cp = cp;
            int adv, lsb, x1, y1;
            stbtt_GetGlyphHMetrics(&f.info, gi, &adv, &lsb);
            r.advance = int(std::lround(float(adv) * s));
            stbtt_GetGlyphBitmapBox(&f.info, gi, s, s, &r.x0, &r.y0, &x1, &y1);
            r.w = std::max(0, x1 - r.x0);
            r.h = std::max(0, y1 - r.y0);
            if (r.w > 0 && r.h > 0 && r.w <= 512 && r.h <= 512) {
                r.coverage.assign(std::size_t(r.w) * r.h, 0);
                stbtt_MakeGlyphBitmap(&f.info, r.coverage.data(), r.w, r.h, r.w, s, s, gi);
                if (std::all_of(r.coverage.begin(), r.coverage.end(),
                                [](std::uint8_t v) { return v == 0; }))
                    r.w = r.h = 0;
            } else {
                r.w = r.h = 0;
            }
            rasters.push_back(std::move(r));
        }
    }
    const auto find = [&](std::uint32_t cp) {
        const auto it = std::lower_bound(rasters.begin(), rasters.end(), cp,
                                         [](const Raster& r, std::uint32_t c) { return r.cp < c; });
        return it != rasters.end() && it->cp == cp ? &*it : nullptr;
    };
    if (!find('A')) // not a Latin text face
        return nullptr;
    // Shelf pack in code point order into the smallest power-of-two width (height <= 2x width),
    // 1 px clear around each glyph.
    constexpr std::uint32_t kPad = 1;
    std::uint32_t width = 0, height = 0;
    for (std::uint32_t w = 256; w <= kMaxAtlas && !width; w *= 2) {
        std::uint32_t x = 0, y = 0, shelf = 0;
        bool fits = true;
        for (auto& r : rasters) {
            if (r.w == 0)
                continue;
            const std::uint32_t cw = std::uint32_t(r.w) + 2 * kPad;
            const std::uint32_t ch = std::uint32_t(r.h) + 2 * kPad;
            if (cw > w) {
                fits = false;
                break;
            }
            if (x + cw > w) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            r.ax = x + kPad;
            r.ay = y + kPad;
            x += cw;
            shelf = std::max(shelf, ch);
        }
        if (fits && y + shelf <= std::min(kMaxAtlas, 2 * w)) {
            width = w;
            height = std::max<std::uint32_t>(y + shelf, 1);
        }
    }
    if (!width)
        return nullptr;
    auto out = std::make_shared<Atlas>();
    out->cap = cap;
    out->width = width;
    out->height = height;
    out->rgba.assign(std::size_t(width) * height * 4, 0xFF);
    for (std::size_t i = 3; i < out->rgba.size(); i += 4)
        out->rgba[i] = 0;
    for (const auto& r : rasters)
        for (int y = 0; y < r.h; ++y)
            for (int x = 0; x < r.w; ++x)
                out->rgba[((std::size_t(r.ay) + y) * width + r.ax + x) * 4 + 3] =
                    r.coverage[std::size_t(y) * r.w + x];
    const auto glyph_of = [](const Raster& r) {
        EdenDsmodFontGlyph g{};
        if (r.w > 0) {
            g.x = std::uint16_t(r.ax);
            g.y = std::uint16_t(r.ay);
            g.w = std::uint16_t(r.w);
            g.h = std::uint16_t(r.h);
            g.bearing_x = std::int16_t(r.x0);
            g.bearing_y = std::int16_t(-r.y0);
        }
        g.advance = std::uint16_t(std::max(0, r.advance));
        return g;
    };
    EdenDsmodFontGlyph blank{};
    if (const Raster* space = find(' '))
        blank.advance = glyph_of(*space).advance;
    out->glyphs.assign(kLast - kFirst + 1, blank);
    for (const auto& r : rasters)
        out->glyphs[r.cp - kFirst] = glyph_of(r);
    return out;
}

} // namespace totk::font

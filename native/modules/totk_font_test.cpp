// SPDX-License-Identifier: GPL-3.0-or-later
// totk_font.h: the .bfotf XOR header, the sfnt bounds check, the spec, seeded header mutations,
// and atlases built from system fonts served as base: .bfotf files through the fake read_romfs
// (skipped without one), their metrics checked against stb_truetype's own.
#include "totk_font.h"
#include "totk_test_host.h"

#include <cstdio>
#include <random>

namespace font = totk::font;
using Bytes = std::vector<std::uint8_t>;

namespace {

using dsmod_sdk::Be16;
using dsmod_sdk::Be32;

void PutBe(Bytes& b, std::size_t at, std::uint32_t v, int n) {
    for (int i = 0; i < n; ++i)
        b[at + i] = std::uint8_t(v >> (8 * (n - 1 - i)));
}

// A minimal sfnt header + table directory: `tables` records of (offset, length).
Bytes Sfnt(std::uint32_t tag, const std::vector<std::pair<std::uint32_t, std::uint32_t>>& tables,
           std::size_t size) {
    Bytes b(size);
    PutBe(b, 0, tag, 4);
    PutBe(b, 4, std::uint32_t(tables.size()), 2);
    for (std::size_t t = 0; t < tables.size(); ++t) {
        PutBe(b, 12 + 16 * t, 0x61626364, 4); // "abcd"
        PutBe(b, 12 + 16 * t + 8, tables[t].first, 4);
        PutBe(b, 12 + 16 * t + 12, tables[t].second, 4);
    }
    return b;
}

// `f` (a plain TrueType or CFF font) with a cmap that maps only `cp`, to the glyph `from` had,
// and unitsPerEm `upem`: an External-like face with a glyph at a private-use code point. The
// new format 12 subtable goes at the end; every encoding record points at it.
Bytes Pictogram(Bytes f, std::uint32_t cp, std::uint32_t from, std::uint16_t upem) {
    stbtt_fontinfo info{};
    assert(stbtt_InitFont(&info, f.data(), 0));
    const std::uint32_t glyph = std::uint32_t(stbtt_FindGlyphIndex(&info, int(from)));
    assert(glyph != 0);
    const auto table = [&f](const char* tag) -> std::size_t {
        for (std::size_t t = 0, n = Be16(f.data() + 4); t < n; ++t)
            if (std::memcmp(f.data() + 12 + 16 * t, tag, 4) == 0)
                return Be32(f.data() + 12 + 16 * t + 8);
        assert(!"table missing");
        return 0;
    };
    const std::size_t cmap = table("cmap"), head = table("head");
    const std::size_t sub = (f.size() + 3) & ~std::size_t(3);
    f.resize(sub + 28);
    PutBe(f, sub, 12, 2);       // format 12
    PutBe(f, sub + 4, 28, 4);   // length
    PutBe(f, sub + 12, 1, 4);   // one group
    PutBe(f, sub + 16, cp, 4);  // first code point
    PutBe(f, sub + 20, cp, 4);  // last code point
    PutBe(f, sub + 24, glyph, 4);
    for (std::size_t r = 0, n = Be16(f.data() + cmap + 2); r < n; ++r)
        PutBe(f, cmap + 4 + 8 * r + 4, std::uint32_t(sub - cmap), 4);
    PutBe(f, head + 18, upem, 2);
    return f;
}

// stb_truetype's own view of a plain font: the oracle for the atlas metrics.
struct Ref {
    Bytes plain;
    stbtt_fontinfo info{};
    explicit Ref(Bytes b) : plain(std::move(b)) {
        assert(stbtt_InitFont(&info, plain.data(), 0));
    }
    Ref(const Ref&) = delete;
    float Upem() const {
        return std::round(1.0f / stbtt_ScaleForMappingEmToPixels(&info, 1.0f));
    }
    // The text face's scale for a cap height: cap / the top of 'H'.
    float Scale(std::uint32_t cap) const {
        int x0, y0, x1, y1;
        assert(stbtt_GetGlyphBox(&info, stbtt_FindGlyphIndex(&info, 'H'), &x0, &y0, &x1, &y1));
        return float(cap) / float(y1);
    }
    // `g` is `cp` at `scale` as stb_truetype measures it.
    void Expect(const EdenDsmodFontGlyph& g, std::uint32_t cp, float scale) const {
        const int gi = stbtt_FindGlyphIndex(&info, int(cp));
        assert(gi != 0);
        int adv, lsb, x0, y0, x1, y1;
        stbtt_GetGlyphHMetrics(&info, gi, &adv, &lsb);
        stbtt_GetGlyphBitmapBox(&info, gi, scale, scale, &x0, &y0, &x1, &y1);
        assert(g.advance == std::lround(float(adv) * scale));
        assert(g.bearing_x == x0 && g.bearing_y == -y0 && g.w == x1 - x0 && g.h == y1 - y0);
    }
};

totk::test::Host& FontHost(totk::test::Host& h, const Bytes& text, const Bytes& ext) {
    h.api.capabilities |= EDEN_DSMOD_CAP_SOURCE_BASE;
    h.PutRomfs(font::kTextPath, totk::test::Bfotf(text, 0x49621806));
    h.PutRomfs(font::kExtPath, totk::test::Bfotf(ext, 0x0BADF00D));
    return h;
}

void CheckAtlas(const font::Atlas& a, std::uint32_t cap, const Ref& text, bool cff) {
    assert(a.cap == cap);
    assert(a.width >= 256 && a.width <= font::kMaxAtlas && (a.width & (a.width - 1)) == 0);
    assert(a.height >= 1 && a.height <= font::kMaxAtlas && a.height <= 2 * a.width);
    assert(a.rgba.size() == std::size_t(a.width) * a.height * 4);
    for (std::size_t i = 0; i < a.rgba.size(); i += 4)
        assert(a.rgba[i] == 0xFF && a.rgba[i + 1] == 0xFF && a.rgba[i + 2] == 0xFF);
    // Dense run U+0020..U+E068; every glyph inside the atlas and inked; each box with its 1 px
    // clear border overlaps no other; no ink outside the boxes.
    assert(a.glyphs.size() == font::kLast - font::kFirst + 1);
    std::vector<std::uint8_t> owner(std::size_t(a.width) * a.height, 0);
    for (const auto& g : a.glyphs) {
        assert(std::uint32_t(g.x) + g.w <= a.width && std::uint32_t(g.y) + g.h <= a.height);
        if (g.w == 0)
            continue;
        assert(g.x >= 1 && g.y >= 1);
        bool ink = false;
        for (int y = g.y - 1; y <= g.y + g.h; ++y)
            for (int x = g.x - 1; x <= g.x + g.w; ++x) {
                if (x >= int(a.width) || y >= int(a.height))
                    continue;
                const std::size_t at = std::size_t(y) * a.width + x;
                assert(owner[at] == 0);
                const bool inside = x >= g.x && x < g.x + g.w && y >= g.y && y < g.y + g.h;
                owner[at] = inside ? 2 : 1;
                ink = ink || (inside && a.rgba[at * 4 + 3] != 0);
            }
        assert(ink);
    }
    for (std::size_t at = 0; at < owner.size(); ++at)
        assert(owner[at] == 2 || a.rgba[at * 4 + 3] == 0);
    const auto at = [&a](std::uint32_t cp) { return a.glyphs[cp - font::kFirst]; };
    const auto space = at(' ');
    assert(space.w == 0 && space.h == 0 && space.advance > 0);
    // 'H' and 'A' exactly as stb_truetype measures them at cap / top of 'H'; 'H' stands on the
    // baseline with its top at the cap height (+-1 px of rasterizer rounding).
    const float scale = text.Scale(cap);
    text.Expect(at('H'), 'H', scale);
    text.Expect(at('A'), 'A', scale);
    assert(at('H').bearing_y >= int(cap) - 1 && at('H').bearing_y <= int(cap) + 1);
    // Outside the set (U+0180, U+2015, U+3000) and External slots no face here has: empty glyphs
    // advancing like a space.
    for (const std::uint32_t cp : {0x180u, 0x2015u, 0x3000u, 0xE041u, 0xE068u}) {
        const auto g = at(cp);
        assert(g.w == 0 && g.h == 0 && g.x == 0 && g.y == 0 && g.advance == space.advance);
    }
    // In the set: Latin Extended-A and the punctuation.
    for (const std::uint32_t cp : {0x17Fu, 0x2014u, 0x201Cu, 0x2026u})
        assert(at(cp).advance > 0);
    std::printf("totk_font_test: %s atlas cap %u: %ux%u\n", cff ? "CFF" : "TrueType", cap, a.width,
                a.height);
}

} // namespace

int main() {
    // XOR header round trip with a synthetic key; a length not a multiple of 4.
    const Bytes plain{0x4F, 0x54, 0x54, 0x4F, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (const std::uint32_t key : {0x12345678u, 0u, 0xFFFFFFFFu}) {
        const Bytes file = totk::test::Bfotf(plain, key);
        assert(file.size() == plain.size() + 8);
        assert(font::Decode(file) == plain);
        Bytes padded = file; // bytes past `size` are ignored
        padded.push_back(0xAA);
        assert(font::Decode(padded) == plain);
    }
    // Key 0: the header is S32's little-endian magic 0x18029a7f, then the big-endian size.
    {
        const Bytes file = totk::test::Bfotf(plain, 0);
        const Bytes header{0x7F, 0x9A, 0x02, 0x18, 0, 0, 0, 13};
        assert(Bytes(file.begin(), file.begin() + 8) == header);
    }
    // Bad key or size: refused.
    {
        Bytes file = totk::test::Bfotf(plain, 0x12345678);
        Bytes bad = file;
        bad[0] ^= 0x01; // another key: the size no longer decodes to 13
        assert(font::Decode(bad).empty());
        bad = file;
        bad[7] ^= 13 ^ 14; // size 14 > file - 8
        assert(font::Decode(bad).empty());
        bad = file;
        bad[7] ^= 13 ^ 11; // size 11: shorter than an sfnt header
        assert(font::Decode(bad).empty());
        bad = file;
        bad[4] ^= 0x80; // size + 2^31
        assert(font::Decode(bad).empty());
        assert(font::Decode(Bytes(file.begin(), file.end() - 1)).empty()); // truncated
        assert(font::Decode(Bytes(file.begin(), file.begin() + 19)).empty());
        assert(font::Decode({}).empty());
        assert(font::Decode(totk::test::Bfotf(Bytes(12, 0), 7)) == Bytes(12, 0)); // smallest
    }

    // The sfnt table directory and its tables must lie inside the font.
    assert(font::TablesInside(Sfnt(0x4F54544F, {{28, 4}}, 32)));
    assert(font::TablesInside(Sfnt(0x00010000, {{28, 4}, {0, 32}}, 44)));
    assert(!font::TablesInside(Sfnt(0x4F54544F, {{28, 5}}, 32)));          // table past the end
    assert(!font::TablesInside(Sfnt(0x4F54544F, {{0xFFFFFFFF, 2}}, 32)));  // offset overflow
    assert(!font::TablesInside(Sfnt(0x4F54544F, {}, 32)));                 // no table
    assert(!font::TablesInside(Sfnt(0x74746366, {{28, 4}}, 32)));          // "ttcf": a collection
    {
        Bytes dir = Sfnt(0x4F54544F, {{0, 4}, {0, 4}}, 44);
        assert(font::TablesInside(dir));
        dir.resize(43); // the second record runs past the end
        assert(!font::TablesInside(dir));
    }
    assert(!font::TablesInside(Bytes(11, 0)));

    // Spec: "body:<cap>", cap 8..96.
    assert(font::ParseSpec("body:48") == 48u);
    assert(font::ParseSpec("body:8") == 8u && font::ParseSpec("body:96") == 96u);
    for (const char* bad : {"", "body", "body:", "body:7", "body:97", "body:48x", "body:-1",
                            "body: 48", " body:48", "body:48\n", "BODY:48", "ui:48", "body:+48",
                            "body:4294967344"})
        assert(!font::ParseSpec(bad));

    // Seeded mutations of a valid .bfotf (header and sfnt directory: a bit flipped in the
    // ciphertext flips it in the plain bytes), truncated or zero-extended, and random bytes:
    // Decode and TablesInside refuse them or return bytes that keep their own bounds.
    {
        std::mt19937 rng(20261102);
        const Bytes good = totk::test::Bfotf(Sfnt(0x4F54544F, {{28, 4}, {0, 32}}, 44), 0x12345678);
        unsigned decoded = 0, inside = 0;
        for (int i = 0; i < 20000; ++i) {
            Bytes f = good;
            if (rng() % 2)
                f.resize(rng() % (good.size() + 8));
            for (int n = 1 + int(rng() % 4); n-- && !f.empty();)
                f[rng() % std::min<std::size_t>(f.size(), 8 + 28)] ^=
                    std::uint8_t(1u << (rng() % 8));
            if (i % 4 == 0)
                for (auto& c : f)
                    c = std::uint8_t(rng());
            const Bytes p = font::Decode(f);
            if (p.empty())
                continue;
            ++decoded;
            const std::uint32_t key = Be32(f.data()) ^ font::kMagic;
            assert(p.size() == (Be32(f.data() + 4) ^ key) && p.size() >= 12 &&
                   p.size() <= f.size() - 8);
            if (!font::TablesInside(p))
                continue;
            ++inside;
            for (std::size_t t = 0, n = Be16(p.data() + 4); t < n; ++t)
                assert(12 + 16 * t + 16 <= p.size() &&
                       std::uint64_t{Be32(p.data() + 12 + 16 * t + 8)} +
                               Be32(p.data() + 12 + 16 * t + 12) <=
                           p.size());
        }
        std::printf("totk_font_test: 20000 header mutations: %u decoded, %u in bounds\n", decoded,
                    inside);
        assert(decoded > 0 && inside > 0 && inside < decoded);
    }

    // ReadAll refuses a size answer over the host's 64 MiB cap without reading.
    {
        totk::test::Host h;
        h.api.read_romfs = [](void* u, const char*, std::uint64_t, void* out,
                              std::size_t) -> std::size_t {
            ++static_cast<totk::test::Host*>(u)->romfs_calls;
            return out ? 0 : (std::size_t{64} << 20) + 1;
        };
        Bytes out;
        assert(!totk::ReadAll(h.api, font::kTextPath, out) && out.empty() && h.romfs_calls == 1);
    }

    // Unreadable base: source: no atlas. Without the host's base: source nothing is read.
    {
        totk::test::Host h;
        assert(!font::Load(h.api, 48) && h.romfs_calls == 0);
        h.api.capabilities |= EDEN_DSMOD_CAP_SOURCE_BASE;
        assert(!font::Load(h.api, 48));
        assert(h.romfs_calls == 1); // the text face's size query, then nothing
        h.api.read_romfs = nullptr;
        assert(!font::Load(h.api, 48));
    }

    const Bytes text = totk::test::SystemTextFont(), ext = totk::test::SystemExtFont();
    if (text.empty() || ext.empty()) {
        std::puts("totk_font_test: no system font found; atlas cases skipped");
        std::puts("totk_font_test: ok");
        return 0;
    }
    const bool cff = font::TablesInside(text) && text[0] == 'O';
    const Ref text_ref{text}, ext_ref{ext};
    // Only base: paths are read: the same faces under romfs: (where LayeredFS mods live) give
    // nothing, and without the base: capability nothing is asked at all.
    {
        totk::test::Host h;
        FontHost(h, text, ext);
        assert(font::Load(h.api, 48));
        assert(!h.romfs_paths.empty());
        for (const auto& path : h.romfs_paths)
            assert(path.starts_with("base:Font/Font.Nin_NX_NVN.bfarc.zs#scft/"));
        totk::test::Host r;
        r.api.capabilities |= EDEN_DSMOD_CAP_SOURCE_BASE;
        r.PutRomfs("romfs:Font/Font.Nin_NX_NVN.bfarc.zs#scft/nintendo_NTLG-DB_DH_002.bfotf",
                   totk::test::Bfotf(text, 1));
        r.PutRomfs("romfs:Font/Font.Nin_NX_NVN.bfarc.zs#scft/External.bfotf",
                   totk::test::Bfotf(ext, 2));
        assert(!font::Load(r.api, 48));
        FontHost(r, text, ext).api.capabilities = 0;
        const unsigned calls = r.romfs_calls;
        assert(!font::Load(r.api, 48) && r.romfs_calls == calls);
    }
    // Only the text face, a corrupt face, a face without a cmap or a usable 'H', an External
    // with unitsPerEm 0: no atlas.
    {
        totk::test::Host h;
        h.api.capabilities |= EDEN_DSMOD_CAP_SOURCE_BASE;
        h.PutRomfs(font::kTextPath, totk::test::Bfotf(text, 0x0BADF00D));
        assert(!font::Load(h.api, 48));
        Bytes bad = totk::test::Bfotf(ext, 0x01020304);
        bad[0] ^= 1;
        h.PutRomfs(font::kExtPath, bad);
        assert(!font::Load(h.api, 48));
        h.PutRomfs(font::kExtPath, totk::test::Bfotf(Pictogram(ext, 0xE040, 'H', 0), 3));
        assert(!font::Load(h.api, 48));
        h.PutRomfs(font::kExtPath, totk::test::Bfotf(ext, 0x01020304));
        assert(font::Load(h.api, 48));
        h.PutRomfs(font::kTextPath,
                   totk::test::Bfotf(Pictogram(text, 'A', 'A', std::uint16_t(text_ref.Upem())), 4));
        assert(!font::Load(h.api, 48));
        // 'H' with no outline (the space's glyph) or entirely below the baseline ('_'): no
        // cap height.
        for (const std::uint32_t glyph : {std::uint32_t(' '), std::uint32_t('_')}) {
            const Ref odd{Pictogram(text, 'H', glyph, std::uint16_t(text_ref.Upem()))};
            int x0, y0, x1, y1;
            const int gi = stbtt_FindGlyphIndex(&odd.info, 'H');
            assert(gi != 0 && (!stbtt_GetGlyphBox(&odd.info, gi, &x0, &y0, &x1, &y1) || y1 <= 0));
            h.PutRomfs(font::kTextPath, totk::test::Bfotf(odd.plain, 6));
            assert(!font::Load(h.api, 48));
        }
        h.PutRomfs(font::kTextPath, totk::test::Bfotf(Sfnt(0x00010000, {{28, 4}}, 32), 5));
        assert(!font::Load(h.api, 48)); // no cmap: stb_truetype refuses it
    }
    // The atlas at the plan's cap and at both bounds of the spec; the smallest fits 256 px.
    {
        totk::test::Host h;
        FontHost(h, text, ext);
        for (const std::uint32_t cap : {48u, 8u, 96u}) {
            const auto atlas = font::Load(h.api, cap);
            assert(atlas);
            CheckAtlas(*atlas, cap, text_ref, cff);
            assert(atlas->glyphs[0xE040 - font::kFirst].w == 0);
            assert(cap != 8 || atlas->width == 256);
        }
        assert(!font::Load(h.api, 7) && !font::Load(h.api, 97));
    }
    // A TrueType text face too.
    {
        totk::test::Host h;
        const auto tt = font::Load(FontHost(h, ext, ext).api, 48);
        assert(tt);
        CheckAtlas(*tt, 48, ext_ref, false);
    }
    // A pictogram at U+E040 in an External with another unitsPerEm: drawn at the text face's em
    // (scale x text upem / External upem); one so large it passes 512 px is an empty glyph that
    // still advances.
    {
        const std::uint16_t upem = text_ref.Upem() == 1000 ? 1500 : 1000;
        const Ref pict{Pictogram(ext, 0xE040, 'H', upem)};
        totk::test::Host h;
        const auto a = font::Load(FontHost(h, text, pict.plain).api, 48);
        assert(a);
        CheckAtlas(*a, 48, text_ref, cff);
        const float scale = text_ref.Scale(48);
        const float ext_scale = scale * text_ref.Upem() / pict.Upem();
        assert(pict.Upem() == upem && ext_scale != scale);
        const auto g = a->glyphs[0xE040 - font::kFirst];
        assert(g.w > 0);
        pict.Expect(g, 0xE040, ext_scale);

        const Ref huge{Pictogram(ext, 0xE040, 'H', 16)};
        totk::test::Host hh;
        const auto b = font::Load(FontHost(hh, text, huge.plain).api, 48);
        assert(b);
        const float huge_scale = scale * text_ref.Upem() / 16.0f;
        const auto big = b->glyphs[0xE040 - font::kFirst];
        int adv, lsb;
        stbtt_GetGlyphHMetrics(&huge.info, stbtt_FindGlyphIndex(&huge.info, 0xE040), &adv, &lsb);
        assert(big.w == 0 && big.h == 0 && big.advance == std::lround(float(adv) * huge_scale) &&
               big.advance > 512);
    }

    std::puts("totk_font_test: ok");
    return 0;
}

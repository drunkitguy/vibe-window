// SPDX-License-Identifier: GPL-3.0-or-later
// totk_font.h: the .bfotf XOR header, the sfnt bounds check, the spec, and an atlas built from
// system fonts served as .bfotf files through the fake read_romfs (skipped without one).
#include "totk_font.h"
#include "totk_test_host.h"

#include <cstdio>

namespace font = totk::font;
using Bytes = std::vector<std::uint8_t>;

namespace {

// A minimal sfnt header + table directory: `tables` records of (offset, length).
Bytes Sfnt(std::uint32_t tag, const std::vector<std::pair<std::uint32_t, std::uint32_t>>& tables,
           std::size_t size) {
    Bytes b(size);
    const auto be = [&b](std::size_t at, std::uint32_t v, int n) {
        for (int i = 0; i < n; ++i)
            b[at + i] = std::uint8_t(v >> (8 * (n - 1 - i)));
    };
    be(0, tag, 4);
    be(4, std::uint32_t(tables.size()), 2);
    for (std::size_t t = 0; t < tables.size(); ++t) {
        be(12 + 16 * t, 0x61626364, 4); // "abcd"
        be(12 + 16 * t + 8, tables[t].first, 4);
        be(12 + 16 * t + 12, tables[t].second, 4);
    }
    return b;
}

void CheckAtlas(const font::Atlas& a, std::uint32_t cap, bool cff) {
    assert(a.cap == cap);
    assert(a.width >= 256 && a.width <= font::kMaxAtlas && (a.width & (a.width - 1)) == 0);
    assert(a.height >= 1 && a.height <= font::kMaxAtlas && a.height <= 2 * a.width);
    assert(a.rgba.size() == std::size_t(a.width) * a.height * 4);
    for (std::size_t i = 0; i < a.rgba.size(); i += 4)
        assert(a.rgba[i] == 0xFF && a.rgba[i + 1] == 0xFF && a.rgba[i + 2] == 0xFF);
    // Dense run U+0020..U+E068; every glyph inside the atlas.
    assert(a.glyphs.size() == font::kLast - font::kFirst + 1);
    for (const auto& g : a.glyphs)
        assert(std::uint32_t(g.x) + g.w <= a.width && std::uint32_t(g.y) + g.h <= a.height);
    const auto at = [&a](std::uint32_t cp) { return a.glyphs[cp - font::kFirst]; };
    const auto space = at(' ');
    assert(space.w == 0 && space.h == 0 && space.advance > 0);
    // 'H' stands on the baseline and its top is the cap height (+-1 px of rasterizer rounding).
    const auto h = at('H');
    assert(h.w > 0 && h.h > 0 && h.advance > 0);
    assert(h.bearing_y >= int(cap) - 1 && h.bearing_y <= int(cap) + 1);
    // 'A' has ink in its box.
    const auto A = at('A');
    unsigned ink = 0;
    for (int y = 0; y < A.h; ++y)
        for (int x = 0; x < A.w; ++x)
            ink += a.rgba[((std::size_t(A.y) + y) * a.width + A.x + x) * 4 + 3] != 0;
    assert(ink > unsigned(A.w * A.h) / 8);
    // Outside the set (U+0180, U+2015) and the External slots these fonts lack: empty glyphs
    // advancing like a space.
    for (const std::uint32_t cp : {0x180u, 0x2015u, 0x3000u, 0xE040u, 0xE068u}) {
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

    // Unreadable romfs: no atlas.
    {
        totk::test::Host h;
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
    // Only the text face, a corrupt face, or a text face without Latin: no atlas.
    {
        totk::test::Host h;
        h.PutRomfs(font::kTextPath, totk::test::Bfotf(text, 0x0BADF00D));
        assert(!font::Load(h.api, 48));
        Bytes bad = totk::test::Bfotf(ext, 0x01020304);
        bad[0] ^= 1;
        h.PutRomfs(font::kExtPath, bad);
        assert(!font::Load(h.api, 48));
        h.PutRomfs(font::kExtPath, totk::test::Bfotf(ext, 0x01020304));
        assert(font::Load(h.api, 48));
        h.PutRomfs(font::kTextPath, totk::test::Bfotf(Sfnt(0x00010000, {{28, 4}}, 32), 5));
        assert(!font::Load(h.api, 48)); // no cmap: stb_truetype refuses it
    }
    // The atlas at the plan's cap and at both bounds of the spec.
    totk::test::Host h;
    h.PutRomfs(font::kTextPath, totk::test::Bfotf(text, 0x49621806));
    h.PutRomfs(font::kExtPath, totk::test::Bfotf(ext, 0x0BADF00D));
    for (const std::uint32_t cap : {48u, 8u, 96u}) {
        const auto atlas = font::Load(h.api, cap);
        assert(atlas);
        CheckAtlas(*atlas, cap, cff);
    }
    assert(!font::Load(h.api, 7) && !font::Load(h.api, 97));
    // A TrueType text face too.
    h.PutRomfs(font::kTextPath, totk::test::Bfotf(ext, 0x0BADF00D));
    const auto tt = font::Load(h.api, 48);
    assert(tt);
    CheckAtlas(*tt, 48, false);

    std::puts("totk_font_test: ok");
    return 0;
}

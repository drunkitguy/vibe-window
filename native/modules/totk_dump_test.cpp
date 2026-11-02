// SPDX-License-Identifier: GPL-3.0-or-later
// totk_dump.h: the T8a dump's lines from a synthetic layout archive (SARC with a BNTX and a
// BFLYT) and message archive; nothing without the player's user:totk-dump file; one line and stop
// on a failure.
#include "totk_dump.h"
#include "totk_test_host.h"

#include <array>
#include <cstdio>

using totk::test::Host;
using totk::test::PutU16;
using totk::test::PutU32;
using totk::test::Sarc;
using Bytes = std::vector<std::uint8_t>;

namespace {

void PutStr(Bytes& b, std::size_t at, const std::string& s) {
    if (b.size() < at + s.size() + 1)
        b.resize(at + s.size() + 1);
    std::memcpy(b.data() + at, s.data(), s.size());
}
void PutF32(Bytes& b, std::size_t at, float v) {
    std::uint32_t u;
    std::memcpy(&u, &v, 4);
    PutU32(b, at, u);
}

struct Tex {
    std::string name;
    std::uint32_t w, h, format;
    std::array<std::uint8_t, 4> channels;
};
// A Switch BNTX with only the texture table: header, pointer table, one BRTI per texture, names.
Bytes Bntx(const std::vector<Tex>& textures) {
    Bytes b(0x40);
    std::memcpy(b.data(), "BNTX", 4);
    std::memcpy(b.data() + 0x20, "NX  ", 4);
    PutU32(b, 0x24, std::uint32_t(textures.size()));
    PutU32(b, 0x28, 0x40);
    const std::size_t brti = 0x40 + 8 * textures.size();
    std::size_t names = brti + 0x80 * textures.size();
    b.resize(names);
    for (std::size_t i = 0; i < textures.size(); ++i) {
        const Tex& t = textures[i];
        const std::size_t at = brti + 0x80 * i;
        PutU32(b, 0x40 + 8 * i, std::uint32_t(at));
        std::memcpy(b.data() + at, "BRTI", 4);
        PutU32(b, at + 0x1C, t.format);
        PutU32(b, at + 0x24, t.w);
        PutU32(b, at + 0x28, t.h);
        std::memcpy(b.data() + at + 0x58, t.channels.data(), 4);
        PutU32(b, at + 0x60, std::uint32_t(names));
        PutU16(b, names, std::uint16_t(t.name.size()));
        PutStr(b, names + 2, t.name);
        names = b.size();
    }
    return b;
}

// One BFLYT section: tag, size, then `body` from +8.
Bytes Section(const char* tag, Bytes body) {
    Bytes s(8);
    std::memcpy(s.data(), tag, 4);
    s.insert(s.end(), body.begin(), body.end());
    PutU32(s, 4, std::uint32_t(s.size()));
    return s;
}
Bytes Flyt(const std::vector<Bytes>& sections) {
    Bytes b(0x14);
    std::memcpy(b.data(), "FLYT", 4);
    PutU16(b, 4, 0xFEFF);
    PutU16(b, 6, 0x14);
    PutU32(b, 8, 0x09000000);
    PutU16(b, 0x10, std::uint16_t(sections.size()));
    for (const Bytes& s : sections)
        b.insert(b.end(), s.begin(), s.end());
    PutU32(b, 0xC, std::uint32_t(b.size()));
    return b;
}
// txl1: count, then offsets relative to +0xC.
Bytes Txl1(const std::vector<std::string>& names) {
    Bytes body(4 + 4 * names.size());
    PutU16(body, 0, std::uint16_t(names.size()));
    for (std::size_t i = 0; i < names.size(); ++i) {
        PutU32(body, 4 + 4 * i, std::uint32_t(body.size() - 4));
        PutStr(body, body.size(), names[i]);
    }
    return Section("txl1", body);
}
struct Mat {
    std::string name;
    std::array<std::uint8_t, 4> black, white;
    std::vector<std::uint16_t> tex;
};
// mat1: count, offsets relative to the section; name[0x1C], flags (texmap count), black, white,
// texmaps (u16 texture index, wrap bytes).
Bytes Mat1(const std::vector<Mat>& mats) {
    Bytes body(4 + 4 * mats.size());
    PutU16(body, 0, std::uint16_t(mats.size()));
    for (std::size_t i = 0; i < mats.size(); ++i) {
        const std::size_t at = body.size();
        PutU32(body, 4 + 4 * i, std::uint32_t(at + 8));
        body.resize(at + 0x2C + 4 * mats[i].tex.size());
        PutStr(body, at, mats[i].name);
        PutU32(body, at + 0x1C, std::uint32_t(mats[i].tex.size()));
        std::memcpy(body.data() + at + 0x24, mats[i].black.data(), 4);
        std::memcpy(body.data() + at + 0x28, mats[i].white.data(), 4);
        for (std::size_t t = 0; t < mats[i].tex.size(); ++t)
            PutU16(body, at + 0x2C + 4 * t, mats[i].tex[t]);
    }
    return Section("mat1", body);
}
// The pane header shared by pic1/wnd1 (offsets from the section start).
Bytes Pane(const std::string& name, float x, float y, float w, float h) {
    Bytes s(0x54);
    s[8] = 1; // visible
    PutStr(s, 0xC, name);
    PutF32(s, 0x2C, x);
    PutF32(s, 0x30, y);
    PutF32(s, 0x44, 1);
    PutF32(s, 0x48, 1);
    PutF32(s, 0x4C, w);
    PutF32(s, 0x50, h);
    return s;
}
Bytes Pic1(const std::string& name, float x, float y, float w, float h, std::uint16_t mat) {
    Bytes s = Pane(name, x, y, w, h);
    s.resize(0x68, 0xFF); // four white vertex colours, then the material
    PutU16(s, 0x64, mat);
    std::memcpy(s.data(), "pic1", 4);
    PutU32(s, 4, std::uint32_t(s.size()));
    return s;
}
// wnd1: 8 u16 insets, frame count, content at +0x70 (material at +0x10), frame table at +0x84.
Bytes Wnd1(const std::string& name, std::uint16_t content, const std::vector<std::uint16_t>& frames) {
    Bytes s = Pane(name, 10, -20, 300, 60);
    s.resize(0x70);
    s[0x64] = std::uint8_t(frames.size());
    PutU32(s, 0x68, 0x70);
    s.resize(0x70 + 0x14);
    PutU16(s, 0x80, content);
    PutU32(s, 0x6C, std::uint32_t(s.size()));
    const std::size_t table = s.size();
    s.resize(table + 4 * frames.size());
    for (std::size_t f = 0; f < frames.size(); ++f) {
        PutU32(s, table + 4 * f, std::uint32_t(s.size()));
        PutU16(s, s.size(), frames[f]);
        s.resize(s.size() + 2);
    }
    std::memcpy(s.data(), "wnd1", 4);
    PutU32(s, 4, std::uint32_t(s.size()));
    return s;
}

const std::string kCommon110 = "romfs:UI/LayoutArchive/Common.Product.110.Nin_NX_NVN.blarc.zs";
const std::string kMals120 = "romfs:Mals/USen.Product.120.sarc.zs";

std::vector<std::string> Logs(Host& h) {
    std::scoped_lock lock{h.log_mutex};
    return h.logs;
}

} // namespace

int main() {
    const Bytes bntx = Bntx({{"Btn_Frame^w", 64, 32, 0x0B06, {2, 3, 4, 5}},
                             {"Heart_00^r", 48, 48, 0x0201, {2, 2, 2, 1}}});
    const Bytes flyt = Flyt({
        Txl1({"Btn_Frame^w", "Heart_00^r"}),
        Mat1({{"M_Heart", {0x10, 0x20, 0x30, 0x40}, {0xFF, 0x80, 0x00, 0xFF}, {1}},
              {"M_Frame", {0, 0, 0, 0}, {0xFF, 0xFF, 0xFF, 0xFF}, {0, 1}}}),
        Section("pan1", Bytes(0x4C)),
        Section("pas1", {}),
        Pic1("P_Heart", -120.5f, 40, 48, 48, 0),
        Pic1("P_Bad", 0, 0, 1, 1, 7), // material out of range
        Wnd1("W_Frame", 1, {0, 9}),
        Section("pae1", {}),
    });
    const Bytes layouts = Sarc({{"anim/PauseMenu_00_In.bflan", {1, 2, 3}},
                                {"blyt/PauseMenu_00.bflyt", flyt},
                                {"blyt/Other_00.bflyt", {9, 9}}, // not a dumped layout
                                {"timg/__Combined.bntx", bntx}});
    const Bytes messages = Sarc({{"ActorMsg/PouchContent.msbt", {1}},
                                 {"LayoutMsg/PauseMenu_00.msbt", {2}},
                                 {"LayoutMsg/Common.msbt", {3}}});
    const auto full = [&](Host& h) {
        h.PutRomfs("user:totk-dump", {'1'});
        h.PutRomfs(kCommon110, {0x28, 0xB5, 0x2F, 0xFD});
        h.PutRomfs(kCommon110 + "#", layouts);
        h.PutRomfs(kMals120 + "#", messages);
    };
    const std::string flyt_size = std::to_string(flyt.size()), bntx_size = std::to_string(bntx.size());
    const std::vector<std::string> head{"totk dump common 110 (4 bytes)", "totk dump mals 120"};
    const std::vector<std::string> members{
        "totk dump member anim/PauseMenu_00_In.bflan 3",
        "totk dump member blyt/PauseMenu_00.bflyt " + flyt_size,
        "totk dump member blyt/Other_00.bflyt 2",
        "totk dump member timg/__Combined.bntx " + bntx_size,
        "totk dump tex Btn_Frame^w 64x32 fmt=0B06 ch=2345",
        "totk dump tex Heart_00^r 48x48 fmt=0201 ch=2221"};
    const auto concat = [](std::vector<std::vector<std::string>> parts) {
        std::vector<std::string> out;
        for (const auto& p : parts)
            out.insert(out.end(), p.begin(), p.end());
        return out;
    };

    // The whole dump.
    {
        Host h;
        full(h);
        totk::dump::Run(h.api, 120);
        const auto expected = concat(
            {head, members,
             {"totk dump pane PauseMenu_00 pic1 P_Heart t=-120.5,40 s=48x48 mat=M_Heart "
              "tex=Heart_00^r white=FF8000FF black=10203040",
              "totk dump pane PauseMenu_00 pic1 P_Bad t=0,0 s=1x1 mat=?",
              "totk dump pane PauseMenu_00 wnd1 W_Frame t=10,-20 s=300x60 mat=M_Frame "
              "tex=Btn_Frame^w,Heart_00^r white=FFFFFFFF black=00000000",
              "totk dump frame PauseMenu_00 W_Frame 0 mat=M_Heart tex=Heart_00^r white=FF8000FF "
              "black=10203040",
              "totk dump frame PauseMenu_00 W_Frame 1 mat=?",
              "totk dump msg LayoutMsg/PauseMenu_00.msbt", "totk dump msg LayoutMsg/Common.msbt",
              "totk dump done"}});
        assert(Logs(h) == expected);
        for (const auto& line : Logs(h))
            assert(line.size() <= 200);
        assert(h.MemoryCalls() == 0);
    }

    // No user file: one size query, nothing else read, nothing logged. No read_romfs or no log:
    // nothing at all.
    {
        Host h;
        full(h);
        {
            std::scoped_lock lock{h.romfs_mutex};
            h.romfs.erase("user:totk-dump");
        }
        totk::dump::Run(h.api, 120);
        assert(h.romfs_calls == 1 && Logs(h).empty());
        h.PutRomfs("user:totk-dump", {});
        totk::dump::Run(h.api, 120); // an empty file counts as missing
        assert(h.romfs_calls == 2 && Logs(h).empty());
        EdenDsmodHostApi api = h.api;
        api.read_romfs = nullptr;
        totk::dump::Run(api, 120);
        api = h.api;
        api.log = nullptr;
        totk::dump::Run(api, 120);
        assert(h.romfs_calls == 2 && Logs(h).empty());
    }

    // Failures: one line, then the dump stops.
    const auto failed = [&](void (*mutate)(Host&), int mals, std::vector<std::string> expected) {
        Host h;
        full(h);
        mutate(h);
        totk::dump::Run(h.api, mals);
        return Logs(h) == expected;
    };
    assert(failed([](Host& h) { h.romfs.erase(kCommon110); }, 120,
                  {"totk dump mals 120",
                   "totk dump failed: no UI/LayoutArchive/Common.Product.<100-200>"}));
    assert(failed([](Host& h) { h.romfs.erase(kCommon110 + "#"); }, 120,
                  concat({head, {"totk dump failed: " + kCommon110 +
                                 "# unreadable (Eden Duo patch 0010 missing?)"}})));
    assert(failed([](Host& h) { h.romfs[kCommon110 + "#"] = {'S', 'A', 'R', 'C'}; }, 120,
                  concat({head, {"totk dump failed: " + kCommon110 + "# is not a SARC"}})));
    assert(failed([](Host& h) { h.romfs[kCommon110 + "#"] = Sarc({{"timg/x.bntx", {1}}}); }, 120,
                  concat({head,
                          {"totk dump member timg/x.bntx 1",
                           "totk dump failed: no BNTX timg/__Combined.bntx"}})));
    assert(failed([](Host& h) { h.romfs[kCommon110 + "#"] = Sarc({{"timg/__Combined.bntx", {1}}}); },
                  120,
                  concat({head,
                          {"totk dump member timg/__Combined.bntx 1",
                           "totk dump failed: no BNTX timg/__Combined.bntx"}})));
    {
        Host h;
        full(h);
        totk::dump::Run(h.api, 0);
        auto logs = Logs(h);
        assert(logs.size() > 3 && logs[1] == "totk dump mals 0" &&
               logs.back() == "totk dump failed: no Mals archive");
    }
    {
        Host h;
        full(h);
        {
            std::scoped_lock lock{h.romfs_mutex};
            h.romfs.erase(kMals120 + "#");
        }
        totk::dump::Run(h.api, 120);
        assert(Logs(h).back() == "totk dump failed: " + kMals120 + "# unreadable");
    }
    {
        Host h;
        full(h);
        h.PutRomfs(kCommon110 + "#", Sarc({{"timg/__Combined.bntx", bntx},
                                           {"blyt/PouchList_00.bflyt", {'F', 'L', 'Y', 'X'}}}));
        totk::dump::Run(h.api, 120);
        assert(Logs(h).back() == "totk dump failed: blyt/PouchList_00.bflyt is not a BFLYT");
    }

    // Layout names: blyt/<one of the prefixes>*.bflyt only.
    for (const char* yes : {"blyt/PauseMenuBG_00.bflyt", "blyt/PouchList.bflyt", "blyt/AppMap_00.bflyt",
                            "blyt/PlayerStatus_00.bflyt", "blyt/ShortCutBG_00.bflyt",
                            "blyt/MapIcon.bflyt"})
        assert(totk::dump::IsDumpedLayout(yes));
    for (const char* no : {"blyt/SystemActionGuide_00.bflyt", "anim/PauseMenu_00.bflan",
                           "blyt/PauseMenu_00.bflan", "PauseMenu_00.bflyt", "blyt/.bflyt"})
        assert(!totk::dump::IsDumpedLayout(no));

    // Every truncation of the three formats stays in bounds (and the full files parse).
    {
        Host h;
        for (std::size_t n = 0; n <= flyt.size(); ++n)
            totk::dump::Panes(h.api, "x", std::span(flyt.data(), n));
        for (std::size_t n = 0; n <= bntx.size(); ++n)
            totk::dump::Textures(h.api, std::span(bntx.data(), n));
        for (std::size_t n = 0; n < layouts.size(); ++n)
            assert(totk::dump::Sarc(std::span(layouts.data(), n)).size() < 4);
        assert(totk::dump::Sarc(layouts).size() == 4);
    }

    std::puts("totk_dump_test: ok");
    return 0;
}

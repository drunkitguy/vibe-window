// SPDX-License-Identifier: GPL-3.0-or-later
// totk_msbt.h: MSBT labels and texts, the Mals probe and the PouchContent read.
#include "totk_msbt.h"
#include "totk_test_host.h"

#include <cstdio>

using totk::test::Host;
using totk::test::Msbt;
namespace msbt = totk::msbt;
using namespace std::string_literals;

int main() {
    // Labels and texts: tags (0x0E with parameters, 0x0F closing) stripped, a surrogate pair
    // joined, '\n' kept, other controls and a lone surrogate dropped, text ends at its NUL.
    const auto file = Msbt({
        {"Weapon_Sword_001_Name", u"Traveler's Sword"},
        {"Weapon_Sword_001_Caption", u"A common\nsword.\u000E\u0001\u0002\u0004\uAAAA\uBBBBEnd"},
        {"Tagged", u"\u000F\u0000\u0003x\u0001y"s},
        {"Emoji", u"a\xD83D\xDE00z\xDC00!"},
        {"Accent", u"Pok\u00E9 \u2014 \u30BC"},
        {"Empty", u""},
    });
    msbt::Labels labels;
    assert(msbt::Parse(file, labels));
    assert(labels.size() == 6);
    assert(labels.at("Weapon_Sword_001_Name") == "Traveler's Sword");
    assert(labels.at("Weapon_Sword_001_Caption") == "A common\nsword.End");
    assert(labels.at("Tagged") == "xy");
    assert(labels.at("Emoji") == "a\xF0\x9F\x98\x80z!");
    assert(labels.at("Accent") == "Pok\xC3\xA9 \xE2\x80\x94 \xE3\x82\xBC");
    assert(labels.at("Empty").empty());

    // Bad files: refused, nothing kept.
    const auto refused = [&](std::vector<std::uint8_t> b) {
        labels = {{"stale", "x"}};
        return !msbt::Parse(b, labels) && labels.empty();
    };
    assert(refused({}));
    assert(refused(std::vector<std::uint8_t>(file.begin(), file.begin() + 0x1F)));
    auto bad = file;
    bad[0] = 'X'; // magic
    assert(refused(bad));
    bad = file;
    std::swap(bad[8], bad[9]); // big-endian
    assert(refused(bad));
    bad = file;
    bad[12] = 0; // UTF-8 encoding
    assert(refused(bad));
    bad = file;
    totk::test::PutU32(bad, 0x24, 0x7FFFFFF0); // LBL1 size past the end
    assert(refused(bad));
    bad = file;
    totk::test::PutU32(bad, 0x30, 1000); // LBL1 bucket count past the section
    assert(refused(bad));
    bad = file;
    totk::test::PutU32(bad, 0x38, 0xFFFF); // bucket offset past the section
    assert(refused(bad));
    bad = file;
    bad[0x3C] = 0xFF; // first label length past the section
    assert(refused(bad));
    // Every truncation is refused or parses within bounds.
    for (std::size_t n = 0; n < file.size(); ++n) {
        msbt::Labels l;
        msbt::Parse(std::span(file.data(), n), l);
    }
    // A TXT2 offset past the section drops only the labels it bounds (its own and the one before).
    bad = file;
    const std::size_t txt2 = 0x20 + 16 + ((totk::test::GetU32(file, 0x24) + 15) & ~15u);
    assert(std::memcmp(bad.data() + txt2, "TXT2", 4) == 0);
    totk::test::PutU32(bad, txt2 + 16 + 4 + 4 * 2, 0xFFFFFF); // "Tagged" starts past the end
    assert(msbt::Parse(bad, labels) && labels.size() == 4 && !labels.contains("Tagged") &&
           !labels.contains("Weapon_Sword_001_Caption") && labels.contains("Emoji"));

    // The Mals probe: n 200 down to 100, first hit; read_romfs size queries only.
    {
        Host h;
        assert(msbt::FindMals(h.api) == 0 && h.romfs_calls == 101);
        h.PutRomfs("romfs:Mals/USen.Product.99.sarc.zs", {1});
        h.PutRomfs("romfs:Mals/USen.Product.120.sarc.zs", {1});
        h.PutRomfs("romfs:Mals/USen.Product.112.sarc.zs", {1});
        assert(msbt::FindMals(h.api) == 120);
    }
    // PouchContent.msbt: a size query and one read of exactly that size.
    {
        Host h;
        const char* why = nullptr;
        assert(!msbt::LoadNames(h.api, 120, labels, why) && why && h.romfs_calls == 1);
        const std::string member = "romfs:Mals/USen.Product.120.sarc.zs#ActorMsg/PouchContent.msbt";
        h.PutRomfs(member, {1, 2, 3});
        why = nullptr;
        assert(!msbt::LoadNames(h.api, 120, labels, why) && why && labels.empty());
        h.PutRomfs(member, file);
        h.romfs_calls = 0;
        assert(msbt::LoadNames(h.api, 120, labels, why) && labels.size() == 6 &&
               h.romfs_calls == 2);
    }

    std::puts("totk_msbt_test: ok");
    return 0;
}

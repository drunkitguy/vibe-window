// SPDX-License-Identifier: GPL-3.0-or-later
// totk_msbt.h: MSBT labels and texts, the Mals probe and the PouchContent read.
#include "totk_msbt.h"
#include "totk_test_host.h"

#include <cstdio>

using totk::test::Host;
using totk::test::Msbt;
namespace msbt = totk::msbt;
using namespace std::string_literals;

namespace {
std::vector<std::uint8_t> g_romfs_file; // what the short-reading read_romfs below serves
} // namespace

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
    totk::test::PutU32(bad, 0x24, 3); // LBL1 size under 4 (no room for the bucket count)
    assert(refused(bad));
    // A bucket table past the section is refused even when every byte it would read is zero (an
    // empty label at index 0 is five zero bytes; the reads past the section give zeros too).
    {
        auto zeros = Msbt({{"", u"hi"}});
        assert(msbt::Parse(zeros, labels) && labels.at("") == "hi");
        totk::test::PutU32(zeros, 0x30, 3); // 3 buckets: 28 bytes of table in a 17-byte section
        assert(refused(zeros));
    }
    // No labels at all.
    assert(refused(Msbt({})));
    // Overlapping buckets: 20 buckets x the same 20 labels = 400 labels in a 304-byte table.
    {
        std::vector<std::uint8_t> lbl(4 + 8 * 20), txt(8);
        totk::test::PutU32(lbl, 0, 20);
        for (int b = 0; b < 20; ++b) {
            totk::test::PutU32(lbl, 4 + 8 * b, 20);
            totk::test::PutU32(lbl, 8 + 8 * b, 4 + 8 * 20);
        }
        for (int k = 0; k < 20; ++k) {
            lbl.push_back(2);
            lbl.push_back('L');
            lbl.push_back(std::uint8_t('a' + k));
            totk::test::PutU32(lbl, lbl.size(), 0);
        }
        totk::test::PutU32(txt, 0, 1);
        totk::test::PutU32(txt, 4, 8);
        txt.resize(10); // "" (a NUL)
        assert(refused(totk::test::MsbtFile(lbl, txt)));
        totk::test::PutU32(lbl, 0, 1); // one bucket: 20 labels, accepted
        assert(msbt::Parse(totk::test::MsbtFile(lbl, txt), labels) && labels.size() == 20);
    }
    bad = file;
    totk::test::PutU32(bad, 0x38, 0xFFFF); // bucket offset past the section
    assert(refused(bad));
    bad = file;
    bad[0x3C] = 0xFF; // first label length past the section
    assert(refused(bad));
    // Every truncation is refused or parses within bounds (each in a buffer of exactly that size,
    // so a read past it is a heap overflow under ASan).
    for (std::size_t n = 0; n < file.size(); ++n) {
        const std::vector<std::uint8_t> cut(file.begin(), file.begin() + std::ptrdiff_t(n));
        msbt::Labels l;
        msbt::Parse(cut, l);
    }
    // A text runs to its section's end, not into the padding after it (no NUL here: "h", then
    // the 0xAB padding would read as U+ABAB).
    {
        std::vector<std::uint8_t> lbl(12), txt(10);
        totk::test::PutU32(lbl, 0, 1);
        totk::test::PutU32(lbl, 4, 1);
        totk::test::PutU32(lbl, 8, 12);
        lbl.insert(lbl.end(), {1, 'H', 0, 0, 0, 0});
        totk::test::PutU32(txt, 0, 1);
        totk::test::PutU32(txt, 4, 8);
        txt[8] = 'h';
        assert(msbt::Parse(totk::test::MsbtFile(lbl, txt), labels) && labels.at("H") == "h");
    }
    // A TXT2 entry count past the section.
    bad = file;
    const std::size_t txt2 = 0x20 + 16 + ((totk::test::GetU32(file, 0x24) + 15) & ~15u);
    totk::test::PutU32(bad, txt2 + 16, 0x10000000);
    assert(refused(bad));
    // A label whose index is the entry count is dropped, not read from the text that follows the
    // offset table (here the first text's code units, 0x0010 0x0000, would point at "bee").
    {
        auto two = Msbt({{"A", u"\x0010"}, {"B", u"bee"}});
        assert(msbt::Parse(two, labels) && labels.size() == 2 && labels.at("B") == "bee");
        assert(two[0x42] == 1 && two[0x43] == 'B');
        totk::test::PutU32(two, 0x44, 2); // B's index = TXT2's count
        assert(msbt::Parse(two, labels) && labels.size() == 1 && labels.contains("A"));
    }
    // A TXT2 offset past the section drops only the labels it bounds (its own and the one before).
    bad = file;
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
        // A read one byte shorter than the size query gave: refused, though those bytes parse.
        g_romfs_file = file;
        g_romfs_file.push_back(0); // the size query says one byte more than `file`
        EdenDsmodHostApi short_read = h.api;
        short_read.read_romfs = [](void*, const char*, std::uint64_t, void* out,
                                   std::size_t size) -> std::size_t {
            if (!out)
                return g_romfs_file.size();
            std::memcpy(out, g_romfs_file.data(), size - 1);
            return size - 1;
        };
        msbt::Labels partial;
        assert(msbt::Parse(std::span(g_romfs_file.data(), g_romfs_file.size() - 1), partial));
        why = nullptr;
        assert(!msbt::LoadNames(short_read, 120, labels, why) && why);
    }

    std::puts("totk_msbt_test: ok");
    return 0;
}

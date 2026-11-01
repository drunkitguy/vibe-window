// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_test_host.h"

#include <cmath>
#include <cstdio>

using totk::GameData;
using totk::Murmur3;
using totk::test::EntryAt;
using totk::test::GetU32;
using totk::test::PutU32;
using totk::test::SaveBuilder;

namespace {

SaveBuilder Full() {
    SaveBuilder b;
    b.Bool("Flag.On", true)
        .Bool("Flag.Off", false)
        .Int("Some.Int", -7)
        .UInt("Some.UInt", 0xFFFFFFF0u)
        .Float("Some.Float", 2.5f)
        .Enum("Some.Enum", "AttackUp")
        .Vector3("Some.Vec", 1.f, -2.f, 3.5f)
        .IntArray("Some.Ints", {3, -1, 99})
        .IntArray("Empty.Ints", {})
        .EnumArray("Some.Enums", {"None", "GuardUp"})
        .String64Array("Some.Names", {"Weapon_Sword_001", "", std::string(63, 'x')});
    return b;
}

} // namespace

int main() {
    // Murmur3 at run time (the static_asserts in the header cover compile time).
    const std::string key = "PlayerStatus.MaxLife";
    assert(Murmur3(key) == 0xfbe01da1);
    assert(Murmur3("abc") == 0xb3dd93fa && Murmur3("abcd") == 0x43ed676a); // 3- and 4-byte tails

    // Lookups by key, every type, typed.
    const auto bytes = Full().Build();
    auto g = GameData::Parse(bytes);
    assert(g);
    assert(g->size() == 11);
    assert(g->Bool(Murmur3("Flag.On")) == true && g->Bool(Murmur3("Flag.Off")) == false);
    assert(g->Int(Murmur3("Some.Int")) == -7);
    assert(g->UInt(Murmur3("Some.UInt")) == 0xFFFFFFF0u);
    assert(g->Float(Murmur3("Some.Float")) == 2.5f);
    assert(g->Enum(Murmur3("Some.Enum")) == Murmur3("AttackUp"));
    const auto v = g->Vector3(Murmur3("Some.Vec"));
    assert(v && (*v)[0] == 1.f && (*v)[1] == -2.f && (*v)[2] == 3.5f);
    assert((g->IntArray(Murmur3("Some.Ints")) == std::vector<std::int32_t>{3, -1, 99}));
    assert(g->IntArray(Murmur3("Empty.Ints"))->empty());
    assert((g->EnumArray(Murmur3("Some.Enums")) ==
            std::vector<std::uint32_t>{Murmur3("None"), Murmur3("GuardUp")}));
    const auto names = g->String64Array(Murmur3("Some.Names"));
    assert(names && names->size() == 3 && (*names)[0] == "Weapon_Sword_001" &&
           (*names)[1].empty() && (*names)[2] == std::string(63, 'x'));

    // Typed: the wrong type or a missing key is absent.
    assert(!g->UInt(Murmur3("Some.Int")) && !g->Int(Murmur3("Some.UInt")));
    assert(!g->Bool(Murmur3("Some.Int")) && !g->IntArray(Murmur3("Some.Enums")));
    assert(!g->Int(Murmur3("Not.There")) && !g->String64Array(Murmur3("Not.There")));

    // Header: bad magic, too short, data offset before 0x20 or past the end.
    auto bad = bytes;
    PutU32(bad, 0, 0x04030201);
    assert(!GameData::Parse(bad));
    assert(!GameData::Parse(std::vector<std::uint8_t>(bytes.begin(), bytes.begin() + 0x1F)));
    assert(!GameData::Parse({}));
    bad = bytes;
    PutU32(bad, 8, std::uint32_t(bad.size() + 1));
    assert(!GameData::Parse(bad));
    bad = bytes;
    PutU32(bad, 8, 0x1C);
    assert(!GameData::Parse(bad));
    bad = bytes;
    PutU32(bad, 8, 0xFFFFFFFF);
    assert(!GameData::Parse(bad));
    // Data offset at exactly 0x20: no entries, still a file.
    bad = bytes;
    PutU32(bad, 8, 0x20);
    assert(GameData::Parse(bad) && GameData::Parse(bad)->size() == 0);
    // Over the 64 MiB cap.
    std::vector<std::uint8_t> huge(GameData::kMaxBytes + 1);
    PutU32(huge, 0, GameData::kMagic);
    PutU32(huge, 8, 0x20);
    assert(!GameData::Parse(std::move(huge)));

    // Entry offsets past the end: that key is absent, the others stay.
    const auto offset_past = [&](const char* k, std::uint32_t value) {
        auto b = bytes;
        PutU32(b, EntryAt(b, k) + 4, value);
        auto p = GameData::Parse(b);
        assert(p && p->size() == 10 && p->Int(Murmur3("Some.Int")) == -7);
        return p;
    };
    const auto size = std::uint32_t(bytes.size());
    assert(!offset_past("Some.Vec", size - 11)->Vector3(Murmur3("Some.Vec")));
    assert(!offset_past("Some.Vec", 0xFFFFFFFF)->Vector3(Murmur3("Some.Vec")));
    assert(!offset_past("Some.Ints", size - 3)->IntArray(Murmur3("Some.Ints")));
    assert(!offset_past("Some.Ints", 0xFFFFFFFC)->IntArray(Murmur3("Some.Ints")));
    assert(!offset_past("Some.Names", size)->String64Array(Murmur3("Some.Names")));
    { // Vector3 ending exactly at the end of the file is fine.
        auto b = bytes;
        PutU32(b, EntryAt(b, "Some.Vec") + 4, size - 12);
        assert(GameData::Parse(b)->Vector3(Murmur3("Some.Vec")));
    }

    // Counts: one element too many, and count * 64 / count * 4 that would overflow 32 bits.
    const auto count_is = [&](const char* k, std::uint32_t count) {
        auto b = bytes;
        PutU32(b, GetU32(b, EntryAt(b, k) + 4), count);
        return GameData::Parse(b);
    };
    const auto room = [&](const char* k, std::uint32_t elem) { // elements up to the end of file
        return (size - GetU32(bytes, EntryAt(bytes, k) + 4) - 4) / elem;
    };
    assert(count_is("Some.Ints", room("Some.Ints", 4))->IntArray(Murmur3("Some.Ints")));
    assert(!count_is("Some.Ints", room("Some.Ints", 4) + 1)->IntArray(Murmur3("Some.Ints")));
    assert(!count_is("Some.Ints", 0x40000001)->IntArray(Murmur3("Some.Ints"))); // *4 = 2^32+4
    assert(!count_is("Some.Names", room("Some.Names", 64) + 1)->String64Array(Murmur3("Some.Names")));
    assert(!count_is("Some.Names", 0x04000001)->String64Array(Murmur3("Some.Names"))); // *64 = 2^32+64
    assert(!count_is("Some.Names", 0xFFFFFFFF)->String64Array(Murmur3("Some.Names")));
    assert(count_is("Some.Names", 2)->String64Array(Murmur3("Some.Names"))->size() == 2);

    // A 64-byte string without a NUL rejects its array.
    {
        auto b = bytes;
        const std::uint32_t data = GetU32(b, EntryAt(b, "Some.Names") + 4) + 4;
        b[data + 64 * 2 + 63] = 'y';
        auto p = GameData::Parse(b);
        assert(p && !p->String64Array(Murmur3("Some.Names")) && p->size() == 10);
    }

    // Unknown type sections are skipped; entries before the first section header have no type.
    {
        SaveBuilder b = Full();
        b.Unread(0x05, "A.FloatArray", 0xFFFFFFFF).Unread(0x77, "A.Weird", 1);
        auto p = GameData::Parse(b.Build());
        assert(p && p->size() == 11 && !p->Int(Murmur3("A.Weird")) &&
               p->Bool(Murmur3("Flag.On")) == true);
        auto raw = bytes;
        PutU32(raw, 0x20, Murmur3("Flag.On")); // first section header replaced by an entry
        auto q = GameData::Parse(raw);
        assert(q && !q->Bool(Murmur3("Flag.On")) && !q->Bool(Murmur3("Flag.Off")) &&
               q->Int(Murmur3("Some.Int")) == -7);
    }

    // One hash twice (here Bool and Int): neither is trusted.
    {
        SaveBuilder b = Full();
        b.Int("Flag.On", 5);
        auto p = GameData::Parse(b.Build());
        assert(p && !p->Bool(Murmur3("Flag.On")) && !p->Int(Murmur3("Flag.On")) &&
               p->Bool(Murmur3("Flag.Off")) == false);
    }

    // A partial trailing entry (data offset not a multiple of 8 past 0x20) is ignored.
    {
        auto b = bytes;
        const std::uint32_t data_offset = GetU32(b, 8);
        b.insert(b.begin() + data_offset, {1, 2, 3, 4});
        PutU32(b, 8, data_offset + 4); // array offsets now point 4 bytes early: scalars only
        auto p = GameData::Parse(b);
        assert(p && p->Int(Murmur3("Some.Int")) == -7);
    }

    std::puts("totk_gamedata_test: ok");
    return 0;
}

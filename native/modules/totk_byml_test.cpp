// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_byml.h"
#include "totk_test_host.h" // asserts stay on; the BYML writer

#include <cmath>
#include <cstdio>

int main() {
    namespace byml = totk::byml;
    using namespace totk::test;

    // A written tree reads back: dict members by key, array elements, numbers and strings.
    const auto root =
        Dict({{"Shrine", Arr({Dict({{"LocationName", Str("Work/Dungeon001.gyml")},
                                    {"Trans", Arr({Arr({Num(1.5f), Int(-2), Num(3)})})}})})},
              {"Flag", byml::Node{}}});
    const auto bytes = BymlWriter{}.Write(root);
    const auto doc = byml::Parse(bytes);
    assert(doc && doc->type == byml::Node::Type::Dict && doc->Size() == 2);
    const byml::Node* entry = doc->Get("Shrine")->At(0);
    assert(entry && *entry->Get("LocationName")->String() == "Work/Dungeon001.gyml");
    const byml::Node* pos = entry->Get("Trans")->At(0);
    assert(pos->Size() == 3 && *pos->At(0)->Number() == 1.5 && *pos->At(1)->Number() == -2 &&
           *pos->At(1)->Integer() == -2 && !pos->At(0)->Integer());
    assert(doc->Get("Flag")->type == byml::Node::Type::Null && !doc->Get("Missing") &&
           !entry->At(0) && !pos->Get("x") && !pos->At(3));

    // Fail closed: magic, version, truncation, an out-of-range string, an unknown node type.
    const auto bad = [&](auto mutate) {
        auto b = bytes;
        mutate(b);
        return !byml::Parse(b);
    };
    assert(bad([](auto& b) { b[0] = 'X'; }));
    assert(bad([](auto& b) { b[2] = 1; }) && bad([](auto& b) { b[2] = 8; }));
    assert(bad([](auto& b) { b.resize(b.size() - 1); }) && bad([](auto& b) { b.resize(15); }));
    assert(!byml::Parse(std::vector<std::uint8_t>{}));
    // The root dict's first entry: {key index (u24), type (u8)}, then its value.
    const std::uint32_t at = GetU32(bytes, 12);
    assert(bad([&](auto& b) { b[at + 7] = 0x55; }));               // unknown type
    assert(bad([&](auto& b) { PutU32(b, at + 8, 0xFFFFFFF0u); })); // value offset past the end
    assert(bad([&](auto& b) {
        b[at + 4] = 0xFF;
        b[at + 5] = 0xFF;
    })); // key index out of range
    // Depth and node limits.
    byml::Node deep = Num(1);
    for (int i = 0; i < 70; ++i)
        deep = Arr({deep});
    const auto two = BymlWriter{}.Write(Arr({Num(0), Num(1)}));
    assert(!byml::Parse(two, {1, 64}) && byml::Parse(two, {2, 64}));
    {
        BymlWriter w;
        w.Collect(deep);
        w.bytes[0] = 'Y';
        w.bytes[1] = 'B';
        w.bytes[2] = 7;
        w.Put(12, w.Encode(deep).second);
        assert(!byml::Parse(w.bytes));
    }
    // An empty document (root 0) is an empty node.
    std::vector<std::uint8_t> empty(16);
    empty[0] = 'Y';
    empty[1] = 'B';
    empty[2] = 2;
    assert(byml::Parse(empty) && byml::Parse(empty)->type == byml::Node::Type::Null);

    std::puts("totk_byml_test: ok");
    return 0;
}

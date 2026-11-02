// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_save.h"
#include "totk_test_host.h"

#include <cstdio>

using totk::test::Caption;
using totk::test::FixtureSave;
using totk::test::SaveBuilder;
using totk::test::SaveDir;

namespace {

std::string CaptionPath(int slot) {
    return "slot_0" + std::to_string(slot) + "/caption.sav";
}
std::string ProgressPath(int slot) {
    return "slot_0" + std::to_string(slot) + "/progress.sav";
}

totk::Snapshot Snap(const SaveBuilder& b) {
    const auto g = totk::GameData::Parse(b.Build());
    assert(g);
    return totk::BuildSnapshot(*g);
}

} // namespace

int main() {
    // ---- caption choice -------------------------------------------------------------------------
    {
        SaveDir d;
        assert(!totk::NewestSlot(d.api)); // no slots: nothing
        d.Put(CaptionPath(0), Caption(2024, 5, 1, 10, 30, false));
        d.Put(CaptionPath(3), Caption(2024, 5, 2, 9, 0, true));
        d.Put(CaptionPath(5), Caption(2023, 12, 31, 23, 59, false));
        auto n = totk::NewestSlot(d.api);
        assert(n && n->slot == 3 && n->autosave); // newest date wins, autosave or not
        // A later minute, hour, day, month or year each wins.
        d.Put(CaptionPath(1), Caption(2024, 5, 2, 9, 1, true));
        assert(totk::NewestSlot(d.api)->slot == 1);
        d.Put(CaptionPath(2), Caption(2024, 6, 1, 0, 0, true));
        assert(totk::NewestSlot(d.api)->slot == 2);
        d.Put(CaptionPath(4), Caption(2025, 1, 1, 0, 0, true));
        assert(totk::NewestSlot(d.api)->slot == 4);
        // Tie: the manual save beats the autosave, whichever slot comes first.
        d.Put(CaptionPath(5), Caption(2025, 1, 1, 0, 0, false));
        n = totk::NewestSlot(d.api);
        assert(n && n->slot == 5 && !n->autosave);
        d.Put(CaptionPath(4), Caption(2025, 1, 1, 0, 0, false));
        assert(totk::NewestSlot(d.api)->slot == 4); // two manual saves: the lower slot
        // Unreadable slots are skipped: a missing date field, out-of-range fields, a truncated
        // file, a garbage file. Slot 4 and 5 broken -> slot 2 (2024-06-01) is newest.
        auto missing = Caption(2026, 1, 1, 0, 0, false);
        totk::test::PutU32(missing, 0x28 + 8 * 3, 0); // the day key
        d.Put(CaptionPath(4), missing);
        assert(totk::NewestSlot(d.api)->slot == 5);
        d.Put(CaptionPath(5), std::vector<std::uint8_t>(0x1C0, 0xFF));
        assert(totk::NewestSlot(d.api)->slot == 2);
        // Each date field just outside its range (and the edges just inside).
        const std::uint32_t bad_dates[][4] = {{0, 1, 0, 0},  {13, 1, 0, 0}, {1, 0, 0, 0},
                                              {1, 32, 0, 0}, {1, 1, 24, 0}, {1, 1, 0, 60}};
        for (const auto& b : bad_dates) {
            d.Put(CaptionPath(5), Caption(2026, b[0], b[1], b[2], b[3], false));
            assert(totk::NewestSlot(d.api)->slot == 2);
        }
        d.Put(CaptionPath(5), Caption(2026, 12, 31, 23, 59, false));
        assert(totk::NewestSlot(d.api)->slot == 5);
        d.Put(CaptionPath(5), Caption(2026, 1, 1, 0, 0, false));
        assert(totk::NewestSlot(d.api)->slot == 5);
        auto truncated = Caption(2026, 1, 1, 0, 0, false);
        truncated.resize(0x28 + 8 * 5); // the minute and autosave entries are cut off
        d.Put(CaptionPath(5), truncated);
        assert(totk::NewestSlot(d.api)->slot == 2);
        d.Put(CaptionPath(5), std::vector<std::uint8_t>(0x1C0, 0xFF));
        assert(totk::NewestSlot(d.api)->slot == 2);
        // A key past 0x1C0 is not read (the scan stops at the caption's key table).
        auto far = std::vector<std::uint8_t>(0x400, 0);
        const std::uint32_t date[][2] = {{0x9811A3F7, 2030}, {0xDFD840D3, 1}, {0xBD46F485, 1},
                                         {0x23F3D75E, 0},    {0x27853BF7, 0}};
        for (int i = 0; i < 5; ++i) {
            totk::test::PutU32(far, i == 4 ? 0x1C0 : 0x28 + 8 * i, date[i][0]);
            totk::test::PutU32(far, (i == 4 ? 0x1C0 : 0x28 + 8 * i) + 4, date[i][1]);
        }
        d.Put(CaptionPath(5), far);
        assert(totk::NewestSlot(d.api)->slot == 2);
        // ... and the last entry of the table, at 0x1B8, is.
        totk::test::PutU32(far, 0x1C0, 0);
        totk::test::PutU32(far, 0x1B8, date[4][0]);
        totk::test::PutU32(far, 0x1BC, date[4][1]);
        d.Put(CaptionPath(5), far);
        assert(totk::NewestSlot(d.api)->slot == 5);
        // Only the first 0x1C0 bytes are asked for.
        assert(d.last_request[CaptionPath(5)] == 0x1C0);
    }

    // ---- progress.sav read ----------------------------------------------------------------------
    {
        SaveDir d;
        const auto read = [&d](int slot) {
            return totk::ReadProgress(d.api, slot, totk::ProgressSize(d.api, slot));
        };
        assert(totk::ProgressSize(d.api, 0) == 0 && !read(0)); // missing
        const auto save = FixtureSave().Build();
        d.Put(ProgressPath(0), save);
        auto bytes = read(0);
        assert(bytes && *bytes == save);
        assert(d.size_queries[ProgressPath(0)] == 1 && d.Reads(ProgressPath(0)) == 1);
        assert(d.last_request[ProgressPath(0)] == save.size() + 1);
        // The file grows between the size query and the read: rejected.
        d.grow_after_query = ProgressPath(0);
        assert(!read(0));
        // A stale size (the file changed since the query) is rejected too.
        assert(!totk::ReadProgress(d.api, 0, save.size()));
        // Too small for a header, or over the cap: not even read.
        d.Put(ProgressPath(1), std::vector<std::uint8_t>(0x1F, 0));
        assert(!read(1) && d.Reads(ProgressPath(1)) == 0);
        assert(!totk::ReadProgress(d.api, 1, totk::GameData::kMaxBytes));
    }
    { // The file shrinks between the size query and the read: rejected.
        SaveDir d;
        const auto save = FixtureSave().Build();
        auto api = d.api;
        static SaveDir* dir = &d;
        static std::size_t calls = 0;
        api.read_save_file = [](void* u, const char* path, std::uint64_t off, void* out,
                                std::size_t n) -> std::size_t {
            const std::size_t r = dir->api.read_save_file(u, path, off, out, n);
            return ++calls == 1 ? r : r - 1;
        };
        d.Put(ProgressPath(0), save);
        assert(!totk::ReadProgress(api, 0, totk::ProgressSize(api, 0)));
    }

    // ---- snapshot -------------------------------------------------------------------------------
    const totk::Snapshot s = Snap(FixtureSave());
    // Status (MaxLife is a UInt in the fixture: both integer types are read).
    assert(s.hearts == 12 && s.max_hearts == 40 && s.max_stamina == 1000 && s.rupees == 4321);
    assert(s.max_battery == 150); // float x100, rounded
    // Position published as (x, -z).
    assert(s.position && (*s.position)[0] == 100.5 && (*s.position)[1] == 300.25);

    // Gear from EquipIndex[0], with fuse and effect labels.
    const auto& weapon = s.gear[0];
    assert(weapon && weapon->id == "Weapon_Spear_002");
    assert(weapon->kind == 1 && weapon->value == 12);
    assert(weapon->icon == "romfs:/UI/Tex/Icon/Weapon_Spear_002.bntx.zs");
    assert(weapon->fuse_id == "Item_Ruby" &&
           weapon->fuse_icon == "romfs:/UI/Tex/Icon/Item_Ruby.bntx.zs");
    // Icon paths only for [A-Za-z0-9_]{1,63}; anything else is empty (not published).
    {
        using totk::detail::IconKey;
        const std::string longest(63, 'a');
        assert(IconKey("Obj_KeyItem_X") == "romfs:/UI/Tex/Icon/Obj_KeyItem_X.bntx.zs");
        assert(IconKey(longest) == "romfs:/UI/Tex/Icon/" + longest + ".bntx.zs");
        for (const std::string& bad : {std::string(), std::string(64, 'a'), std::string("../Pack"),
                                      std::string("a/b"), std::string("a.b"), std::string("a-b"),
                                      std::string("a b"), std::string("a#b"), std::string("a:b"),
                                      std::string("\xC3\xA9"), std::string("a\0b", 3)})
            assert(IconKey(bad).empty());
    }
    assert(std::string(weapon->effect) == "Attack Up+");
    const auto& bow = s.gear[1];
    assert(bow && bow->id == "Weapon_Bow_001" && bow->value == 40 && bow->fuse_id.empty() &&
           std::string(bow->effect) == "Rapid Fire");
    assert(s.gear_known == (std::array<bool, 4>{true, true, true, true}));
    assert(!s.gear[2]); // shield EquipIndex -1: known, nothing equipped
    const auto& arrow = s.gear[3];
    assert(arrow && arrow->id == "FireArrow" && arrow->kind == 2 && arrow->value == 7);
    // EquipIndex out of range or on an empty name: known, not present.
    for (std::int32_t index : {3, 1, -5, 0x7FFFFFFF}) {
        const auto o = Snap(FixtureSave(index));
        assert(o.gear_known[0] && !o.gear[0]);
    }
    { // No EquipIndex (or an empty one): not known, and no item says equipped or not.
        const auto o = Snap(FixtureSave(std::nullopt));
        assert(!o.gear_known[0] && !o.gear[0] && o.gear_known[1]);
        assert(!o.tabs[0][0].equipped && !o.tabs[0][1].equipped);
        SaveBuilder b;
        b.String64Array("Pouch.Bow.Content.Name", {"Weapon_Bow_001"}).IntArray("Pouch.Bow.EquipIndex", {});
        assert(!Snap(b).gear_known[1] && !Snap(b).tabs[1][0].equipped);
    }
    assert(Snap(FixtureSave(0)).gear[0]->id == "Weapon_Sword_001");
    assert(std::string(Snap(FixtureSave(0)).gear[0]->effect).empty()); // None -> ""

    // Effect labels: every known value, unknown hash -> null.
    assert(std::string(totk::EffectLabel(totk::Murmur3("DurabilityUp"))) == "Durability Up");
    assert(std::string(totk::EffectLabel(totk::Murmur3("GuardUpPlus"))) == "Guard Up+");
    assert(std::string(totk::EffectLabel(totk::Murmur3("FiveWay"))) == "Five Way");
    assert(!totk::EffectLabel(totk::Murmur3("Bogus")) && !totk::EffectLabel(0));

    // Abilities need both flags.
    const std::array<bool, totk::kAbilities> owned = {true, false, true, false, false, true, true};
    assert(s.owned == owned);

    // Tabs in game order; empty names skipped; Bows then Arrows; kinds, values, equipped, hearts.
    const auto ids = [&](int t) {
        std::vector<std::string> out;
        for (const auto& it : s.tabs[t])
            out.push_back(it.id);
        return out;
    };
    using V = std::vector<std::string>;
    assert((ids(0) == V{"Weapon_Sword_001", "Weapon_Spear_002"}));
    assert((ids(1) == V{"Weapon_Bow_001", "NormalArrow", "FireArrow"}));
    assert((ids(2) == V{"Weapon_Shield_001"}));
    assert((ids(3) == V{"Armor_001_Head", "Armor_001_Upper"}));
    assert((ids(4) == V{"Item_Fruit_A"}) && (ids(5) == V{"Item_Cook_A"}));
    assert((ids(6) == V{"SpecialParts_Fan"}) && (ids(7) == V{"Obj_KeyItem_X"}));
    assert(s.tabs[0][0].equipped == false && s.tabs[0][1].equipped == true);
    assert(s.tabs[1][0].equipped == true && s.tabs[1][1].equipped == false &&
           s.tabs[1][2].equipped == true);
    assert(s.tabs[2][0].equipped == false);
    assert(s.tabs[4][0].equipped == std::nullopt); // no Pouch.Material.EquipIndex
    // Armor: the equip key is UNKNOWN (the fixture's Pouch.Armor.EquipIndex is ignored) and armor
    // has no value (the fixture's Pouch.Armor.Content.StockNum is ignored).
    assert(!s.tabs[3][0].equipped && !s.tabs[3][1].equipped);
    assert(s.tabs[3][0].kind == 0 && s.tabs[3][1].kind == 0);
    assert(s.tabs[4][0].kind == 2 && s.tabs[4][0].value == 5 && !s.tabs[4][0].effect);
    assert(!s.tabs[5][0].effect); // food Effect.Type (AttackUp) gets no gear label
    assert(s.tabs[5][0].hearts == 20 && !s.tabs[4][0].hearts);
    assert(std::string(s.tabs[2][0].effect) == "Guard Up" && s.tabs[2][0].fuse_id.empty());

    // At most kMaxItems per tab (Bows + Arrows together too).
    {
        SaveBuilder b;
        b.String64Array("Pouch.Material.Content.Name", V(500, "Item_A"))
            .String64Array("Pouch.Bow.Content.Name", V(300, "Weapon_Bow_001"))
            .String64Array("Pouch.Arrow.Content.Name", V(300, "NormalArrow"));
        const auto big = Snap(b);
        assert(big.tabs[4].size() == totk::kMaxItems && big.tabs[1].size() == totk::kMaxItems);
        assert(big.tabs[1][299].id == "Weapon_Bow_001" && big.tabs[1][300].id == "NormalArrow");
        assert(big.tabs[4][0].kind == 0); // no StockNum array: no value
    }

    // An empty save: every value absent, nothing owned, every tab empty.
    {
        const auto e = Snap(SaveBuilder());
        assert(!e.hearts && !e.max_hearts && !e.max_stamina && !e.rupees && !e.max_battery);
        assert(!e.position && !e.gear[0] && !e.gear[3]);
        for (bool o : e.owned)
            assert(!o);
        for (const auto& t : e.tabs)
            assert(t.empty());
    }
    // Non-finite battery or position: absent.
    {
        SaveBuilder b;
        b.Float("PlayerStatus.MaxEnergy", INFINITY).Vector3("PlayerStatus.SavePos", NAN, 0, 0);
        const auto e = Snap(b);
        assert(!e.max_battery && !e.position);
        SaveBuilder z;
        z.Vector3("PlayerStatus.SavePos", 1, 2, NAN); // z is the published y
        assert(!Snap(z).position);
        SaveBuilder y;
        y.Vector3("PlayerStatus.SavePos", 1, NAN, 2); // height is not published
        assert(Snap(y).position);
    }
    // Battery beyond the cap (|x| < 1e9, so x100 always fits): absent; just inside: rounded.
    for (const auto& [energy, expected] :
         std::initializer_list<std::pair<float, std::optional<std::int64_t>>>{
             {2e9f, std::nullopt}, {-2e9f, std::nullopt}, {1e9f, std::nullopt},
             {999999.94f, 99999994}, {-0.004f, 0}, {2.345f, 235}}) {
        SaveBuilder b;
        b.Float("PlayerStatus.MaxEnergy", energy);
        assert(Snap(b).max_battery == expected);
    }

    std::puts("totk_save_test: ok");
    return 0;
}

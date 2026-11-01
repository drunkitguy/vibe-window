// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_keys.h"
#include "totk_test_host.h" // asserts stay on

#include <cstdio>
#include <set>
#include <string>

int main() {
    namespace pub = totk::pub;

    // Fixed keys and action names are unique and in the totk. namespace.
    std::set<std::string> fixed;
    for (const char* k : pub::kFixed) {
        assert(std::string_view(k).starts_with("totk.") && fixed.insert(k).second);
        assert(pub::Listed(k));
    }
    std::set<std::string> actions;
    for (const char* a : pub::kActionNames)
        assert(std::string_view(a).starts_with("totk.") && actions.insert(a).second);

    // ActionIndex: exact names only.
    for (int a = 0; a < pub::kActions; ++a)
        assert(pub::ActionIndex(pub::kActionNames[a]) == a);
    assert(pub::ActionIndex("totk.gear.swap") == pub::kActGearSwap);
    assert(pub::ActionIndex("") == pub::kActions);
    assert(pub::ActionIndex("totk.ui.pag") == pub::kActions);
    assert(pub::ActionIndex("TOTK.UI.PAGE") == pub::kActions);
    assert(pub::ActionIndex("totk.ui.page ") == pub::kActions);

    // Expand fills the one placeholder.
    assert(pub::Expand(pub::kGearKeys[pub::kFuseIcon], "shield") == "totk.gear.shield.fuse_icon");
    assert(pub::Expand(pub::kAbilityOwned, "travel") == "totk.ability.travel.owned");
    assert(pub::Expand(pub::kItemKeys[pub::kItemEquipped], "399") == "totk.inv.399.equipped");
    assert(pub::Expand(pub::kTabCount, "7") == "totk.inv.tab7.count");

    // Listed: fixed keys and family instances; nothing else.
    for (const char* s : pub::kGearSlotNames)
        for (const char* f : pub::kGearKeys)
            assert(pub::Listed(pub::Expand(f, s)));
    for (const char* a : pub::kAbilityNames)
        assert(pub::Listed(pub::Expand(pub::kAbilityOwned, a)));
    assert(pub::Listed("totk.inv.0.icon") && pub::Listed("totk.inv.399.fuse_icon"));
    assert(pub::Listed("totk.inv.tab0.count") && pub::Listed("totk.inv.tab7.count"));
    for (const char* bad :
         {"totk.gear.head.present", "totk.gear..present", "totk.gear.weapon.colour",
          "totk.gear.<s>.present", "totk.ability.dash.owned", "totk.ability.fuse.name",
          "totk.inv.01.icon", "totk.inv.x.icon", "totk.inv..icon", "totk.inv.{i}.icon",
          "totk.inv.-1.icon", "totk.inv.tab8.count", "totk.inv.tab10.count",
          "totk.inv.tab.count", "totk.names.valid", "totk.map.tile.0", "totk.gear.swap",
          "totk.ready.", "totk.ready ", "", "totk"})
        assert(!pub::Listed(bad));

    std::puts("totk_keys_test: ok");
    return 0;
}

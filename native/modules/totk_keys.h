// SPDX-License-Identifier: GPL-3.0-or-later
// The single list of the keys the module publishes and the module action names it handles
// [docs/PLAN.md "Data model", "Actions"]. The module builds its key strings from here, and
// tools/totk/check_manifest.py parses this file: every string literal starting "totk." outside
// kActionNames is a key or a key family; kActionNames holds the module actions.
// Family placeholders: <s> one of kGearSlotNames, <a> one of kAbilityNames, {i} an item or map
// tile index, {k} an inventory tab index (0-7).
#pragma once

#include <cstddef>
#include <iterator>
#include <string>
#include <string_view>

namespace totk::pub {

inline constexpr const char* kGearSlotNames[] = {"weapon", "bow", "shield", "arrow"}; // <s>
inline constexpr const char* kAbilityNames[] = {"ultrahand", "fuse",   "ascend", "recall",
                                                "autobuild", "camera", "travel"}; // <a>

// ---- fixed keys ---------------------------------------------------------------------------------
inline constexpr char kReady[] = "totk.ready";
inline constexpr char kUiPage[] = "totk.ui.page";
inline constexpr char kInvTab[] = "totk.inv.tab";
inline constexpr char kInvSel[] = "totk.inv.sel";
inline constexpr char kMapLayer[] = "totk.map.layer";
inline constexpr char kStatusValid[] = "totk.status.valid";
inline constexpr char kStatusSource[] = "totk.status.source";
inline constexpr char kStatusHearts[] = "totk.status.hearts";
inline constexpr char kStatusMaxHearts[] = "totk.status.max_hearts";
inline constexpr char kStatusStamina[] = "totk.status.stamina";
inline constexpr char kStatusMaxStamina[] = "totk.status.max_stamina";
inline constexpr char kStatusRupees[] = "totk.status.rupees";
inline constexpr char kStatusMaxBattery[] = "totk.status.max_battery";
inline constexpr char kStatusBattery[] = "totk.status.battery";
inline constexpr char kGearValid[] = "totk.gear.valid";
inline constexpr char kGearSource[] = "totk.gear.source";
inline constexpr char kAbilityValid[] = "totk.ability.valid";
inline constexpr char kAbilitySource[] = "totk.ability.source";
inline constexpr char kInvValid[] = "totk.inv.valid";
inline constexpr char kInvSource[] = "totk.inv.source";
inline constexpr char kInvCount[] = "totk.inv.count";
inline constexpr char kInvSelIcon[] = "totk.inv.sel.icon";
inline constexpr char kInvSelName[] = "totk.inv.sel.name";
inline constexpr char kInvSelKind[] = "totk.inv.sel.kind";
inline constexpr char kInvSelValue[] = "totk.inv.sel.value";
inline constexpr char kInvSelFuseName[] = "totk.inv.sel.fuse_name";
inline constexpr char kInvSelEffect[] = "totk.inv.sel.effect";
inline constexpr char kInvSelHearts[] = "totk.inv.sel.hearts";
inline constexpr char kInvSelEquipped[] = "totk.inv.sel.equipped";
inline constexpr char kInvSelCaption[] = "totk.inv.sel.caption";
inline constexpr char kMapValid[] = "totk.map.valid";
inline constexpr char kMapSource[] = "totk.map.source";
inline constexpr char kMapX[] = "totk.map.x";
inline constexpr char kMapY[] = "totk.map.y";
inline constexpr char kNamesValid[] = "totk.names.valid";
inline constexpr char kLoading[] = "totk.loading";
// The Map page's travel destinations and medallion move (A2; published only on that page).
inline constexpr char kTravelCount[] = "totk.travel.count";
inline constexpr char kTravelIcon[] = "totk.travel.icon";
inline constexpr char kTravelSelValid[] = "totk.travel.sel.valid";
inline constexpr char kTravelSelName[] = "totk.travel.sel.name";
inline constexpr char kTravelSelOpen[] = "totk.travel.sel.open";
inline constexpr char kTravelSelKind[] = "totk.travel.sel.kind";
inline constexpr char kTravelMoved[] = "totk.travel.moved";
inline constexpr char kTravelOldX[] = "totk.travel.old.x";
inline constexpr char kTravelOldY[] = "totk.travel.old.y";
inline constexpr char kTravelOldZ[] = "totk.travel.old.z";
inline constexpr char kTravelMsg[] = "totk.travel.msg";

inline constexpr const char* kFixed[] = {
    kReady,          kUiPage,         kInvTab,           kInvSel,          kMapLayer,
    kStatusValid,    kStatusSource,   kStatusHearts,     kStatusMaxHearts, kStatusStamina,
    kStatusMaxStamina, kStatusRupees, kStatusMaxBattery, kGearValid,       kGearSource,
    kAbilityValid,   kAbilitySource,  kInvValid,         kInvSource,       kInvCount,
    kInvSelIcon,     kInvSelName,     kInvSelKind,       kInvSelValue,     kInvSelFuseName,
    kInvSelEffect,   kInvSelHearts,   kInvSelEquipped,   kInvSelCaption,   kMapValid,
    kMapSource,      kMapX,           kMapY,             kNamesValid,      kStatusBattery,
    kLoading,        kTravelCount,    kTravelIcon,       kTravelSelValid,  kTravelSelName,
    kTravelSelOpen,  kTravelSelKind,  kTravelMoved,      kTravelOldX,      kTravelOldY,
    kTravelOldZ,     kTravelMsg};

// ---- key families -------------------------------------------------------------------------------
enum GearField { kPresent, kId, kName, kIcon, kLife, kCount, kFuse, kFuseName, kFuseIcon, kEffect,
                 kGearFields };
inline constexpr const char* kGearKeys[kGearFields] = {
    "totk.gear.<s>.present", "totk.gear.<s>.id",   "totk.gear.<s>.name",
    "totk.gear.<s>.icon",    "totk.gear.<s>.life", "totk.gear.<s>.count",
    "totk.gear.<s>.fuse",    "totk.gear.<s>.fuse_name", "totk.gear.<s>.fuse_icon",
    "totk.gear.<s>.effect"};
inline constexpr char kAbilityOwned[] = "totk.ability.<a>.owned";
enum ItemField { kItemIcon, kItemValue, kItemKind, kItemEquipped, kItemFuseIcon, kItemFields };
inline constexpr const char* kItemKeys[kItemFields] = {
    "totk.inv.{i}.icon", "totk.inv.{i}.value", "totk.inv.{i}.kind", "totk.inv.{i}.equipped",
    "totk.inv.{i}.fuse_icon"};
inline constexpr char kTabCount[] = "totk.inv.tab{k}.count";
// Map tile n of the selected layer: the overview and the detail picture (totk_map.h).
inline constexpr const char* kTileKeys[] = {"totk.map.tile.{i}", "totk.map.tile.{i}.d"};
// Travel destination i (A2): x and -z as totk.map.x/.y, kind (-1 off the selected layer), dim.
enum TravelField { kTravelX, kTravelY, kTravelKind, kTravelDim, kTravelFields };
inline constexpr const char* kTravelKeys[kTravelFields] = {
    "totk.travel.{i}.x", "totk.travel.{i}.y", "totk.travel.{i}.kind", "totk.travel.{i}.dim"};

// ---- module actions (on_action) -----------------------------------------------------------------
// UI actions touch only module state; game actions are refused until T3 finds verified offsets,
// except A2's travel.go and travel.restore behind their fail-closed checks.
enum Action { kActPage, kActInvTab, kActInvTabNext, kActInvSelect, kActMapLayer, kActGearSwap,
              kActInvUse, kActAbilitySelect, kActTravelSelect, kActTravelGo, kActTravelRestore,
              kActions };
inline constexpr const char* kActionNames[kActions] = {
    "totk.ui.page",  "totk.inv.tab",  "totk.inv.tab_next",  "totk.inv.select",
    "totk.map.layer", "totk.gear.swap", "totk.inv.use", "totk.ability.select",
    "totk.travel.select", "totk.travel.go", "totk.travel.restore"};
// Argument range [0, n) per action; 0: any argument (tab_next, travel.go and travel.restore ignore
// it; the selects and use take the slot index from "$payload" and check it themselves). ui.page:
// 0 home, 1 abilities, 2 map; inv.tab: the 8 tabs; map.layer: 0 surface, 1 sky, 2 depths;
// gear.swap: <s>; ability.select: <a>.
inline constexpr int kActionArgs[kActions] = {3, 8, 0, 0, 3, 4, 0, 7, 0, 0, 0};
static_assert(kActionArgs[kActGearSwap] == std::size(kGearSlotNames) &&
              kActionArgs[kActAbilitySelect] == std::size(kAbilityNames));

// kActions when the name is not a module action.
inline int ActionIndex(std::string_view name) {
    for (int a = 0; a < kActions; ++a)
        if (name == kActionNames[a])
            return a;
    return kActions;
}

// `family` with its one placeholder (<s>, <a>, {i} or {k}) replaced by `part`.
inline std::string Expand(std::string_view family, std::string_view part) {
    const std::size_t at = family.find_first_of("<{");
    const std::size_t end = family.find_first_of(">}", at);
    std::string out(family.substr(0, at));
    out += part;
    out += family.substr(end + 1);
    return out;
}

namespace detail {
template <std::size_t N>
bool OneOf(std::string_view s, const char* const (&list)[N]) {
    for (const char* x : list)
        if (s == x)
            return true;
    return false;
}

inline bool Matches(std::string_view family, std::string_view key) {
    const std::size_t at = family.find_first_of("<{");
    if (at == std::string_view::npos)
        return family == key;
    const std::size_t end = family.find_first_of(">}", at);
    const std::string_view head = family.substr(0, at), tail = family.substr(end + 1);
    const std::string_view holder = family.substr(at, end + 1 - at);
    if (key.size() <= head.size() + tail.size() || !key.starts_with(head) || !key.ends_with(tail))
        return false;
    const std::string_view part = key.substr(head.size(), key.size() - head.size() - tail.size());
    if (holder == "<s>")
        return OneOf(part, kGearSlotNames);
    if (holder == "<a>")
        return OneOf(part, kAbilityNames);
    if (holder == "{k}")
        return part.size() == 1 && part[0] >= '0' && part[0] <= '7';
    if (part.size() > 1 && part[0] == '0') // {i}: a decimal index, no leading zero
        return false;
    for (char c : part)
        if (c < '0' || c > '9')
            return false;
    return true;
}
} // namespace detail

// True when `key` is a fixed key or an instance of a family above.
inline bool Listed(std::string_view key) {
    for (const char* k : kFixed)
        if (key == k)
            return true;
    for (const char* f : kGearKeys)
        if (detail::Matches(f, key))
            return true;
    for (const char* f : kItemKeys)
        if (detail::Matches(f, key))
            return true;
    for (const char* f : kTileKeys)
        if (detail::Matches(f, key))
            return true;
    for (const char* f : kTravelKeys)
        if (detail::Matches(f, key))
            return true;
    return detail::Matches(kAbilityOwned, key) || detail::Matches(kTabCount, key);
}

} // namespace totk::pub

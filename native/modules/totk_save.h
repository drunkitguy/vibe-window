// SPDX-License-Identifier: GPL-3.0-or-later
// The last save, read through the runtime's read-only save extension: the newest slot from the
// caption.sav dates, then progress.sav (size query, one read), parsed into an immutable Snapshot (status, gear,
// the 8 inventory tabs, abilities, position) with the published keys' data [docs/PLAN.md
// "Data model"]. Everything fails closed: an unreadable slot is skipped, a bad file gives nothing.
#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "core/mods/dsmod_module_extensions.h"
#include "totk_gamedata.h"

namespace totk {

inline constexpr int kSlots = 6;                   // slot_00 .. slot_05 [S29 zelda-totk.js:1114]
inline constexpr std::size_t kCaptionBytes = 0x1C0; // the caption's key table [S29 :1036-1056]
inline constexpr std::size_t kMaxItems = 400;       // per inventory tab (bounds the publish count)

// caption.sav keys [S29 zelda-totk.js:1120-1131]; the names behind these hashes are not public.
namespace caption {
inline constexpr std::uint32_t kYear = 0x9811A3F7, kMonth = 0xDFD840D3, kDay = 0xBD46F485,
                               kHour = 0x23F3D75E, kMinute = 0x27853BF7, kAutosave = 0x25F03CAA;
}

struct SlotStamp {
    int slot = -1;
    std::uint64_t when = 0; // year, month, day, hour, minute packed so that newer is larger
    bool autosave = false;
    bool operator==(const SlotStamp&) const = default;
};

// slot_0N/caption.sav: the five date fields (all required, in range) and the autosave flag.
inline std::optional<SlotStamp> ReadCaption(const EdenDsmodHostSaveApi& api, int slot) {
    char path[] = "slot_00/caption.sav";
    path[6] = char('0' + slot);
    std::uint8_t b[kCaptionBytes];
    const std::size_t n = api.read_save_file(api.userdata, path, 0, b, sizeof b);
    if (n > sizeof b)
        return std::nullopt;
    std::optional<std::uint32_t> year, month, day, hour, minute;
    bool autosave = false;
    for (std::size_t at = 0x28; at + 8 <= n; at += 8) {
        const std::uint32_t hash = dsmod_sdk::Le32(b + at), value = dsmod_sdk::Le32(b + at + 4);
        switch (hash) {
        case caption::kYear: year = value; break;
        case caption::kMonth: month = value; break;
        case caption::kDay: day = value; break;
        case caption::kHour: hour = value; break;
        case caption::kMinute: minute = value; break;
        case caption::kAutosave: autosave = (value & 0xFF) == 1; break; // one byte [S29 :1041-1047]
        default: break;
        }
    }
    if (!year || !month || !day || !hour || !minute || *month < 1 || *month > 12 || *day < 1 ||
        *day > 31 || *hour > 23 || *minute > 59)
        return std::nullopt;
    const std::uint64_t when = std::uint64_t(*year) << 32 | *month << 24 | *day << 16 |
                               *hour << 8 | *minute;
    return SlotStamp{slot, when, autosave};
}

// Newest date wins; on a tie a manual save beats an autosave [S29 :1144-1149], then the lower slot.
inline std::optional<SlotStamp> NewestSlot(const EdenDsmodHostSaveApi& api) {
    std::optional<SlotStamp> best;
    for (int slot = 0; slot < kSlots; ++slot) {
        const auto c = ReadCaption(api, slot);
        if (c && (!best || c->when > best->when ||
                  (c->when == best->when && best->autosave && !c->autosave)))
            best = c;
    }
    return best;
}

namespace detail {
struct ProgressPath {
    char path[21] = "slot_00/progress.sav";
    explicit ProgressPath(int slot) {
        path[6] = char('0' + slot);
    }
};
} // namespace detail

// slot_0N/progress.sav size (the size query; 0 when missing).
inline std::size_t ProgressSize(const EdenDsmodHostSaveApi& api, int slot) {
    return api.read_save_file(api.userdata, detail::ProgressPath(slot).path, 0, nullptr, 0);
}

// One read of `size` + 1 bytes that must return exactly `size` (the size the caller queried):
// a file that grew or shrank since is rejected.
inline std::optional<std::vector<std::uint8_t>> ReadProgress(const EdenDsmodHostSaveApi& api,
                                                             int slot, std::size_t size) {
    if (size < GameData::kEntriesBegin || size >= GameData::kMaxBytes)
        return std::nullopt;
    std::vector<std::uint8_t> bytes(size + 1);
    if (api.read_save_file(api.userdata, detail::ProgressPath(slot).path, 0, bytes.data(),
                           bytes.size()) != size)
        return std::nullopt;
    bytes.resize(size);
    return bytes;
}

// ---- keys, hashed at compile time ---------------------------------------------------------------

namespace detail {
consteval std::uint32_t Key(std::string_view a, std::string_view b, std::string_view c = {}) {
    char buf[128] = {};
    std::size_t n = 0;
    for (std::string_view part : {a, b, c})
        for (char ch : part)
            buf[n++] = ch;
    return Murmur3(std::string_view(buf, n));
}
} // namespace detail

enum Pouch { kWeapon, kBow, kShield, kArmor, kArrow, kMaterial, kFood, kSpecialParts, kKeyItem };
struct PouchKeys {
    std::uint32_t name, life, stock, combined, effect, equip, life_recover;
};
// Pouch.<P>.* [S29 zelda-totk.class.pouch.js:454-518]; EquipIndex [S28 bottom_inventory.c:123-124].
consteval PouchKeys MakePouchKeys(std::string_view p) {
    using detail::Key;
    return {Key("Pouch.", p, ".Content.Name"),        Key("Pouch.", p, ".Content.Life"),
            Key("Pouch.", p, ".Content.StockNum"),    Key("Pouch.", p, ".Content.Combined.Name"),
            Key("Pouch.", p, ".Content.Effect.Type"), Key("Pouch.", p, ".EquipIndex"),
            Key("Pouch.", p, ".Content.LifeRecover")};
}
inline constexpr std::array<PouchKeys, 9> kPouchKeys = {
    MakePouchKeys("Weapon"),   MakePouchKeys("Bow"),  MakePouchKeys("Shield"),
    MakePouchKeys("Armor"),    MakePouchKeys("Arrow"), MakePouchKeys("Material"),
    MakePouchKeys("Food"),     MakePouchKeys("SpecialParts"), MakePouchKeys("KeyItem")};
static_assert(kPouchKeys[kWeapon].name == 0x65efd0be && kPouchKeys[kMaterial].name == 0xd96ebf12);

namespace keys {
inline constexpr std::uint32_t kLife = Murmur3("PlayerStatus.Life"),
                               kMaxLife = Murmur3("PlayerStatus.MaxLife"),
                               kMaxStamina = Murmur3("PlayerStatus.MaxStamina"),
                               kRupees = Murmur3("PlayerStatus.CurrentRupee"),
                               kMaxEnergy = Murmur3("PlayerStatus.MaxEnergy"),
                               kSavePos = Murmur3("PlayerStatus.SavePos");
} // namespace keys

// Abilities in published order (<a> = ultrahand, fuse, ascend, recall, autobuild, camera,
// travel): owned when IsGet.Obj_X and IsGetAnyway.Obj_X are both true [S29 zelda-totk.js:1686-1689].
inline constexpr int kAbilities = 7;
inline constexpr std::array<std::array<std::uint32_t, 2>, kAbilities> kAbilityKeys = [] {
    using detail::Key;
    return std::array<std::array<std::uint32_t, 2>, kAbilities>{{
        {Key("IsGet.Obj_", "UltraHand"), Key("IsGetAnyway.Obj_", "UltraHand")},
        {Key("IsGet.Obj_", "OneTouchBond"), Key("IsGetAnyway.Obj_", "OneTouchBond")},
        {Key("IsGet.Obj_", "Tooreroof"), Key("IsGetAnyway.Obj_", "Tooreroof")},
        {Key("IsGet.Obj_", "ReverseRecorder"), Key("IsGetAnyway.Obj_", "ReverseRecorder")},
        {Key("IsGet.Obj_", "AutoBuilder"), Key("IsGetAnyway.Obj_", "AutoBuilder")},
        {Key("IsGet.Obj_", "Camera"), Key("IsGetAnyway.Obj_", "Camera")},
        {Key("IsGet.Obj_", "Warp"), Key("IsGetAnyway.Obj_", "Warp")},
    }};
}();

// Weapon, bow and shield Effect.Type values [S29 pouch.js:457,468,475] -> short English labels
// taken from the enum names. "" for None; null (not published) for anything else.
inline const char* EffectLabel(std::uint32_t hash) {
    struct Label {
        std::uint32_t hash;
        const char* text;
    };
    static constexpr Label kLabels[] = {
        {Murmur3("None"), ""},
        {Murmur3("AttackUp"), "Attack Up"},
        {Murmur3("AttackUpPlus"), "Attack Up+"},
        {Murmur3("DurabilityUp"), "Durability Up"},
        {Murmur3("DurabilityUpPlus"), "Durability Up+"},
        {Murmur3("FinishBlow"), "Finish Blow"},
        {Murmur3("LongThrow"), "Long Throw"},
        {Murmur3("RapidFire"), "Rapid Fire"},
        {Murmur3("FiveWay"), "Five Way"},
        {Murmur3("GuardUp"), "Guard Up"},
        {Murmur3("GuardUpPlus"), "Guard Up+"},
    };
    for (const Label& l : kLabels)
        if (l.hash == hash)
            return l.text;
    return nullptr;
}

// ---- snapshot -----------------------------------------------------------------------------------

struct Item {
    std::string id, icon;           // icon = IconKey(id): a romfs path, empty for a bad id
    std::string fuse_id, fuse_icon; // empty when nothing is fused
    std::string name, fuse_name;    // from PouchContent.msbt; empty: the id is shown instead
    std::string caption;            // <id>_Caption from PouchContent.msbt; empty: not published
    const char* effect = nullptr;   // EffectLabel; null when unknown or not a gear pouch
    int kind = 0;                   // 1 durability (Life), 2 count (StockNum), 0 none
    std::int32_t value = 0;
    std::optional<std::int32_t> hearts; // food LifeRecover
    std::optional<bool> equipped;       // absent when the pouch's equip key is not known
};

inline constexpr int kGearSlots = 4; // weapon, bow, shield, arrow
inline constexpr int kTabs = 8;

struct Snapshot {
    std::optional<std::int64_t> hearts, max_hearts, max_stamina, rupees, max_battery;
    // gear_known[s]: the pouch's EquipIndex was read; gear[s] is then the equipped item, if any.
    std::array<bool, kGearSlots> gear_known{};
    std::array<std::optional<Item>, kGearSlots> gear;
    std::array<bool, kAbilities> owned{};
    std::array<std::vector<Item>, kTabs> tabs; // game order, at most kMaxItems each
    std::optional<std::array<double, 2>> position; // map (x, -z)
};

namespace detail {
// Status fields are Int in BotW; TotK's type is UNVERIFIED, so UInt is accepted too.
inline std::optional<std::int64_t> IntOrUInt(const GameData& g, std::uint32_t hash) {
    if (const auto v = g.Int(hash))
        return *v;
    if (const auto v = g.UInt(hash))
        return *v;
    return std::nullopt;
}

template <class T>
const T* At(const std::optional<std::vector<T>>& v, std::size_t i) {
    return v && i < v->size() ? &(*v)[i] : nullptr;
}

// The item's icon as the runtime reads it (zstd via Eden Duo patch 0009), or empty (not
// published) unless the id is [A-Za-z0-9_]{1,63}: a save's string never shapes another path.
inline std::string IconKey(const std::string& id) {
    if (id.empty() || id.size() > 63)
        return {};
    for (const char c : id)
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
              c == '_'))
            return {};
    return "romfs:/UI/Tex/Icon/" + id + ".bntx.zs";
}

// Pouch.<P>.EquipIndex[0] (-1: nothing equipped), or nullopt when not known. Armor's equip key is
// UNKNOWN (three slots; candidate Pouch.Armor.EquipIndex UNVERIFIED), so never known.
inline std::optional<std::int64_t> EquipIndex(const GameData& g, Pouch p) {
    if (p == kArmor)
        return std::nullopt;
    const auto equip = g.IntArray(kPouchKeys[p].equip);
    if (!equip || equip->empty())
        return std::nullopt;
    return (*equip)[0];
}

// One pouch in save order, empty names skipped. Equipped: index == EquipIndex[0], when known.
inline std::vector<Item> ReadPouch(const GameData& g, Pouch p) {
    const PouchKeys& k = kPouchKeys[p];
    const auto names = g.String64Array(k.name);
    if (!names)
        return {};
    const bool durability = p == kWeapon || p == kBow || p == kShield;
    const auto values = g.IntArray(durability ? k.life : k.stock);
    const auto fused = g.String64Array(k.combined);
    const auto effects = g.EnumArray(k.effect);
    const auto recover = g.IntArray(k.life_recover);
    const auto equipped = EquipIndex(g, p);
    std::vector<Item> out;
    for (std::size_t i = 0; i < names->size() && out.size() < kMaxItems; ++i) {
        const std::string& id = (*names)[i];
        if (id.empty())
            continue;
        Item it;
        it.id = id;
        it.icon = IconKey(id);
        if (equipped)
            it.equipped = std::int64_t(i) == *equipped;
        if (const auto* v = At(values, i); v && p != kArmor) {
            it.kind = durability ? 1 : 2;
            it.value = *v;
        }
        if (const auto* f = At(fused, i); f && !f->empty()) {
            it.fuse_id = *f;
            it.fuse_icon = IconKey(*f);
        }
        if (const auto* e = At(effects, i); e && durability)
            it.effect = EffectLabel(*e);
        if (const auto* h = At(recover, i))
            it.hearts = *h;
        out.push_back(std::move(it));
    }
    return out;
}
} // namespace detail

inline Snapshot BuildSnapshot(const GameData& g) {
    using detail::IntOrUInt;
    Snapshot s;
    s.hearts = IntOrUInt(g, keys::kLife);
    s.max_hearts = IntOrUInt(g, keys::kMaxLife);
    s.max_stamina = IntOrUInt(g, keys::kMaxStamina);
    s.rupees = IntOrUInt(g, keys::kRupees);
    if (const auto e = g.Float(keys::kMaxEnergy); e && std::isfinite(*e) && std::fabs(*e) < 1e9f)
        s.max_battery = std::llround(double(*e) * 100.0);

    std::array<std::vector<Item>, 9> pouches;
    for (int p = 0; p < 9; ++p)
        pouches[p] = detail::ReadPouch(g, Pouch(p));
    constexpr Pouch kGearPouch[kGearSlots] = {kWeapon, kBow, kShield, kArrow};
    for (int i = 0; i < kGearSlots; ++i) {
        s.gear_known[i] = detail::EquipIndex(g, kGearPouch[i]).has_value();
        for (const Item& it : pouches[kGearPouch[i]])
            if (it.equipped == true)
                s.gear[i] = it;
    }

    for (int a = 0; a < kAbilities; ++a)
        s.owned[a] = g.Bool(kAbilityKeys[a][0]).value_or(false) &&
                     g.Bool(kAbilityKeys[a][1]).value_or(false);

    // Tabs in game order; Bows then Arrows (UNVERIFIED that the game shows arrows there).
    s.tabs = {std::move(pouches[kWeapon]),   std::move(pouches[kBow]),
              std::move(pouches[kShield]),   std::move(pouches[kArmor]),
              std::move(pouches[kMaterial]), std::move(pouches[kFood]),
              std::move(pouches[kSpecialParts]), std::move(pouches[kKeyItem])};
    for (Item& arrow : pouches[kArrow])
        if (s.tabs[1].size() < kMaxItems)
            s.tabs[1].push_back(std::move(arrow));

    if (const auto p = g.Vector3(keys::kSavePos);
        p && std::isfinite((*p)[0]) && std::isfinite((*p)[2]))
        s.position = std::array<double, 2>{(*p)[0], -double((*p)[2])};
    return s;
}

} // namespace totk

// SPDX-License-Identifier: GPL-3.0-or-later
// The Legend of Zelda: Tears of the Kingdom companion module.
// Three build gates: the loader's build_ids prefix, supports_build and create (all 32 bytes);
// 1.2.0 and 1.2.1 pass, and only 1.2.1 gets offsets (T2b).
// Every callback catches all exceptions: nothing may unwind into the host.
// Data: the last save through the save extension (a worker thread polls it every 2 s) and the
// live status reader, whose offsets stay UNKNOWN (0) so it never touches guest memory yet. The
// worker also loads the item names from the game's romfs (totk_msbt.h) and, once per session
// when the player asks for it, logs T8a's texture dump (totk_dump.h), and finds the map's tile
// files once (totk_map.h).
// Base extensions: module actions drive the UI state (page, tab, selection, map layer); game
// actions are refused; load_image serves only the font atlas (icons are romfs: paths the runtime
// reads). Font extension: decode_font builds that atlas from the game's own font (totk_font.h).

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "core/mods/modules/dsmod_module_sdk.h"
#include "totk_build.h"
#include "totk_dump.h"
#include "totk_font.h"
#include "totk_keys.h"
#include "totk_map.h"
#include "totk_msbt.h"
#include "totk_offsets.h"
#include "totk_save.h"
#include "totk_status.h"

namespace {

constexpr auto kPollInterval = std::chrono::seconds(2);
namespace pub = totk::pub;
static_assert(std::size(pub::kGearSlotNames) == totk::kGearSlots);
static_assert(std::size(pub::kAbilityNames) == totk::kAbilities);
static_assert(pub::kActionArgs[pub::kActInvTab] == totk::kTabs);
constexpr int kPageHome = 0; // inventory, gear and status [docs/PLAN.md R5]
constexpr int kPageMap = 2;
static_assert(pub::kActionArgs[pub::kActMapLayer] == totk::map::kLayerCount);

// Every key sample publishes with a computed name, built once in create so sample never allocates.
struct Keys {
    std::array<std::array<std::string, pub::kGearFields>, totk::kGearSlots> gear;
    std::array<std::string, totk::kAbilities> owned;
    std::array<std::string, totk::kTabs> tab_count;
    std::vector<std::array<std::string, pub::kItemFields>> items;
    std::array<std::array<std::string, totk::map::kLevelCount>, totk::map::kTiles> tiles;

    Keys() : items(totk::kMaxItems) {
        for (int s = 0; s < totk::kGearSlots; ++s)
            for (int f = 0; f < pub::kGearFields; ++f)
                gear[s][f] = pub::Expand(pub::kGearKeys[f], pub::kGearSlotNames[s]);
        for (int a = 0; a < totk::kAbilities; ++a)
            owned[a] = pub::Expand(pub::kAbilityOwned, pub::kAbilityNames[a]);
        for (int t = 0; t < totk::kTabs; ++t)
            tab_count[t] = pub::Expand(pub::kTabCount, std::to_string(t));
        for (std::size_t i = 0; i < items.size(); ++i)
            for (int f = 0; f < pub::kItemFields; ++f)
                items[i][f] = pub::Expand(pub::kItemKeys[f], std::to_string(i));
        for (int n = 0; n < totk::map::kTiles; ++n)
            for (int l = 0; l < totk::map::kLevelCount; ++l)
                tiles[n][l] = pub::Expand(pub::kTileKeys[l], std::to_string(n));
    }
};

struct Instance {
    totk::Offsets offsets; // set by Create: kOffsets121 on 1.2.1, all 0 on 1.2.0
    // The create host, for the worker's read_romfs and log (runtime 16: usable from create on
    // [S1 dsmod_module_abi.h:215-217]; the worker is joined before destroy returns).
    EdenDsmodHostApi host{};
    Keys keys;
    // UI state, set by on_action. on_action "runs serialized with sample/tick on the timing
    // thread" [S1 dsmod_module_extensions.h:75]: ModRuntime::Tick runs sample, the module tick,
    // then the taps' actions [S1 mod_runtime.cpp:9-14, mod_actions.cpp:539]. So these and the log
    // bits below need no lock; the worker never touches them.
    int page = 0, tab = 0, sel = 0, layer = 0;
    // Refused actions: one log line per action name per session, written by the next tick (the
    // host struct is borrowed per call, and on_action gets none). An unknown name is refused
    // without a line of ours: the runtime logs every decline by name [S1 mod_module.cpp:335].
    std::uint32_t refused = 0, logged = 0;

    std::mutex mutex; // guards everything below; sample holds it only to copy `snapshot`
    std::condition_variable wake;
    bool stop = false;
    std::uint64_t requested = 0, completed = 0; // poll generations (configure asks for one)
    std::optional<EdenDsmodHostSaveApi> save;
    std::optional<EdenDsmodHostExtensions> extensions; // decode_astc, for T3a's load_image
    std::shared_ptr<const totk::Snapshot> snapshot;
    // The snapshot replaced last time: kept one more poll so the worker, not a sample still
    // holding its copy, frees it.
    std::shared_ptr<const totk::Snapshot> retired;
    bool names = false; // the item names loaded (totk.names.valid)
    std::shared_ptr<const totk::map::Tiles> tiles; // set once by the worker

    // The font atlas, built on first use by decode_font (tick thread) or load_image (asset
    // worker); the lock is held while it builds so the two never build it twice.
    std::mutex font_mutex;
    std::shared_ptr<const totk::font::Atlas> font;

    std::thread worker; // started last in Create, joined in Destroy
};

// The worker's view of the save between polls.
struct Watch {
    std::optional<totk::SlotStamp> shown; // the (slot, date) the published snapshot came from
    totk::SlotStamp stamp;                // a newer (slot, date) being read, and its file size
    std::size_t size = 0;
    std::optional<std::uint64_t> last; // FNV-1a of that progress.sav at the previous read
    int reads = 0;                     // reads of (stamp, size) so far
};
// A newer save that never reads twice the same and parses is dropped until its caption date or
// its progress.sav size changes (8 reads = 16 s).
constexpr int kMaxReads = 8;

// The worker's item names: one attempt per poll until they load or kNameTries pass (the romfs
// may not be open yet early in a session), then one log line either way.
struct Names {
    int tries = 0;
    bool done = false;
    int mals = 0; // Mals/USen.Product.<n>, 0 = not found
    totk::msbt::Labels labels;
};
constexpr int kNameTries = 30;

// Ids become names where PouchContent.msbt has an <id>_Name label; otherwise the id stays.
// Captions come from <id>_Caption [S28 §4] (the detail pane's text).
void ApplyNames(totk::Snapshot& s, const totk::msbt::Labels& labels) {
    const auto text = [&](const std::string& id, const char* suffix) {
        const auto it = id.empty() ? labels.end() : labels.find(id + suffix);
        return it == labels.end() ? std::string{} : it->second;
    };
    const auto apply = [&](totk::Item& it) {
        it.name = text(it.id, "_Name");
        it.fuse_name = text(it.fuse_id, "_Name");
        it.caption = text(it.id, "_Caption");
    };
    for (auto& gear : s.gear)
        if (gear)
            apply(*gear);
    for (auto& tab : s.tabs)
        for (auto& it : tab)
            apply(it);
}

// Publishes `next`. The replaced snapshot is kept one more swap so the worker, not a sample still
// holding its copy, frees it.
void Replace(Instance& in, std::shared_ptr<const totk::Snapshot> next) {
    std::shared_ptr<const totk::Snapshot> freed; // released below, after the lock
    std::scoped_lock lock{in.mutex};
    freed = std::move(in.retired);
    in.retired = std::move(in.snapshot);
    in.snapshot = std::move(next);
}

// True when the names have just loaded. The try is counted first, so a try that throws still
// counts towards kNameTries.
bool TryNames(const EdenDsmodHostApi& h, Names& n) {
    if (n.done || !h.read_romfs)
        return false;
    n.done = ++n.tries >= kNameTries;
    const char* why = "no Mals/USen.Product.<100-200>.sarc.zs";
    n.mals = totk::msbt::FindMals(h);
    const bool ok = n.mals && totk::msbt::LoadNames(h, n.mals, n.labels, why);
    if (!ok && !n.done)
        return false;
    n.done = true;
    if (h.log) {
        char line[200];
        if (ok)
            std::snprintf(line, sizeof line, "totk: item names from Mals/USen.Product.%d (%u labels)",
                          n.mals, unsigned(n.labels.size()));
        else
            std::snprintf(line, sizeof line, "totk: item names unavailable: %s", why);
        h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
    }
    return ok;
}

// One poll. progress.sav is re-read only while the newest (slot, date) differs from the shown
// one. Eden writes saves in place (no commit), and progress.sav has a fixed size, so a read can
// catch a half-written file: a save is taken only when two consecutive reads match and it parses.
// Until then the previous snapshot stays.
// shortcut: a progress.sav that stays old for a whole poll (2 s) after its caption is newer is
// taken as is; T8 checks the game's write order.
void Poll(Instance& in, const EdenDsmodHostSaveApi& api, Watch& w,
          const totk::msbt::Labels& labels) {
    const auto newest = totk::NewestSlot(api);
    if (!newest || newest == w.shown)
        return;
    const std::size_t size = totk::ProgressSize(api, newest->slot);
    if (*newest != w.stamp || size != w.size)
        w = Watch{w.shown, *newest, size, std::nullopt, 0};
    if (w.reads >= kMaxReads)
        return;
    ++w.reads;
    auto bytes = totk::ReadProgress(api, newest->slot, size);
    if (!bytes) {
        w.last.reset();
        return;
    }
    const std::uint64_t hash = dsmod_sdk::Fnv1a64(bytes->data(), bytes->size());
    if (w.last != hash) {
        w.last = hash;
        return;
    }
    const auto data = totk::GameData::Parse(std::move(*bytes));
    if (!data)
        return;
    auto next = std::make_shared<totk::Snapshot>(totk::BuildSnapshot(*data));
    ApplyNames(*next, labels);
    Replace(in, std::move(next));
    w = Watch{newest, {}, 0, std::nullopt, 0};
}

void Worker(Instance& in) {
    Watch watch;
    Names names;
    bool dumped = false, probed = false;
    std::unique_lock lock{in.mutex};
    while (!in.stop) {
        const std::uint64_t generation = in.requested;
        const auto api = in.save;
        lock.unlock();
        try {
            if (TryNames(in.host, names)) {
                std::shared_ptr<const totk::Snapshot> shown;
                {
                    std::scoped_lock names_lock{in.mutex};
                    shown = in.snapshot;
                }
                if (shown) { // a save read before the names: name it now
                    auto next = std::make_shared<totk::Snapshot>(*shown);
                    ApplyNames(*next, names.labels);
                    Replace(in, std::move(next));
                }
                std::scoped_lock names_lock{in.mutex}; // after the renamed snapshot is shown
                in.names = true;
            }
            // The dump needs only the Mals n: it runs once the romfs answered the Mals probe, or
            // when the names gave up (a romfs without that archive).
            if ((names.mals || names.done) && !dumped) {
                dumped = true;
                totk::dump::Run(in.host, names.mals);
            }
            // The map tiles: once, on the same condition (the romfs answered, or never will).
            if ((names.mals || names.done) && !probed && in.host.read_romfs) {
                probed = true;
                auto tiles = std::make_shared<const totk::map::Tiles>(totk::map::Probe(in.host));
                std::scoped_lock tiles_lock{in.mutex};
                in.tiles = std::move(tiles);
            }
            if (api)
                Poll(in, *api, watch, names.labels);
        } catch (...) {
        }
        lock.lock();
        in.completed = generation;
        in.wake.wait_for(lock, kPollInterval,
                         [&] { return in.stop || in.requested != generation; });
    }
}

EdenDsmodBool SupportsBuild(const char* build_id) {
    try {
        return totk::IsSupportedBuild(build_id);
    } catch (...) {
        return false;
    }
}

void* Create(const EdenDsmodHostApi* host, const char*) {
    try {
        if (!dsmod_sdk::HostAbiMatches(host) || host->title_id != totk::kTitleId ||
            !totk::IsSupportedBuild(host->build_id))
            return nullptr;
        auto* in = new (std::nothrow) Instance;
        if (!in)
            return nullptr;
        in->host = *host;
        in->offsets = totk::Is121(host->build_id) ? totk::kOffsets121 : totk::Offsets{};
        try {
            in->worker = std::thread(Worker, std::ref(*in));
        } catch (...) {
            delete in;
            return nullptr;
        }
        return in;
    } catch (...) {
        return nullptr;
    }
}

void Destroy(void* instance) {
    auto* in = static_cast<Instance*>(instance);
    if (!in)
        return;
    try {
        {
            std::scoped_lock lock{in->mutex};
            in->stop = true;
        }
        in->wake.notify_all();
        if (in->worker.joinable())
            in->worker.join();
    } catch (...) {
        // The worker may still run and use `in`: freeing it would be a use after free, and
        // destroying a joinable std::thread calls std::terminate. Leak it instead.
        if (in->worker.joinable())
            return;
    }
    delete in;
}

void ConfigureSave(void* instance, const EdenDsmodHostSaveApi* api) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !api || api->version != EDEN_DSMOD_SAVE_EXT_VERSION ||
            api->struct_size < sizeof(EdenDsmodHostSaveApi) ||
            api->abi_hash != EDEN_DSMOD_SAVE_EXT_HASH || !api->read_save_file)
            return;
        {
            std::scoped_lock lock{in->mutex};
            in->save = *api;
            ++in->requested; // poll now, not in 2 s
        }
        in->wake.notify_all();
    } catch (...) {
    }
}

// Publishing with borrowed strings only: no allocation. Empty text is never published (an icon
// whose id failed IconKey's check is empty).
struct Out {
    const EdenDsmodHostApi& h;
    void I(const char* k, std::int64_t v) const {
        h.publish_i64(h.userdata, k, v);
    }
    void T(const char* k, const char* v) const {
        if (h.publish_text && v && *v)
            h.publish_text(h.userdata, k, v);
    }
    void T(const std::string& k, const std::string& v) const {
        T(k.c_str(), v.c_str());
    }
    // Published even when empty: the runtime draws an absent bind_text as "-" [S1
    // mod_ui_expand.cpp:368-371], so optional text lines (the detail pane's fuse, effect and
    // caption, a present gear item's effect) are "" when there is nothing.
    void Line(const char* k, const char* v) const {
        if (h.publish_text)
            h.publish_text(h.userdata, k, v ? v : "");
    }
    void F(const char* k, double v) const {
        if (h.publish_f64)
            h.publish_f64(h.userdata, k, v);
    }
};

// The game's name when PouchContent.msbt has one, else the id.
const std::string& Shown(const std::string& name, const std::string& id) {
    return name.empty() ? id : name;
}

void PublishStatus(const Out& o, const totk::Status& live, const totk::Snapshot* s) {
    // A valid live value beats the save; with every offset 0 the save is the only source.
    if (live.valid) {
        o.I(pub::kStatusValid, 1);
        o.I(pub::kStatusSource, 2);
        o.I(pub::kStatusHearts, live.hearts);
        o.I(pub::kStatusMaxHearts, live.max_hearts);
        o.I(pub::kStatusStamina, live.stamina);
        o.I(pub::kStatusMaxStamina, live.max_stamina);
        o.I(pub::kStatusRupees, live.rupees);
        if (s && s->max_battery)
            o.I(pub::kStatusMaxBattery, *s->max_battery);
        return;
    }
    o.I(pub::kStatusValid, s ? 1 : 0);
    if (!s)
        return;
    o.I(pub::kStatusSource, 1);
    const std::pair<const char*, const std::optional<std::int64_t>&> fields[] = {
        {pub::kStatusHearts, s->hearts},
        {pub::kStatusMaxHearts, s->max_hearts},
        {pub::kStatusMaxStamina, s->max_stamina},
        {pub::kStatusRupees, s->rupees},
        {pub::kStatusMaxBattery, s->max_battery}};
    for (const auto& [key, value] : fields)
        if (value)
            o.I(key, *value);
}

void PublishGear(const Out& o, const Keys& k, const totk::Snapshot& s) {
    o.I(pub::kGearValid, 1);
    o.I(pub::kGearSource, 1);
    for (int i = 0; i < totk::kGearSlots; ++i) {
        const auto& key = k.gear[i];
        const auto& item = s.gear[i];
        if (s.gear_known[i]) // EquipIndex read: 1 equipped, 0 nothing (-1 or out of range)
            o.I(key[pub::kPresent].c_str(), item ? 1 : 0);
        if (!item)
            continue;
        o.T(key[pub::kId], item->id);
        o.T(key[pub::kName], Shown(item->name, item->id));
        o.T(key[pub::kIcon], item->icon);
        if (item->kind == 1)
            o.I(key[pub::kLife].c_str(), item->value);
        if (item->kind == 2)
            o.I(key[pub::kCount].c_str(), item->value);
        if (i == 0 || i == 2) { // only weapons and shields fuse
            o.I(key[pub::kFuse].c_str(), item->fuse_id.empty() ? 0 : 1);
            if (!item->fuse_id.empty()) {
                o.T(key[pub::kFuseName], Shown(item->fuse_name, item->fuse_id));
                o.T(key[pub::kFuseIcon], item->fuse_icon);
            }
        }
        if (i != 3) // the effect line of the weapon, bow and shield: "" for none, never absent
            o.Line(key[pub::kEffect].c_str(), item->effect);
    }
}

void PublishInventory(const Out& o, const Instance& in, const totk::Snapshot& s) {
    o.I(pub::kInvValid, 1);
    o.I(pub::kInvSource, 1);
    for (int t = 0; t < totk::kTabs; ++t)
        o.I(in.keys.tab_count[t].c_str(), std::int64_t(s.tabs[t].size()));
    if (in.tab < 0 || in.tab >= totk::kTabs)
        return;
    const auto& items = s.tabs[in.tab];
    o.I(pub::kInvCount, std::int64_t(items.size()));
    if (in.page != kPageHome)
        return; // the list is heavy: only while its page is open
    for (std::size_t i = 0; i < items.size() && i < in.keys.items.size(); ++i) {
        const auto& key = in.keys.items[i];
        const auto& it = items[i];
        o.T(key[pub::kItemIcon], it.icon);
        o.I(key[pub::kItemKind].c_str(), it.kind);
        if (it.kind)
            o.I(key[pub::kItemValue].c_str(), it.value);
        if (it.equipped)
            o.I(key[pub::kItemEquipped].c_str(), *it.equipped ? 1 : 0);
        if (!it.fuse_id.empty())
            o.T(key[pub::kItemFuseIcon], it.fuse_icon);
    }
    if (in.sel < 0 || std::size_t(in.sel) >= items.size())
        return;
    const auto& it = items[std::size_t(in.sel)];
    o.T(pub::kInvSelIcon, it.icon.c_str());
    o.T(pub::kInvSelName, Shown(it.name, it.id).c_str());
    o.I(pub::kInvSelKind, it.kind);
    if (it.kind)
        o.I(pub::kInvSelValue, it.value);
    if (it.equipped)
        o.I(pub::kInvSelEquipped, *it.equipped ? 1 : 0);
    o.Line(pub::kInvSelFuseName, Shown(it.fuse_name, it.fuse_id).c_str());
    o.Line(pub::kInvSelEffect, it.effect);
    o.Line(pub::kInvSelCaption, it.caption.c_str());
    if (it.hearts)
        o.I(pub::kInvSelHearts, *it.hearts);
}

void Sample(void* instance, const EdenDsmodHostApi* host) {
    try {
        if (!instance || !host || !host->publish_i64)
            return;
        auto& in = *static_cast<Instance*>(instance);
        const totk::Status live = totk::ReadStatus(*host, in.offsets);
        std::shared_ptr<const totk::Snapshot> snap;
        std::shared_ptr<const totk::map::Tiles> tiles;
        bool names;
        {
            std::scoped_lock lock{in.mutex};
            snap = in.snapshot;
            tiles = in.tiles;
            names = in.names;
        }
        const Out o{*host};
        o.I(pub::kReady, 1);
        o.I(pub::kNamesValid, names ? 1 : 0);
        o.I(pub::kUiPage, in.page);
        o.I(pub::kInvTab, in.tab);
        o.I(pub::kInvSel, in.sel);
        o.I(pub::kMapLayer, in.layer);
        PublishStatus(o, live, snap.get());
        o.I(pub::kMapValid, snap && snap->position ? 1 : 0);
        if (!snap) { // no save read yet: every save domain is empty
            o.I(pub::kGearValid, 0);
            o.I(pub::kAbilityValid, 0);
            o.I(pub::kInvValid, 0);
            return;
        }
        PublishGear(o, in.keys, *snap);
        o.I(pub::kAbilityValid, 1);
        o.I(pub::kAbilitySource, 1);
        for (int a = 0; a < totk::kAbilities; ++a)
            o.I(in.keys.owned[a].c_str(), snap->owned[a] ? 1 : 0);
        PublishInventory(o, in, *snap);
        if (snap->position) {
            o.I(pub::kMapSource, 1);
            o.F(pub::kMapX, (*snap->position)[0]);
            o.F(pub::kMapY, (*snap->position)[1]);
            // The selected layer's tiles: heavy, so only while the map page is open, and only
            // with a position (totk.map.valid), so no save shows no map.
            if (tiles && in.page == kPageMap && in.layer >= 0 && in.layer < totk::map::kLayerCount)
                for (int n = 0; n < totk::map::kTiles; ++n)
                    for (int l = 0; l < totk::map::kLevelCount; ++l)
                        o.T(in.keys.tiles[n][l], (*tiles)[in.layer][n][l]);
        }
    } catch (...) {
    }
}

// Writes the log line of each action refused since the last tick, once per action name.
void Tick(void* instance, const EdenDsmodHostApi* host) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !host || !host->log || !(in->refused & ~in->logged))
            return;
        for (int a = 0; a < pub::kActions; ++a) {
            const std::uint32_t bit = 1u << a;
            if (!(in->refused & bit) || (in->logged & bit))
                continue;
            in->logged |= bit;
            char line[160];
            if (a >= pub::kActGearSwap)
                std::snprintf(line, sizeof line, "totk: %s disabled: no verified offsets",
                              pub::kActionNames[a]);
            else
                std::snprintf(line, sizeof line, "totk: %s refused: argument out of range",
                              pub::kActionNames[a]);
            host->log(host->userdata, EDEN_DSMOD_LOG_INFO, line);
        }
    } catch (...) {
    }
}

void Configure(void* instance, const EdenDsmodHostExtensions* host) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !host || host->version != EDEN_DSMOD_EXT_VERSION ||
            host->struct_size < sizeof(EdenDsmodHostExtensions) ||
            host->abi_hash != EDEN_DSMOD_EXT_HASH)
            return;
        std::scoped_lock lock{in->mutex};
        in->extensions = *host;
    } catch (...) {
    }
}

// The action table [docs/PLAN.md "Actions"]: UI actions within range are accepted and change
// only module state; game actions, unknown names and out-of-range arguments return false (the
// runtime plays the refused haptic). Nothing here reads or writes guest memory.
EdenDsmodBool OnAction(void* instance, const char* action, std::int64_t arg) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !action)
            return false;
        const int a = pub::ActionIndex(action);
        switch (a) {
        case pub::kActPage:
            if (arg < 0 || arg >= pub::kActionArgs[a])
                break;
            in->page = int(arg);
            return true;
        case pub::kActInvTab:
            if (arg < 0 || arg >= pub::kActionArgs[a])
                break;
            in->tab = int(arg);
            in->sel = 0;
            return true;
        case pub::kActInvTabNext: // the argument is ignored
            in->tab = in->tab >= 0 && in->tab < totk::kTabs - 1 ? in->tab + 1 : 0;
            in->sel = 0;
            return true;
        case pub::kActInvSelect: {
            std::shared_ptr<const totk::Snapshot> snap;
            {
                std::scoped_lock lock{in->mutex};
                snap = in->snapshot;
            }
            if (!snap || in->tab < 0 || in->tab >= totk::kTabs || arg < 0 ||
                std::uint64_t(arg) >= snap->tabs[in->tab].size()) // < totk.inv.count
                break;
            in->sel = int(arg);
            return true;
        }
        case pub::kActMapLayer:
            if (arg < 0 || arg >= pub::kActionArgs[a])
                break;
            in->layer = int(arg);
            return true;
        default: // kActGearSwap, kActInvUse, kActAbilitySelect: no verified offsets; or unknown
            break;
        }
        if (a < pub::kActions)
            in->refused |= 1u << a;
        return false;
    } catch (...) {
        return false;
    }
}

// The atlas at `cap`, built from the base: romfs (totk_font.h) through the create host's
// read_romfs (decode_font gets no host [S2 010055D009F78000.cpp:151]); null while the font is not
// readable.
std::shared_ptr<const totk::font::Atlas> Font(Instance& in, std::uint32_t cap) {
    std::scoped_lock lock{in.font_mutex};
    if (!in.font || in.font->cap != cap) {
        auto atlas = totk::font::Load(in.host, cap);
        if (!atlas)
            return nullptr;
        in.font = std::move(atlas);
    }
    return in.font;
}

// The bytes are the package's totk_font.txt, a spec ("body:48", totk_font.h). False (no sink
// call) on a bad spec or while the font is not readable: the host retries about once a second,
// 30 times [S1 mod_assets.cpp:393-405].
EdenDsmodBool DecodeFont(void* instance, const std::uint8_t* bytes, std::size_t size,
                         void* receiver, EdenDsmodFontSink sink) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !bytes || !sink || size == 0 || size > 256)
            return false;
        std::string_view spec{reinterpret_cast<const char*>(bytes), size};
        while (!spec.empty() && (spec.back() == '\n' || spec.back() == '\r' || spec.back() == ' '))
            spec.remove_suffix(1);
        const auto cap = totk::font::ParseSpec(spec);
        const auto atlas = cap ? Font(*in, *cap) : nullptr;
        if (!atlas)
            return false;
        sink(receiver, atlas->cap, totk::font::kFirst, atlas->glyphs.data(),
             std::uint32_t(atlas->glyphs.size()));
        return true;
    } catch (...) {
        return false;
    }
}

// Only module:totk:font/<spec>, the font atlas. Icons are romfs: paths the runtime decodes itself
// (T3a'); map tiles are T7. Every other key fails and the host gives up after its retries
// [S3 MODULE_GUIDE §1.4].
EdenDsmodBool LoadImage(void* instance, const EdenDsmodHostApi*, const char* key, void* receiver,
                        EdenDsmodImageSink sink) {
    try {
        constexpr std::string_view prefix = "module:totk:font/";
        auto* in = static_cast<Instance*>(instance);
        if (!in || !key || !sink || !std::string_view{key}.starts_with(prefix))
            return false;
        const auto cap = totk::font::ParseSpec(std::string_view{key}.substr(prefix.size()));
        const auto atlas = cap ? Font(*in, *cap) : nullptr;
        if (!atlas)
            return false;
        sink(receiver, atlas->width, atlas->height, atlas->rgba.data(), atlas->rgba.size());
        return true;
    } catch (...) {
        return false;
    }
}

const EdenDsmodModuleApi kModule{
    EDEN_DSMOD_MODULE_ABI_VERSION,
    sizeof(EdenDsmodModuleApi),
    0,
    EDEN_DSMOD_MODULE_ABI_HASH,
    totk::kTitleId,
    "Tears of the Kingdom companion",
    EDEN_DSMOD_CAP_EXTENSIONS,
    SupportsBuild,
    Create,
    Destroy,
    Sample,
    Tick,
};

const EdenDsmodModuleExtensions kExtensions{
    EDEN_DSMOD_EXT_VERSION, sizeof(EdenDsmodModuleExtensions), EDEN_DSMOD_EXT_HASH,
    Configure,              OnAction,                          LoadImage,
};

const EdenDsmodFontExtensions kFontExtensions{
    EDEN_DSMOD_FONT_EXT_VERSION,
    sizeof(EdenDsmodFontExtensions),
    EDEN_DSMOD_FONT_EXT_HASH,
    DecodeFont,
};

const EdenDsmodModuleSaveExtensions kSaveExtensions{
    EDEN_DSMOD_SAVE_EXT_VERSION,
    sizeof(EdenDsmodModuleSaveExtensions),
    EDEN_DSMOD_SAVE_EXT_HASH,
    ConfigureSave,
};

} // namespace

DSMOD_SDK_EXPORT_MODULE(kModule)
DSMOD_SDK_EXPORT_EXTENSIONS(kExtensions)
DSMOD_SDK_EXPORT_FONT_EXTENSIONS(kFontExtensions)
// The SDK has no save macro [S3 MODULE_GUIDE §1.5]; this is the generic getter the macros expand
// to, as the SMO module does [S2 native/modules/0100000000010000.cpp:1499].
DSMOD_SDK_DEFINE_GETTER(eden_dsmod_get_save_extensions, EdenDsmodModuleSaveExtensions,
                        EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH, kSaveExtensions)

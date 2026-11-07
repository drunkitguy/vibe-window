// SPDX-License-Identifier: GPL-3.0-or-later
// The Legend of Zelda: Tears of the Kingdom companion module.
// Three build gates: the loader's build_ids prefix, supports_build and create (all 32 bytes);
// 1.2.0 and 1.2.1 pass, and only 1.2.1 gets offsets (T2b).
// Every callback catches all exceptions: nothing may unwind into the host.
// Data: the last save through the save extension (a worker thread polls it every 2 s) and, on
// 1.2.1 only, the live status and position read in sample through kOffsets121's chains
// (read-only; on 1.2.0 every chain is empty and guest memory is never touched), and there the
// GameData loading gate that permits the manifest's wheel buttons (totk.loading, §A1). The
// worker also loads the item names from the game's romfs (totk_msbt.h) and, once per session
// when the player asks for it, logs T8a's texture dump (totk_dump.h), and finds the map's tile
// files and the travel destinations once (totk_map.h, totk_travel.h).
// Base extensions: module actions drive the UI state (page, tab, selection, map layer, travel
// destination); game actions are refused except A2's travel.go and travel.restore, which move
// the Travel Medallion through one 4-op write batch behind fail-closed checks (§A2, write
// extension); load_image serves only the font atlas (icons are romfs: paths the runtime
// reads). Font extension: decode_font builds that atlas from the game's own font (totk_font.h).

#include <algorithm>
#include <array>
#include <cstdarg>
#include <chrono>
#include <cmath>
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
#include "totk_travel.h"

namespace {

constexpr auto kPollInterval = std::chrono::seconds(2);
namespace pub = totk::pub;
static_assert(std::size(pub::kGearSlotNames) == totk::kGearSlots);
static_assert(std::size(pub::kAbilityNames) == totk::kAbilities);
static_assert(pub::kActionArgs[pub::kActInvTab] == totk::kTabs);
constexpr int kPageHome = 0; // inventory, gear and status [docs/PLAN.md R5]
constexpr int kPageMap = 2;
// Consecutive failed live samples (0.5 s at 60 Hz) before the status or the position falls back
// to the save.
// shortcut: N samples grace, tune after T8c.
constexpr int kLiveGrace = 30;
// Clear loading-gate samples in a row before totk.loading turns 0.
// shortcut: 1 s settle after loading, tune after T8c.
constexpr int kLoadSettle = 60;
static_assert(pub::kActionArgs[pub::kActMapLayer] == totk::map::kLayerCount);
constexpr int kAbilityTravel = 6; // the Travel Medallion's totk.ability.<a>
static_assert(std::string_view(pub::kAbilityNames[kAbilityTravel]) == "travel");
constexpr std::size_t kMaxDest = totk::travel::kMaxDest;
constexpr int kMoveWatch = 60; // ticks the move is re-read after go
using totk::travel::Bits;

// Every key sample publishes with a computed name, built once in create so sample never allocates.
struct Keys {
    std::array<std::array<std::string, pub::kGearFields>, totk::kGearSlots> gear;
    std::array<std::string, totk::kAbilities> owned;
    std::array<std::string, totk::kTabs> tab_count;
    std::vector<std::array<std::string, pub::kItemFields>> items;
    std::array<std::array<std::string, totk::map::kLevelCount>, totk::map::kTiles> tiles;
    std::array<std::array<std::string, pub::kTravelFields>, kMaxDest> travel;

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
        for (std::size_t i = 0; i < kMaxDest; ++i)
            for (int f = 0; f < pub::kTravelFields; ++f)
                travel[i][f] = pub::Expand(pub::kTravelKeys[f], std::to_string(i));
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
    // Live status, tick thread only like the UI state: the last valid one and the failed samples
    // since (kLiveGrace: none valid yet, or past the grace), and the two once-per-session lines.
    totk::Status live;
    int misses = kLiveGrace;
    bool live_logged = false, lost_logged = false;
    // Live position, the same way with its own misses; pos_layer is Link's layer at the last valid
    // one (-1: none yet), so totk.map.layer follows him only when it changes.
    totk::Position pos;
    int pos_misses = kLiveGrace, pos_layer = -1;
    // The loading gate: clear samples in a row (capped at kLoadSettle), the published
    // totk.loading (-1: none yet) and the unknown cause kinds logged (bit 1 << Unknown).
    int load_clear = 0, loading = -1;
    unsigned load_unknown_logged = 0;
    std::uint64_t gate_mgr = 0; // the GameDataMgr this sample's gate read (A2 precondition 1)
    bool sampled = false; // a sample ran since the last tick (tick runs without one while hidden)
    // A2 travel, tick thread only like `page`: the selected destination (-1 none), the queued go
    // or restore (precondition 4 reads the vec3 on two ticks: req_read, req_bits), whether this
    // session's first go asked for a manual save, the moved medallion (no pointers: every read
    // walks the chain again) and the last result line (totk.travel.msg).
    bool v121 = false;
    EdenDsmodHostWriteApi write{}; // set by the write extension's configure
    int travel_sel = -1;
    enum class Req : std::uint8_t { none, go, restore } req = Req::none;
    bool req_read = false, save_asked = false;
    Bits req_bits{};
    struct Moved {
        Bits old, dest;
        int ticks = kMoveWatch;
        bool seen = false; // `last` is a value other than old and dest, seen the tick before
        Bits last{};
    };
    std::optional<Moved> moved;
    char msg[200] = "";
    bool report = false, reported = false; // user:totk-re asked (copied from `re`); report ran

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
    bool re = false;    // user:totk-re exists and is non-empty (A2's read-only report)
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
    std::string why = "no Mals/USen.Product.<100-200>.sarc.zs";
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
            std::snprintf(line, sizeof line, "totk: item names unavailable: %s", why.c_str());
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
          const totk::msbt::Labels& labels, const std::vector<totk::travel::Dest>& places) {
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
    next->travel = totk::travel::Apply(places, *data);
    Replace(in, std::move(next));
    w = Watch{newest, {}, 0, std::nullopt, 0};
}

void Worker(Instance& in) {
    Watch watch;
    Names names;
    bool dumped = false, probed = false, placed = false;
    std::vector<totk::travel::Dest> places;
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
                // A2's read-only report for T8c, asked for like the dump by a player's file.
                const bool re = in.host.read_romfs(in.host.userdata, "user:totk-re", 0, nullptr, 0);
                std::scoped_lock re_lock{in.mutex};
                in.re = re;
            }
            // The map tiles: once, on the same condition (the romfs answered, or never will).
            if ((names.mals || names.done) && !probed && in.host.read_romfs) {
                probed = true;
                auto tiles = std::make_shared<const totk::map::Tiles>(totk::map::Probe(in.host));
                std::scoped_lock tiles_lock{in.mutex};
                in.tiles = std::move(tiles);
            }
            // The travel destinations: once, likewise; a save already shown is read again for
            // their unlock state.
            if ((names.mals || names.done) && !placed && in.host.read_romfs) {
                placed = true;
                places = totk::travel::Load(in.host, names.mals);
                if (!places.empty())
                    watch = Watch{};
            }
            if (api)
                Poll(in, *api, watch, names.labels, places);
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
        in->v121 = totk::Is121(host->build_id);
        in->offsets = in->v121 ? totk::kOffsets121 : totk::Offsets{};
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
        // No write at shutdown: the old spot goes to the log (an error line, on disk at once).
        if (in->moved)
            totk::Say(in->host, EDEN_DSMOD_LOG_ERROR,
                      "totk travel: medallion still moved; place it by hand at its old "
                      "spot %s",
                      totk::travel::Format(in->moved->old).s);
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

void PublishStatus(const Out& o, const totk::Status* live, const totk::Snapshot* s) {
    // A valid live status (or the last one, within the grace) beats the save.
    if (live) {
        o.I(pub::kStatusValid, 1);
        o.I(pub::kStatusSource, 2);
        o.I(pub::kStatusHearts, live->hearts);
        o.I(pub::kStatusMaxHearts, live->max_hearts);
        o.I(pub::kStatusStamina, std::lround(live->stamina));
        o.I(pub::kStatusMaxStamina, std::lround(live->max_stamina));
        o.I(pub::kStatusRupees, live->rupees);
        if (live->has_battery) { // x100 rounded, as the save's .max_battery
            o.I(pub::kStatusBattery, std::llround(double(live->battery) * 100.0));
            o.I(pub::kStatusMaxBattery, std::llround(double(live->max_battery) * 100.0));
        } else if (s && s->max_battery) {
            o.I(pub::kStatusMaxBattery, *s->max_battery);
        }
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

// T8c's line: the live values (raw floats) beside the save's, so the units can be compared.
void LogStatus(const EdenDsmodHostApi& h, const char* what, const totk::Status& l,
               const totk::Snapshot* s) {
    if (!h.log)
        return;
    char save[4][24], battery[64] = "-", line[320];
    const std::optional<std::int64_t>* fields[] = {s ? &s->hearts : nullptr,
                                                   s ? &s->max_hearts : nullptr,
                                                   s ? &s->max_stamina : nullptr,
                                                   s ? &s->rupees : nullptr};
    for (int i = 0; i < 4; ++i)
        if (fields[i] && *fields[i])
            std::snprintf(save[i], sizeof save[i], "%lld", static_cast<long long>(**fields[i]));
        else
            std::snprintf(save[i], sizeof save[i], "-");
    if (l.has_battery)
        std::snprintf(battery, sizeof battery, "%g/%g", double(l.battery), double(l.max_battery));
    std::snprintf(line, sizeof line,
                  "totk live status%s: hearts=%u/%u stamina=%g/%g rupees=%u battery=%s | save: "
                  "hearts=%s max_hearts=%s max_stamina=%s rupees=%s",
                  what, unsigned(l.hearts), unsigned(l.max_hearts), double(l.stamina),
                  double(l.max_stamina), unsigned(l.rupees), battery, save[0], save[1], save[2],
                  save[3]);
    h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
}

// One live sample: a valid one is kept and resets the misses; a torn or failed one keeps the last
// valid status until kLiveGrace misses in a row. Null while the save is the source.
const totk::Status* LiveStatus(Instance& in, const EdenDsmodHostApi& h, const totk::Snapshot* s) {
    const totk::Status read = totk::ReadStatus(h, in.offsets);
    if (read.valid) {
        in.live = read;
        in.misses = 0;
        if (!in.live_logged) {
            in.live_logged = true;
            LogStatus(h, "", read, s);
        }
    } else if (in.misses < kLiveGrace && ++in.misses == kLiveGrace && !in.lost_logged) {
        in.lost_logged = true;
        LogStatus(h, " lost", in.live, s);
    }
    return in.misses < kLiveGrace ? &in.live : nullptr;
}

// One live position sample, with the status's grace. A valid one whose layer differs from the last
// moves the map to it (the pills still switch it by hand). Null while the save is the source.
// shortcut: tick-thread reads, move to the worker if T8c's profile line shows the cost.
const totk::Position* LivePosition(Instance& in, const EdenDsmodHostApi& h) {
    const totk::Position read = totk::ReadPosition(h, in.offsets);
    if (read.valid) {
        in.pos = read;
        in.pos_misses = 0;
        if (const int layer = totk::Layer(read.y); layer != in.pos_layer)
            in.layer = in.pos_layer = layer;
    } else if (in.pos_misses < kLiveGrace) {
        ++in.pos_misses;
    }
    return in.pos_misses < kLiveGrace ? &in.pos : nullptr;
}

// totk.loading: 0 only after kLoadSettle clear samples in a row, else 1. Unknown counts as
// loading: !totk.loading permits the wheel's input, and an unreadable gate cannot tell a load
// screen from play. One line per change of the published value (none for the first) and one per
// unknown cause kind per session (the boot's "managers differ" must not hide a later wrong count
// or file hop); ReadLoading names none for an empty chain (1.2.0).
int Loading(Instance& in, const EdenDsmodHostApi& h) {
    totk::Unknown why;
    std::uint32_t n = 0;
    const int gate = totk::ReadLoading(h, h.main_base, in.offsets, why, n, in.gate_mgr);
    in.load_clear = gate == 0 ? std::min(in.load_clear + 1, kLoadSettle) : 0;
    const int loading = in.load_clear < kLoadSettle ? 1 : 0;
    const unsigned bit = 1u << unsigned(why);
    char line[64];
    if (why != totk::Unknown::none && !(in.load_unknown_logged & bit)) {
        in.load_unknown_logged |= bit;
        if (why == totk::Unknown::count)
            std::snprintf(line, sizeof line, "totk loading: unknown (count %u)", unsigned(n));
        else
            std::snprintf(line, sizeof line, "totk loading: unknown (%s)",
                          totk::kUnknownText[unsigned(why)]);
        if (h.log)
            h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
    }
    if (in.loading >= 0 && loading != in.loading && h.log) {
        std::snprintf(line, sizeof line, "totk loading: %d -> %d", in.loading, loading);
        h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
    }
    return in.loading = loading;
}

// The live position (source 2) or the save's (1); without either, totk.map.valid 0 and nothing
// else. The selected layer's tiles are heavy: only while the map page is open.
void PublishMap(const Out& o, const Instance& in, const totk::Position* live,
                const totk::Snapshot* s, const totk::map::Tiles* tiles) {
    const bool save = s && s->position;
    o.I(pub::kMapValid, live || save ? 1 : 0);
    if (!live && !save)
        return;
    o.I(pub::kMapSource, live ? 2 : 1);
    o.F(pub::kMapX, live ? live->x : (*s->position)[0]);
    o.F(pub::kMapY, live ? -double(live->z) : (*s->position)[1]);
    if (tiles && in.page == kPageMap && in.layer >= 0 && in.layer < totk::map::kLayerCount)
        for (int n = 0; n < totk::map::kTiles; ++n)
            for (int l = 0; l < totk::map::kLevelCount; ++l)
                o.T(in.keys.tiles[n][l], (*tiles)[in.layer][n][l]);
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

// The Map page's destinations and the move's state (only while that page is open: CA10's prebuilt
// keys). A destination off the selected layer has kind -1 (hidden); dim 1 unless it is open.
void PublishTravel(const Out& o, const Instance& in, const totk::Snapshot* s) {
    const std::size_t n = s ? std::min(s->travel.size(), kMaxDest) : 0;
    o.I(pub::kTravelCount, std::int64_t(n));
    for (std::size_t i = 0; i < n; ++i) {
        const auto& d = s->travel[i];
        const auto& k = in.keys.travel[i];
        o.F(k[pub::kTravelX].c_str(), d.x);
        o.F(k[pub::kTravelY].c_str(), -double(d.z));
        o.I(k[pub::kTravelKind].c_str(), d.layer == in.layer ? d.kind : -1);
        o.I(k[pub::kTravelDim].c_str(), d.open == 1 ? 0 : 1);
    }
    o.T(pub::kTravelIcon, "file:assets/travel-dot.png");
    const bool sel = in.travel_sel >= 0 && std::size_t(in.travel_sel) < n;
    o.I(pub::kTravelSelValid, sel ? 1 : 0);
    if (sel) {
        const auto& d = s->travel[std::size_t(in.travel_sel)];
        o.T(pub::kTravelSelName, Shown(d.name, d.label).c_str());
        o.I(pub::kTravelSelOpen, d.open);
        o.I(pub::kTravelSelKind, d.kind);
    }
    o.I(pub::kTravelMoved, in.moved ? 1 : 0);
    if (in.moved) {
        const auto& b = in.moved->old;
        o.F(pub::kTravelOldX, totk::detail::Float(b[0]));
        o.F(pub::kTravelOldY, totk::detail::Float(b[1]));
        o.F(pub::kTravelOldZ, totk::detail::Float(b[2]));
    }
    o.Line(pub::kTravelMsg, in.msg);
}

void Sample(void* instance, const EdenDsmodHostApi* host) {
    try {
        if (!instance || !host || !host->publish_i64)
            return;
        auto& in = *static_cast<Instance*>(instance);
        std::shared_ptr<const totk::Snapshot> snap;
        std::shared_ptr<const totk::map::Tiles> tiles;
        bool names;
        {
            std::scoped_lock lock{in.mutex};
            snap = in.snapshot;
            tiles = in.tiles;
            names = in.names;
            in.report = in.re;
        }
        // Status first, then the position (which may move the layer before it is published).
        const totk::Status* status = LiveStatus(in, *host, snap.get());
        const int loading = Loading(in, *host);
        in.sampled = true;
        const totk::Position* pos = LivePosition(in, *host);
        const Out o{*host};
        o.I(pub::kReady, 1);
        o.I(pub::kNamesValid, names ? 1 : 0);
        o.I(pub::kUiPage, in.page);
        o.I(pub::kInvTab, in.tab);
        o.I(pub::kInvSel, in.sel);
        o.I(pub::kMapLayer, in.layer);
        o.I(pub::kLoading, loading);
        PublishStatus(o, status, snap.get());
        PublishMap(o, in, pos, snap.get(), tiles.get());
        if (in.page == kPageMap)
            PublishTravel(o, in, snap.get());
        if (!snap) { // no save read yet: every other save domain is empty
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
    } catch (...) {
    }
}

// Writes the log line of each action refused since the last tick, once per action name.
void LogRefused(Instance& in, const EdenDsmodHostApi& h) {
    if (!h.log || !(in.refused & ~in.logged))
        return;
    for (int a = 0; a < pub::kActions; ++a) {
        const std::uint32_t bit = 1u << a;
        if (!(in.refused & bit) || (in.logged & bit))
            continue;
        in.logged |= bit;
        char line[224];
        if (a == pub::kActAbilitySelect) // the manifest sends it only for the medallion
            std::snprintf(line, sizeof line,
                          "totk: %s disabled: the Travel Medallion is placed in the + menu "
                          "and used from the game's map; the companion's medallion move "
                          "(Map page) waits for T8c",
                          pub::kActionNames[a]);
        else if (a >= pub::kActGearSwap && a < pub::kActTravelSelect)
            std::snprintf(line, sizeof line, "totk: %s disabled: no verified offsets",
                          pub::kActionNames[a]);
        else
            std::snprintf(line, sizeof line, "totk: %s refused: argument out of range",
                          pub::kActionNames[a]);
        h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
    }
}

// totk.travel.msg, the last result line (printf-style; no allocation).
__attribute__((format(printf, 2, 3))) void Msg(Instance& in, const char* format, ...) {
    va_list args;
    va_start(args, format);
    std::vsnprintf(in.msg, sizeof in.msg, format, args);
    va_end(args);
}

// The selected destination of the shown snapshot, or null.
const totk::travel::Dest* Selected(const Instance& in, const totk::Snapshot* s) {
    const bool ok = s && in.travel_sel >= 0 &&
                    std::size_t(in.travel_sel) < std::min(s->travel.size(), kMaxDest);
    return ok ? &s->travel[std::size_t(in.travel_sel)] : nullptr;
}

std::shared_ptr<const totk::Snapshot> Snap(Instance& in) {
    std::scoped_lock lock{in.mutex};
    return in.snapshot;
}

// Preconditions 1-4 of §A2 (host reads only): null when they hold, else the message.
const char* Preconditions(const Instance& in, const EdenDsmodHostApi& h, totk::travel::Walked& w) {
    if (!in.sampled || in.loading != 0) // no sample since the last tick: the gate is stale
        return "Game loading or not readable: try again";
    if (!totk::travel::ReadMedallion(h, in.offsets, w))
        return "Medallion not readable";
    if (w.ptr[0] != in.gate_mgr)
        return "Game data changed: try again";
    if (const char* placed = totk::travel::PlacedCheck(h, in.offsets, w))
        return placed;
    if (!totk::travel::InWorld(w.bits))
        return "Medallion position not readable";
    return nullptr;
}

// x X y Y z Z, one decimal, for the screen (the log keeps Format's exact floats).
struct Spot {
    char s[64];
};
Spot ShortSpot(const Bits& b) {
    Spot t;
    std::snprintf(t.s, sizeof t.s, "x %.1f y %.1f z %.1f", double(totk::detail::Float(b[0])),
                  double(totk::detail::Float(b[1])), double(totk::detail::Float(b[2])));
    return t;
}

// Precondition 5, the "moved from" line (before the batch, so a crash during it still leaves the
// old spot in the log), the batch, then the 60-tick watch.
void Go(Instance& in, const EdenDsmodHostApi& h, const totk::travel::Walked& w) {
    const auto snap = Snap(in);
    const totk::travel::Dest* d = Selected(in, snap.get());
    if (!d || d->open != 1)
        return Msg(in, "Select an open destination");
    const Bits dest = totk::travel::ToBits(d->x, d->y + totk::travel::kArrivalHover, d->z);
    if (!totk::travel::InWorld(dest))
        return Msg(in, "Destination out of the world");
    // shortcut: label cut at 64 in the log, real stems are <= 16; upgrade if a mod needs longer
    totk::Say(h, EDEN_DSMOD_LOG_ERROR,
              "totk travel: medallion moved from %s to %.64s %s; if Eden closes before Put "
              "back, place it again by hand at the old x/z",
              totk::travel::Format(w.bits).s, d->label.c_str(), totk::travel::Format(dest).s);
    if (!totk::travel::SendBatch(in.write, w, w.bits, dest)) {
        totk::Say(h, EDEN_DSMOD_LOG_ERROR, "totk travel: move not applied");
        return Msg(in, "Not applied, try again");
    }
    in.moved = Instance::Moved{w.bits, dest};
    Msg(in, "Open the map and travel to your medallion");
}

void Restore(Instance& in, const EdenDsmodHostApi& h, const totk::travel::Walked& w) {
    const auto& m = *in.moved;
    if (w.bits == m.old) { // the game reverted it after the watch, or the player put it there
        totk::Say(h, EDEN_DSMOD_LOG_ERROR, "totk travel: medallion already back at %s",
                  totk::travel::Format(m.old).s);
        in.moved.reset();
        return Msg(in, "Medallion is already back");
    }
    if (w.bits != m.dest) { // changed after the watch, or moved by the player
        totk::Say(h, EDEN_DSMOD_LOG_ERROR,
                  "totk travel: medallion not where the companion put it; place it by "
                  "hand at its old spot %s",
                  totk::travel::Format(m.old).s);
        Msg(in,
            "The medallion is not where the companion put it; place it by hand at its old spot %s",
            ShortSpot(m.old).s);
        in.moved.reset();
        return;
    }
    if (!totk::travel::SendBatch(in.write, w, m.dest, m.old))
        return Msg(in, "Not applied, try again");
    totk::Say(h, EDEN_DSMOD_LOG_ERROR, "totk travel: medallion back at %s",
              totk::travel::Format(m.old).s);
    in.moved.reset();
    Msg(in, "Medallion put back");
}

// One of the 60 ticks after go: did the game keep the move? A failed walk skips the tick.
void WatchMove(Instance& in, const EdenDsmodHostApi& h) {
    auto& m = *in.moved;
    --m.ticks;
    totk::travel::Walked w;
    if (!totk::travel::ReadMedallion(h, in.offsets, w))
        return;
    if (w.bits == m.old) {
        totk::Say(h, EDEN_DSMOD_LOG_ERROR, "totk travel: move not applied (medallion still at %s)",
                  totk::travel::Format(w.bits).s);
        in.moved.reset();
        return Msg(in, "The game did not keep the move (not applied)");
    }
    if (w.bits == m.dest) {
        m.seen = false;
        return;
    }
    if (m.seen && w.bits == m.last && totk::travel::InWorld(w.bits)) { // held two ticks
        m.dest = w.bits;
        m.seen = false;
        totk::Say(h, EDEN_DSMOD_LOG_ERROR,
                  "totk travel: medallion changed by the game; now at %s; old spot %s",
                  totk::travel::Format(m.dest).s, totk::travel::Format(m.old).s);
        return Msg(in, "The game changed the medallion; its old spot is %s", ShortSpot(m.old).s);
    }
    m.seen = true;
    m.last = w.bits;
}

// A queued go or restore: preconditions 1-4 on two ticks with the same vec3 (any failure drops
// the request and keeps `moved`), then the move itself.
void Step(Instance& in, const EdenDsmodHostApi& h) {
    if (in.req == Instance::Req::restore && !in.moved) { // the watch saw a revert meanwhile
        in.req = Instance::Req::none;
        return;
    }
    totk::travel::Walked w;
    const char* fail = Preconditions(in, h, w);
    if (!fail && in.req_read && w.bits != in.req_bits)
        fail = "The medallion moved meanwhile: tap again";
    if (fail) {
        in.req = Instance::Req::none;
        return Msg(in, "%s", fail);
    }
    if (!in.req_read) {
        in.req_read = true;
        in.req_bits = w.bits;
        return;
    }
    const auto req = in.req;
    in.req = Instance::Req::none;
    req == Instance::Req::go ? Go(in, h, w) : Restore(in, h, w);
}

void Travel(Instance& in, const EdenDsmodHostApi& h) {
    // T8c's read-only report: once, the first time the gate publishes 0 (1.2.1 offsets only).
    if (in.report && !in.reported && in.loading == 0 && in.offsets.v3a_table &&
        in.offsets.int_table) {
        in.reported = true;
        totk::travel::Report(h, in.gate_mgr, in.offsets);
    }
    if (in.moved && in.moved->ticks > 0)
        WatchMove(in, h);
    if (in.req != Instance::Req::none)
        Step(in, h);
}

void Tick(void* instance, const EdenDsmodHostApi* host) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !host)
            return;
        LogRefused(*in, *host);
        Travel(*in, *host);
        in->sampled = false;
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

// totk.travel.go's on_action checks (§A2), in order; a refusal sets totk.travel.msg. Passing
// queues the request for the next ticks' preconditions.
EdenDsmodBool TravelGo(Instance& in) {
    const auto& o = in.offsets;
    const auto refuse = [&](const char* why) {
        Msg(in, "%s", why);
        return EDEN_DSMOD_FALSE;
    };
    if (!in.v121)
        return refuse("Moving the medallion needs game version 1.2.1");
    if (!o.medallion.n || !o.v3a_table || !o.int_table || !o.medallion_index ||
        !(o.medallion_len || o.medallion_index_placed.set))
        return refuse("Moving the medallion is waiting for T8c (offsets not checked yet)");
    if (!in.write.write_batch)
        return refuse("This Eden build cannot write game memory (no write extension)");
    if (!in.host.log) // the "moved from" line is the only record of the old spot
        return refuse("This Eden build gives the module no log");
    const auto snap = Snap(in);
    const totk::travel::Dest* d = Selected(in, snap.get());
    if (!d || d->open != 1)
        return refuse("Select an open destination");
    if (!snap->owned[kAbilityTravel])
        return refuse("The last save has no Travel Medallion");
    if (in.moved)
        return refuse("Put the medallion back first");
    if (!in.save_asked) { // data-loss rule: a save to fall back on
        in.save_asked = true;
        return refuse("Make a manual save in game, then tap again");
    }
    in.req = Instance::Req::go;
    in.req_read = false;
    Msg(in, "Moving the medallion");
    return EDEN_DSMOD_TRUE;
}

// The action table [docs/PLAN.md "Actions"]: UI actions within range are accepted and change
// only module state; game actions, unknown names and out-of-range arguments return false (the
// runtime plays the refused haptic). Nothing here reads or writes guest memory: travel.go and
// travel.restore only queue a request for Tick.
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
            const auto snap = Snap(*in);
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
        case pub::kActTravelSelect: { // < totk.travel.count
            const auto snap = Snap(*in);
            if (!snap || arg < 0 || std::uint64_t(arg) >= std::min(snap->travel.size(), kMaxDest))
                break;
            in->travel_sel = int(arg);
            return true;
        }
        case pub::kActTravelGo:
            return TravelGo(*in);
        case pub::kActTravelRestore:
            if (!in->moved) {
                Msg(*in, "Nothing to put back");
                return false;
            }
            in->req = Instance::Req::restore;
            in->req_read = false;
            Msg(*in, "Putting the medallion back");
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

// The write extension's host table, kept only when it matches. configure runs right after create
// on the runtime's thread, before any tick, and only tick and on_action read it.
void ConfigureWrite(void* instance, const EdenDsmodHostWriteApi* api) {
    try {
        auto* in = static_cast<Instance*>(instance);
        if (!in || !api || api->version != EDEN_DSMOD_WRITE_EXT_VERSION ||
            api->struct_size < sizeof(EdenDsmodHostWriteApi) ||
            api->abi_hash != EDEN_DSMOD_WRITE_EXT_HASH || !api->write_batch)
            return;
        in->write = *api;
    } catch (...) {
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

const EdenDsmodModuleWriteExtensions kWriteExtensions{
    EDEN_DSMOD_WRITE_EXT_VERSION,
    sizeof(EdenDsmodModuleWriteExtensions),
    EDEN_DSMOD_WRITE_EXT_HASH,
    ConfigureWrite,
};

} // namespace

DSMOD_SDK_EXPORT_MODULE(kModule)
DSMOD_SDK_EXPORT_EXTENSIONS(kExtensions)
DSMOD_SDK_EXPORT_FONT_EXTENSIONS(kFontExtensions)
DSMOD_SDK_EXPORT_WRITE_EXTENSIONS(kWriteExtensions)
// The SDK has no save macro [S3 MODULE_GUIDE §1.5]; this is the generic getter the macros expand
// to, as the SMO module does [S2 native/modules/0100000000010000.cpp:1499].
DSMOD_SDK_DEFINE_GETTER(eden_dsmod_get_save_extensions, EdenDsmodModuleSaveExtensions,
                        EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH, kSaveExtensions)

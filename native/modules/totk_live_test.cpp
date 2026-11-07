// SPDX-License-Identifier: GPL-3.0-or-later
// The live inventory (docs/PLAN.md §T3c): totk_live.h ReadGameData over a fake 1.2.1
// GameDataMgr (totk_test_host.h PutGameData) built from the fixture save, and the module's worker
// that shows it. Compiled in from the module source like totk_loading_test (totk_module_test sits
// at CA9's 64 KiB file cap).
#if defined(__GNUC__) && !defined(__clang__)
// The replaced operator new/delete below pair malloc with free; GCC cannot see that.
#pragma GCC diagnostic ignored "-Wmismatched-new-delete"
#endif
#include "0100F2C0115B6000.cpp"
#include "totk_test_host.h"

#include <cstdlib>

// CA10 with a live inventory shown: allocations on this (the sampling) thread.
namespace {
thread_local long t_news = 0;
} // namespace
void* operator new(std::size_t n) {
    if (void* p = std::malloc(n ? n : 1)) {
        ++t_news;
        return p;
    }
    throw std::bad_alloc();
}
void* operator new(std::size_t n, const std::nothrow_t&) noexcept { // create's instance
    ++t_news;
    return std::malloc(n ? n : 1);
}
void operator delete(void* p) noexcept {
    std::free(p);
}
void operator delete(void* p, std::size_t) noexcept {
    std::free(p);
}

namespace {

using namespace totk::test;
using totk::live::Result;
const auto& kO = totk::kOffsets121;

// Every IntArray and String64Array key the live reader copies, as `g` holds them.
GmdKeys KeysOf(const totk::GameData& g) {
    GmdKeys k;
    for (const auto& pouch : totk::kPouchNames)
        for (const auto& key : totk::live::detail::kKeys) {
            const std::string name = "Pouch." + std::string(pouch) + key.suffix;
            if (key.text) {
                if (const auto v = g.String64Array(totk::Murmur3(name)))
                    k.texts.emplace_back(name, *v);
            } else if (const auto v = g.IntArray(totk::Murmur3(name))) {
                k.ints.emplace_back(name, *v);
            }
        }
    return k;
}

const totk::GameData& Save() {
    static const totk::GameData g = *totk::GameData::Parse(FixtureSave().Build());
    return g;
}

struct Got {
    Result r;
    std::string why;
    std::optional<totk::Snapshot> s;
};
Got Read(Host& h, const totk::GameData* save = &Save(), const totk::Offsets& o = kO) {
    std::vector<std::uint8_t> bytes;
    Got got{Result::off, {}, {}};
    got.r = totk::live::ReadGameData(h.api, o, save, bytes, got.why);
    if (got.r == Result::ok)
        got.s = totk::BuildSnapshot(*totk::GameData::Parse(std::move(bytes)));
    return got;
}

bool Same(const totk::Item& a, const totk::Item& b) {
    return a.id == b.id && a.icon == b.icon && a.fuse_id == b.fuse_id &&
           a.fuse_icon == b.fuse_icon && a.effect == b.effect && a.kind == b.kind &&
           a.value == b.value && a.hearts == b.hearts && a.equipped == b.equipped;
}
// The tabs and the gear, the part of a snapshot the live reader gives.
bool SameInv(const totk::Snapshot& a, const totk::Snapshot& b) {
    for (int s = 0; s < totk::kGearSlots; ++s)
        if (a.gear_known[s] != b.gear_known[s] || a.gear[s].has_value() != b.gear[s].has_value() ||
            (a.gear[s] && !Same(*a.gear[s], *b.gear[s])))
            return false;
    for (int t = 0; t < totk::kTabs; ++t) {
        if (a.tabs[t].size() != b.tabs[t].size())
            return false;
        for (std::size_t i = 0; i < a.tabs[t].size(); ++i)
            if (!Same(a.tabs[t][i], b.tabs[t][i]))
                return false;
    }
    return true;
}

template <class V>
V& Of(std::vector<std::pair<std::string, V>>& list, std::string_view key) {
    for (auto& [k, v] : list)
        if (k == key)
            return v;
    assert(!"key not in the fixture");
    return list[0].second;
}

// The IntArray block's values moved to `to` with every IntArray entry's bytes (indices kept, the
// address arithmetic mod 2^64).
void MoveValues(Host& h, const Gmd& g, const totk::Offsets& o, std::uint64_t to) {
    const std::uint64_t slot = g.mgr + o.int_table - totk::kTableToValues,
                        values = h.Get<std::uint64_t>(slot);
    std::vector<std::pair<std::uint64_t, std::array<std::uint8_t, totk::kIntArrayEntry>>> moved;
    for (const auto& [key, e] : g.entry)
        if (e - values < 0x100000) // in the values block (1 MiB): an IntArray entry
            moved.emplace_back(to + (e - values),
                               h.Get<std::array<std::uint8_t, totk::kIntArrayEntry>>(e));
    for (const auto& [at, bytes] : moved) // read all first: the new range may overlap the old
        h.Put(at, bytes);
    h.Put(slot, to);
}

// A module over `h` with the fixture save shown (`saved` false: no progress.sav); without `names`
// read_romfs is off (the worker only polls), with it the romfs holds the Mals archive and PutNames
// adds PouchContent.msbt naming Item_Fruit_A.
struct Module {
    Host h;
    SaveDir d;
    const EdenDsmodModuleApi* m =
        eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
    const EdenDsmodModuleExtensions* ext =
        eden_dsmod_get_extensions(EDEN_DSMOD_EXT_VERSION, EDEN_DSMOD_EXT_HASH);
    void* instance = nullptr;
    Gmd g;

    explicit Module(bool v121, const GmdKeys* keys = nullptr, const totk::Offsets& lay = kO,
                    bool names = false, bool saved = true) {
        if (v121)
            std::memcpy(h.api.build_id, totk::kBuild121Bytes.data(), 32);
        if (names) {
            h.PutRomfs("romfs:Mals/USen.Product.121.sarc.zs", {0x28, 0xB5, 0x2F, 0xFD});
        } else {
            h.api.read_romfs = nullptr;
        }
        if (keys)
            g = PutGameData(h, *keys, lay);
        d.Put("slot_01/caption.sav", Caption(2024, 1, 2, 8, 0, false));
        if (saved)
            d.Put("slot_01/progress.sav", FixtureSave().Build());
        instance = m->create(&h.api, "{}");
        assert(instance);
        eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH)
            ->configure(instance, &d.api);
        Poll(); // the second matching read: the save is shown, then the live read
        Poll();
    }
    ~Module() {
        m->destroy(instance);
    }
    void PutNames() {
        h.PutRomfs("romfs:Mals/USen.Product.121.sarc.zs#ActorMsg/PouchContent.msbt",
                   Msbt({{"Item_Fruit_A_Name", u"Apple"}}));
    }
    Instance& In() {
        return *static_cast<Instance*>(instance);
    }
    // Ask the worker for a poll and wait (at most 1 s) until it finished.
    void Poll() {
        std::uint64_t generation;
        {
            std::scoped_lock lock{In().mutex};
            generation = ++In().requested;
        }
        In().wake.notify_all();
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
        while (std::chrono::steady_clock::now() < deadline) {
            {
                std::scoped_lock lock{In().mutex};
                if (In().completed >= generation)
                    return;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        assert(!"the worker did not poll within 1 s");
    }
    void Sample() {
        h.ClearPublished();
        m->sample(instance, &h.api);
    }
    bool Act(const char* name, std::int64_t arg) {
        return ext->on_action(instance, name, arg) == EDEN_DSMOD_TRUE;
    }
    std::int64_t Source() {
        Sample();
        assert(h.ints.at("totk.inv.source") == h.ints.at("totk.gear.source"));
        return h.ints.at("totk.inv.source");
    }
    std::vector<std::string> Lines() {
        std::scoped_lock lock{h.log_mutex};
        std::vector<std::string> out;
        for (const auto& l : h.logs)
            if (l.starts_with("totk live gamedata"))
                out.push_back(l);
        return out;
    }
    unsigned WorkerReads() {
        std::scoped_lock lock{h.memory_mutex};
        return h.worker_read_calls;
    }
};

} // namespace

int main() {
    const GmdKeys keys = KeysOf(Save());
    const totk::Snapshot save = totk::BuildSnapshot(Save());
    assert(keys.texts.size() == 11 && keys.ints.size() == 15); // every pouch of the fixture

    // The fixture as live GameData: the same tabs and gear as the save, effects included; no
    // write.
    {
        Host h;
        PutGameData(h, keys);
        const Got got = Read(h);
        assert(got.r == Result::ok && SameInv(*got.s, save) && h.write_calls == 0);
        assert(got.s->gear[0]->id == "Weapon_Spear_002" &&
               std::string(got.s->gear[0]->effect) == "Attack Up+");
    }
    // Live values win; an effect is the save's only where the save has the same id at that index.
    {
        GmdKeys k = keys;
        Of(k.ints, "Pouch.Material.Content.StockNum") = {9};
        Of(k.texts, "Pouch.Weapon.Content.Name")[2] = "Weapon_Spear_009";
        Of(k.ints, "Pouch.Bow.EquipIndex") = {-1};
        Host h;
        PutGameData(h, k);
        const Got got = Read(h);
        assert(got.r == Result::ok && got.s->tabs[4][0].value == 9);
        assert(got.s->gear[0]->id == "Weapon_Spear_009" && got.s->gear[0]->effect == nullptr);
        assert(got.s->tabs[0][0].effect && *got.s->tabs[0][0].effect == '\0'); // "None": ids match
        assert(got.s->gear_known[1] && !got.s->gear[1]);
        // Without a save: no effect at all.
        const Got bare = Read(h, nullptr);
        assert(bare.r == Result::ok && !bare.s->tabs[0][0].effect && !bare.s->tabs[2][0].effect);
    }
    // Each of these keeps the save.
    {
        totk::Offsets lay = kO;
        lay.pouch_index[totk::kWeapon] = 21; // the index check
        Host h;
        PutGameData(h, keys, lay);
        const Got got = Read(h);
        assert(got.r == Result::index && got.why == "Pouch.Weapon.Content.Name 21");
    }
    {
        GmdKeys k = keys; // a checked name's hash missing
        std::erase_if(k.texts, [](const auto& e) { return e.first == "Pouch.Bow.Content.Name"; });
        Host h;
        PutGameData(h, k);
        const Got got = Read(h);
        assert(got.r == Result::index && got.why == "Pouch.Bow.Content.Name hash missing");
    }
    { // an unchecked pouch (Food) missing is an empty pouch, as in the save path
        GmdKeys k = keys;
        std::erase_if(k.texts, [](const auto& e) { return e.first == "Pouch.Food.Content.Name"; });
        Host h;
        PutGameData(h, k);
        const Got got = Read(h);
        assert(got.r == Result::ok && got.s->tabs[5].empty());
    }
    { // an empty IntArray and String64Array, their data pointers null: ok, as the save reads them
        GmdKeys k = keys;
        Of(k.ints, "Pouch.Food.Content.LifeRecover").clear();
        Of(k.texts, "Pouch.Shield.Content.Combined.Name").clear();
        totk::SaveBuilder b;
        for (const auto& [key, v] : k.texts)
            b.String64Array(key, v);
        for (const auto& [key, v] : k.ints)
            b.IntArray(key, v);
        const auto same = totk::GameData::Parse(b.Build());
        Host h;
        const Gmd g = PutGameData(h, k);
        h.Put<std::uint64_t>(g.entry.at("Pouch.Food.Content.LifeRecover") + totk::kIntArrayData, 0);
        h.Put<std::uint64_t>(g.entry.at("Pouch.Shield.Content.Combined.Name") + totk::kS64aData, 0);
        const Got got = Read(h, &*same);
        assert(got.r == Result::ok && SameInv(*got.s, totk::BuildSnapshot(*same)) &&
               got.why == "lengths=3,1,1,2,2,1,1,1,1");
    }
    const auto broken = [&](const std::function<void(Host&, const Gmd&)>& change,
                            const totk::Offsets& o = kO) {
        Host h;
        const Gmd g = PutGameData(h, keys, o);
        change(h, g);
        return Read(h, &Save(), o).r;
    };
    const auto none = [](Host&, const Gmd&) {};
    // The IntArray values below 0x10000, wrapping past 2^64 (index 1 on at 0), an entry not
    // 8-aligned: every entry readable at the moved address.
    for (const std::uint64_t to : {std::uint64_t(0x8000), 0 - std::uint64_t(totk::kIntArrayEntry)})
        assert(broken([to](Host& h, const Gmd& g) { MoveValues(h, g, kO, to); }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) {
               MoveValues(h, g, kO, h.Get<std::uint64_t>(g.mgr + kO.int_table -
                                                         totk::kTableToValues) + 4);
           }) == Result::layout);
    { // the IntArray block not 8-aligned (its table 4 on)
        totk::Offsets o = kO;
        o.int_table += 4;
        assert(broken(none, o) == Result::layout);
    }
    { // the manager not 8-aligned, the gate, both blocks and both tables aligned
        totk::Offsets o = kO;
        o.int_table += 4;
        o.s64a_table += 4;
        o.gmd_loading.hop[1] += 4;
        Host h;
        h.Put<std::uint64_t>(Host::kMainBase + o.gmd_mgr.hop[0], 0xA0000004);
        PutGameData(h, keys, o);
        assert(Read(h, &Save(), o).r == Result::layout);
    }
    { // manager + s64a_table wraps past 2^64 onto a mapped low address
        totk::Offsets o = kO;
        o.s64a_table = 0x2000 - std::uint64_t(0xA0000000);
        Host h;
        h.Put<std::uint64_t>(Host::kMainBase + o.gmd_mgr.hop[0], 0xA0000000);
        PutGameData(h, keys, o);
        assert(Read(h, &Save(), o).r == Result::layout);
    }
    const auto element = [](const Gmd& g, const char* key, std::uint64_t i) {
        return g.data.at(key) + i * totk::kS64Element;
    };
    assert(broken([&](Host& h, const Gmd& g) { // a text without NUL
               for (std::uint64_t c = 0; c < 64; ++c)
                   h.Put<char>(element(g, "Pouch.Material.Content.Name", 0) + totk::kS64Text + c,
                               'A');
           }) == Result::layout);
    assert(broken([&](Host& h, const Gmd& g) { // the text and its pointer 4 bytes on, zeros at +0xC
               const std::uint64_t e = element(g, "Pouch.Weapon.Content.Name", 0);
               h.Put(e, e + totk::kS64Text + 4);
               for (std::uint64_t c = 0; c < 64; ++c)
                   h.Put<char>(e + totk::kS64Text + 4 + c, c < 16 ? "Weapon_Sword_001"[c] : 0);
               h.Put<std::uint32_t>(e + totk::kS64Text, 0);
           }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) { h.Put<std::uint8_t>(g.flags, 2); }) ==
           Result::loading);
    assert(broken([](Host& h, const Gmd& g) { // [[main+gmd_holder]] != [main+gmd_mgr]
               h.Put(h.Get<std::uint64_t>(Host::kMainBase + kO.gmd_holder.hop[0]), g.mgr + 8);
           }) == Result::gate_unknown);
    // A vtable outside main: the manager's, each block's, an entry's.
    for (const std::uint64_t at : {std::uint64_t(0), kO.int_table - totk::kTableToBlock,
                                   kO.s64a_table - totk::kTableToBlock})
        assert(broken([at](Host& h, const Gmd& g) {
                   h.Put<std::uint64_t>(g.mgr + at, Host::kMainBase + Host::kMainSize);
               }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) {
               h.Put<std::uint64_t>(g.entry.at("Pouch.Arrow.Content.StockNum"), 0x90000000);
           }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) { // an element array not 4-aligned (all readable)
               const std::uint64_t e = g.entry.at("Pouch.Arrow.Content.StockNum"),
                                   d = g.data.at("Pouch.Arrow.Content.StockNum");
               h.Put<std::uint32_t>(d + 8, 0);
               h.Put(e + totk::kIntArrayData, d + 2);
           }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) { // an element array below 0x10000 (readable)
               h.Put(g.entry.at("Pouch.Arrow.Content.StockNum") + totk::kIntArrayData,
                     std::uint64_t(0x8000));
               h.Put(std::uint64_t(0x8000), std::array<std::int32_t, 2>{50, 7});
           }) == Result::layout);
    assert(broken([](Host& h, const Gmd& g) { // a probe refusal past the names: index >= count
               h.Put<std::uint32_t>(g.mgr + kO.int_table + totk::kTableCount, 1);
           }) == Result::layout);
    // A pouch of 401 names publishes 400 items, as the save path does.
    {
        GmdKeys k = keys;
        auto& names = Of(k.texts, "Pouch.Material.Content.Name");
        auto& stock = Of(k.ints, "Pouch.Material.Content.StockNum");
        names.clear();
        stock.clear();
        totk::SaveBuilder b;
        for (int i = 0; i < 401; ++i) {
            names.push_back("Item_" + std::to_string(i));
            stock.push_back(i);
        }
        Host h;
        const Gmd g = PutGameData(h, k);
        h.memory.erase(element(g, "Pouch.Material.Content.Name", 400)); // never read
        const Got got = Read(h);
        b.String64Array("Pouch.Material.Content.Name", names);
        const auto saved = totk::BuildSnapshot(*totk::GameData::Parse(b.Build()));
        assert(got.r == Result::ok && got.s->tabs[4].size() == 400 && saved.tabs[4].size() == 400 &&
               got.s->tabs[4][399].id == "Item_399" && got.s->tabs[4][399].value == 399);
        assert(got.why == "lengths=3,1,1,2,2,401,1,1,1"); // the length before the 400 cut
    }
    // A save with fewer Weapon effects than names (its names the live ones): no effect past them.
    {
        totk::SaveBuilder b;
        b.String64Array("Pouch.Weapon.Content.Name", {"Weapon_Sword_001", "", "Weapon_Spear_002"})
            .EnumArray("Pouch.Weapon.Content.Effect.Type", {"None"});
        const auto short_save = totk::GameData::Parse(b.Build());
        Host h;
        PutGameData(h, keys);
        const Got got = Read(h, &*short_save);
        assert(got.r == Result::ok && got.s->tabs[0].size() == 2 && got.s->tabs[0][0].effect &&
               !got.s->tabs[0][1].effect); // [1]: the save's third weapon (the empty name skipped)
    }
    // Empty offsets with a fake GameDataMgr in memory: no host call.
    {
        Host h;
        PutGameData(h, keys);
        totk::Offsets no_text = kO, no_int = kO;
        no_text.s64a_table = 0;
        no_int.int_table = 0;
        assert(Read(h, &Save(), totk::Offsets{}).r == Result::off &&
               Read(h, &Save(), no_text).r == Result::off &&
               Read(h, &Save(), no_int).r == Result::off && h.MemoryCalls() == 0);
    }
    // A torn second pass (the last read: KeyItem's LifeRecover data).
    {
        GmdKeys k = keys;
        k.ints.emplace_back("Pouch.KeyItem.Content.LifeRecover", std::vector<std::int32_t>{1});
        Host h;
        PutGameData(h, k);
        const unsigned before = h.read_calls;
        assert(Read(h).r == Result::ok);
        h.corrupt_read = h.read_calls + (h.read_calls - before);
        assert(Read(h).r == Result::torn && Read(h).r == Result::ok);
    }

    // The module: the live fixture publishes the same keys as the save alone (1.2.0, which makes no
    // memory call with the same GameData in memory), with sources 2; no allocation in sample.
    {
        Module live(true, &keys), saved(false, &keys);
        assert(live.Lines() ==
               std::vector<std::string>{"totk live gamedata: ok lengths=3,1,1,2,2,1,1,1,1"});
        assert(live.Act("totk.ui.page", 0) && saved.Act("totk.ui.page", 0));
        for (int t = 0; t < totk::kTabs; ++t) {
            assert(live.Act("totk.inv.tab", t) && saved.Act("totk.inv.tab", t));
            const auto items = save.tabs[t].size();
            assert(!items || (live.Act("totk.inv.select", items - 1) &&
                              saved.Act("totk.inv.select", items - 1)));
            live.Sample();
            saved.Sample();
            for (const char* k : {"totk.inv.source", "totk.gear.source", "totk.loading"}) {
                assert(live.h.ints.at(k) == (k[5] == 'l' ? 1 : 2) && saved.h.ints.at(k) == 1);
                live.h.ints.erase(live.h.ints.find(k));
                saved.h.ints.erase(saved.h.ints.find(k));
            }
            assert(live.h.ints == saved.h.ints && live.h.texts == saved.h.texts &&
                   live.h.floats == saved.h.floats);
        }
        assert(saved.h.MemoryCalls() == 0 && saved.Lines().empty());
        for (int i = 0; i < kLoadSettle; ++i) // past the gate's "1 -> 0" line (logging allocates)
            live.m->sample(live.instance, &live.h.api);
        const long before = t_news;
        for (int i = 0; i < 1000; ++i)
            live.m->sample(live.instance, &live.h.api);
        assert(t_news == before && live.h.write_calls == 0 && live.h.batch_calls == 0);
    }
    // The worker's outcomes: a torn read and loading keep the live inventory, a layout failure
    // drops it (not for the session), the gate unknown too; one line per kind.
    {
        GmdKeys k = keys;
        k.ints.emplace_back("Pouch.KeyItem.Content.LifeRecover", std::vector<std::int32_t>{1});
        Of(k.texts, "Pouch.Material.Content.Name").push_back("Item_Fruit_B");
        Of(k.ints, "Pouch.Weapon.EquipIndex") = {0};
        Module w(true, &k);
        w.Sample(); // the live gear: the save equips Weapon_Spear_002
        assert(w.h.texts.at("totk.gear.weapon.id") == "Weapon_Sword_001");
        // Select checks the shown (live) tab: two Materials live, one in the save.
        assert(w.Act("totk.inv.tab", 4) && w.Act("totk.inv.select", 1) &&
               !w.Act("totk.inv.select", 2));
        const auto stock = [&] {
            assert(w.Act("totk.inv.tab", 4));
            w.Sample();
            return w.h.ints.at("totk.inv.0.value");
        };
        assert(w.Source() == 2 && stock() == 5);
        const std::uint64_t at = w.g.data.at("Pouch.Material.Content.StockNum");
        const unsigned start = w.WorkerReads();
        w.Poll();
        const unsigned per_poll = w.WorkerReads() - start;
        w.h.Put<std::int32_t>(at, 9);
        {
            std::scoped_lock lock{w.h.memory_mutex};
            w.h.corrupt_worker_read = w.h.worker_read_calls + per_poll;
        }
        w.Poll();
        assert(w.Source() == 2 && stock() == 5); // torn: the previous one
        w.Poll();
        assert(stock() == 9);
        w.h.Put<std::uint8_t>(w.g.flags, 2); // loading: kept
        w.h.Put<std::int32_t>(at, 10);
        w.Poll();
        assert(w.Source() == 2 && stock() == 9);
        w.h.Put<std::uint8_t>(w.g.flags, 0);
        const std::uint64_t block = w.g.mgr + kO.int_table - totk::kTableToBlock;
        w.h.Put<std::uint64_t>(block, 0x90000000);
        w.Poll();
        w.Poll();
        assert(w.Source() == 1 && stock() == 5); // the save's
        w.h.Put(block, w.g.vtable);
        w.Poll();
        assert(w.Source() == 2 && stock() == 10);
        // The holder's manager differs: the gate is unknown, the save shows.
        w.h.Put(w.h.Get<std::uint64_t>(Host::kMainBase + kO.gmd_holder.hop[0]), w.g.mgr + 8);
        w.Poll();
        assert(w.Source() == 1);
        const std::vector<std::string> lines{"totk live gamedata: ok lengths=3,1,1,2,2,2,1,1,1",
                                             "totk live gamedata: index check FAILED layout",
                                             "totk live gamedata: gate unknown"};
        assert(w.Lines() == lines);
    }
    // The live items get the game's names too: names loaded after the live inventory and while
    // the gate reads loading rename it, then the next live read is named.
    {
        Module w(true, &keys, kO, true);
        assert(w.Act("totk.inv.tab", 4) && w.Source() == 2 &&
               w.h.texts.at("totk.inv.sel.name") != "Apple");
        w.h.Put<std::uint8_t>(w.g.flags, 2);
        w.PutNames();
        w.Poll();
        assert(w.Source() == 2 && w.h.texts.at("totk.inv.sel.name") == "Apple" &&
               w.h.ints.at("totk.names.valid") == 1);
        w.h.Put<std::uint8_t>(w.g.flags, 0);
        w.Poll();
        assert(w.Source() == 2 && w.h.texts.at("totk.inv.sel.name") == "Apple");
    }
    // No save shown yet: no live read.
    {
        Module w(true, &keys, kO, false, false);
        assert(w.WorkerReads() == 0 && w.Lines().empty());
    }
    // A failed index check: the save, one line, and no more reads this session.
    {
        totk::Offsets lay = kO;
        lay.pouch_index[totk::kShield] = 17;
        Module w(true, &keys, lay);
        const std::vector<std::string> line{
            "totk live gamedata: index check FAILED Pouch.Shield.Content.Name 17"};
        assert(w.Source() == 1 && w.Lines() == line);
        const unsigned reads = w.WorkerReads();
        w.Poll();
        assert(w.WorkerReads() == reads && w.Source() == 1);
    }
    std::puts("totk_live_test: ok");
    return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
// The module's C ABI surface, compiled in from the module source.
#if defined(__GNUC__) && !defined(__clang__)
// The replaced operator new/delete below pair malloc with free; GCC cannot see that.
#pragma GCC diagnostic ignored "-Wmismatched-new-delete"
#endif
#include "0100F2C0115B6000.cpp"
#include "totk_test_host.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>

using totk::test::Caption;
using totk::test::FixtureSave;
using totk::test::Host;
using totk::test::SaveBuilder;
using totk::test::SaveDir;

// Every allocation is counted: per thread (sample runs on this one, the worker on its own) and
// live blocks overall (leak check). Create allocates the instance with nothrow new; that block is
// tracked so the test sees Destroy free it.
namespace {
std::atomic<long> g_live_blocks{0};
thread_local long t_news = 0, t_deletes = 0;
int g_nothrow_news = 0;
void* g_nothrow_live = nullptr;
} // namespace
void* operator new(std::size_t n) {
    if (void* p = std::malloc(n ? n : 1)) {
        ++t_news;
        ++g_live_blocks;
        return p;
    }
    throw std::bad_alloc();
}
void* operator new(std::size_t n, const std::nothrow_t&) noexcept {
    ++g_nothrow_news;
    ++t_news;
    ++g_live_blocks;
    return g_nothrow_live = std::malloc(n ? n : 1);
}
void operator delete(void* p) noexcept {
    if (!p)
        return;
    --g_live_blocks;
    ++t_deletes;
    if (p == g_nothrow_live)
        g_nothrow_live = nullptr;
    std::free(p);
}
void operator delete(void* p, std::size_t) noexcept {
    operator delete(p);
}
void operator delete(void* p, const std::nothrow_t&) noexcept {
    operator delete(p);
}

namespace {

const EdenDsmodModuleApi* M() {
    return eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
}
const EdenDsmodModuleSaveExtensions* SaveExt() {
    return eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH);
}

// Wait until a poll that started after poll request `generation` has finished. The deadline is
// well under the 2 s poll interval, so a lost wake-up fails instead of passing slowly.
void WaitPoll(Instance& in, std::uint64_t generation) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while (std::chrono::steady_clock::now() < deadline) {
        {
            std::scoped_lock lock{in.mutex};
            if (in.completed >= generation)
                return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    assert(!"the worker did not poll within 1 s");
}

// Ask the worker for a poll and wait for it.
void PollNow(void* instance) {
    auto& in = *static_cast<Instance*>(instance);
    std::uint64_t generation;
    {
        std::scoped_lock lock{in.mutex};
        generation = ++in.requested;
    }
    in.wake.notify_all();
    WaitPoll(in, generation);
}

// configure, then wait for the poll it asks for (so the polls after it are counted exactly).
void Configure(const EdenDsmodModuleSaveExtensions* se, void* instance, SaveDir& dir) {
    se->configure(instance, &dir.api);
    auto& in = *static_cast<Instance*>(instance);
    std::uint64_t generation;
    {
        std::scoped_lock lock{in.mutex};
        generation = in.requested;
    }
    WaitPoll(in, generation);
}

std::string Slot(int n, const char* file) {
    return "slot_0" + std::to_string(n) + "/" + file;
}

// Fixture save directory: slot 1 newest (manual), slot 0 older with other values.
void PutFixture(SaveDir& d) {
    d.Put(Slot(0, "caption.sav"), Caption(2024, 1, 1, 8, 0, false));
    d.Put(Slot(0, "progress.sav"), SaveBuilder().Int("PlayerStatus.Life", 4).Build());
    d.Put(Slot(1, "caption.sav"), Caption(2024, 1, 2, 8, 0, false));
    d.Put(Slot(1, "progress.sav"), FixtureSave().Build());
}

void SampleClean(const EdenDsmodModuleApi* m, void* instance, Host& h) {
    h.ClearPublished();
    m->sample(instance, &h.api);
}

} // namespace

int main() {
    // eden_dsmod_get_module: version and hash gate.
    assert(!eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION + 1, EDEN_DSMOD_MODULE_ABI_HASH));
    assert(!eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH ^ 1));
    const EdenDsmodModuleApi* m = M();
    assert(m);
    assert(m->abi_version == EDEN_DSMOD_MODULE_ABI_VERSION);
    assert(m->abi_hash == EDEN_DSMOD_MODULE_ABI_HASH);
    assert(m->struct_size == sizeof(EdenDsmodModuleApi));
    assert(m->title_id == 0x0100F2C0115B6000ULL);
    assert(m->capabilities == 0);
    assert(m->name && *m->name);
    assert(m->supports_build && m->create && m->destroy && m->sample && m->tick);

    // eden_dsmod_get_save_extensions: version and hash gate, then the table the host checks.
    assert(!eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION + 1, EDEN_DSMOD_SAVE_EXT_HASH));
    assert(!eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH ^ 1));
    const EdenDsmodModuleSaveExtensions* se = SaveExt();
    assert(se && se->version == EDEN_DSMOD_SAVE_EXT_VERSION &&
           se->struct_size == sizeof(EdenDsmodModuleSaveExtensions) &&
           se->abi_hash == EDEN_DSMOD_SAVE_EXT_HASH && se->configure);

    // supports_build: only the exact target string.
    assert(m->supports_build(totk::kTargetBuild));
    assert(!m->supports_build(nullptr));
    assert(!m->supports_build(""));
    assert(!m->supports_build("6F32C68DD3BC7D77"));
    assert(!m->supports_build("277178B7DBA1B6D4949A84778D4ABC58B31B34F5000000000000000000000000"));
    assert(!m->supports_build("8E9978D50BDD20B4C8395A106C27FFDE00000000000000000000000000000000"));

    // create: every gate. A fresh host per case; none of them may touch guest memory.
    const auto refused = [m](void (*mutate)(EdenDsmodHostApi&)) {
        Host h;
        mutate(h.api);
        const bool ok = m->create(&h.api, "{}") == nullptr;
        return ok && h.MemoryCalls() == 0;
    };
    assert(!m->create(nullptr, "{}"));
    assert(refused([](EdenDsmodHostApi& a) { a.abi_hash ^= 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.abi_version += 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.struct_size = sizeof(EdenDsmodHostApi) - 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.title_id = 0x01007EF00011E000ULL; }));
    assert(refused([](EdenDsmodHostApi& a) { a.build_id[31] ^= 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.build_id[0] ^= 0x80; }));

    assert(g_nothrow_news == 0); // refused creates allocate nothing
    Host h;
    void* instance = m->create(&h.api, nullptr);
    assert(instance && g_nothrow_news == 1 && g_nothrow_live == instance);
    auto& in = *static_cast<Instance*>(instance);

    // No save extension yet: ready, every domain invalid, no memory access, no text.
    PollNow(instance);
    m->sample(instance, &h.api);
    m->tick(instance, &h.api);
    SampleClean(m, instance, h);
    assert(h.MemoryCalls() == 0);
    const Host::Ints empty{
        {"totk.ready", 1},        {"totk.ui.page", 0},       {"totk.inv.tab", 0},
        {"totk.inv.sel", 0},      {"totk.status.valid", 0},  {"totk.map.valid", 0},
        {"totk.gear.valid", 0},   {"totk.ability.valid", 0}, {"totk.inv.valid", 0}};
    assert(h.ints == empty && h.texts.empty() && h.floats.empty());

    // configure ignores a null or mismatched save API. (Save dirs outlive every instance.)
    SaveDir d, none, cycles;
    PutFixture(d);
    PutFixture(cycles);
    se->configure(nullptr, &d.api);
    se->configure(instance, nullptr);
    for (int i = 0; i < 4; ++i) {
        EdenDsmodHostSaveApi bad = d.api;
        if (i == 0)
            bad.version += 1;
        if (i == 1)
            bad.abi_hash ^= 1;
        if (i == 2)
            bad.struct_size -= 1;
        if (i == 3)
            bad.read_save_file = nullptr;
        se->configure(instance, &bad);
    }
    PollNow(instance);
    assert(d.Reads(Slot(1, "caption.sav")) == 0);
    SampleClean(m, instance, h);
    assert(h.ints == empty);

    // An empty save directory: no slots, nothing published.
    Configure(se, instance, none);
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.ints == empty && h.texts.empty());

    // The fixture save: the newest slot (1) is taken once two reads in a row match.
    Configure(se, instance, d); // configure's own poll: the first read
    assert(d.Reads(Slot(1, "progress.sav")) == 1);
    SampleClean(m, instance, h);
    assert(h.ints == empty);
    PollNow(instance);
    assert(d.Reads(Slot(1, "progress.sav")) == 2 && d.Reads(Slot(0, "progress.sav")) == 0);
    SampleClean(m, instance, h);
    const Host::Ints page0{
        {"totk.ready", 1},
        {"totk.ui.page", 0},
        {"totk.inv.tab", 0},
        {"totk.inv.sel", 0},
        {"totk.status.valid", 1},
        {"totk.status.source", 1},
        {"totk.status.hearts", 12},
        {"totk.status.max_hearts", 40},
        {"totk.status.max_stamina", 1000},
        {"totk.status.rupees", 4321},
        {"totk.status.max_battery", 150},
        {"totk.map.valid", 1},
        {"totk.map.source", 1},
        {"totk.gear.valid", 1},
        {"totk.gear.source", 1},
        {"totk.gear.weapon.present", 1},
        {"totk.gear.weapon.life", 12},
        {"totk.gear.weapon.fuse", 1},
        {"totk.gear.bow.present", 1},
        {"totk.gear.bow.life", 40},
        {"totk.gear.shield.present", 0},
        {"totk.gear.arrow.present", 1},
        {"totk.gear.arrow.count", 7},
        {"totk.ability.valid", 1},
        {"totk.ability.source", 1},
        {"totk.ability.ultrahand.owned", 1},
        {"totk.ability.fuse.owned", 0},
        {"totk.ability.ascend.owned", 1},
        {"totk.ability.recall.owned", 0},
        {"totk.ability.autobuild.owned", 0},
        {"totk.ability.camera.owned", 1},
        {"totk.ability.travel.owned", 1},
        {"totk.inv.valid", 1},
        {"totk.inv.source", 1},
        {"totk.inv.tab0.count", 2},
        {"totk.inv.tab1.count", 3},
        {"totk.inv.tab2.count", 1},
        {"totk.inv.tab3.count", 2},
        {"totk.inv.tab4.count", 1},
        {"totk.inv.tab5.count", 1},
        {"totk.inv.tab6.count", 1},
        {"totk.inv.tab7.count", 1},
        {"totk.inv.count", 2}};
    assert(h.ints == page0);
    const Host::Texts page0_text{
        {"totk.gear.weapon.id", "Weapon_Spear_002"},
        {"totk.gear.weapon.name", "Weapon_Spear_002"},
        {"totk.gear.weapon.icon", "module:totk:icon/Weapon_Spear_002"},
        {"totk.gear.weapon.fuse_name", "Item_Ruby"},
        {"totk.gear.weapon.fuse_icon", "module:totk:icon/Item_Ruby"},
        {"totk.gear.weapon.effect", "Attack Up+"},
        {"totk.gear.bow.id", "Weapon_Bow_001"},
        {"totk.gear.bow.name", "Weapon_Bow_001"},
        {"totk.gear.bow.icon", "module:totk:icon/Weapon_Bow_001"},
        {"totk.gear.bow.effect", "Rapid Fire"},
        {"totk.gear.arrow.id", "FireArrow"},
        {"totk.gear.arrow.name", "FireArrow"},
        {"totk.gear.arrow.icon", "module:totk:icon/FireArrow"}};
    assert(h.texts == page0_text);
    assert((h.floats == Host::Floats{{"totk.map.x", 100.5}, {"totk.map.y", 300.25}}));
    assert(h.MemoryCalls() == 0);

    // The inventory page publishes the current tab's list and the selection.
    in.page = kPageInventory;
    in.tab = 1;
    in.sel = 2;
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.inv.count") == 3);
    assert(h.texts.at("totk.inv.0.name") == "Weapon_Bow_001" && h.ints.at("totk.inv.0.kind") == 1 &&
           h.ints.at("totk.inv.0.value") == 40 && h.ints.at("totk.inv.0.equipped") == 1);
    assert(h.texts.at("totk.inv.1.icon") == "module:totk:icon/NormalArrow" &&
           h.ints.at("totk.inv.1.value") == 50 && h.ints.at("totk.inv.1.equipped") == 0);
    assert(h.texts.at("totk.inv.2.name") == "FireArrow" && h.ints.at("totk.inv.2.equipped") == 1);
    assert(!h.ints.contains("totk.inv.3.kind") && !h.texts.contains("totk.inv.0.fuse_icon"));
    assert(h.texts.at("totk.inv.sel.name") == "FireArrow" && h.ints.at("totk.inv.sel.kind") == 2 &&
           h.ints.at("totk.inv.sel.value") == 7 && !h.ints.contains("totk.inv.sel.hearts") &&
           !h.texts.contains("totk.inv.sel.effect") && !h.texts.contains("totk.inv.sel.fuse_name"));
    in.tab = 0;
    in.sel = 1;
    SampleClean(m, instance, h);
    assert(h.texts.at("totk.inv.1.fuse_icon") == "module:totk:icon/Item_Ruby");
    assert(h.texts.at("totk.inv.sel.fuse_name") == "Item_Ruby" &&
           h.texts.at("totk.inv.sel.effect") == "Attack Up+");
    in.tab = 5;
    in.sel = 0;
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.inv.sel.hearts") == 20 && h.ints.at("totk.inv.sel.value") == 1);
    in.tab = 3; // armor: kind 0, no value, equipped not known
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.inv.0.kind") == 0 && !h.ints.contains("totk.inv.0.value") &&
           !h.ints.contains("totk.inv.0.equipped"));
    in.tab = 4; // materials: no EquipIndex, so equipped not known
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.inv.0.value") == 5 && !h.ints.contains("totk.inv.0.equipped"));
    in.sel = 9; // past the list: no selection keys
    in.tab = 99; // out of range: counts only, no list
    SampleClean(m, instance, h);
    assert(!h.texts.contains("totk.inv.sel.name") && !h.ints.contains("totk.inv.count") &&
           h.ints.at("totk.inv.tab7.count") == 1 && !h.texts.contains("totk.inv.0.name"));
    in.tab = 0;
    in.sel = 0;
    in.page = 0; // other pages: no list
    SampleClean(m, instance, h);
    assert(!h.texts.contains("totk.inv.0.name") && h.ints.at("totk.inv.count") == 2);

    // Unchanged captions: no re-read.
    PollNow(instance);
    PollNow(instance);
    assert(d.Reads(Slot(1, "progress.sav")) == 2);

    // A newer slot whose progress.sav is missing, then garbage: the previous snapshot stays.
    // Garbage is read 8 times at most, until its caption date or file size changes.
    const std::string p4 = Slot(4, "progress.sav");
    d.Put(Slot(4, "caption.sav"), Caption(2024, 2, 1, 8, 0, true));
    PollNow(instance);
    assert(d.Reads(p4) == 0);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    d.Put(p4, std::vector<std::uint8_t>(64, 0x5A));
    for (int i = 0; i < 10; ++i)
        PollNow(instance);
    assert(d.Reads(p4) == 8);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    // A new size: tried again. It changes size between the size query and the read (rejected),
    // then reads the same twice and replaces the shown save.
    d.Put(p4, SaveBuilder().Int("PlayerStatus.Life", 33).Build());
    d.GrowAfterQuery(p4);
    PollNow(instance);
    assert(d.Reads(p4) == 9);
    PollNow(instance);
    assert(d.Reads(p4) == 10);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    PollNow(instance);
    assert(d.Reads(p4) == 11);
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.status.hearts") == 33 && !h.ints.contains("totk.status.max_hearts"));
    // No Pouch.<P>.EquipIndex in this save: equipped is not known, so .present is absent.
    for (const char* slot : kGearSlotNames)
        assert(!h.ints.contains(std::string("totk.gear.") + slot + ".present"));
    assert(h.ints.at("totk.gear.valid") == 1 && h.ints.at("totk.map.valid") == 0);
    assert(h.ints.at("totk.inv.tab0.count") == 0 && h.texts.empty() && h.floats.empty());
    PollNow(instance);
    assert(d.Reads(p4) == 11); // shown: no re-read

    // Back to slot 1 (a manual save on the same minute beats the autosave), whose caption is
    // written before its progress.sav: the first read sees the old file, then the new one lands
    // (same size, in place). The new content must win, not the old one.
    std::weak_ptr<const totk::Snapshot> old_snapshot;
    std::shared_ptr<const totk::Snapshot> held; // what a sample holds across the swap
    {
        std::scoped_lock lock{in.mutex};
        held = in.snapshot;
        old_snapshot = held;
    }
    d.Put(Slot(1, "caption.sav"), Caption(2024, 2, 1, 8, 0, false));
    PollNow(instance); // reads slot 1's old progress.sav (the fixture with the spear)
    const auto weapon_sword = FixtureSave(0).Build();
    assert(weapon_sword.size() == FixtureSave().Build().size());
    d.Put(Slot(1, "progress.sav"), weapon_sword);
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.status.hearts") == 33); // nothing taken yet
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.texts.at("totk.gear.weapon.id") == "Weapon_Sword_001");
    // The worker keeps the replaced snapshot for one more swap, so the sample's copy is not the
    // last owner: releasing it here frees nothing on this thread.
    {
        const long deletes = t_deletes;
        held.reset();
        assert(t_deletes == deletes && !old_snapshot.expired());
    }
    // Back to the fixture (a newer caption); the worker frees the snapshot retired above.
    d.Put(Slot(1, "caption.sav"), Caption(2024, 2, 1, 8, 1, false));
    d.Put(Slot(1, "progress.sav"), FixtureSave().Build());
    PollNow(instance);
    PollNow(instance);
    assert(old_snapshot.expired());
    SampleClean(m, instance, h);
    assert(h.ints == page0 && h.texts == page0_text);

    // A failed read between two matching ones (same size): the match does not count, because
    // the failed read sits between them; the next matching read is taken.
    const std::string p2 = Slot(2, "progress.sav");
    d.Put(p2, SaveBuilder().Int("PlayerStatus.Life", 44).Build());
    d.Put(Slot(2, "caption.sav"), Caption(2024, 3, 1, 8, 0, false));
    PollNow(instance); // read 1: ok
    d.FailNextRead(p2);
    PollNow(instance); // read 2: fails
    PollNow(instance); // read 3: ok, equals read 1, but not consecutive
    assert(d.Reads(p2) == 3);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    PollNow(instance); // read 4: equals read 3
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.status.hearts") == 44);
    // Back to the fixture.
    d.Put(Slot(1, "caption.sav"), Caption(2024, 3, 1, 8, 1, false));
    PollNow(instance);
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.ints == page0 && h.texts == page0_text);

    // CA10: no allocation in 1000 samples on each page with the full fixture snapshot.
    for (int page = 0; page < 4; ++page) {
        in.page = page;
        in.tab = 1;
        in.sel = 1;
        m->sample(instance, &h.api); // the host's maps already hold every key
        const long before = t_news;
        for (int i = 0; i < 1000; ++i)
            m->sample(instance, &h.api);
        assert(t_news == before);
    }
    in.page = 0;
    in.tab = 0;
    in.sel = 0;
    // CA7c: the whole save path never touched guest memory.
    assert(h.read_calls == 0 && h.is_mapped_calls == 0 && h.write_calls == 0);

    // Null instance or host: nothing happens, nothing crashes.
    m->sample(nullptr, &h.api);
    m->sample(instance, nullptr);
    m->tick(nullptr, nullptr);
    m->tick(instance, nullptr);
    m->destroy(nullptr);

    // A throwing publish callback does not escape sample.
    h.throw_on_publish = true;
    m->sample(instance, &h.api);
    h.throw_on_publish = false;

    { // A valid live status beats the save (test-only synthetic offsets over fake memory).
        Host v;
        in.offsets = {0x1000, 0x10, 0x14, 0x18, 0x1C, 0x20};
        constexpr std::uint64_t root = 0x90000000;
        v.Put<std::uint64_t>(Host::kMainBase + 0x1000, root);
        const std::int32_t values[] = {7, 40, 900, 1000, 4321};
        for (int i = 0; i < 5; ++i)
            v.Put<std::int32_t>(root + 0x10 + 4 * i, values[i]);
        m->sample(instance, &v.api);
        const Host::Ints live{
            {"totk.status.valid", 1},     {"totk.status.source", 2},
            {"totk.status.hearts", 7},    {"totk.status.max_hearts", 40},
            {"totk.status.stamina", 900}, {"totk.status.max_stamina", 1000},
            {"totk.status.rupees", 4321}, {"totk.status.max_battery", 150}};
        for (const auto& [key, value] : live)
            assert(v.ints.at(key) == value);
        assert(v.write_calls == 0);
        in.offsets = {};
    }

    m->destroy(instance);
    assert(g_nothrow_live == nullptr); // destroy freed the instance

    // 100 create/destroy cycles with a save to read: no hang, no leak. Some destroys land while
    // the worker is mid-read. Cycle -1 warms up the save dir's counters before the leak count.
    long live_before = 0;
    for (int i = -1; i < 100; ++i) {
        if (i == 0)
            live_before = g_live_blocks;
        Host c;
        void* cycle = m->create(&c.api, "{}");
        assert(cycle);
        se->configure(cycle, &cycles.api);
        if (i % 10 == 0 || i < 0) {
            PollNow(cycle);
            PollNow(cycle);
        }
        m->sample(cycle, &c.api);
        // destroy wakes the worker: it must not wait out the 2 s poll interval.
        const auto start = std::chrono::steady_clock::now();
        m->destroy(cycle);
        assert(std::chrono::steady_clock::now() - start < std::chrono::milliseconds(500));
        assert(g_nothrow_live == nullptr);
    }
    assert(g_live_blocks == live_before);

    std::puts("totk_module_test: ok");
    return 0;
}

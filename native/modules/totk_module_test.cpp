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
thread_local long t_news = 0;
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

// Ask the worker for a poll and wait until one that started after the request has finished.
void PollNow(void* instance) {
    auto& in = *static_cast<Instance*>(instance);
    std::uint64_t generation;
    {
        std::scoped_lock lock{in.mutex};
        generation = ++in.requested;
    }
    in.wake.notify_all();
    for (int i = 0; i < 30000; ++i) {
        {
            std::scoped_lock lock{in.mutex};
            if (in.completed >= generation)
                return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    assert(!"the worker did not poll within 30 s");
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
    se->configure(instance, &none.api);
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.ints == empty && h.texts.empty());

    // The fixture save: the newest slot (1) is read once and published.
    se->configure(instance, &d.api);
    PollNow(instance);
    assert(d.Reads(Slot(1, "progress.sav")) == 1 && d.Reads(Slot(0, "progress.sav")) == 0);
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
    in.tab = 3; // armor: kind 0, no value
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.inv.0.kind") == 0 && !h.ints.contains("totk.inv.0.value"));
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
    assert(d.Reads(Slot(1, "progress.sav")) == 1);

    // A newer slot whose progress.sav is missing, then garbage, then changes size between the
    // size query and the read: each keeps the previous snapshot (and is retried every poll).
    d.Put(Slot(4, "caption.sav"), Caption(2024, 2, 1, 8, 0, true));
    PollNow(instance);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    d.Put(Slot(4, "progress.sav"), std::vector<std::uint8_t>(64, 0x5A));
    PollNow(instance);
    assert(d.Reads(Slot(4, "progress.sav")) == 1);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    d.Put(Slot(4, "progress.sav"), SaveBuilder().Int("PlayerStatus.Life", 33).Build());
    {
        std::scoped_lock lock{d.mutex};
        d.grow_after_query = Slot(4, "progress.sav");
    }
    PollNow(instance);
    assert(d.Reads(Slot(4, "progress.sav")) == 2);
    SampleClean(m, instance, h);
    assert(h.ints == page0);
    // Next poll reads it (the file no longer changes): the new save replaces the old one.
    PollNow(instance);
    assert(d.Reads(Slot(4, "progress.sav")) == 3);
    SampleClean(m, instance, h);
    assert(h.ints.at("totk.status.hearts") == 33 && !h.ints.contains("totk.status.max_hearts"));
    assert(h.ints.at("totk.gear.weapon.present") == 0 && h.ints.at("totk.map.valid") == 0);
    assert(h.ints.at("totk.inv.tab0.count") == 0 && h.texts.empty() && h.floats.empty());
    // Back to the fixture as the newest (a manual save on the same minute beats the autosave).
    d.Put(Slot(1, "caption.sav"), Caption(2024, 2, 1, 8, 0, false));
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
        if (i % 10 == 0 || i < 0)
            PollNow(cycle);
        m->sample(cycle, &c.api);
        m->destroy(cycle);
        assert(g_nothrow_live == nullptr);
    }
    assert(g_live_blocks == live_before);

    std::puts("totk_module_test: ok");
    return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Travel (docs/PLAN.md §A2): the destination list (totk_travel.h Load, Apply), the GameData hash
// probe (totk_status.h FindHash), and, through the module's ABI over a fake 1.2.1 GameDataMgr,
// go, restore, the 60-tick watch, the T8c report and destroy. Compiled in from the module source
// like totk_module_test, which sits at CA9's 64 KiB file cap.
#if defined(__GNUC__) && !defined(__clang__)
// The replaced operator new/delete below pair malloc with free; GCC cannot see that.
#pragma GCC diagnostic ignored "-Wmismatched-new-delete"
#endif
#include "0100F2C0115B6000.cpp"
#include "totk_test_host.h"

#include <cmath>
#include <cstdlib>
#include <limits>

// CA10 on the map page: allocations on this (the sampling) thread, as in totk_module_test.
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
using totk::travel::Bits;
using totk::travel::Dest;
using totk::travel::ToBits;
namespace byml = totk::byml;
const auto& kO = totk::kOffsets121;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
constexpr float kInf = std::numeric_limits<float>::infinity();

byml::Node Place(const std::string& name, std::vector<byml::Node> trans) {
    return Dict({{"InstanceID", Arr({})},
                 {"LocationName", Str("Work/Location/" + name + ".gyml")},
                 {"Trans", Arr(std::move(trans))}});
}
byml::Node V(float x, float y, float z) {
    return Arr({Num(x), Num(y), Num(z)});
}

// The fixture romfs: MainField with two Trans in one shrine (Surface and Sky), a locked shrine,
// an open shrine at y 3499 and a tower the save does not know; MinusField with one lightroot;
// Location.msbt names two of them.
void PutPlaces(Host& h) {
    h.PutRomfs(totk::travel::kFiles[0],
               BymlWriter{}.Write(Dict(
                   {{"Shrine", Arr({Place("Dungeon001", {V(100, 50, -200), V(110, 1200, -210)}),
                                    Place("Dungeon002", {V(300, 20, 400)}),
                                    Place("Dungeon003", {V(3000, 3499, 1000)})})},
                    {"Tower", Arr({Place("Tower01", {Arr({Int(1000), Int(300), Int(-1000)})})})},
                    {"Stable", Arr({Place("Stable001", {V(1, 2, 3)})})}})));
    h.PutRomfs(totk::travel::kFiles[1],
               BymlWriter{}.Write(
                   Dict({{"CheckPoint", Arr({Place("CheckPoint001", {V(-4321.98779f, -989.654297f, -2345.67896f)})})}})));
    h.PutRomfs("romfs:Mals/USen.Product.121.sarc.zs", {1});
    h.PutRomfs("romfs:Mals/USen.Product.121.sarc.zs#LocationMsg/Location.msbt",
               Msbt({{"Dungeon001", u"First Shrine"}, {"CheckPoint001", u"Root One"}}));
}

SaveBuilder TravelSave(bool owned = true) {
    SaveBuilder b = FixtureSave();
    b.Enum("DungeonState.Dungeon001", "Open")
        .Enum("DungeonState.Dungeon002", "Hidden")
        .Enum("DungeonState.Dungeon003", "Clear")
        .Enum("ArrivalPointState.CheckPoint001", "Open");
    if (!owned)
        b.Bool("IsGet.Obj_Warp", false);
    return b;
}

const Bits kOld = ToBits(-1234.5f, 150.f, 2345.5f);
constexpr std::uint64_t kLen = 0x2F8; // the lead for medallion_len (T8c decides)
constexpr std::int32_t kPlacedIndex = 7;

struct Options {
    bool v121 = true, write = true, layout = true, owned = true, log = true;
    int re = 0; // user:totk-re: 0 absent, 1 "1", 2 empty
};

// Ask the worker for a poll and wait (at most 2 s) until it finished.
void PollNow(Instance& in) {
    std::uint64_t generation;
    {
        std::scoped_lock lock{in.mutex};
        generation = ++in.requested;
    }
    in.wake.notify_all();
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    for (;;) {
        {
            std::scoped_lock lock{in.mutex};
            if (in.completed >= generation)
                return;
        }
        assert(std::chrono::steady_clock::now() < deadline);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

// A 1.2.1 module with the fixture romfs and save, the write extension and a GameDataMgr: the
// loading gate, the medallion chain (P in its own block, not base + 0x300) holding kOld, the
// Vector3Array table with WarpMarkerData.Pos at index 10, the IntArray table with
// WarpMarkerData.Index at index 2 (Index[0] = kPlacedIndex), and u32 1 at base + kLen.
struct World {
    Host h;
    SaveDir dir;
    void* instance = nullptr;
    const EdenDsmodModuleApi* m =
        eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
    const EdenDsmodModuleExtensions* ext =
        eden_dsmod_get_extensions(EDEN_DSMOD_EXT_VERSION, EDEN_DSMOD_EXT_HASH);
    std::uint64_t mgr = 0, base = 0, p = 0, idata = 0;

    explicit World(Options opt = {}) {
        if (opt.v121)
            std::memcpy(h.api.build_id, totk::kBuild121Bytes.data(), 32);
        PutPlaces(h);
        if (opt.re)
            h.PutRomfs("user:totk-re",
                       opt.re == 1 ? std::vector<std::uint8_t>{'1'} : std::vector<std::uint8_t>{});
        if (opt.layout)
            Layout();
        dir.Put("slot_00/caption.sav", Caption(2024, 1, 2, 8, 0, false));
        dir.Put("slot_00/progress.sav", TravelSave(opt.owned).Build());
        if (!opt.log) {
            h.api.log = nullptr;
            h.romfs.erase(totk::travel::kFiles[1]); // Load's "missing" line then meets the null log
        }
        instance = m->create(&h.api, "{}");
        assert(instance);
        if (opt.write)
            eden_dsmod_get_write_extensions(EDEN_DSMOD_WRITE_EXT_VERSION, EDEN_DSMOD_WRITE_EXT_HASH)
                ->configure(instance, &h.write);
        eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH)
            ->configure(instance, &dir.api);
        for (int i = 0; i < 20 && !Ready(); ++i)
            Poll();
        assert(Ready());
        In().offsets.medallion_len = kLen; // T8c's field, set here so the trigger runs
    }
    ~World() {
        if (instance)
            m->destroy(instance);
    }
    Instance& In() {
        return *static_cast<Instance*>(instance);
    }
    void Poll() {
        PollNow(In());
    }
    bool Ready() {
        std::scoped_lock lock{In().mutex};
        return In().snapshot && !In().snapshot->travel.empty();
    }
    void Layout() {
        const std::uint64_t main = h.api.main_base;
        PutLoading(h);
        p = PutChain(h, main, kO.medallion);
        mgr = h.Get<std::uint64_t>(main + kO.medallion.hop[0]);
        base = h.Get<std::uint64_t>(mgr + kO.medallion.hop[1]);
        h.Put(p, kOld);
        h.Put<std::uint32_t>(base + kLen, 1);
        PutTable(h, mgr + kO.v3a_table, 12, 16, {{totk::travel::kWarpPos, 10}});
        PutTable(h, mgr + kO.int_table, 4, 8, {{totk::travel::kWarpIndex, 2}});
        const std::uint64_t values = Block(h);
        h.Put(mgr + kO.int_table - totk::kTableToValues, values);
        idata = Block(h);
        h.Put<std::uint32_t>(values + 2 * totk::kIntArrayEntry + totk::kIntArrayLen, 1);
        h.Put(values + 2 * totk::kIntArrayEntry + totk::kIntArrayData, idata);
        h.Put(idata, kPlacedIndex);
    }
    // n runtime ticks: sample, then tick.
    void Run(int n = 1) {
        for (int i = 0; i < n; ++i) {
            m->sample(instance, &h.api);
            m->tick(instance, &h.api);
        }
    }
    void Settle() {
        Run(kLoadSettle);
        assert(In().loading == 0);
    }
    bool Act(const char* name, std::int64_t arg = 0) {
        return ext->on_action(instance, name, arg) == EDEN_DSMOD_TRUE;
    }
    std::string Msg() {
        return In().msg;
    }
    // Select `dest`, tap go until it is queued (the first tap asks for a manual save), and run the
    // two ticks of precondition 4. Returns whether a go was queued.
    bool Go(int dest = 0) {
        if (!Act("totk.travel.select", dest))
            return false;
        bool queued = Act("totk.travel.go");
        if (!queued && Msg() == "Make a manual save in game, then tap again")
            queued = Act("totk.travel.go");
        if (queued)
            Run(2);
        return queued;
    }
    // Log lines starting with `prefix`, each with its level.
    std::vector<std::pair<std::string, std::uint32_t>> Lines(std::string_view prefix) {
        std::scoped_lock lock{h.log_mutex};
        std::vector<std::pair<std::string, std::uint32_t>> out;
        for (std::size_t i = 0; i < h.logs.size(); ++i)
            if (h.logs[i].starts_with(prefix))
                out.emplace_back(h.logs[i], h.levels[i]);
        return out;
    }
    Bits AtP() {
        return h.Get<Bits>(p);
    }
};

std::vector<std::uint8_t> BitsBytes(const Bits& b) {
    std::vector<std::uint8_t> v(12);
    std::memcpy(v.data(), b.data(), 12);
    return v;
}
std::vector<std::uint8_t> U64Bytes(std::uint64_t x) {
    std::vector<std::uint8_t> v(8);
    std::memcpy(v.data(), &x, 8);
    return v;
}
const std::string kMovedFrom = "totk travel: medallion moved from ";
// Dest 0 (Dungeon001's first Trans) plus the arrival hover.
const Bits kDest = ToBits(100, 52, -200);

// The one batch go or restore sends: ops 1-3 identity at main+0x4721B98, mgr+0x438, base+0x300;
// op 4 at P from `expect` to `value`.
void AssertBatch(World& w, const std::vector<Host::Op>& ops, std::uint64_t base, std::uint64_t p,
                 const Bits& expect, const Bits& value) {
    assert(ops.size() == 4);
    const std::uint64_t slots[] = {w.h.api.main_base + 0x4721B98, w.mgr + 0x438, base + 0x300};
    const std::uint64_t ptrs[] = {w.mgr, base, p};
    for (int i = 0; i < 3; ++i)
        assert(ops[i].address == slots[i] && ops[i].expect == U64Bytes(ptrs[i]) &&
               ops[i].value == ops[i].expect);
    assert(ops[3].address == p && p != base + 0x300 && ops[3].expect == BitsBytes(expect) &&
           ops[3].value == BitsBytes(value));
}

} // namespace

int main() {
    // InWorld: each bound on both sides, NaN and infinity.
    {
        const auto below = [](float f) { return std::nextafter(f, -kInf); };
        const auto above = [](float f) { return std::nextafter(f, kInf); };
        assert(totk::InWorld(-6000, -1500, -5000) && totk::InWorld(6000, 3500, 5000));
        assert(!totk::InWorld(below(-6000), 0, 0) && !totk::InWorld(above(6000), 0, 0));
        assert(!totk::InWorld(0, below(-1500), 0) && !totk::InWorld(0, above(3500), 0));
        assert(!totk::InWorld(0, 0, below(-5000)) && !totk::InWorld(0, 0, above(5000)));
        assert(!totk::InWorld(kNaN, 0, 0) && !totk::InWorld(0, kNaN, 0) &&
               !totk::InWorld(0, 0, kNaN));
        assert(!totk::InWorld(kInf, 0, 0) && !totk::InWorld(0, -kInf, 0));
    }
    // The vec3 format: the same floats give the same text, one ulp apart a different one.
    {
        const auto a = totk::travel::Format(ToBits(-1234.5f, 0.1f, 2345.25f));
        assert(std::string(a.s) == "x=-1234.5 y=0.100000001 z=2345.25");
        assert(std::string(a.s) == totk::travel::Format(ToBits(-1234.5f, 0.1f, 2345.25f)).s);
        assert(std::string(a.s) !=
               totk::travel::Format(ToBits(-1234.5f, std::nextafter(0.1f, 1.f), 2345.25f)).s);
    }

    // The destination list: one Dest per valid Trans, in file and group order (Stable is not
    // read), names from Location.msbt (else the label), MinusField on the Depths; then the save's
    // unlock state (DungeonState Open/Clear 1, Hidden 0; an unknown tower -1).
    {
        Host h;
        PutPlaces(h);
        auto list = totk::travel::Load(h.api, 121);
        assert(h.logs.empty());
        const auto g = totk::GameData::Parse(TravelSave().Build());
        list = totk::travel::Apply(std::move(list), *g);
        struct Want {
            const char* label;
            const char* name;
            int kind, layer;
            float x, y, z;
            int open;
        };
        const Want want[] = {{"Dungeon001", "First Shrine", 0, 0, 100, 50, -200, 1},
                             {"Dungeon001", "First Shrine", 0, 1, 110, 1200, -210, 1},
                             {"Dungeon002", "", 0, 0, 300, 20, 400, 0},
                             {"Dungeon003", "", 0, 1, 3000, 3499, 1000, 1},
                             {"Tower01", "", 2, 0, 1000, 300, -1000, -1},
                             {"CheckPoint001", "Root One", 1, 2, -4321.98779f, -989.654297f, -2345.67896f, 1}};
        assert(list.size() == std::size(want));
        for (std::size_t i = 0; i < list.size(); ++i) {
            const Dest& d = list[i];
            const Want& e = want[i];
            assert(d.label == e.label && d.name == e.name && d.kind == e.kind &&
                   d.layer == e.layer && d.x == e.x && d.y == e.y && d.z == e.z &&
                   d.open == e.open);
        }
        // Lightroot: ArrivalPointState Close is locked; a tower's IsActivateCannon false too.
        const auto g2 = totk::GameData::Parse(SaveBuilder()
                                                  .Enum("ArrivalPointState.CheckPoint001", "Close")
                                                  .Bool("IsActivateCannon.Tower01", false)
                                                  .Bool("IsActivateCannon.Dungeon001", true)
                                                  .Build());
        list = totk::travel::Apply(std::move(list), *g2);
        assert(list[5].open == 0 && list[4].open == 0 && list[0].open == -1);
        // Shrine Enter is open; Appear is not; Enter is a shrine state only.
        const auto g3 = totk::GameData::Parse(SaveBuilder()
                                                  .Enum("DungeonState.Dungeon001", "Enter")
                                                  .Enum("DungeonState.Dungeon002", "Appear")
                                                  .Enum("ArrivalPointState.CheckPoint001", "Enter")
                                                  .Build());
        list = totk::travel::Apply(std::move(list), *g3);
        assert(list[0].open == 1 && list[2].open == 0 && list[5].open == 0);
    }
    // Missing files: an empty list and one line per file; a corrupt BYML: empty, one line; no
    // Mals: labels for names and one line.
    {
        Host h;
        assert(totk::travel::Load(h.api, 121).empty());
        assert(h.logs == WithTravelMissing({}));
        Host c;
        c.PutRomfs(totk::travel::kFiles[0], {'Y', 'B', 7, 0, 1, 2, 3});
        assert(totk::travel::Load(c.api, 121).empty());
        assert(c.logs[0] == std::string("totk travel: ") + totk::travel::kFiles[0] + " unparsable");
        Host n;
        PutPlaces(n);
        const auto list = totk::travel::Load(n.api, 0);
        assert(list.size() == 6 && list[0].name.empty() &&
               n.logs == std::vector<std::string>{
                             "totk travel: LocationMsg/Location.msbt missing (no Mals archive)"});
    }
    // Trust boundary: a 2-element Trans, a NaN element, an out-of-bounds x, a string element, a
    // 4-element Trans and every Trans of an entry with a bad stem (a newline and a fake vec3, 65
    // characters, empty) give no Dest and one "Trans dropped" line for the file; an entry without
    // Trans or without LocationName gives no Dest and is not counted.
    {
        Host h;
        h.PutRomfs(totk::travel::kFiles[0],
                   BymlWriter{}.Write(Dict(
                       {{"Shrine", Arr({Place("Dungeon001", {Arr({Num(1), Num(2)}), V(1, kNaN, 3),
                                                             V(6001, 0, 0), V(5, 6, 7)}),
                                        Dict({{"LocationName", Str("Work/Location/D2.gyml")}}),
                                        Dict({{"Trans", Arr({V(8, 8, 8)})}}),
                                        Place("D3\nx=1 y=2 z=3", {V(8, 8, 8), V(9, 9, 9)}),
                                        Place(std::string(65, 'L'), {V(8, 8, 8)}),
                                        Place("Dungeon_" + std::string(56, 'L'), {V(4, 4, 4)}),
                                        Place("", {V(8, 8, 8)})})}})));
        h.PutRomfs(
            totk::travel::kFiles[1],
            BymlWriter{}.Write(Dict(
                {{"CheckPoint", Arr({Place("CheckPoint001", {Arr({Num(1), Str("2"), Num(3)}),
                                                             Arr({Num(1), Num(2), Num(3), Num(4)}),
                                                             V(1, kInf, 3)})})}})));
        const auto list = totk::travel::Load(h.api, 0);
        assert(list.size() == 2 && list[0].x == 5 && list[1].label == "Dungeon_" + std::string(56, 'L'));
        assert(h.logs[0] ==
                   std::string("totk travel: ") + totk::travel::kFiles[0] + ": 7 Trans dropped" &&
               h.logs[1] ==
                   std::string("totk travel: ") + totk::travel::kFiles[1] + ": 3 Trans dropped");
    }

    // The GameData hash probe.
    {
        Host h;
        const std::uint64_t at = Block(h);
        // A clash: two hashes on slot 5, the second found in slot 6 (two slot reads).
        PutTable(h, at, 3, 8, {{5, 0}, {13, 2}});
        unsigned reads = h.read_calls;
        auto r = totk::FindHash(h.api, at, 13);
        assert(r.fail == totk::ProbeFail::none && r.index == 2 && h.read_calls - reads == 3 + 2);
        // A missing hash stops at the first empty slot (slots 5, 6, then empty 7: three reads).
        reads = h.read_calls;
        r = totk::FindHash(h.api, at, 21);
        assert(r.fail == totk::ProbeFail::missing && h.read_calls - reads == 3 + 3);
        // An index >= count is a miss.
        PutTable(h, at, 2, 8, {{13, 2}});
        assert(totk::FindHash(h.api, at, 13).fail == totk::ProbeFail::index);
        // A full table: a missing hash stops after `size` slot reads.
        std::vector<std::pair<std::uint32_t, std::uint32_t>> full;
        for (std::uint32_t s = 1; s <= 8; ++s)
            full.emplace_back(s, 0);
        PutTable(h, at, 8, 8, full);
        reads = h.read_calls;
        assert(totk::FindHash(h.api, at, 99).fail == totk::ProbeFail::missing &&
               h.read_calls - reads == 3 + 8);
        // Refused before any slot read: count 0, count > size (also size 0), a low table pointer,
        // size above kMaxTable (no RangeMapped either: is_mapped only for the 3 header reads).
        const auto refused = [&](std::uint32_t count, std::uint32_t size, totk::ProbeFail why) {
            PutTable(h, at, count, size, {});
            const unsigned r0 = h.read_calls, m0 = h.is_mapped_calls;
            const auto p = totk::FindHash(h.api, at, 1);
            return p.fail == why && p.count == count && p.size == size && h.read_calls - r0 == 3 &&
                   h.is_mapped_calls - m0 == 3;
        };
        assert(refused(0, 8, totk::ProbeFail::count_zero));
        assert(refused(1, 0, totk::ProbeFail::count_over_size));
        assert(refused(9, 8, totk::ProbeFail::count_over_size));
        assert(refused(1, 0x10001, totk::ProbeFail::size_over_cap));
        h.Put<std::uint64_t>(at, 0xFFFF);
        assert(totk::FindHash(h.api, at, 1).fail == totk::ProbeFail::pointer);
        // An unmapped table range: one RangeMapped, no slot read.
        PutTable(h, at, 1, 8, {});
        h.Put<std::uint64_t>(at, 0x7000000000ULL);
        const unsigned r0 = h.read_calls, m0 = h.is_mapped_calls;
        r = totk::FindHash(h.api, at, 1);
        assert(r.fail == totk::ProbeFail::unmapped && r.table == 0x7000000000ULL &&
               h.read_calls - r0 == 3 && h.is_mapped_calls - m0 == 4);
        // Only the last slot's last byte unmapped: still refused up front (the range is size * 8).
        const std::uint64_t half = PutTable(h, at, 1, 8, {});
        h.memory.erase(half + 8 * 8 - 1);
        const unsigned r1 = h.read_calls, m1 = h.is_mapped_calls;
        assert(totk::FindHash(h.api, at, 1).fail == totk::ProbeFail::unmapped &&
               h.read_calls - r1 == 3 && h.is_mapped_calls - m1 == 4);
        // An unreadable header.
        assert(totk::FindHash(h.api, 0x7100000000ULL, 1).fail == totk::ProbeFail::header);
        // The report's failure line keeps the raw header, so a size above the cap reaches the log.
        Host rh;
        const std::uint64_t mgr = Block(rh);
        PutTable(rh, mgr + kO.v3a_table, 1, 0x10001, {});
        const std::uint64_t t = PutTable(rh, mgr + kO.int_table, 9, 8, {});
        totk::travel::Report(rh.api, mgr, kO);
        char want[2][160];
        std::snprintf(want[0], sizeof want[0],
                      "totk travel: v3a_table probe failed (size > cap; table 0x%llx count 1 size "
                      "65537)",
                      static_cast<unsigned long long>(rh.Get<std::uint64_t>(mgr + kO.v3a_table)));
        std::snprintf(want[1], sizeof want[1],
                      "totk travel: int_table probe failed (count > size; table 0x%llx count 9 "
                      "size 8)",
                      static_cast<unsigned long long>(t));
        assert((rh.logs == std::vector<std::string>{want[0], want[1]}) && !rh.write_calls);
    }

    // The write extension getter: version and hash gate, then the table the host checks.
    {
        assert(!eden_dsmod_get_write_extensions(EDEN_DSMOD_WRITE_EXT_VERSION + 1,
                                                EDEN_DSMOD_WRITE_EXT_HASH));
        assert(!eden_dsmod_get_write_extensions(EDEN_DSMOD_WRITE_EXT_VERSION,
                                                EDEN_DSMOD_WRITE_EXT_HASH ^ 1));
        const auto* we = eden_dsmod_get_write_extensions(EDEN_DSMOD_WRITE_EXT_VERSION,
                                                         EDEN_DSMOD_WRITE_EXT_HASH);
        assert(we && we->version == EDEN_DSMOD_WRITE_EXT_VERSION &&
               we->struct_size == sizeof(EdenDsmodModuleWriteExtensions) &&
               we->abi_hash == EDEN_DSMOD_WRITE_EXT_HASH && we->configure);
    }

    // Page 2 publishes the list: x and -z, kind on the selected layer (-1 off it), dim unless open,
    // the dot picture; the selection; nothing on the other pages.
    {
        World w;
        auto& h = w.h;
        w.Run();
        assert(!h.ints.contains("totk.travel.count"));
        assert(w.Act("totk.ui.page", kPageMap) && w.Act("totk.map.layer", 0));
        h.ClearPublished();
        w.Run();
        assert(h.ints.at("totk.travel.count") == 6 && h.floats.at("totk.travel.0.x") == 100 &&
               h.floats.at("totk.travel.0.y") == 200 && h.ints.at("totk.travel.0.kind") == 0 &&
               h.ints.at("totk.travel.0.dim") == 0 && h.ints.at("totk.travel.1.kind") == -1 &&
               h.ints.at("totk.travel.2.dim") == 1 && h.ints.at("totk.travel.4.kind") == 2 &&
               h.ints.at("totk.travel.4.dim") == 1 && h.ints.at("totk.travel.5.kind") == -1 &&
               !h.floats.contains("totk.travel.6.x") && !h.ints.contains("totk.travel.6.kind"));
        assert(h.texts.at("totk.travel.icon") == "file:assets/travel-dot.png");
        assert(h.ints.at("totk.travel.sel.valid") == 0 && h.ints.at("totk.travel.moved") == 0 &&
               h.texts.at("totk.travel.msg").empty() && !h.texts.contains("totk.travel.sel.name"));
        assert(w.Act("totk.map.layer", 2) && w.Act("totk.travel.select", 5));
        h.ClearPublished();
        w.Run();
        assert(h.ints.at("totk.travel.5.kind") == 1 && h.ints.at("totk.travel.0.kind") == -1 &&
               h.ints.at("totk.travel.sel.valid") == 1 && h.ints.at("totk.travel.sel.open") == 1 &&
               h.ints.at("totk.travel.sel.kind") == 1 &&
               h.texts.at("totk.travel.sel.name") == "Root One");
        assert(w.Act("totk.travel.select", 4));
        h.ClearPublished();
        w.Run();
        assert(h.texts.at("totk.travel.sel.name") == "Tower01" &&
               h.ints.at("totk.travel.sel.open") == -1);
        // select: 0 <= arg < totk.travel.count; a refusal keeps the selection.
        for (std::int64_t bad :
             {std::int64_t(-1), std::int64_t(6), std::int64_t(INT64_MIN), std::int64_t(INT64_MAX)})
            assert(!w.Act("totk.travel.select", bad));
        assert(w.In().travel_sel == 4);
        for (const auto& [key, value] : h.ints)
            assert(totk::pub::Listed(key));
        for (const auto& [key, value] : h.floats)
            assert(totk::pub::Listed(key));
        for (const auto& [key, value] : h.texts)
            assert(totk::pub::Listed(key));
        assert(h.batch_calls == 0 && h.write_calls == 0);
    }

    // Go on the full layout: one batch, ops 1-3 identity, op 4 at P from the old vec3 to the
    // destination + 2.0 in y; the "moved from" line (level 3) before it; the moved state.
    {
        World w;
        w.Settle();
        bool logged_first = false; // the "moved from" line is already in the log at the batch
        w.h.before_batch = [&logged_first](Host& h) {
            std::scoped_lock lock{h.log_mutex};
            logged_first = !h.logs.empty() && h.logs.back().starts_with(kMovedFrom);
        };
        assert(w.Go(0) && w.h.batch_calls == 1 && logged_first);
        w.h.before_batch = nullptr;
        AssertBatch(w, w.h.batches[0], w.base, w.p, kOld, kDest);
        assert(w.AtP() == kDest && w.In().moved &&
               w.Msg() == "Open the map and travel to your medallion");
        const auto moved = w.Lines(kMovedFrom);
        assert(moved.size() == 1 && moved[0].second == EDEN_DSMOD_LOG_ERROR &&
               moved[0].first == kMovedFrom + std::string(totk::travel::Format(kOld).s) +
                                     " to Dungeon001 " + totk::travel::Format(kDest).s +
                                     "; if Eden closes before Put back, place it again by hand at "
                                     "the old x/z");
        assert(w.Act("totk.ui.page", kPageMap));
        w.h.ClearPublished();
        w.Run();
        assert(w.h.ints.at("totk.travel.moved") == 1 &&
               w.h.floats.at("totk.travel.old.x") == -1234.5 &&
               w.h.floats.at("totk.travel.old.y") == 150 &&
               w.h.floats.at("totk.travel.old.z") == 2345.5 &&
               w.h.texts.at("totk.travel.msg") == "Open the map and travel to your medallion");
        // CA10 on the map page with the list, a selection and the moved state.
        w.Run();
        const long before = t_news;
        for (int i = 0; i < 1000; ++i)
            w.m->sample(w.instance, &w.h.api);
        assert(t_news == before);
        // A second go while moved; restore while not moved.
        assert(!w.Act("totk.travel.go") && w.Msg() == "Put the medallion back first");
        // Restore: one batch from the destination back to the old vec3, "back at" once at level 3.
        w.Run(60);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2);
        AssertBatch(w, w.h.batches[1], w.base, w.p, kDest, kOld);
        const auto back = w.Lines("totk travel: medallion back at ");
        assert(back.size() == 1 && back[0].second == EDEN_DSMOD_LOG_ERROR &&
               back[0].first ==
                   "totk travel: medallion back at " + std::string(totk::travel::Format(kOld).s));
        assert(!w.In().moved && w.AtP() == kOld && w.Msg() == "Medallion put back");
        assert(!w.Act("totk.travel.restore") && w.Msg() == "Nothing to put back");
        // Destroy without a moved medallion: no line.
        w.m->destroy(w.instance);
        w.instance = nullptr;
        assert(w.Lines("totk travel: medallion still").empty() && w.h.write_calls == 0);
    }
    // A "moved from" line with 9-digit in-world floats is over 200 characters and logged whole.
    {
        World w;
        w.h.Put(w.p, ToBits(-4567.12305f, -1234.56787f, -3456.78906f));
        w.Settle();
        assert(w.Go(5) && w.h.batch_calls == 1);
        const std::string line =
            kMovedFrom + "x=-4567.12305 y=-1234.56787 z=-3456.78906 to CheckPoint001 "
                         "x=-4321.98779 y=-987.654297 z=-2345.67896; if Eden closes before Put "
                         "back, place it again by hand at the old x/z";
        const auto moved = w.Lines(kMovedFrom);
        assert(line.size() > 200 && moved.size() == 1 && moved[0].first == line);
    }

    // The vec3 chain: a later move from the spot the companion set prints that spot exactly as
    // the earlier line's destination.
    {
        World w;
        w.Settle();
        assert(w.Go(0) && w.h.batch_calls == 1);
        w.In().moved.reset(); // as a new session would start
        assert(w.Go(5) && w.h.batch_calls == 2);
        const auto lines = w.Lines(kMovedFrom);
        const std::string first = lines[0].first, second = lines[1].first;
        const std::string end = first.substr(first.find(" to Dungeon001 ") + 15);
        assert(end.substr(0, end.find(';')) ==
               second.substr(kMovedFrom.size(), second.find(" to ") - kMovedFrom.size()));
    }

    // Refusals: zero batches and the message, the module state unchanged.
    {
        const auto refused = [](World& w, int dest, const std::string& msg) {
            const unsigned batches = w.h.batch_calls;
            const bool queued = w.Go(dest);
            return w.h.batch_calls == batches && !w.In().moved && w.Msg() == msg &&
                   (queued || !w.Act("totk.travel.go"));
        };
        {
            // CA7c: every travel action, valid and out of range, reads and writes nothing.
            World w({.v121 = false});
            w.Run(kLoadSettle);
            for (int d = 0; d < 6; ++d)
                assert(w.Act("totk.travel.select", d));
            for (std::int64_t bad : {std::int64_t(-1), std::int64_t(6), INT64_MIN, INT64_MAX})
                assert(!w.Act("totk.travel.select", bad) && !w.Act("totk.travel.go", bad) &&
                       !w.Act("totk.travel.restore", bad));
            assert(refused(w, 0, "Moving the medallion needs game version 1.2.1"));
            assert(!w.Act("totk.travel.restore") && w.Msg() == "Nothing to put back");
            w.Run(2);
            assert(w.h.MemoryCalls() == 0 && w.h.batch_calls == 0);
        }
        {
            World w;
            w.In().offsets.medallion_len = 0; // the committed kOffsets121
            w.Settle();
            assert(
                refused(w, 0, "Moving the medallion is waiting for T8c (offsets not checked yet)"));
            assert(w.Lines(kMovedFrom).empty());
        }
        {
            World w({.write = false});
            w.Settle();
            assert(refused(w, 0, "This Eden build cannot write game memory (no write extension)"));
        }
        // A host write table with a wrong version, a wrong hash, a short struct_size or no
        // write_batch is not kept.
        for (int k = 0; k < 4; ++k) {
            World w({.write = false});
            EdenDsmodHostWriteApi bad = w.h.write;
            bad.version += k == 0;
            bad.abi_hash ^= k == 1;
            bad.struct_size -= k == 2;
            if (k == 3)
                bad.write_batch = nullptr;
            eden_dsmod_get_write_extensions(EDEN_DSMOD_WRITE_EXT_VERSION, EDEN_DSMOD_WRITE_EXT_HASH)
                ->configure(w.instance, &bad);
            w.Settle();
            assert(refused(w, 0, "This Eden build cannot write game memory (no write extension)"));
        }
        {
            World w({.log = false}); // no "moved from" line possible; Load's "missing" Say runs
            w.Settle();
            assert(refused(w, 0, "This Eden build gives the module no log") && w.h.logs.empty());
        }
        {
            World w({.owned = false});
            w.Settle();
            assert(refused(w, 0, "The last save has no Travel Medallion"));
        }
        {
            World w;
            w.Settle();
            assert(refused(w, 2, "Select an open destination") && // locked
                   refused(w, 4, "Select an open destination"));  // unknown
            // Those refusals did not use up the first go's manual-save prompt.
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Msg() == "Make a manual save in game, then tap again");
        }
        {
            World w;
            w.Settle();
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Msg() == "Make a manual save in game, then tap again" &&
                   w.Act("totk.travel.go"));
            w.Run(2);
            assert(w.h.batch_calls == 1); // the second tap goes
        }
        // Tick preconditions: placed signal, probe, gate, walk, the vec3.
        const auto tick_refused = [&](void (*mutate)(World&), const std::string& msg) {
            World w;
            w.Settle();
            mutate(w);
            w.Run(); // the gate reads the change
            return refused(w, 0, msg);
        };
        assert(tick_refused([](World& w) { w.h.Put<std::uint32_t>(w.base + kLen, 0); },
                            "Medallion not placed: place it, then tap again"));
        assert(tick_refused([](World& w) { w.h.Put<std::uint32_t>(w.base + kLen, 2); },
                            "More than one medallion placed"));
        assert(tick_refused(
            [](World& w) {
                const std::scoped_lock lock{w.h.memory_mutex}; // the worker reads too (T3c)
                w.h.memory.erase(w.base + kLen);
            },
            "Medallion data not found (offsets wrong, T8c)"));
        assert(tick_refused(
            [](World& w) { // 3 hops end at base + 0x300, P's slot (12 readable bytes there)
                w.In().offsets.medallion = {3, {0x4721B98, 0x438, 0x300}};
                w.h.Put<std::uint32_t>(w.base + 0x308, 0);
            },
            "Medallion not readable"));
        assert(tick_refused(
            [](World& w) {
                PutTable(w.h, w.mgr + kO.v3a_table, 12, 16, {{totk::travel::kWarpPos, 9}});
            },
            "Medallion data not found (offsets wrong, T8c)"));
        assert(tick_refused([](World& w) { PutTable(w.h, w.mgr + kO.v3a_table, 12, 16, {}); },
                            "Medallion data not found (offsets wrong, T8c)"));
        assert(tick_refused(
            [](World& w) {
                // holder != mgr: the gate reads unknown, so it is loading
                w.h.Put(totk::test::PutChain(w.h, w.h.api.main_base,
                                             {2, {kO.gmd_holder.hop[0], kO.gmd_holder.hop[1]}}),
                        w.mgr + 8);
            },
            "Game loading or not readable: try again"));
        assert(tick_refused(
            [](World& w) {
                totk::test::PutLoading(w.h, 0x02); // loading
            },
            "Game loading or not readable: try again"));
        assert(tick_refused([](World& w) { w.h.Put(w.p, ToBits(1, kNaN, 2)); },
                            "Medallion position not readable"));
        assert(tick_refused([](World& w) { w.h.Put(w.p, ToBits(7000, 1, 2)); },
                            "Medallion position not readable"));
        assert(tick_refused(
            [](World& w) {
                const std::scoped_lock lock{w.h.memory_mutex}; // the worker reads too (T3c)
                w.h.memory.erase(w.base + 0x300);
            },
            "Medallion not readable"));
        assert(tick_refused(
            [](World& w) { // P not 4-aligned
                w.h.Put<std::uint64_t>(w.base + 0x300, w.p + 2);
                w.h.Put(w.p + 2, kOld);
            },
            "Medallion not readable"));
        assert(tick_refused(
            [](World& w) {
                const std::uint64_t base2 = w.h.Get<std::uint64_t>(w.mgr + 0x438);
                w.h.Put<std::uint64_t>(base2 + 0x300, 0x8000); // a low pointer
            },
            "Medallion not readable"));
        // The vec3 changes between the two ticks of precondition 4.
        {
            World w;
            w.Settle();
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Act("totk.travel.go"));
            w.Run();
            w.h.Put(w.p, ToBits(-1234.5f, 151.f, 2345.25f));
            w.Run();
            assert(w.h.batch_calls == 0 && w.Msg() == "The medallion moved meanwhile: tap again");
        }
        // A tick without a sample since the last one (the screen hidden): the gate is stale.
        {
            World w;
            w.Settle();
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Act("totk.travel.go"));
            w.Run();
            w.m->tick(w.instance, &w.h.api);
            w.Run(2);
            assert(w.h.batch_calls == 0 && w.Msg() == "Game loading or not readable: try again");
        }
        // A new save locks the selected destination between the tap and the move.
        {
            World w;
            w.Settle();
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Act("totk.travel.go"));
            w.Run();
            auto locked = std::make_shared<totk::Snapshot>(*Snap(w.In()));
            locked->travel[0].open = 0;
            Replace(w.In(), locked);
            w.Run();
            assert(w.h.batch_calls == 0 && w.Msg() == "Select an open destination");
        }
        // The manager changes between the gate's read (sample) and the walk (tick).
        {
            World w;
            w.Settle();
            assert(w.Act("totk.travel.select", 0) && !w.Act("totk.travel.go") &&
                   w.Act("totk.travel.go"));
            w.Run();
            w.m->sample(w.instance, &w.h.api);
            const std::uint64_t mgr2 = Block(w.h);
            w.h.Put(mgr2 + 0x438, w.base);
            w.h.Put(w.h.api.main_base + 0x4721B98, mgr2);
            w.m->tick(w.instance, &w.h.api);
            assert(w.h.batch_calls == 0 && w.Msg() == "Game data changed: try again");
        }
        // Destination out of the world after the hover: Dungeon003 at y 3499 (3501).
        {
            World w;
            w.Settle();
            assert(w.Go(3) && w.h.batch_calls == 0 && w.Msg() == "Destination out of the world" &&
                   w.Lines(kMovedFrom).empty());
        }
        // A batch that returns false (op 4's expect changed under the module): nothing kept, and
        // "move not applied" (level 3) closes the "moved from" line.
        {
            World w;
            w.Settle();
            w.h.before_batch = [p = w.p](Host& h) { h.Put(p, ToBits(9, 9, 9)); };
            assert(w.Go(0) && w.h.batch_calls == 1 && !w.In().moved &&
                   w.Msg() == "Not applied, try again");
            const auto lines = w.Lines("totk travel: m");
            assert(lines.size() == 2 && lines[0].first.starts_with(kMovedFrom) &&
                   lines[1] == std::make_pair(std::string("totk travel: move not applied"),
                                              std::uint32_t(EDEN_DSMOD_LOG_ERROR)));
        }
    }

    // medallion_len 0 and medallion_index_placed {set, V}: Index[0] == V moves; another value, a
    // missing WarpMarkerData.Index, a length 0 entry or a values pointer below 0x10000 does not.
    {
        for (int k = 0; k < 5; ++k) {
            World w;
            w.In().offsets.medallion_len = 0;
            w.In().offsets.medallion_index_placed = {true, kPlacedIndex};
            if (k == 1)
                w.h.Put<std::int32_t>(w.idata, kPlacedIndex + 1);
            if (k == 2)
                PutTable(w.h, w.mgr + kO.int_table, 4, 8, {});
            const std::uint64_t at = w.mgr + kO.int_table - totk::kTableToValues;
            const std::uint64_t entry = 2 * totk::kIntArrayEntry;
            if (k == 3)
                w.h.Put<std::uint32_t>(w.h.Get<std::uint64_t>(at) + entry + totk::kIntArrayLen, 0);
            if (k == 4) { // a low values pointer whose entry would pass
                w.h.Put<std::uint64_t>(at, 0x8000);
                w.h.Put<std::uint32_t>(0x8000 + entry + totk::kIntArrayLen, 1);
                w.h.Put(0x8000 + entry + totk::kIntArrayData, w.idata);
            }
            w.Settle();
            assert(w.Go(0) && w.h.batch_calls == (k == 0 ? 1u : 0u));
            assert(k == 0 || w.Msg() == "Medallion not placed: place it, then tap again");
        }
    }

    // Restore's other outcomes.
    {
        // The vec3 at P a third value: moved dropped, the "not where" message and line (level 3,
        // once), no batch.
        World w;
        w.Settle();
        assert(w.Go(0));
        w.Run(60);
        w.h.Put(w.p, ToBits(5, 6, 7));
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 1 && !w.In().moved &&
               w.Msg() == "The medallion is not where the companion put it; place it by hand at "
                          "its old spot x -1234.5 y 150.0 z 2345.5");
        const auto lines = w.Lines("totk travel: medallion not where");
        assert(lines.size() == 1 && lines[0].second == EDEN_DSMOD_LOG_ERROR &&
               lines[0].first == "totk travel: medallion not where the companion put it; place it "
                                 "by hand at its old spot " +
                                     std::string(totk::travel::Format(kOld).s));
    }
    {
        // P == old after the 60 ticks: "already back", no batch; destroy then logs nothing.
        World w;
        w.Settle();
        assert(w.Go(0));
        w.Run(60);
        w.h.Put(w.p, kOld);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 1 && !w.In().moved && w.Msg() == "Medallion is already back");
        const auto lines = w.Lines("totk travel: medallion already back at ");
        assert(lines.size() == 1 && lines[0].second == EDEN_DSMOD_LOG_ERROR);
        w.m->destroy(w.instance);
        w.instance = nullptr;
        assert(w.Lines("totk travel: medallion still").empty());
    }
    {
        // A failed restore batch keeps moved; a failed placed check keeps moved with its message.
        World w;
        w.Settle();
        assert(w.Go(0));
        w.h.before_batch = [p = w.p](Host& h) { h.Put(p, ToBits(9, 9, 9)); };
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2 && w.In().moved && w.Msg() == "Not applied, try again");
        w.h.before_batch = nullptr;
        w.h.Put(w.p, kDest);
        w.h.Put<std::uint32_t>(w.base + kLen, 0);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2 && w.In().moved &&
               w.Msg() == "Medallion not placed: place it, then tap again");
        // Destroy with the medallion moved: the old spot, once, level 3, no batch.
        w.m->destroy(w.instance);
        w.instance = nullptr;
        const auto lines = w.Lines("totk travel: medallion still moved");
        assert(lines.size() == 1 && lines[0].second == EDEN_DSMOD_LOG_ERROR &&
               lines[0].first == "totk travel: medallion still moved; place it by hand at its old "
                                 "spot " +
                                     std::string(totk::travel::Format(kOld).s) &&
               w.h.batch_calls == 2);
    }
    {
        // The game moved the block after go: restore walks again and sends ops 2-4 to the new
        // base and P.
        World w;
        w.Settle();
        assert(w.Go(0));
        const std::uint64_t base2 = Block(w.h), p2 = Block(w.h);
        w.h.Put(w.mgr + 0x438, base2);
        w.h.Put(base2 + 0x300, p2);
        w.h.Put<std::uint32_t>(base2 + kLen, 1);
        w.h.Put(p2, kDest);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2 && !w.In().moved);
        AssertBatch(w, w.h.batches[1], base2, p2, kDest, kOld);
    }

    // The 60-tick watch after go.
    {
        // Back to old: "not applied", moved dropped, one level-3 line after the "moved from" one.
        World w;
        w.Settle();
        assert(w.Go(0));
        w.Run(10);
        w.h.Put(w.p, kOld);
        w.Run();
        assert(!w.In().moved && w.Msg() == "The game did not keep the move (not applied)");
        const auto lines = w.Lines("totk travel: m");
        assert(lines.size() == 2 && lines[0].first.starts_with(kMovedFrom) &&
               lines[1].first == "totk travel: move not applied (medallion still at " +
                                     std::string(totk::travel::Format(kOld).s) + ")" &&
               lines[1].second == EDEN_DSMOD_LOG_ERROR);
    }
    {
        // Put back tapped inside the watch, and the game reverts the move before it runs: the
        // watch drops moved, the queued restore sends nothing.
        World w;
        w.Settle();
        assert(w.Go(0));
        w.Run(5);
        assert(w.Act("totk.travel.restore"));
        w.h.Put(w.p, kOld);
        w.Run(2);
        assert(w.h.batch_calls == 1 && !w.In().moved && w.In().req == Instance::Req::none &&
               w.Msg() == "The game did not keep the move (not applied)");
    }
    {
        // Restore queued in the watch, and the game's value held on the tick of the restore's
        // second read: the watch takes it first, so the batch expects it ("back at", no "not
        // where").
        World w;
        w.Settle();
        assert(w.Go(0));
        const Bits third = ToBits(100, 48.5f, -200);
        w.h.Put(w.p, third);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2 && !w.In().moved);
        AssertBatch(w, w.h.batches[1], w.base, w.p, third, kOld);
        assert(w.Lines("totk travel: medallion back at ").size() == 1 &&
               w.Lines("totk travel: medallion not where").empty());
    }
    {
        // An out-of-world third value held three ticks never becomes the destination: no
        // "changed by the game" line, the message stays.
        World w;
        w.Settle();
        assert(w.Go(0));
        const std::string msg = w.Msg();
        w.h.Put(w.p, ToBits(7000, 1, 2));
        w.Run(3);
        assert(w.In().moved && w.In().moved->dest == kDest && w.Msg() == msg &&
               w.Lines("totk travel: medallion changed").empty());
    }
    {
        // A third value on one tick only leaves the destination; held two ticks it becomes the
        // destination (one level-3 line), moved and old.* stay; restore then expects it.
        World w;
        w.Settle();
        assert(w.Go(0));
        const Bits third = ToBits(100, 48.5f, -200);
        w.h.Put(w.p, third);
        w.Run();
        w.h.Put(w.p, kDest);
        w.Run();
        assert(w.In().moved->dest == kDest && w.Lines("totk travel: medallion changed").empty());
        w.h.Put(w.p, third);
        w.Run(); // the destination in between: one tick of `third` is not two
        assert(w.In().moved->dest == kDest && w.Lines("totk travel: medallion changed").empty());
        w.Run();
        assert(w.In().moved && w.In().moved->dest == third &&
               w.Msg() == "The game changed the medallion; its old spot is x -1234.5 y 150.0 z "
                          "2345.5");
        const auto lines = w.Lines("totk travel: medallion changed");
        assert(lines.size() == 1 && lines[0].second == EDEN_DSMOD_LOG_ERROR &&
               lines[0].first == "totk travel: medallion changed by the game; now at " +
                                     std::string(totk::travel::Format(third).s) + "; old spot " +
                                     totk::travel::Format(kOld).s);
        assert(w.Act("totk.ui.page", kPageMap));
        w.h.ClearPublished();
        w.Run();
        assert(w.h.ints.at("totk.travel.moved") == 1 && w.h.floats.at("totk.travel.old.y") == 150);
        assert(w.Act("totk.travel.restore"));
        w.Run(2);
        assert(w.h.batch_calls == 2);
        AssertBatch(w, w.h.batches[1], w.base, w.p, third, kOld);
        assert(w.Lines("totk travel: medallion back at ").size() == 1);
    }

    // The read-only report for T8c: with user:totk-re, once, after totk.loading turns 0, with the
    // committed kOffsets121 (medallion_len 0); no batch. Without the file: no line, no probe read.
    {
        World w({.re = true});
        w.In().offsets.medallion_len = 0;
        w.Run(kLoadSettle - 1);
        assert(w.Lines("totk travel: v3a").empty());
        w.Run(3);
        char data[96];
        std::snprintf(data, sizeof data,
                      "totk travel: int_table 0xc6dc3b56 -> index 2 (size 8); data at 0x%llx",
                      static_cast<unsigned long long>(w.idata));
        assert((
            w.Lines("totk travel: v3a") ==
            std::vector<std::pair<std::string, std::uint32_t>>{
                {"totk travel: v3a_table 0x636b4637 -> index 10 (size 16)", EDEN_DSMOD_LOG_INFO}}));
        assert(w.Lines("totk travel: int_table").size() == 1 &&
               w.Lines("totk travel: int_table")[0].first == data && w.h.batch_calls == 0);
    }
    {
        World w({.re = true});
        PutTable(w.h, w.mgr + kO.int_table, 9, 8, {});
        w.Run(kLoadSettle + 1);
        assert(w.Lines("totk travel: v3a_table 0x636b4637 -> index 10").size() == 1 &&
               w.Lines("totk travel: int_table probe failed (count > size; table 0x").size() == 1);
    }
    for (int re : {0, 2}) { // no file, an empty file
        World w({.re = re});
        w.Settle();
        const unsigned reads = w.h.read_calls;
        w.m->tick(w.instance, &w.h.api);
        assert(w.h.read_calls == reads && w.Lines("totk travel: v3a").empty() &&
               w.Lines("totk travel: int").empty());
    }

    // A3 step 1's `totk re:` line: with user:totk-re, once, after totk.loading turns 0, the
    // committed ia_queue's control address, capacity and buffer, and the four hashes.
    {
        World w({.re = 1});
        const std::uint64_t store = w.mgr + 0x128; // the plan's values, not the constants
        w.h.Put<std::int32_t>(store + 0x28, 512);
        w.h.Put<std::uint64_t>(store + 0x30, 0x9a000000);
        w.Run(kLoadSettle - 1);
        assert(w.Lines("totk re:").empty());
        w.Run(3);
        char line[400];
        std::snprintf(line, sizeof line,
                      "totk re: IntArray queue control at 0x%llx (capacity 512, buffer "
                      "0x9a000000); hashes Pouch.Weapon.EquipIndex 0xb80e3dbb "
                      "Pouch.Shield.EquipIndex 0x350ad90e Pouch.Bow.EquipIndex 0x2cf18d2b "
                      "Pouch.Food.Content.StockNum 0x2a952a60",
                      static_cast<unsigned long long>(w.mgr + 0x160));
        assert((w.Lines("totk re:") == std::vector<std::pair<std::string, std::uint32_t>>{
                                           {line, EDEN_DSMOD_LOG_INFO}}));
        assert(w.Lines("totk travel: v3a_table").size() == 1 && w.h.batch_calls == 0);
    }
    // Its memory calls alone (the A2 report off, also with only one of its two tables): is_mapped
    // + read for capacity and buffer with the file, none without it, with an empty one or with
    // ia_queue 0; an unreadable store (its first is_mapped fails) or buffer (only the capacity
    // mapped) still gives the address.
    struct Case {
        int re;
        std::uint64_t ia_queue;
        int store; // store fields Put: 2 both, 1 the capacity only, 0 none
        unsigned reads;
        const char* what;
        std::uint64_t totk::Offsets::*kept = nullptr; // the one A2 table left set
    };
    for (const Case c : {Case{1, 0x128, 2, 4, "capacity 512, buffer 0x9a000000"},
                         Case{1, 0x128, 1, 3, "store unreadable"},
                         Case{1, 0x128, 0, 1, "store unreadable"}, Case{0, 0x128, 2, 0, ""},
                         Case{2, 0x128, 2, 0, ""}, Case{1, 0, 2, 0, ""},
                         Case{1, 0x128, 2, 4, "capacity 512, buffer 0x9a000000",
                              &totk::Offsets::v3a_table},
                         Case{1, 0x128, 2, 4, "capacity 512, buffer 0x9a000000",
                              &totk::Offsets::int_table}}) {
        World w({.re = c.re});
        w.In().offsets.v3a_table = w.In().offsets.int_table = 0;
        if (c.kept)
            w.In().offsets.*c.kept = kO.*c.kept;
        w.In().offsets.ia_queue = c.ia_queue;
        if (c.store >= 1)
            w.h.Put<std::int32_t>(w.mgr + 0x150, 512);
        if (c.store == 2)
            w.h.Put<std::uint64_t>(w.mgr + 0x158, 0x9a000000);
        unsigned reads = 0;
        for (int i = 0; i < kLoadSettle + 5; ++i) {
            w.m->sample(w.instance, &w.h.api);
            const unsigned before = w.h.MemoryCalls();
            w.m->tick(w.instance, &w.h.api);
            reads += w.h.MemoryCalls() - before;
        }
        const auto lines = w.Lines("totk re:");
        assert(reads == c.reads && lines.size() == (*c.what ? 1u : 0u) &&
               w.Lines("totk travel:").empty());
        char head[128];
        std::snprintf(head, sizeof head, "totk re: IntArray queue control at 0x%llx (%s); hashes ",
                      static_cast<unsigned long long>(w.mgr + 0x160), c.what);
        assert(!*c.what || lines[0].first.starts_with(head));
    }

    // The romfs list arriving after the save (no Mals: the names give up after 30 tries): the
    // shown save is read again, so the list comes with its unlock state.
    {
        Host h;
        PutPlaces(h);
        h.romfs.erase("romfs:Mals/USen.Product.121.sarc.zs");
        SaveDir dir;
        dir.Put("slot_00/caption.sav", Caption(2024, 1, 2, 8, 0, false));
        dir.Put("slot_00/progress.sav", TravelSave().Build());
        const auto* m =
            eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
        void* instance = m->create(&h.api, "{}");
        auto& in = *static_cast<Instance*>(instance);
        eden_dsmod_get_save_extensions(EDEN_DSMOD_SAVE_EXT_VERSION, EDEN_DSMOD_SAVE_EXT_HASH)
            ->configure(instance, &dir.api);
        const auto shown = [&] {
            std::scoped_lock lock{in.mutex};
            return in.snapshot;
        };
        for (int i = 0; i < 4; ++i)
            PollNow(in);
        assert(shown() && shown()->travel.empty());
        for (int i = 0; i < 40 && shown()->travel.empty(); ++i)
            PollNow(in);
        assert(shown()->travel.size() == 6 && shown()->travel[0].open == 1 &&
               shown()->travel[2].open == 0 && shown()->travel[0].name.empty());
        m->destroy(instance);
    }

    // 321 destinations: count 320, the cut logged once.
    {
        World w;
        std::vector<byml::Node> shrines;
        for (int i = 0; i < 321; ++i)
            shrines.push_back(Place("Dungeon" + std::to_string(i), {V(float(i), 0, 0)}));
        Host h;
        h.PutRomfs(totk::travel::kFiles[0], BymlWriter{}.Write(Dict({{"Shrine", Arr(shrines)}})));
        assert(totk::travel::Load(h.api, 0).size() == kMaxDest);
        assert(h.logs.size() == 3 && h.logs[2] == "totk travel: 321 destinations, list cut at 320");
        // Through the module: the shown snapshot carries at most 320, and 320 are published.
        auto big = std::make_shared<totk::Snapshot>();
        big->travel = totk::travel::Load(h.api, 0);
        Replace(w.In(), big);
        assert(w.Act("totk.ui.page", kPageMap));
        w.h.ClearPublished();
        w.Run();
        assert(w.h.ints.at("totk.travel.count") == 320 &&
               w.h.floats.contains("totk.travel.319.x") &&
               !w.h.floats.contains("totk.travel.320.x"));
        assert(w.Act("totk.travel.select", 319) && !w.Act("totk.travel.select", 320));
    }

    std::puts("totk_travel_test: ok");
    return 0;
}

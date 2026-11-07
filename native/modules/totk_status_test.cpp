// SPDX-License-Identifier: GPL-3.0-or-later
// ReadStatus over a fake 1.2.1 layout (totk_test_host.h PutLive): kOffsets121's chains with
// synthetic heap addresses, then each way it must fail closed.
#include "totk_status.h"
#include "totk_test_host.h"

#include <cmath>
#include <cstdio>
#include <functional>
#include <limits>
#include <string_view>

namespace {

using totk::kOffsets121;
using totk::test::Host;
using totk::test::Live;
using totk::test::PutChain;
using totk::test::PutLive;
using totk::test::PutLoading;
constexpr auto kMax = std::numeric_limits<std::uint64_t>::max();

totk::Status Read(Host& h, const totk::Offsets& o = kOffsets121) {
    return totk::ReadStatus(h.api, o);
}

// Invalid on a fresh full layout after `change(host, offsets)` (the offsets start as kOffsets121
// and the layout is laid out from them after the change, so only the change can reject it).
void Invalid(const std::function<void(Host&, totk::Offsets&)>& change) {
    Host h;
    totk::Offsets o = kOffsets121;
    change(h, o);
    PutLive(h, {}, o);
    assert(!Read(h, o).valid);
}

// The pointer slot at main + o.rupees.hop[0] is moved to an address that wraps:
// slot = kMax - 7, so slot + 0xD8 wraps to 0xD0, which gets the old pointer. Every byte the walk
// reads is mapped and holds what the real chain holds: only the overflow check rejects it.
void WrapRupees(Host& h, const totk::Offsets& o) {
    const std::uint64_t slot = h.api.main_base + o.rupees.hop[0];
    const std::uint64_t old = h.Get<std::uint64_t>(slot);
    h.Put<std::uint64_t>(slot, kMax - 7);
    h.Put<std::uint64_t>(kMax - 7 + o.rupees.hop[1], h.Get<std::uint64_t>(old + o.rupees.hop[1]));
}

// ReadLoading on `h`, asserting the unknown cause it names ("" for none) and, for "count", n.
int Gate(Host& h, std::string_view cause = "", const totk::Offsets& o = kOffsets121,
         std::uint32_t count = 0) {
    totk::Unknown why;
    std::uint32_t n = 0;
    std::uint64_t mgr = 1;
    const int r = totk::ReadLoading(h.api, h.api.main_base, o, why, n, mgr);
    assert(totk::kUnknownText[unsigned(why)] == cause);
    assert(why != totk::Unknown::count || n == count);
    // mgr is the GameDataMgr the walk read (A2 compares it), 0 when a chain is empty.
    const bool all = o.gmd_mgr.n && o.gmd_holder.n && o.gmd_count.n && o.gmd_loading.n;
    assert(mgr == (all ? totk::detail::Walk(h.api, h.api.main_base, o.gmd_mgr) : 0));
    return r;
}

} // namespace

int main() {
    { // The full layout: every value exact, read twice, never a write.
        Host h;
        PutLive(h);
        const auto s = Read(h);
        assert(s.valid && s.hearts == 48 && s.max_hearts == 80 && s.rupees == 1234);
        assert(s.stamina == 1500.f && s.max_stamina == 2000.f);
        assert(s.has_battery && s.battery == 900.f && s.max_battery == 3000.f);
        assert(h.write_calls == 0 && h.read_calls > 0);
    }
    { // "Player\0" at an address with addr % 8 == 1 is still read (a char*, no alignment).
        Host h;
        PutLive(h);
        const std::uint64_t actor = h.Get<std::uint64_t>(h.Get<std::uint64_t>(
                                        Host::kMainBase + kOffsets121.actor.hop[0]) +
                                    kOffsets121.actor.hop[1]);
        const std::uint64_t odd = 0xA0000001;
        static_assert(odd % 8 == 1);
        h.Put<std::uint64_t>(actor + kOffsets121.name.hop[0], odd);
        for (std::size_t i = 0; i < 7; ++i)
            h.memory[odd + i] = std::uint8_t("Player"[i]);
        assert(Read(h).valid);
        // Another name: the actor's values are not trusted.
        h.memory[odd] = 'Q';
        assert(!Read(h).valid);
        h.memory[odd] = 'P';
        h.memory[odd + 6] = '!'; // "Player!" is not "Player"
        assert(!Read(h).valid);
    }
    // Offsets{} (1.2.0, any other build) and any one empty required chain: no host call.
    {
        Host h;
        PutLive(h);
        assert(!Read(h, totk::Offsets{}).valid && h.MemoryCalls() == 0);
        totk::Chain totk::Offsets::*required[] = {
            &totk::Offsets::actor,      &totk::Offsets::name,    &totk::Offsets::hearts,
            &totk::Offsets::max_hearts, &totk::Offsets::stamina, &totk::Offsets::max_stamina,
            &totk::Offsets::rupees};
        for (auto field : required) {
            totk::Offsets o = kOffsets121;
            o.*field = {};
            assert(!Read(h, o).valid && h.MemoryCalls() == 0);
        }
        // An empty battery chain only drops the battery pair.
        totk::Offsets o = kOffsets121;
        o.max_battery = {};
        const auto s = Read(h, o);
        assert(s.valid && !s.has_battery);
    }
    // An unmapped hop (each slot of the hearts chain, one byte gone).
    for (int hop = 0; hop < 3; ++hop) {
        Host h;
        PutLive(h);
        std::uint64_t at = Host::kMainBase + kOffsets121.actor.hop[0];
        at = h.Get<std::uint64_t>(at) + kOffsets121.actor.hop[1];
        at = h.Get<std::uint64_t>(at) + kOffsets121.hearts.hop[0]; // actor + 0x228
        for (int i = 0; i < hop; ++i)
            at = h.Get<std::uint64_t>(at) + kOffsets121.hearts.hop[i + 1];
        h.memory.erase(at + 7);
        assert(!Read(h).valid);
    }
    // A u64 slot not 8-aligned (4-aligned, mapped): only the alignment check rejects it.
    Invalid([](Host&, totk::Offsets& o) { o.rupees.hop[1] = 0xDC; });
    Invalid([](Host&, totk::Offsets& o) { o.actor.hop[1] = 0x484; });
    // A u32 or f32 not 4-aligned.
    Invalid([](Host&, totk::Offsets& o) { o.rupees.hop[2] = 0x28EA6; });
    Invalid([](Host&, totk::Offsets& o) { o.stamina.hop[2] = 0x13FE; });
    Invalid([](Host&, totk::Offsets& o) { o.max_hearts.hop[3] = 0x9; });
    // A null or low pointer, with the rest of the chain mapped behind it.
    for (const std::uint64_t low : {0x0ULL, 0x8000ULL, 0xFFF8ULL}) {
        Invalid([low](Host& h, totk::Offsets& o) {
            h.Put<std::uint64_t>(h.api.main_base + o.rupees.hop[0], low);
        });
        Invalid([low](Host& h, totk::Offsets& o) {
            PutLive(h, {}, o); // the actor first, then its name pointer
            const std::uint64_t actor = h.Get<std::uint64_t>(
                h.Get<std::uint64_t>(h.api.main_base + o.actor.hop[0]) + o.actor.hop[1]);
            h.Put<std::uint64_t>(actor + o.name.hop[0], low);
        });
    }
    { // The low pointer just at the floor is accepted.
        Host h;
        h.Put<std::uint64_t>(Host::kMainBase + kOffsets121.rupees.hop[0], 0x10000);
        PutLive(h);
        assert(Read(h).valid);
    }
    // A first slot outside main (all bytes mapped), and an aligned one straddling its end.
    Invalid([](Host&, totk::Offsets& o) { o.rupees.hop[0] = Host::kMainSize; });
    Invalid([](Host&, totk::Offsets& o) { o.rupees = {1, {Host::kMainSize}}; }); // one hop too
    Invalid([](Host& h, totk::Offsets& o) {
        h.api.main_size = Host::kMainSize - 4;
        o.max_stamina.hop[0] = Host::kMainSize - 8;
    });
    { // The last whole slot inside main is fine.
        Host h;
        totk::Offsets o = kOffsets121;
        o.rupees.hop[0] = Host::kMainSize - 8;
        PutLive(h, {}, o);
        assert(Read(h, o).valid);
    }
    { // main_base + hop[0] wraps past 2^64.
        Host h;
        PutLive(h);
        h.api.main_base = kMax - 0x10;
        assert(!Read(h).valid);
    }
    { // A loaded pointer + hop wraps to a mapped low address: only the overflow check rejects.
        // Rupees get their own main slot so no other chain shares the wrapped pointer.
        Host h;
        totk::Offsets o = kOffsets121;
        o.rupees.hop[0] = 0x100;
        PutLive(h, {}, o);
        assert(Read(h, o).valid);
        WrapRupees(h, o);
        assert(!Read(h, o).valid);
    }
    { // The actor pointer + a tail hop wraps: the actor is moved to kMax - 7 with its bytes
        // (wrapping into low memory), so only the base overflow check rejects it.
        Host h;
        PutLive(h);
        const std::uint64_t slot =
            h.Get<std::uint64_t>(Host::kMainBase + kOffsets121.actor.hop[0]) +
            kOffsets121.actor.hop[1];
        const std::uint64_t actor = h.Get<std::uint64_t>(slot), moved = kMax - 7;
        for (std::uint64_t off = 0; off < 0x1000; ++off)
            if (h.memory.contains(actor + off))
                h.memory[moved + off] = h.memory.at(actor + off);
        h.Put<std::uint64_t>(slot, moved);
        assert(!Read(h).valid);
    }
    { // A torn read anywhere in the second pass (or the first: then the passes differ).
        Host count;
        PutLive(count);
        assert(Read(count).valid);
        for (unsigned n = 1; n <= count.read_calls; ++n) {
            Host h;
            PutLive(h);
            h.corrupt_read = n;
            assert(!Read(h).valid);
        }
    }
    // Each bound broken (the five values all or nothing).
    const std::function<void(Live&)> broken[] = {
        [](Live& v) { v.max_hearts = 11, v.hearts = 0; },
        [](Live& v) { v.max_hearts = 161; },
        [](Live& v) { v.hearts = 2 * v.max_hearts + 1; },
        [](Live& v) { v.rupees = 1000000; },
        [](Live& v) { v.max_stamina = 0, v.stamina = 0; },
        [](Live& v) { v.max_stamina = 3000.5f; },
        [](Live& v) { v.max_stamina = NAN; },
        [](Live& v) { v.max_stamina = INFINITY; },
        [](Live& v) { v.stamina = -1; },
        [](Live& v) { v.stamina = 2 * v.max_stamina + 1; },
        [](Live& v) { v.stamina = NAN; },
        [](Live& v) { v.stamina = INFINITY; },
    };
    for (const auto& change : broken) {
        Host h;
        Live v;
        change(v);
        PutLive(h, v);
        assert(!Read(h).valid);
    }
    { // The bounds themselves pass.
        Host h;
        PutLive(h, {.hearts = 320, .max_hearts = 160, .rupees = 999999, .stamina = 6000.f,
                    .max_stamina = 3000.f, .battery_now = 48000.f, .max_battery = 48000.f});
        const auto s = Read(h);
        assert(s.valid && s.has_battery);
        Host l;
        PutLive(l, {.hearts = 0, .max_hearts = 12, .rupees = 0, .stamina = 0.f,
                    .max_stamina = 0.5f, .battery_now = 0.f, .max_battery = 0.5f});
        assert(Read(l).valid && Read(l).has_battery);
    }
    // A battery out of bounds, or a missing battery chain: the five values without the battery.
    const std::function<void(Live&)> no_battery[] = {
        [](Live& v) { v.battery = false; },
        [](Live& v) { v.max_battery = 0, v.battery_now = 0; },
        [](Live& v) { v.max_battery = 48000.5f; },
        [](Live& v) { v.max_battery = NAN; },
        [](Live& v) { v.battery_now = -1; },
        [](Live& v) { v.battery_now = v.max_battery + 1; },
        [](Live& v) { v.battery_now = INFINITY; },
        [](Live& v) { v.battery_now = NAN; },
        [](Live& v) { v.max_battery = INFINITY; },
    };
    for (const auto& change : no_battery) {
        Host h;
        Live v;
        change(v);
        PutLive(h, v);
        const auto s = Read(h);
        assert(s.valid && !s.has_battery && s.battery == 0 && s.max_battery == 0);
        assert(s.hearts == 48 && s.max_hearts == 80 && s.rupees == 1234 && s.stamina == 1500.f &&
               s.max_stamina == 2000.f);
    }
    { // Battery pair absent (max battery's last byte unmapped) and the battery word torn in the
        // second pass: the leftover words are cleared, so the passes still agree.
        Host h;
        PutLive(h);
        h.memory.erase(PutChain(h, Host::kMainBase, kOffsets121.max_battery) + 3);
        assert(Read(h).valid);
        const unsigned per_pass = h.read_calls / 2;
        // The battery word is the last read before max battery's n - 1 pointer reads.
        h.corrupt_read = h.read_calls + 2 * per_pass - (kOffsets121.max_battery.n - 1);
        const auto s = Read(h);
        assert(s.valid && !s.has_battery);
    }
    { // A host without is_mapped is rejected (MissingIsMapped::Reject).
        Host h;
        PutLive(h);
        h.api.is_mapped = nullptr;
        assert(!Read(h).valid);
    }

    { // Walk refuses base 0 and a chain longer than its hop array, before any memory call.
        Host h;
        PutLive(h);
        assert(!totk::detail::Walk(h.api, 0, kOffsets121.name));
        assert(!totk::detail::Walk(h.api, Host::kMainBase, totk::Chain{7, {}}));
        assert(h.MemoryCalls() == 0);
    }

    // ReadPosition: the same actor walk and name check, its own two passes and bounds.
    { // The full layout: x, y, z exact; a broken status bound leaves the position valid.
        Host h;
        PutLive(h, {.rupees = 1000000});
        const auto p = totk::ReadPosition(h.api, kOffsets121);
        assert(p.valid && p.x == -1234.5f && p.y == 150.f && p.z == 2345.25f);
        assert(!Read(h).valid && h.write_calls == 0);
        // And no position leaves the status valid.
        Host n;
        PutLive(n, {.position = false});
        assert(!totk::ReadPosition(n.api, kOffsets121).valid && Read(n).valid);
    }
    { // Another name, a torn read in either pass, a position not 4-aligned: invalid.
        Host h;
        PutLive(h);
        const std::uint64_t actor = h.Get<std::uint64_t>(h.Get<std::uint64_t>(
                                        Host::kMainBase + kOffsets121.actor.hop[0]) +
                                    kOffsets121.actor.hop[1]);
        const std::uint64_t name = h.Get<std::uint64_t>(actor + kOffsets121.name.hop[0]);
        h.memory[name] = 'Q';
        assert(!totk::ReadPosition(h.api, kOffsets121).valid);
        h.memory[name] = 'P';
        const unsigned before = h.read_calls;
        assert(totk::ReadPosition(h.api, kOffsets121).valid);
        const unsigned per_read = h.read_calls - before;
        for (unsigned n = 1; n <= per_read; ++n) {
            h.corrupt_read = h.read_calls + n;
            assert(!totk::ReadPosition(h.api, kOffsets121).valid);
        }
        totk::Offsets o = kOffsets121;
        o.position.hop[0] = 0x2B6;
        h.Put(actor + 0x2B6, std::array<float, 3>{1.f, 2.f, 3.f});
        assert(!totk::ReadPosition(h.api, o).valid);
    }
    { // Offsets{} or an empty actor, name or position chain: no host call.
        Host h;
        PutLive(h);
        assert(!totk::ReadPosition(h.api, totk::Offsets{}).valid);
        for (auto field : {&totk::Offsets::actor, &totk::Offsets::name, &totk::Offsets::position}) {
            totk::Offsets o = kOffsets121;
            o.*field = {};
            assert(!totk::ReadPosition(h.api, o).valid);
        }
        assert(h.MemoryCalls() == 0);
    }
    // Each bound: on it valid, past it (or not finite) invalid.
    const std::function<void(Live&)> on_bound[] = {
        [](Live& v) { v.x = -6000; }, [](Live& v) { v.x = 6000; },
        [](Live& v) { v.y = -1500; }, [](Live& v) { v.y = 3500; },
        [](Live& v) { v.z = -5000; }, [](Live& v) { v.z = 5000; },
    };
    for (const auto& change : on_bound) {
        Host h;
        Live v;
        change(v);
        PutLive(h, v);
        assert(totk::ReadPosition(h.api, kOffsets121).valid);
    }
    const std::function<void(Live&)> out_of_bounds[] = {
        [](Live& v) { v.x = -6000.5f; }, [](Live& v) { v.x = 6000.5f; },
        [](Live& v) { v.y = -1500.5f; }, [](Live& v) { v.y = 3500.5f; },
        [](Live& v) { v.z = -5000.5f; }, [](Live& v) { v.z = 5000.5f; },
        [](Live& v) { v.x = NAN; },      [](Live& v) { v.y = NAN; },
        [](Live& v) { v.z = NAN; },      [](Live& v) { v.x = INFINITY; },
        [](Live& v) { v.y = INFINITY; }, [](Live& v) { v.z = INFINITY; },
    };
    for (const auto& change : out_of_bounds) {
        Host h;
        Live v;
        change(v);
        PutLive(h, v);
        assert(!totk::ReadPosition(h.api, kOffsets121).valid);
    }
    // Layer: below -100 Depths, above 1000 Sky, else Surface (each side of both thresholds).
    assert(totk::Layer(-100.01f) == 2 && totk::Layer(-100.f) == 0 && totk::Layer(1000.f) == 0 &&
           totk::Layer(1000.01f) == 1 && totk::Layer(-1500.f) == 2 && totk::Layer(3500.f) == 1);

    // The loading gate (§A1): 0 on the full layout, 1 only with flag 0x02, -1 with its cause.
    {
        Host h;
        const std::uint64_t flags = PutLoading(h);
        assert(Gate(h) == 0 && h.read_calls > 0 && h.write_calls == 0);
        h.Put<std::uint8_t>(flags, 0x02);
        assert(Gate(h) == 1);
        h.Put<std::uint8_t>(flags, 0x04); // another bit
        assert(Gate(h) == 0);
        PutLoading(h, 0, 16);
        assert(Gate(h) == 0);
        PutLoading(h, 0, 0);
        assert(Gate(h, "count", kOffsets121, 0) == -1);
        PutLoading(h, 0, 17);
        assert(Gate(h, "count", kOffsets121, 17) == -1);
    }
    // Each unknown cause on a fresh layout: a null file pointer, another manager in the holder,
    // then an unmapped hop (one byte gone from each chain's slots and values).
    using Change = std::function<void(Host&, std::uint64_t mgr, std::uint64_t holder)>;
    const auto& o = kOffsets121;
    const std::pair<const char*, Change> unknown[] = {
        {"file walk", [&](Host& h, auto mgr, auto) {
             h.Put<std::uint64_t>(mgr + o.gmd_loading.hop[1], 0);
         }},
        {"managers differ", [&](Host& h, auto mgr, auto holder) { h.Put(holder, mgr + 8); }},
        {"managers differ", [&](Host& h, auto, auto) { h.memory.clear(); }}, // both walks fail
        {"managers differ", [&](Host& h, auto, auto) {
             h.memory.erase(Host::kMainBase + o.gmd_mgr.hop[0]);
         }},
        {"managers differ", [&](Host& h, auto, auto) {
             h.memory.erase(Host::kMainBase + o.gmd_holder.hop[0] + 7);
         }},
        {"managers differ", [&](Host& h, auto, auto holder) { h.memory.erase(holder + 7); }},
        {"count unreadable", [&](Host& h, auto mgr, auto) {
             h.memory.erase(mgr + o.gmd_count.hop[1] + 3);
         }},
        {"file walk", [&](Host& h, auto mgr, auto) {
             h.memory.erase(mgr + o.gmd_loading.hop[1] + 7);
         }},
        {"file walk", [&](Host& h, auto, auto) { h.memory.erase(PutLoading(h)); }}, // the flags
    };
    for (const auto& [cause, change] : unknown) {
        Host h;
        PutLoading(h);
        change(h, h.Get<std::uint64_t>(Host::kMainBase + o.gmd_mgr.hop[0]),
               h.Get<std::uint64_t>(Host::kMainBase + o.gmd_holder.hop[0]));
        assert(Gate(h, cause) == -1 && h.write_calls == 0);
    }
    { // Offsets{} (1.2.0) and any one empty chain: unknown, no cause, no host call.
        Host h;
        PutLoading(h);
        assert(Gate(h, "", totk::Offsets{}) == -1 && h.MemoryCalls() == 0);
        for (auto field : {&totk::Offsets::gmd_mgr, &totk::Offsets::gmd_holder,
                           &totk::Offsets::gmd_count, &totk::Offsets::gmd_loading}) {
            totk::Offsets o = kOffsets121;
            o.*field = {};
            assert(Gate(h, "", o) == -1 && h.MemoryCalls() == 0);
        }
    }

    std::puts("totk_status_test: ok");
    return 0;
}

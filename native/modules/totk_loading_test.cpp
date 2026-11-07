// SPDX-License-Identifier: GPL-3.0-or-later
// The module's totk.loading (docs/PLAN.md §A1) over a fake 1.2.1 gate (totk_test_host.h
// PutLoading): the settle count and the log lines. Compiled in from the module source like
// totk_module_test, which sits at CA9's 64 KiB file cap.
#include "0100F2C0115B6000.cpp"
#include "totk_test_host.h"

namespace {

using totk::test::Host;

// A 1.2.1 host with the gate laid out; no read_romfs, so the worker stays quiet.
struct Fixture {
    Host h;
    std::uint64_t flags = 0, mgr = 0, holder = 0; // the flags byte, the manager, the holder slot
    void* instance = nullptr;
    const EdenDsmodModuleApi* m =
        eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);

    explicit Fixture(bool v121 = true) {
        if (v121)
            std::memcpy(h.api.build_id, totk::kBuild121Bytes.data(), 32);
        h.api.read_romfs = nullptr;
        const auto& o = totk::kOffsets121;
        flags = totk::test::PutLoading(h);
        mgr = h.Get<std::uint64_t>(Host::kMainBase + o.gmd_mgr.hop[0]);
        holder = h.Get<std::uint64_t>(Host::kMainBase + o.gmd_holder.hop[0]);
        instance = m->create(&h.api, "{}");
        assert(instance);
    }
    ~Fixture() {
        m->destroy(instance);
    }
    std::int64_t Sample() {
        h.ClearPublished();
        m->sample(instance, &h.api);
        return h.ints.at("totk.loading");
    }
    std::vector<std::string> Lines() {
        std::scoped_lock lock{h.log_mutex};
        std::vector<std::string> out;
        for (const auto& l : h.logs)
            if (l.starts_with("totk loading"))
                out.push_back(l);
        return out;
    }
    void Count(std::uint32_t n) {
        h.Put(mgr + totk::kOffsets121.gmd_count.hop[1], n);
    }
};

const std::string kOn = "totk loading: 1 -> 0", kOff = "totk loading: 0 -> 1";

} // namespace

int main() {
    { // 1 for 59 clear samples, 0 from the 60th; one loading or unknown sample restarts it.
        Fixture f;
        for (int n = 1; n < kLoadSettle; ++n)
            assert(f.Sample() == 1);
        assert(f.Sample() == 0 && f.Sample() == 0);
        assert(f.Lines() == std::vector<std::string>{kOn});
        for (const bool unknown : {false, true}) {
            if (unknown)
                f.Count(0);
            else
                f.h.Put<std::uint8_t>(f.flags, 0x02);
            assert(f.Sample() == 1);
            f.Count(1);
            f.h.Put<std::uint8_t>(f.flags, 0);
            for (int n = 1; n < kLoadSettle; ++n)
                assert(f.Sample() == 1);
            assert(f.Sample() == 0);
        }
        assert((f.Lines() == std::vector<std::string>{kOn, kOff, kOn,
                                                      "totk loading: unknown (count 0)", kOff,
                                                      kOn}));
        assert(f.h.write_calls == 0);
    }
    { // 1000 samples in runs of 100, gate 0 then 1: a line per published change only, each 1 -> 0
      // at its run's 60th sample.
        Fixture f;
        std::vector<int> at;
        for (int n = 0; n < 1000; ++n) {
            f.h.Put<std::uint8_t>(f.flags, (n / 100) % 2 ? 0x02 : 0);
            f.Sample();
            if (f.Lines().size() > at.size())
                at.push_back(n);
        }
        std::vector<std::string> lines;
        std::vector<int> expect;
        for (int run = 0; run < 10; run += 2) {
            lines.insert(lines.end(), {kOn, kOff});
            expect.insert(expect.end(), {run * 100 + kLoadSettle - 1, run * 100 + 100});
        }
        assert(f.Lines() == lines && at == expect);
    }
    { // 1000 samples alternating unknown causes: only the first cause's line.
        Fixture f;
        for (int n = 0; n < 1000; ++n) {
            const bool differ = n % 2 == 0;
            f.h.Put<std::uint64_t>(f.holder, differ ? f.mgr + 8 : f.mgr);
            f.Count(differ ? 1 : 0);
            assert(f.Sample() == 1);
        }
        assert(f.Lines() == std::vector<std::string>{"totk loading: unknown (managers differ)"});
    }
    { // 1.2.0 with the gate in memory: 1, no memory call, no line.
        Fixture f{false};
        for (int n = 0; n < 2 * kLoadSettle; ++n)
            assert(f.Sample() == 1);
        assert(f.h.MemoryCalls() == 0 && f.Lines().empty());
    }

    std::puts("totk_loading_test: ok");
    return 0;
}

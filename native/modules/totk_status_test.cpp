// SPDX-License-Identifier: GPL-3.0-or-later
// ReadStatus over fake memory with test-only synthetic offsets (never real game offsets).
#include "totk_status.h"
#include "totk_test_host.h"

#include <cstdio>
#include <limits>

namespace {

using totk::test::Host;

constexpr std::uint64_t kSlot = 0x1000;     // synthetic main-relative slot
constexpr std::uint64_t kRoot = 0x90000000; // synthetic heap object
constexpr totk::Offsets kSynthetic{kSlot, 0x10, 0x14, 0x18, 0x1C, 0x20};

void Fill(Host& h, std::uint64_t root = kRoot) {
    h.Put<std::uint64_t>(Host::kMainBase + kSlot, root);
    h.Put<std::int32_t>(kRoot + 0x10, 12);
    h.Put<std::int32_t>(kRoot + 0x14, 40);
    h.Put<std::int32_t>(kRoot + 0x18, 900);
    h.Put<std::int32_t>(kRoot + 0x1C, 1000);
    h.Put<std::int32_t>(kRoot + 0x20, 4321);
}

} // namespace

int main() {
    { // Default offsets are all 0: invalid without a single host call.
        Host h;
        Fill(h);
        assert(!totk::ReadStatus(h.api, totk::Offsets{}).valid);
        assert(h.MemoryCalls() == 0);
    }
    { // Any one unknown (0) offset is enough to read nothing.
        for (int i = 0; i < 6; ++i) {
            Host h;
            Fill(h);
            totk::Offsets o = kSynthetic;
            std::uint64_t* fields[] = {&o.game_data_root, &o.hearts,      &o.max_hearts,
                                       &o.stamina,        &o.max_stamina, &o.rupees};
            *fields[i] = 0;
            assert(!totk::ReadStatus(h.api, o).valid);
            assert(h.MemoryCalls() == 0);
        }
    }
    { // Reads every value, twice, and never writes.
        Host h;
        Fill(h);
        const auto s = totk::ReadStatus(h.api, kSynthetic);
        assert(s.valid && s.hearts == 12 && s.max_hearts == 40 && s.stamina == 900 &&
               s.max_stamina == 1000 && s.rupees == 4321);
        assert(h.read_calls == 12 && h.write_calls == 0);
    }
    { // Unmapped root slot, unmapped field.
        Host h;
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
        Fill(h);
        h.memory.erase(kRoot + 0x20);
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
    }
    { // main_base + offset overflows.
        Host h;
        Fill(h);
        h.api.main_base = std::numeric_limits<std::uint64_t>::max() - 0x10;
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
    }
    { // Slot outside main (mapped memory, but past main_size).
        Host h;
        Fill(h);
        h.Put<std::uint64_t>(Host::kMainBase + Host::kMainSize, kRoot);
        totk::Offsets o = kSynthetic;
        o.game_data_root = Host::kMainSize;
        assert(!totk::ReadStatus(h.api, o).valid);
        // Straddles the end; all 8 bytes mapped and holding a good root, so only InMain rejects it.
        o.game_data_root = Host::kMainSize - 4;
        h.Put<std::uint64_t>(Host::kMainBase + Host::kMainSize - 4, kRoot);
        assert(!totk::ReadStatus(h.api, o).valid);
    }
    { // Null and misaligned root pointer.
        Host h;
        Fill(h, 0);
        for (std::uint64_t field = 0x10; field <= 0x20; field += 4) // only the null check rejects
            h.Put<std::int32_t>(field, 1);
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
        Host m;
        Fill(m, kRoot + 4);
        m.Put<std::int32_t>(kRoot + 0x24, 1);
        assert(!totk::ReadStatus(m.api, kSynthetic).valid);
    }
    { // root + field overflows (wraps to 0x8..0x18, which is mapped: only the overflow check rejects).
        Host h;
        Fill(h, std::numeric_limits<std::uint64_t>::max() - 7);
        for (std::uint64_t at = 0x8; at < 0x1C; ++at)
            h.memory[at] = 0;
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
    }
    { // Second read differs (torn read): the last value of the second pass.
        Host h;
        Fill(h);
        h.corrupt_read = 12;
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
    }
    { // A host without is_mapped is rejected (MissingIsMapped::Reject).
        Host h;
        Fill(h);
        h.api.is_mapped = nullptr;
        assert(!totk::ReadStatus(h.api, kSynthetic).valid);
    }

    std::puts("totk_status_test: ok");
    return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Fake EdenDsmodHostApi for the totk_*_test programs: byte-map guest memory, call counters and
// recorded publishes. Test-only; never part of the module.
#pragma once

#ifdef NDEBUG
#error "totk tests need assert(); build them with -UNDEBUG"
#endif

#include <cassert>
#include <cstdint>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>

#include "core/mods/dsmod_module_abi.h"
#include "totk_build.h"

namespace totk::test {

struct Host {
    static constexpr std::uint64_t kMainBase = 0x80000000ULL;
    static constexpr std::uint64_t kMainSize = 0x100000ULL;

    std::map<std::uint64_t, std::uint8_t> memory;
    unsigned is_mapped_calls = 0, read_calls = 0, write_calls = 0;
    // When non-zero: flip a bit in the bytes returned by the Nth read_memory call (torn read).
    unsigned corrupt_read = 0;
    bool throw_on_publish = false;
    std::map<std::string, std::int64_t> ints;
    EdenDsmodHostApi api{};

    Host() {
        api.abi_version = EDEN_DSMOD_MODULE_ABI_VERSION;
        api.struct_size = sizeof(EdenDsmodHostApi);
        api.abi_hash = EDEN_DSMOD_MODULE_ABI_HASH;
        api.userdata = this;
        api.title_id = kTitleId;
        std::memcpy(api.build_id, kTargetBuildBytes.data(), 32);
        api.main_base = kMainBase;
        api.main_size = kMainSize;
        api.is_mapped = [](void* u, std::uint64_t at, std::uint64_t n) -> EdenDsmodBool {
            auto& h = *static_cast<Host*>(u);
            ++h.is_mapped_calls;
            for (std::uint64_t i = 0; i < n; ++i)
                if (!h.memory.contains(at + i))
                    return false;
            return true;
        };
        api.read_memory = [](void* u, std::uint64_t at, void* out, std::size_t n) -> EdenDsmodBool {
            auto& h = *static_cast<Host*>(u);
            ++h.read_calls;
            for (std::size_t i = 0; i < n; ++i) {
                const auto it = h.memory.find(at + i);
                if (it == h.memory.end())
                    return false;
                static_cast<std::uint8_t*>(out)[i] = it->second;
            }
            if (n && h.read_calls == h.corrupt_read)
                static_cast<std::uint8_t*>(out)[0] ^= 1;
            return true;
        };
        api.write_memory = [](void* u, std::uint64_t, const void*, std::size_t) -> EdenDsmodBool {
            ++static_cast<Host*>(u)->write_calls;
            return false;
        };
        api.publish_i64 = [](void* u, const char* name, std::int64_t value) {
            auto& h = *static_cast<Host*>(u);
            if (h.throw_on_publish)
                throw std::runtime_error("publish failed");
            h.ints[name] = value;
        };
    }
    Host(const Host&) = delete; // api.userdata points at this object
    Host& operator=(const Host&) = delete;

    template <class T>
    void Put(std::uint64_t at, T value) {
        std::uint8_t bytes[sizeof value];
        std::memcpy(bytes, &value, sizeof value);
        for (std::size_t i = 0; i < sizeof value; ++i)
            memory[at + i] = bytes[i];
    }

    unsigned MemoryCalls() const {
        return is_mapped_calls + read_calls + write_calls;
    }
};

} // namespace totk::test

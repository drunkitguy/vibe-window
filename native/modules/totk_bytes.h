// SPDX-License-Identifier: GPL-3.0-or-later
// Bounds-checked little-endian reads over game files, one whole read through read_romfs and the
// printf-style log line; shared by the MSBT reader (totk_msbt.h), the T8a dump (totk_dump.h), the
// font (totk_font.h) and travel (totk_travel.h).
#pragma once

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <string>
#include <vector>

#include "core/mods/dsmod_module_abi.h"

namespace totk {

// 0 / "" outside the data.
struct Reader {
    std::span<const std::uint8_t> d;
    std::uint32_t U8(std::uint64_t at) const {
        return at < d.size() ? d[at] : 0;
    }
    std::uint32_t U16(std::uint64_t at) const {
        return U8(at) | U8(at + 1) << 8;
    }
    std::uint32_t U32(std::uint64_t at) const {
        return U16(at) | U16(at + 2) << 16;
    }
    // A u16 table index, or ~0 (matches nothing) when it lies outside the data.
    std::uint32_t Index(std::uint64_t at) const {
        return at + 2 <= d.size() ? U16(at) : ~0u;
    }
    float F32(std::uint64_t at) const {
        const std::uint32_t v = U32(at);
        float f;
        std::memcpy(&f, &v, 4);
        return f;
    }
    std::string Str(std::uint64_t at, std::size_t max) const {
        std::string s;
        for (std::size_t i = 0; i < max && U8(at + i); ++i)
            s += char(U8(at + i));
        return s;
    }
};

// The whole of `path`: a size query, then one read that must return exactly that size. The
// host caps both at 64 MiB [S1 mod_module_host.cpp:29,408]; a larger answer is refused here too.
inline bool ReadAll(const EdenDsmodHostApi& h, const char* path, std::vector<std::uint8_t>& out) {
    const std::size_t size = h.read_romfs(h.userdata, path, 0, nullptr, 0);
    if (size == 0 || size > (std::size_t{64} << 20))
        return false;
    out.resize(size);
    return h.read_romfs(h.userdata, path, 0, out.data(), size) == size;
}

// One log line, printf-style, of at most N - 1 characters (the T8a dump's lines: 200).
template <std::size_t N = 400>
__attribute__((format(printf, 3, 4))) inline void
Say(const EdenDsmodHostApi& h, std::uint32_t level, const char* format, ...) {
    if (!h.log)
        return;
    char line[N];
    va_list args;
    va_start(args, format);
    std::vsnprintf(line, sizeof line, format, args);
    va_end(args);
    h.log(h.userdata, level, line);
}

} // namespace totk

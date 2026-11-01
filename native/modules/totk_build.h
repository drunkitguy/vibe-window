// SPDX-License-Identifier: GPL-3.0-or-later
// The one TotK build this module reads. Any other build reads nothing.
#pragma once

#include <array>
#include <cstdint>
#include <cstring>

namespace totk {

inline constexpr std::uint64_t kTitleId = 0x0100F2C0115B6000ULL;

// 1.2.0 (update v327680) main NSO build id: 20 bytes from TkSharp NSO_BINARY_ID_120
// [S6 TkPatch.cs:16] and switch-cheats-db's 6F32C68DD3BC7D77 key [S5], plus 12 zero bytes.
// UNVERIFIED: the zero tail; T2 replaces this with `xxd -s 0x40 -l 32 -p main` from the user's dump.
inline constexpr char kTargetBuild[] =
    "6F32C68DD3BC7D77AA714B80E92A096A737CDA77000000000000000000000000";

namespace detail {
constexpr int HexNibble(char c) {
    return c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
}
constexpr std::array<std::uint8_t, 32> DecodeTarget() {
    std::array<std::uint8_t, 32> out{};
    for (std::size_t i = 0; i < out.size(); ++i)
        out[i] = std::uint8_t(HexNibble(kTargetBuild[2 * i]) << 4 | HexNibble(kTargetBuild[2 * i + 1]));
    return out;
}
constexpr bool TargetIsUpperHex64() {
    for (std::size_t i = 0; i < 64; ++i)
        if (HexNibble(kTargetBuild[i]) < 0)
            return false;
    return sizeof(kTargetBuild) == 65;
}
static_assert(TargetIsUpperHex64(), "kTargetBuild must be 64 upper-case hex characters");
} // namespace detail

inline constexpr std::array<std::uint8_t, 32> kTargetBuildBytes = detail::DecodeTarget();

// Exact match of the loader's 64-hex build id string (supports_build).
inline bool IsTargetBuild(const char* hex) {
    return hex && std::strcmp(hex, kTargetBuild) == 0;
}

// Exact match of all 32 raw bytes (EdenDsmodHostApi::build_id in create).
inline bool IsTargetBuild(const std::uint8_t (&bytes)[32]) {
    return std::memcmp(bytes, kTargetBuildBytes.data(), 32) == 0;
}

} // namespace totk

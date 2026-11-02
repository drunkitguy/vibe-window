// SPDX-License-Identifier: GPL-3.0-or-later
// The two TotK builds this module accepts. Any other build reads nothing.
#pragma once

#include <array>
#include <cstdint>
#include <cstring>

namespace totk {

inline constexpr std::uint64_t kTitleId = 0x0100F2C0115B6000ULL;

// 1.2.0 (update v327680) main NSO build id: 20 bytes from TkSharp NSO_BINARY_ID_120
// [S6 TkPatch.cs:16] and switch-cheats-db's 6F32C68DD3BC7D77 key [S5], plus 12 zero bytes.
// UNVERIFIED: the zero tail; T2 replaces this with `xxd -s 0x40 -l 32 -p main` from the user's dump.
inline constexpr char kBuild120[] =
    "6F32C68DD3BC7D77AA714B80E92A096A737CDA77000000000000000000000000";
// 1.2.1 (update v393216): 20 bytes from TkSharp [S6], 16-hex prefix also in [S5] and [S7
// GameProfiles.hpp:20], plus 12 zero bytes. UNVERIFIED: the zero tail (T2).
inline constexpr char kBuild121[] =
    "9B4E43650501A4D4489B4BBFDB740F26AF3CF850000000000000000000000000";

namespace detail {
constexpr int HexNibble(char c) {
    return c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
}
constexpr std::array<std::uint8_t, 32> Decode(const char (&hex)[65]) {
    std::array<std::uint8_t, 32> out{};
    for (std::size_t i = 0; i < out.size(); ++i)
        out[i] = std::uint8_t(HexNibble(hex[2 * i]) << 4 | HexNibble(hex[2 * i + 1]));
    return out;
}
constexpr bool IsUpperHex64(const char (&hex)[65]) {
    for (std::size_t i = 0; i < 64; ++i)
        if (HexNibble(hex[i]) < 0)
            return false;
    return hex[64] == '\0';
}
static_assert(IsUpperHex64(kBuild120) && IsUpperHex64(kBuild121),
              "build ids must be 64 upper-case hex characters");
} // namespace detail

inline constexpr std::array<std::uint8_t, 32> kBuild120Bytes = detail::Decode(kBuild120);
inline constexpr std::array<std::uint8_t, 32> kBuild121Bytes = detail::Decode(kBuild121);

// Exact match of the loader's 64-hex build id string (supports_build).
inline bool IsSupportedBuild(const char* hex) {
    return hex && (std::strcmp(hex, kBuild120) == 0 || std::strcmp(hex, kBuild121) == 0);
}

// Exact match of all 32 raw bytes (EdenDsmodHostApi::build_id in create).
inline bool Is121(const std::uint8_t (&bytes)[32]) {
    return std::memcmp(bytes, kBuild121Bytes.data(), 32) == 0;
}
inline bool IsSupportedBuild(const std::uint8_t (&bytes)[32]) {
    return std::memcmp(bytes, kBuild120Bytes.data(), 32) == 0 || Is121(bytes);
}

} // namespace totk

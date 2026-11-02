// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_test_host.h"

#include <cstdio>
#include <string>

int main() {
    using totk::IsSupportedBuild;

    static_assert(sizeof(totk::kBuild120) == 65 && sizeof(totk::kBuild121) == 65);
    assert(std::string(totk::kBuild120) ==
           "6F32C68DD3BC7D77AA714B80E92A096A737CDA77000000000000000000000000");
    assert(std::string(totk::kBuild121) ==
           "9B4E43650501A4D4489B4BBFDB740F26AF3CF850000000000000000000000000");

    const char* null_id = nullptr;
    assert(!IsSupportedBuild(null_id));
    assert(!IsSupportedBuild(""));
    // TotK 1.4.3 and BotW 1.6.0.
    assert(!IsSupportedBuild("277178B7DBA1B6D4949A84778D4ABC58B31B34F5000000000000000000000000"));
    assert(!IsSupportedBuild("8E9978D50BDD20B4C8395A106C27FFDE00000000000000000000000000000000"));

    for (const char* id : {totk::kBuild120, totk::kBuild121}) {
        assert(IsSupportedBuild(id));
        assert(!IsSupportedBuild(std::string(id, 16).c_str()));       // 16-hex prefix
        assert(!IsSupportedBuild((std::string(id) + "0").c_str()));  // too long
        std::string lower = id;
        for (auto& c : lower)
            c = char(c >= 'A' && c <= 'F' ? c - 'A' + 'a' : c);
        assert(!IsSupportedBuild(lower.c_str()));
        for (const std::size_t at : {std::size_t(0), std::size_t(39), std::size_t(63)}) {
            std::string nibble = id;
            nibble[at] = nibble[at] == '1' ? '2' : '1';
            assert(!IsSupportedBuild(nibble.c_str()));
        }
    }

    // Raw bytes: each decoded constant matches, every single-bit change of either does not;
    // Is121 is true for 1.2.1 only.
    assert(totk::kBuild120Bytes[0] == 0x6F && totk::kBuild120Bytes[19] == 0x77);
    assert(totk::kBuild121Bytes[0] == 0x9B && totk::kBuild121Bytes[19] == 0x50);
    for (const auto& id : {totk::kBuild120Bytes, totk::kBuild121Bytes}) {
        std::uint8_t bytes[32];
        std::memcpy(bytes, id.data(), 32);
        assert(bytes[20] == 0 && bytes[31] == 0);
        assert(IsSupportedBuild(bytes));
        assert(totk::Is121(bytes) == (id == totk::kBuild121Bytes));
        for (unsigned bit = 0; bit < 256; ++bit) {
            bytes[bit / 8] ^= std::uint8_t(1u << bit % 8);
            assert(!IsSupportedBuild(bytes) && !totk::Is121(bytes));
            bytes[bit / 8] ^= std::uint8_t(1u << bit % 8);
        }
    }

    std::puts("totk_build_test: ok");
    return 0;
}

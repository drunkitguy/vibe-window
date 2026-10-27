// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_test_host.h"

#include <cstdio>
#include <string>

int main() {
    using totk::IsTargetBuild;
    using totk::kTargetBuild;

    static_assert(sizeof(kTargetBuild) == 65);
    assert(std::string(kTargetBuild) ==
           "9B4E43650501A4D4489B4BBFDB740F26AF3CF850000000000000000000000000");
    assert(IsTargetBuild(kTargetBuild));

    const char* null_id = nullptr;
    assert(!IsTargetBuild(null_id));
    assert(!IsTargetBuild(""));
    assert(!IsTargetBuild("9B4E43650501A4D4"));                    // 16-hex prefix
    assert(!IsTargetBuild((std::string(kTargetBuild) + "0").c_str())); // too long
    std::string lower = kTargetBuild;
    for (auto& c : lower)
        c = char(c >= 'A' && c <= 'F' ? c - 'A' + 'a' : c);
    assert(!IsTargetBuild(lower.c_str()));
    std::string nibble = kTargetBuild;
    nibble[63] = '1';
    assert(!IsTargetBuild(nibble.c_str()));
    nibble = kTargetBuild;
    nibble[0] = 'A';
    assert(!IsTargetBuild(nibble.c_str()));
    // TotK 1.4.3 and BotW 1.6.0.
    assert(!IsTargetBuild("277178B7DBA1B6D4949A84778D4ABC58B31B34F5000000000000000000000000"));
    assert(!IsTargetBuild("8E9978D50BDD20B4C8395A106C27FFDE00000000000000000000000000000000"));

    // Raw bytes: the decoded constant matches, every single-bit change does not.
    std::uint8_t bytes[32];
    std::memcpy(bytes, totk::kTargetBuildBytes.data(), 32);
    assert(bytes[0] == 0x9B && bytes[19] == 0x50 && bytes[20] == 0 && bytes[31] == 0);
    assert(IsTargetBuild(bytes));
    for (unsigned bit = 0; bit < 256; ++bit) {
        bytes[bit / 8] ^= std::uint8_t(1u << bit % 8);
        assert(!IsTargetBuild(bytes));
        bytes[bit / 8] ^= std::uint8_t(1u << bit % 8);
    }

    std::puts("totk_build_test: ok");
    return 0;
}

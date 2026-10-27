// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// Standalone check of src/yuzu_cmd/window_rect.cpp (patched eden-duo), the parser behind
// eden-cli's --window-rect and --aux-rect. Runs natively and as a MinGW exe under wine; see run.sh.

#undef NDEBUG
#include <cassert>
#include <cstdio>
#include <string>

#include "yuzu_cmd/window_rect.h"

using DSModHarness::ParseWindowRect;

namespace {

void Valid(const char* text, int x, int y, int w, int h) {
    std::string error;
    const auto rect = ParseWindowRect(text, error);
    assert(rect && error.empty());
    assert(rect->x == x && rect->y == y && rect->w == w && rect->h == h);
}

void Invalid(const char* text) {
    std::string error;
    assert(!ParseWindowRect(text, error));
    assert(!error.empty());
    std::printf("  %-26s -> %s\n", (std::string("'") + text + "'").c_str(), error.c_str());
}

} // namespace

int main() {
    Valid("0,0,1920,1080", 0, 0, 1920, 1080);
    Valid("0,1080,1240,1080", 0, 1080, 1240, 1080);
    Valid("7,8,1,1", 7, 8, 1, 1);
    Valid("2147483647,0,2147483647,1", 2147483647, 0, 2147483647, 1);

    std::printf("rejected:\n");
    Invalid("");                       // missing fields
    Invalid("0,0,1920");               // missing field
    Invalid("0,0,1920,");              // empty last field
    Invalid(",0,1920,1080");           // empty first field
    Invalid("0,0,1920,1080,5");        // extra field
    Invalid("0,0,1920,1080,");         // extra empty field
    Invalid("a,0,1920,1080");          // non-number
    Invalid("0,0,1920x,1080");         // trailing junk
    Invalid("0,0,1.5,1080");           // not an integer
    Invalid("0,0,+1920,1080");         // sign
    Invalid(" 0,0,1920,1080");         // whitespace
    Invalid("0, 0,1920,1080");         // whitespace
    Invalid("-1,0,1920,1080");         // negative x
    Invalid("0,-5,1920,1080");         // negative y
    Invalid("0,0,0,1080");             // zero w
    Invalid("0,0,1920,0");             // zero h
    Invalid("0,0,-1920,1080");         // negative w
    Invalid("0,0,1920,-1");            // negative h
    Invalid("2147483648,0,1920,1080"); // beyond int
    Invalid("0,0,1920,99999999999");   // beyond int
    Invalid("0,-2147483649,1,1");      // below int

    std::printf("window_rect_test: all checks passed\n");
    return 0;
}

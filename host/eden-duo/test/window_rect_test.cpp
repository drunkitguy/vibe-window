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

void Invalid(const char* text, const char* expected) {
    std::string error;
    assert(!ParseWindowRect(text, error));
    std::printf("  %-26s -> %s\n", (std::string("'") + text + "'").c_str(), error.c_str());
    assert(error.find(expected) != std::string::npos);
}

} // namespace

int main() {
    Valid("0,0,1920,1080", 0, 0, 1920, 1080);
    Valid("0,1080,1240,1080", 0, 1080, 1240, 1080);
    Valid("7,8,1,1", 7, 8, 1, 1);
    Valid("0,0,32767,32767", 0, 0, 32767, 32767);
    Valid("32766,32766,1,1", 32766, 32766, 1, 1);

    std::printf("rejected:\n");
    Invalid("", "not a number");                                // missing fields
    Invalid("0,0,1920", "got 3 values");                        // missing field
    Invalid("0,0,1920,", "not a number");                       // empty last field
    Invalid(",0,1920,1080", "not a number");                    // empty first field
    Invalid("0,0,1920,1080,5", "more than 4 values");           // extra field
    Invalid("0,0,1920,1080,", "more than 4 values");            // extra empty field
    Invalid("a,0,1920,1080", "not a number");                   // non-number
    Invalid("0,0,1920x,1080", "not a number");                  // trailing junk
    Invalid("0,0,1.5,1080", "not a number");                    // not an integer
    Invalid("0,0,+1920,1080", "not a number");                  // sign
    Invalid(" 0,0,1920,1080", "not a number");                  // whitespace
    Invalid("0, 0,1920,1080", "not a number");                  // whitespace
    Invalid("-1,0,1920,1080", "x and y");                       // negative x
    Invalid("0,-5,1920,1080", "x and y");                       // negative y
    Invalid("0,0,0,1080", "w and h");                           // zero w
    Invalid("0,0,1920,0", "w and h");                           // zero h
    Invalid("0,0,-1920,1080", "w and h");                       // negative w
    Invalid("0,0,1920,-1", "w and h");                          // negative h
    Invalid("2147483648,0,1920,1080", "out of range");          // beyond int
    Invalid("0,0,1920,99999999999", "out of range");            // beyond int
    Invalid("0,-2147483649,1,1", "out of range");               // below int
    Invalid("32767,0,1,1", "x+w and y+h");                      // x+w past 32767
    Invalid("0,32000,1,768", "x+w and y+h");                    // y+h past 32767
    Invalid("2147483647,0,2147483647,1", "x+w and y+h");        // int max, x+w overflows int
    Invalid("805240832,0,1920,1080", "x+w and y+h");            // SDL_WINDOWPOS_CENTERED
    Invalid("0,536805376,1920,1080", "x+w and y+h");            // SDL_WINDOWPOS_UNDEFINED

    std::printf("window_rect_test: all checks passed\n");
    return 0;
}

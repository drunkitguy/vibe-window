// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// Just the scancodes src/yuzu_cmd/sdl_config.cpp names, with SDL3's values, so qt_config_test
// builds without SDL. The test only compares the strings they turn into.
#pragma once

enum SDL_Scancode {
    SDL_SCANCODE_A = 4, SDL_SCANCODE_B = 5, SDL_SCANCODE_D = 7, SDL_SCANCODE_F = 9,
    SDL_SCANCODE_G = 10, SDL_SCANCODE_H = 11, SDL_SCANCODE_I = 12, SDL_SCANCODE_J = 13,
    SDL_SCANCODE_K = 14, SDL_SCANCODE_L = 15, SDL_SCANCODE_M = 16, SDL_SCANCODE_N = 17,
    SDL_SCANCODE_Q = 20, SDL_SCANCODE_S = 22, SDL_SCANCODE_T = 23, SDL_SCANCODE_W = 26,
    SDL_SCANCODE_X = 27, SDL_SCANCODE_Z = 29, SDL_SCANCODE_1 = 30, SDL_SCANCODE_2 = 31,
    SDL_SCANCODE_7 = 36, SDL_SCANCODE_8 = 37, SDL_SCANCODE_RIGHT = 79, SDL_SCANCODE_LEFT = 80,
    SDL_SCANCODE_DOWN = 81, SDL_SCANCODE_UP = 82,
};

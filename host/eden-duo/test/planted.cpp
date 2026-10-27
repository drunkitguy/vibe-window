// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// stage_test's planted-import case (Windows): with -DPLANTED_DEP this is planted.dll, placed next
// to stage_test.exe; without, a module that imports it.
#ifdef PLANTED_DEP
extern "C" __declspec(dllexport) int planted_value();
int planted_value() {
    return 42;
}
#else
extern "C" __declspec(dllimport) int planted_value();
extern "C" __declspec(dllexport) int importer_value();
int importer_value() {
    return planted_value();
}
#endif

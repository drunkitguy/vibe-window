// SPDX-License-Identifier: GPL-3.0-or-later
// The map's tile files, read by the runtime from the player's romfs (nothing shipped): one path
// per tile and level, found once per session by raw size queries [docs/PLAN.md T7].
#pragma once

#include <array>
#include <cstdio>
#include <string>

#include "core/mods/dsmod_module_abi.h"

namespace totk::map {

// ---- the tile table: names and counts as found on 1.4.3 [S28 §2]; UNVERIFIED on 1.2.0 (T8a) ---
// Layer letters in totk.map.layer order: 0 surface (G), 1 sky (S), 2 depths (U).
inline constexpr char kLayers[] = {'G', 'S', 'U'};
inline constexpr int kCols = 12, kRows = 10, kTiles = kCols * kRows; // n = r * kCols + c
// Levels published per tile: totk.map.tile.{n} the overview, .d the detail.
inline constexpr const char* kLevels[] = {"02", "01"};
// Suffixes in probe order: each reveal with each variant (only fully revealed tiles in v1).
inline constexpr const char* kReveal[] = {"", "_T", "_TT", "_TTT", "_TTTT"};
inline constexpr const char* kVariant[] = {"", "_A_A", "_A"};
// ------------------------------------------------------------------------------------------------

inline constexpr int kLayerCount = int(std::size(kLayers));
inline constexpr int kLevelCount = int(std::size(kLevels));

// The image key the runtime decodes (zstd via Eden Duo patch 0009; a whole file, no '#').
inline std::string TilePath(int layer, int n, int level, const char* reveal, const char* variant) {
    char path[96];
    std::snprintf(path, sizeof path, "romfs:/UI/Map/MainField/%c_%02d-%02d_%s%s%s.bntx.zs",
                  kLayers[layer], n % kCols, n / kCols, kLevels[level], reveal, variant);
    return path;
}

// Every layer's tile paths ([layer][n][level]); empty where no file answered.
using Tiles = std::array<std::array<std::array<std::string, kLevelCount>, kTiles>, kLayerCount>;

// Probes every tile of every layer: the first suffix whose overview file has a size wins and
// names both levels. One log line per layer: the count, and the first missing tile's path.
inline Tiles Probe(const EdenDsmodHostApi& h) {
    Tiles tiles;
    for (int l = 0; l < kLayerCount; ++l) {
        int found = 0;
        std::string missing;
        for (int n = 0; n < kTiles; ++n) {
            for (const char* reveal : kReveal)
                for (const char* variant : kVariant) {
                    if (!tiles[l][n][0].empty())
                        break;
                    std::string path = TilePath(l, n, 0, reveal, variant);
                    if (h.read_romfs(h.userdata, path.c_str(), 0, nullptr, 0) > 0) {
                        tiles[l][n][0] = std::move(path);
                        tiles[l][n][1] = TilePath(l, n, 1, reveal, variant);
                    }
                }
            if (!tiles[l][n][0].empty())
                ++found;
            else if (missing.empty())
                missing = TilePath(l, n, 0, "", "");
        }
        if (h.log) {
            char line[200];
            std::snprintf(line, sizeof line, "totk: map tiles %c: %d/%d%s%s", kLayers[l], found,
                          kTiles, missing.empty() ? "" : ", first missing ", missing.c_str());
            h.log(h.userdata, EDEN_DSMOD_LOG_INFO, line);
        }
    }
    return tiles;
}

} // namespace totk::map

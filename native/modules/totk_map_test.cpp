// SPDX-License-Identifier: GPL-3.0-or-later
#include "totk_map.h"
#include "totk_test_host.h" // asserts stay on

#include <cstdio>
#include <string>
#include <vector>

using totk::map::kTiles;
using totk::map::Probe;
using totk::map::TilePath;
using totk::test::Host;

int main() {
    const std::string dir = "romfs:/UI/Map/MainField/";
    // Paths: layer letter, column and row (n = r * 12 + c), level, suffix; whole NX texture
    // files (no '#', .bntx.zs), the form the runtime's image binds accept.
    assert(TilePath(0, 0, 0, "", "") == dir + "G_00-00_02.bntx.zs");
    assert(TilePath(1, 119, 0, "", "") == dir + "S_11-09_02.bntx.zs");
    assert(TilePath(2, 4 * 12 + 5, 1, "_T", "_A") == dir + "U_05-04_01_T_A.bntx.zs");
    assert(TilePath(0, 12, 1, "_TTTT", "_A_A") == dir + "G_00-01_01_TTTT_A_A.bntx.zs");

    // Nothing in the romfs: every tile tries the 15 suffixes in order (each reveal with each
    // variant), overview level only; every path empty; one line per layer.
    {
        Host h;
        const auto tiles = Probe(h.api);
        assert(h.romfs_calls == 3 * kTiles * 15);
        const std::vector<std::string> first(h.romfs_paths.begin(), h.romfs_paths.begin() + 15);
        std::vector<std::string> want;
        for (const char* reveal : {"", "_T", "_TT", "_TTT", "_TTTT"})
            for (const char* variant : {"", "_A_A", "_A"})
                want.push_back(dir + "G_00-00_02" + reveal + variant + ".bntx.zs");
        assert(first == want);
        for (const auto& path : h.romfs_paths)
            assert(path.find('#') == std::string::npos && path.ends_with(".bntx.zs"));
        for (const auto& layer : tiles)
            for (const auto& tile : layer)
                assert(tile[0].empty() && tile[1].empty());
        const std::string none = ": 0/120, first missing " + dir;
        assert((h.logs == std::vector<std::string>{
                              "totk: map tiles G" + none + "G_00-00_02.bntx.zs",
                              "totk: map tiles S" + none + "S_00-00_02.bntx.zs",
                              "totk: map tiles U" + none + "U_00-00_02.bntx.zs"}));
    }

    // The first suffix with an overview file names both levels; a detail file alone does not
    // count; probing a tile stops at its hit.
    {
        Host h;
        h.PutRomfs(dir + "G_00-00_02.bntx.zs", {1});
        h.PutRomfs(dir + "S_11-09_02_T_A.bntx.zs", {1});
        h.PutRomfs(dir + "S_11-09_02_TT.bntx.zs", {1}); // later in the order: not taken
        h.PutRomfs(dir + "U_05-04_02_TTTT_A.bntx.zs", {1});
        h.PutRomfs(dir + "U_06-04_01.bntx.zs", {1}); // detail only
        const auto tiles = Probe(h.api);
        assert(tiles[0][0][0] == dir + "G_00-00_02.bntx.zs" &&
               tiles[0][0][1] == dir + "G_00-00_01.bntx.zs");
        assert(tiles[1][119][0] == dir + "S_11-09_02_T_A.bntx.zs" &&
               tiles[1][119][1] == dir + "S_11-09_01_T_A.bntx.zs");
        assert(tiles[2][53][0] == dir + "U_05-04_02_TTTT_A.bntx.zs" &&
               tiles[2][53][1] == dir + "U_05-04_01_TTTT_A.bntx.zs");
        assert(tiles[2][54][0].empty() && tiles[2][54][1].empty());
        int hits = 0;
        for (const auto& layer : tiles)
            for (const auto& tile : layer)
                hits += !tile[0].empty();
        assert(hits == 3);
        // Tile 0 of G: one query (its first suffix answered); S 119: six; U 53: all fifteen.
        assert(h.romfs_calls == 3 * kTiles * 15 - 14 - 9);
        const std::string one = ": 1/120, first missing " + dir;
        assert(h.logs[0] == "totk: map tiles G" + one + "G_01-00_02.bntx.zs");
        assert(h.logs[1] == "totk: map tiles S" + one + "S_00-00_02.bntx.zs");
    }

    std::puts("totk_map_test: ok");
    return 0;
}

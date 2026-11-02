#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Usage: ZSTD_DIR=<zstd/ of the zstandard-0.23.0 sdist> render.sh <eden-duo tree> <build dir>.
# Builds <build dir>/render_page from render_page.cpp and the runtime's own manifest parser and
# renderer (src/core/mods) with g++, like host/eden-duo/test/run.sh. Needs nlohmann-json and fmt
# headers and libfmt. Nothing third-party is kept here: stb comes from the tree, unordered_dense from
# the tree's CPM cache or a pinned download, zstd.h (parsed, never linked) from ZSTD_DIR.
set -e; T=$(cd "$(dirname "$0")" && pwd); E=$(cd "$1" && pwd); mkdir -p "$2/inc/ankerl"; cd "$2"
Z=$(cd "${ZSTD_DIR:?set ZSTD_DIR to the zstd/ directory of the zstandard-0.23.0 sdist}" && pwd)
printf '%s  %s\n' 4fab9cf39160cd15fbd9fbc2fd637ee300002fa70e307f85e8a7e45c1e057906 "$Z/zstd.h" | sha256sum -c
U=$(ls "$E"/.cache/cpm/unordered_dense/*/include/ankerl/unordered_dense.h 2>/dev/null | head -n 1) || true
if [ -n "$U" ]; then cp "$U" inc/ankerl/; else
    curl -sSfL -o inc/ankerl/unordered_dense.h \
        https://raw.githubusercontent.com/martinus/unordered_dense/v4.5.0/include/ankerl/unordered_dense.h
    printf '%s  %s\n' b5f67c6895a58e059273cf24bae16cb07e6c72f0e3f88bbebecaddd987b102b3 \
        inc/ankerl/unordered_dense.h | sha256sum -c
fi
# The widget keys the runtime knows: every identifier literal in its sources (check_manifest.py's rule).
grep -ohE '"[A-Za-z0-9_]+"' "$E"/src/core/mods/*.cpp | sort -u | sed 's/$/,/' > runtime_keys.inc
M="$E/src/core/mods"
export F="-std=c++20 -O1 -ffunction-sections -fdata-sections -I$T/inc -I$PWD/inc -I$PWD -I$E/src -I$E/externals/stb -I$Z"
printf '%s\n' "$M"/mod_manifest.cpp "$M"/mod_expr.cpp "$M"/mod_ui*.cpp "$M"/mod_msbt.cpp \
    "$M"/mod_settings.cpp "$M"/mod_actions.cpp "$M"/mod_nx_runtime.cpp "$M"/mod_nav.cpp \
    "$M"/mod_map.cpp "$M"/mod_host_values.cpp "$M"/mod_clock.cpp "$M"/mod_font_pages.cpp \
    "$E"/src/common/cityhash.cpp "$E"/src/common/stb.cpp |
    xargs -P "$(nproc)" -I{} sh -c 'g++ $F -w -c "$1" -o "$(basename "$1" .cpp).o"' _ {}
g++ $F -Wall -Wextra -Werror -c "$T/render_page.cpp" -o render_page.o
# --gc-sections drops the runtime code the parser and renderer never reach (ModRuntime, System).
g++ ./*.o -Wl,--gc-sections -lfmt -o render_page

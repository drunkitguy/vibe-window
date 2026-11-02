#!/bin/sh
# Usage: ZSTD_DIR=<zstd/ of the zstandard-0.23.0 sdist> run.sh <patched eden-duo tree> <build dir>.
# Builds stage_test + the fake module, window_rect_test and zstd_test with g++ and MinGW, then runs
# the Linux builds natively and the Windows builds under wine (wine needs a UTF-8 locale to map the
# non-ASCII cache root to a Unix name). planted.dll lands next to stage_test.exe for the
# planted-import case. ZSTD_DIR's zstd.c/zstd.h must match the hashes in docs/PLAN.md (T3a').
set -e; T=$(cd "$(dirname "$0")" && pwd); E=$(cd "$1" && pwd); mkdir -p "$2"; cd "$2"
Z=$(cd "${ZSTD_DIR:?set ZSTD_DIR to the zstd/ directory of the zstandard-0.23.0 sdist}" && pwd)
printf '%s  %s\n' 3ef459f74b63efd8bf59bea100f260541a9873b34deac0e373381c3d09acb9a1 "$Z/zstd.c" \
    4fab9cf39160cd15fbd9fbc2fd637ee300002fa70e307f85e8a7e45c1e057906 "$Z/zstd.h" | sha256sum -c
F="-std=c++20 -Wall -Wextra -Wshadow -Wmissing-declarations -Werror -I$E/src"
S="$T/stage_test.cpp $E/src/core/mods/mod_module_stage.cpp"; M="$E/src/tests/core/mods/fake_module.cpp"
R="$T/window_rect_test.cpp $E/src/yuzu_cmd/window_rect.cpp"
Q="$T/zstd_test.cpp $E/src/core/mods/mod_zstd.cpp -I$Z"
g++ $F -shared -fPIC $M -o fake.so ; g++ $F $S -ldl -o stage_test ; ./stage_test ./fake.so
g++ $F $R -o window_rect_test ; ./window_rect_test
gcc -O2 -c "$Z/zstd.c" -o zstd.o ; g++ $F $Q zstd.o -o zstd_test ; ./zstd_test
W=x86_64-w64-mingw32-g++-posix; $W $F -static -shared $M -o fake.dll ; $W $F -static $S -o stage_test.exe
$W $F -static $R -o window_rect_test.exe
x86_64-w64-mingw32-gcc-posix -O2 -c "$Z/zstd.c" -o zstd_win.o ; $W $F -static $Q zstd_win.o -o zstd_test.exe
$W $F -static -shared -DPLANTED_DEP "$T/planted.cpp" -o planted.dll ; $W $F -static -shared "$T/planted.cpp" planted.dll -o importer.dll
LC_ALL=C.UTF-8 WINEDEBUG=-all /usr/lib/wine/wine64 stage_test.exe fake.dll importer.dll
LC_ALL=C.UTF-8 WINEDEBUG=-all /usr/lib/wine/wine64 window_rect_test.exe
LC_ALL=C.UTF-8 WINEDEBUG=-all /usr/lib/wine/wine64 zstd_test.exe

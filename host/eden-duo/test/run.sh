#!/bin/sh
# Usage: run.sh <patched eden-duo tree> <build dir>. Builds stage_test + the fake module with g++
# and MinGW, then runs the Linux build natively and the Windows build under wine (wine needs a
# UTF-8 locale to map the non-ASCII cache root to a Unix name).
set -e; T=$(cd "$(dirname "$0")" && pwd); E=$(cd "$1" && pwd); mkdir -p "$2"; cd "$2"
F="-std=c++20 -Wall -Wextra -Wshadow -Wmissing-declarations -Werror -I$E/src"
S="$T/stage_test.cpp $E/src/core/mods/mod_module_stage.cpp"; M="$E/src/tests/core/mods/fake_module.cpp"
g++ $F -shared -fPIC $M -o fake.so ; g++ $F $S -ldl -o stage_test ; ./stage_test ./fake.so
W=x86_64-w64-mingw32-g++-posix; $W $F -static -shared $M -o fake.dll ; $W $F -static $S -o stage_test.exe
LC_ALL=C.UTF-8 WINEDEBUG=-all /usr/lib/wine/wine64 stage_test.exe fake.dll

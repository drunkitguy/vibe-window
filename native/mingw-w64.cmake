# SPDX-License-Identifier: GPL-3.0-or-later
# Cross build of the windows-x86_64 module (a PE DLL) with MinGW-w64; tests run under wine.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER x86_64-w64-mingw32-gcc-posix)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++-posix)
set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
# wine needs a UTF-8 locale here, and its debug channels drown the test output.
set(CMAKE_CROSSCOMPILING_EMULATOR /usr/bin/env;LC_ALL=C.UTF-8;WINEDEBUG=-all;/usr/lib/wine/wine64)

# SPDX-License-Identifier: GPL-3.0-or-later
# TotK companion module. Needs only the three header-only Eden Duo SDK headers: no fmt, json,
# zstd, CPM or network.

cmake_minimum_required(VERSION 3.22)
project(dsmod-totk LANGUAGES CXX)

set(EDEN_SOURCE_ROOT "" CACHE PATH "Eden Duo checkout providing src/core/mods (the module SDK)")
if(NOT IS_ABSOLUTE "${EDEN_SOURCE_ROOT}")
    message(FATAL_ERROR "Set -DEDEN_SOURCE_ROOT=/absolute/path/to/eden-duo")
endif()
foreach(header IN ITEMS dsmod_module_abi.h dsmod_module_extensions.h modules/dsmod_module_sdk.h)
    if(NOT EXISTS "${EDEN_SOURCE_ROOT}/src/core/mods/${header}")
        message(FATAL_ERROR "Eden Duo SDK header missing: ${header}")
    endif()
endforeach()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(MODULE_DIR "${CMAKE_CURRENT_LIST_DIR}/modules")
include_directories("${MODULE_DIR}" "${EDEN_SOURCE_ROOT}/src")
add_compile_options(-Wall -Wextra -Werror)
if(MINGW)
    # No libstdc++/libgcc/libwinpthread DLLs next to the module (or the test programs under wine).
    add_link_options(-static-libgcc -static-libstdc++ -static)
endif()

add_library(dsmod-totk SHARED "${MODULE_DIR}/0100F2C0115B6000.cpp")
set_target_properties(dsmod-totk PROPERTIES
    PREFIX ""
    OUTPUT_NAME 0100F2C0115B6000
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
if(WIN32)
    target_sources(dsmod-totk PRIVATE "${MODULE_DIR}/0100F2C0115B6000.def")
else()
    set(exports "${CMAKE_CURRENT_BINARY_DIR}/dsmod-totk.exports")
    file(WRITE "${exports}" "{\n    global:\n        eden_dsmod_get_module;\n    local:\n        *;\n};\n")
    target_link_options(dsmod-totk PRIVATE "-Wl,--no-undefined" "-Wl,--version-script=${exports}")
    set_property(TARGET dsmod-totk APPEND PROPERTY LINK_DEPENDS "${exports}")
endif()

option(BUILD_TESTING "Build and register the totk_*_test programs" ON)
if(BUILD_TESTING)
    enable_testing()
    file(GLOB tests CONFIGURE_DEPENDS "${MODULE_DIR}/totk_*_test.cpp")
    foreach(source IN LISTS tests)
        get_filename_component(name "${source}" NAME_WE)
        add_executable(${name} "${source}")
        target_compile_options(${name} PRIVATE -UNDEBUG) # asserts stay on in Release
        # Cross builds run through CMAKE_CROSSCOMPILING_EMULATOR (wine, see mingw-w64.cmake).
        add_test(NAME ${name} COMMAND ${name})
    endforeach()
endif()

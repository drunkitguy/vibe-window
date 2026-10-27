// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// Standalone check of src/core/mods/mod_module_stage.cpp (patched eden-duo), built without the
// emulator. Usage: stage_test <fake module built from src/tests/core/mods/fake_module.cpp>
// [Windows: <module importing planted.dll, which sits next to stage_test.exe>].
// Runs natively on Linux and as a MinGW exe under wine; see run.sh.

#undef NDEBUG
#include <cassert>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <windows.h>

#include <aclapi.h>
#else
#include <dlfcn.h>
#endif

#include "core/mods/dsmod_module_abi.h"
#include "core/mods/mod_module_stage.h"

namespace fs = std::filesystem;
using Core::Mods::OpenStaged;
using Core::Mods::RemoveStaged;
using Core::Mods::StageModule;

namespace {
constexpr char Title[] = "0100000000000001";

std::vector<std::uint8_t> ReadFile(const fs::path& path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>{file}, std::istreambuf_iterator<char>{}};
}

void* Symbol(void* handle, const char* name) {
#ifdef _WIN32
    return reinterpret_cast<void*>(GetProcAddress(static_cast<HMODULE>(handle), name));
#else
    return dlsym(handle, name);
#endif
}

void Unload(void* handle) {
#ifdef _WIN32
    assert(FreeLibrary(static_cast<HMODULE>(handle)));
#else
    assert(dlclose(handle) == 0);
#endif
}

bool ReadOnly(const fs::path& file) {
#ifdef _WIN32
    return (GetFileAttributesW(file.c_str()) & FILE_ATTRIBUTE_READONLY) != 0;
#else
    constexpr auto write =
        fs::perms::owner_write | fs::perms::group_write | fs::perms::others_write;
    return (fs::status(file).permissions() & write) == fs::perms::none;
#endif
}

void Age(const fs::path& path, std::chrono::minutes age) {
#ifdef _WIN32
    // MinGW's last_write_time setter cannot open a directory; FILE_FLAG_BACKUP_SEMANTICS can.
    const HANDLE file = CreateFileW(path.c_str(), FILE_WRITE_ATTRIBUTES,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                                    OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    assert(file != INVALID_HANDLE_VALUE);
    FILETIME now;
    GetSystemTimeAsFileTime(&now);
    ULARGE_INTEGER time{.LowPart = now.dwLowDateTime, .HighPart = now.dwHighDateTime};
    time.QuadPart -= static_cast<ULONGLONG>(age.count()) * 60 * 10'000'000; // 100 ns units
    const FILETIME then{.dwLowDateTime = time.LowPart, .dwHighDateTime = time.HighPart};
    assert(SetFileTime(file, nullptr, nullptr, &then));
    CloseHandle(file);
#else
    fs::last_write_time(path, fs::file_time_type::clock::now() - age);
#endif
}

#ifdef _WIN32
bool Wine() {
    return GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version") != nullptr;
}

/// The DACL's ACE count, and whether it ignores the parent's ACL (protected).
std::pair<int, bool> Dacl(const fs::path& path) {
    PACL dacl{};
    PSECURITY_DESCRIPTOR descriptor{};
    assert(GetNamedSecurityInfoW(path.c_str(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, nullptr,
                                 nullptr, &dacl, nullptr, &descriptor) == ERROR_SUCCESS);
    SECURITY_DESCRIPTOR_CONTROL control{};
    DWORD revision{};
    assert(GetSecurityDescriptorControl(descriptor, &control, &revision));
    const std::pair<int, bool> result{dacl ? dacl->AceCount : -1,
                                      (control & SE_DACL_PROTECTED) != 0};
    LocalFree(descriptor);
    return result;
}
#endif

/// A module-* symlink planted in the cache must not lead SweepStale or RemoveStaged to a file
/// outside it. Returns false where the OS refuses to make the link (Windows without privilege).
bool CheckSymlinks(const fs::path& root, const std::vector<std::uint8_t>& module) {
    const auto victim = root.parent_path() / "victim";
    fs::create_directories(victim);
    std::ofstream{victim / "keep.txt"} << "outside the cache";
    fs::permissions(victim / "keep.txt", fs::perms::owner_read);
    std::error_code error;
    fs::create_directory_symlink(victim, root / "module-link", error);
    if (error) {
        std::printf("  symlink case skipped: %s\n", error.message().c_str());
        fs::permissions(victim / "keep.txt", fs::perms::owner_write, fs::perm_options::add);
        fs::remove_all(victim);
        return false;
    }
    Age(victim, std::chrono::minutes{60});
    Core::Mods::SweepStale(root);
    assert(fs::exists(fs::symlink_status(root / "module-link")));
    assert(fs::exists(victim / "keep.txt") && ReadOnly(victim / "keep.txt"));
    fs::remove(root / "module-link");

    const auto staged = StageModule(module, Title, root);
    fs::create_symlink(victim / "keep.txt", staged / "link.txt");
    RemoveStaged(staged);
    assert(!fs::exists(fs::symlink_status(staged)));
    assert(fs::exists(victim / "keep.txt") && ReadOnly(victim / "keep.txt"));
    fs::permissions(victim / "keep.txt", fs::perms::owner_write, fs::perm_options::add);
    fs::remove_all(victim);
    return true;
}

void CheckRoot(const fs::path& root, const std::vector<std::uint8_t>& module) {
    // A failed stage (here: a title whose parent directory does not exist) leaves nothing behind.
    bool threw = false;
    try {
        StageModule(module, "missing/0100000000000001", root);
    } catch (const std::exception&) {
        threw = true;
    }
    assert(threw && fs::is_empty(root));

    const auto first = StageModule(module, Title, root);
    const auto file = first / (std::string{Title} + ".so");
    assert(first.parent_path() == root);
    assert(first.filename().string().starts_with("module-"));
    assert(ReadFile(file) == module);
    assert(ReadOnly(file));
#ifdef _WIN32
    // Owner and SYSTEM only, not inherited; the module inherits the same two entries. Wine does
    // not keep a file's security descriptor (it derives one from the Unix mode), so this is only
    // asserted on real Windows.
    const auto [aces, protected_dacl] = Dacl(first);
    std::printf("  staging dir DACL: %d entries, protected %d; module: %d entries%s\n", aces,
                protected_dacl, Dacl(file).first, Wine() ? " (wine: not checked)" : "");
    assert(Wine() || (aces == 2 && protected_dacl && Dacl(file).first == 2));
#else
    // mkdtemp: owner-only.
    assert(fs::status(first).permissions() == fs::perms::owner_all);
#endif
    const auto second = StageModule(module, Title, root);
    assert(second != first);

    std::string error;
    void* handle = OpenStaged(file, error);
    assert(handle && error.empty());
    const auto get =
        reinterpret_cast<EdenDsmodGetModuleFn>(Symbol(handle, "eden_dsmod_get_module"));
    assert(get);
    const auto* api = get(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
    assert(api && api->struct_size == sizeof(EdenDsmodModuleApi));
    assert(api->title_id == UINT64_C(0x0100000000000001));
    assert(api->supports_build && api->supports_build("AAAAAAAAAAAAAAAA"));

    assert(!OpenStaged(root / "missing" / "0100000000000001.so", error));
    assert(!error.empty());
    std::printf("  bad path error: %s\n", error.c_str());
    // A relative name would be searched for, so it is refused before the loader sees it.
    assert(!OpenStaged("0100000000000001.so", error) && !error.empty());
#ifdef _WIN32
    // So are drive- and root-relative forms, while a \\?\ path is absolute and loads.
    for (const wchar_t* relative : {L"C:0100000000000001.so", L"\\0100000000000001.so"}) {
        assert(!OpenStaged(relative, error) && error == "module path is not absolute");
    }
    void* prefixed = OpenStaged(L"\\\\?\\" + file.native(), error);
    assert(prefixed && error.empty());
    Unload(prefixed);
#endif

    // Facts check (PLAN fact 2): does a plain remove_all cope with the read-only module?
    std::error_code plain;
    fs::remove_all(second, plain);
    std::printf("  plain remove_all of a staged dir: %s\n",
                plain ? plain.message().c_str() : "succeeded");

    Unload(handle);
    RemoveStaged(first);
    RemoveStaged(second);
    assert(!fs::exists(first) && !fs::exists(second));
    assert(fs::is_empty(root));

    // SweepStale: only module-* directories older than 10 minutes go.
    const auto stale = StageModule(module, Title, root);
    const auto recent = StageModule(module, Title, root);
    const auto other = root / "other-old";
    const auto named = root / fs::path{u8"keep-é"};
    fs::create_directory(other);
    fs::create_directory(named);
    std::ofstream{root / "module-file"} << "not a directory";
    Age(stale, std::chrono::minutes{60});
    Age(other, std::chrono::minutes{60});
    Age(recent, std::chrono::minutes{5});
    Core::Mods::SweepStale(root);
    assert(!fs::exists(stale));
    assert(fs::exists(recent) && fs::exists(other) && fs::exists(named));
    assert(fs::exists(root / "module-file"));
    Core::Mods::SweepStale(root / "missing");
    Core::Mods::SweepStale({});
    RemoveStaged(recent);
    fs::remove_all(other);
    fs::remove_all(named);
    fs::remove(root / "module-file");
#ifndef _WIN32
    assert(CheckSymlinks(root, module));
#else
    CheckSymlinks(root, module);
#endif
}

#ifdef _WIN32
/// A module importing a DLL found only next to the exe must not load: staged modules resolve
/// imports from System32 alone.
void CheckPlantedImport(const fs::path& root, const std::vector<std::uint8_t>& importer) {
    const auto directory = StageModule(importer, Title, root);
    const auto file = directory / (std::string{Title} + ".so");
    // Control: the default search order would pick up planted.dll from the exe directory.
    const HMODULE control = LoadLibraryExW(file.c_str(), nullptr, 0);
    assert(control);
    FreeLibrary(control);
    std::string error;
    assert(!OpenStaged(file, error) && !error.empty());
    std::printf("  planted import refused: %s\n", error.c_str());
    // Wine keeps an image whose imports failed open, so there the staging dir outlives
    // RemoveStaged. Nothing sweeps it later on desktop: SweepStale runs only from
    // SetModuleCacheDirectory, which only Android calls.
    RemoveStaged(directory);
    std::printf("  failed-load staging dir removed: %d\n", !fs::exists(directory));
    assert(Wine() || !fs::exists(directory));
}
#endif
} // namespace

int main(int argc, char** argv) {
#ifdef _WIN32
    assert(argc == 3);
    const auto importer = ReadFile(argv[2]);
    assert(!importer.empty());
#else
    assert(argc == 2);
#endif
    const auto module = ReadFile(argv[1]);
    assert(!module.empty());
#ifdef _WIN32
    assert(Core::Mods::Platform() == "windows-x86_64");
#else
    assert(Core::Mods::Platform() == "linux-x86_64");
#endif
    const auto base = fs::temp_directory_path() /
                      ("stage-test-" +
                       std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    for (const auto& name : {fs::path{"cache"}, fs::path{u8"cache-é"}}) {
        // StageModule creates a missing root, nested too.
        const auto root = base / name / "dualscreen";
        std::printf("%s\n", reinterpret_cast<const char*>(root.u8string().c_str()));
        CheckRoot(root, module);
#ifdef _WIN32
        CheckPlantedImport(root, importer);
#endif
    }
    std::error_code leftover;
    fs::remove_all(base, leftover);
    std::printf("stage_test: all checks passed (%s)\n", Core::Mods::Platform().data());
    return 0;
}

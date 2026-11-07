// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// Standalone check of SdlConfig::ReadQtConfig (src/yuzu_cmd/sdl_config.cpp, patched eden-duo, 0013):
// eden-cli's own config, then qt-config.ini, then custom/<id>.ini with use_global, as eden-cli
// applies them; the Qt files stay byte-identical and nothing is created next to them; an absent,
// empty or non-Qt file changes nothing. Built by run.sh against frontend_common/config.cpp and common.

#undef NDEBUG
#include <cassert>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <set>
#include <string>

#include "common/fs/path_util.h"
#include "common/logging.h"
#include "common/settings.h"
#include "input_common/main.h"
#include "yuzu_cmd/sdl_config.h"

namespace fs = std::filesystem;

// The log backend and sdl_config.cpp's two input_common calls; the real ones pull in the log
// threads and the whole input subsystem. Log lines go to stdout; save attempts are counted.
static int g_saves = 0;
namespace Common::Log {
void FmtLogMessageImpl(Class, Level, const char* filename, unsigned int line_num, const char*,
                       fmt::string_view format, const fmt::format_args& args) {
    const std::string line = fmt::vformat(format, args);
    g_saves += line.starts_with("Writing ") || line.find("could not be saved") != std::string::npos;
    std::printf("  log %s:%u: %s\n", filename, line_num, line.c_str());
}
void Stop() {}
} // namespace Common::Log

namespace InputCommon {
std::string GenerateKeyboardParam(int key_code) {
    return "engine:keyboard,code:" + std::to_string(key_code);
}
std::string GenerateAnalogParamFromKeys(int key_up, int key_down, int key_left, int key_right,
                                        int key_modifier, float modifier_scale) {
    return "engine:analog_from_button,up:" + std::to_string(key_up) + ",down:" +
           std::to_string(key_down) + ",left:" + std::to_string(key_left) + ",right:" +
           std::to_string(key_right) + ",modifier:" + std::to_string(key_modifier) +
           ",scale:" + std::to_string(modifier_scale);
}
} // namespace InputCommon

namespace {

void Put(const fs::path& path, const std::string& text) {
    std::ofstream(path, std::ios::binary) << text;
}

std::string Get(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), {}};
}

std::set<fs::path> Tree(const fs::path& dir) {
    std::set<fs::path> out;
    for (const auto& entry : fs::recursive_directory_iterator(dir)) {
        out.insert(entry.path());
    }
    return out;
}

// What Eden Qt writes (WriteSettingGeneric/WritePreparedSetting): "\default=false" before a value
// it means, "\use_global" in a custom file, quotes around strings with special characters, and
// the Paths group inside [UI] (QtConfig::SaveUIValues calls SavePathValues).
const char* const kQtGlobal = "[Core]\n"
                              "use_multi_core\\default=false\n"
                              "use_multi_core=false\n"
                              "speed_limit\\default=false\n"
                              "speed_limit=150\n"
                              "[Miscellaneous]\n"
                              "flush_line\\default=false\n"
                              "flush_line=true\n"
                              "[Controls]\n"
                              "player_0_button_a\\default=false\n"
                              "player_0_button_a=\"engine:sdl,button:1,port:0\"\n"
                              "[UI]\n"
                              "Paths\\external_content_dirs\\size=1\n"
                              "Paths\\external_content_dirs\\1\\path=/games/updates\n";

const char* const kQtGame = "[Core]\n"
                            "use_multi_core\\use_global=true\n"
                            "use_multi_core\\default=true\n"
                            "use_multi_core=true\n"
                            "speed_limit\\use_global=false\n"
                            "speed_limit\\default=false\n"
                            "speed_limit=75\n"
                            "[Controls]\n"
                            "player_0_profile_name=pad\n"
                            "player_0_button_a\\default=false\n"
                            "player_0_button_a=\"engine:keyboard,code:99\"\n";

} // namespace

int main() {
    const fs::path root = fs::temp_directory_path() /
                          ("qt-config-test-" +
                           std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const fs::path qt_dir = root / "config-é";
    fs::create_directories(qt_dir / "custom");
    const fs::path qt_global = qt_dir / "qt-config.ini";
    const fs::path qt_game = qt_dir / "custom" / "0100F2C0115B6000.ini";
    Put(qt_global, kQtGlobal);
    Put(qt_game, kQtGame);
    const auto stamp = fs::file_time_type::clock::now() - std::chrono::hours(1);
    fs::last_write_time(qt_global, stamp);
    fs::last_write_time(qt_game, stamp);
    // Read back: Windows keeps 100 ns, not the clock's precision.
    const auto global_time = fs::last_write_time(qt_global);
    const auto game_time = fs::last_write_time(qt_game);
    const auto tree = Tree(qt_dir);

    // eden-cli's own config still saves to its own file (the destructor's save is kept).
    const fs::path sdl_ini = root / "sdl2-config.ini";
    {
        SdlConfig own{sdl_ini.string()};
    }
    assert(fs::is_regular_file(sdl_ini) && Get(sdl_ini).find("use_multi_core") != std::string::npos);
    assert(Settings::values.use_multi_core.GetValue() && Settings::values.speed_limit.GetValue() == 100);
    g_saves = 0;

    // Absent, empty, not an Eden Qt ini: false, Settings untouched, nothing created.
    assert(!SdlConfig::ReadQtConfig(qt_dir / "custom" / "0100000000000001.ini",
                                    Config::ConfigType::PerGameConfig));
    assert(!SdlConfig::ReadQtConfig(root / "no-dir" / "qt-config.ini", Config::ConfigType::GlobalConfig));
    Put(root / "empty.ini", "");
    assert(!SdlConfig::ReadQtConfig(root / "empty.ini", Config::ConfigType::GlobalConfig));
    Put(root / "garbage.ini", std::string("\x89PNG\r\n\x1a\n\0\xff\xfe junk = 1 [x\n", 24));
    assert(!SdlConfig::ReadQtConfig(root / "garbage.ini", Config::ConfigType::GlobalConfig));
    assert(!fs::exists(root / "no-dir") && Tree(qt_dir) == tree);
    assert(Settings::values.use_multi_core.GetValue() && Settings::values.speed_limit.GetValue() == 100);

    // qt-config.ini replaces every shared value, and its DisabledAddOns list replaces eden-cli's.
    Settings::values.disabled_addons[0x0100000000000001] = {"Update"};
    assert(SdlConfig::ReadQtConfig(qt_global, Config::ConfigType::GlobalConfig));
    assert(Settings::values.disabled_addons.empty());
    assert(!Settings::values.use_multi_core.GetValue());
    assert(Settings::values.speed_limit.GetValue() == 150);
    assert(Settings::values.log_flush_line.GetValue());
    assert(Settings::values.players.UsingGlobal());
    assert(Settings::values.players.GetValue()[0].buttons[0] == "engine:sdl,button:1,port:0");
    assert(Settings::values.external_content_dirs.size() == 1 &&
           Settings::values.external_content_dirs[0] == "/games/updates");

    // custom/<id>.ini: use_global=false values and a named input profile win, the rest stays global.
    assert(SdlConfig::ReadQtConfig(qt_game, Config::ConfigType::PerGameConfig));
    assert(!Settings::values.use_multi_core.GetValue() && Settings::values.use_multi_core.UsingGlobal());
    assert(Settings::values.speed_limit.GetValue() == 75 && !Settings::values.speed_limit.UsingGlobal());
    assert(Settings::values.speed_limit.GetValue(true) == 150);
    assert(!Settings::values.players.UsingGlobal());
    assert(Settings::values.players.GetValue()[0].profile_name == "pad");
    assert(Settings::values.players.GetValue()[0].buttons[0] == "engine:keyboard,code:99");
    assert(Settings::values.players.GetValue(true)[0].buttons[0] == "engine:sdl,button:1,port:0");
    assert(Settings::values.log_flush_line.GetValue());

    // Neither read wrote, touched or created anything next to the Qt files, or tried to save.
    assert(Get(qt_global) == kQtGlobal && Get(qt_game) == kQtGame);
    assert(fs::last_write_time(qt_global) == global_time && fs::last_write_time(qt_game) == game_time);
    assert(Tree(qt_dir) == tree);
    assert(g_saves == 0);

    // -c may not name Eden Qt's files, however spelled and wherever they are (portable mode moves
    // eden-cli's config dir, not Eden Qt's); eden-cli's own and other files are fine.
    const fs::path config_dir = Common::FS::GetEdenPath(Common::FS::EdenPath::ConfigDir);
    const auto utf8 = [](const fs::path& path) { return Common::FS::PathToUTF8String(path); };
    assert(SdlConfig::IsEdenQtFile(utf8(config_dir / "qt-config.ini")));
    assert(SdlConfig::IsEdenQtFile(utf8(config_dir / "custom" / "0100F2C0115B6000.ini")));
    assert(SdlConfig::IsEdenQtFile(utf8(config_dir / "input" / ".." / "qt-config.ini")));
    assert(SdlConfig::IsEdenQtFile(utf8(qt_global)));
    assert(SdlConfig::IsEdenQtFile(utf8(qt_game)));
    assert(!SdlConfig::IsEdenQtFile(utf8(config_dir / "sdl2-config.ini")));
    assert(!SdlConfig::IsEdenQtFile(utf8(qt_dir / "sdl2-config.ini")));
    assert(!SdlConfig::IsEdenQtFile("sdl2-config.ini"));
#ifdef _WIN32
    std::string upper = utf8(config_dir / "qt-config.ini");
    for (char& c : upper) {
        c = c == '\\' ? '/' : static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    }
    assert(SdlConfig::IsEdenQtFile(upper));
    assert(SdlConfig::IsEdenQtFile(utf8(config_dir / "CUSTOM" / "0100f2c0115b6000.ini")));
#endif

    fs::remove_all(root);
    std::puts("qt_config_test: all checks passed");
}

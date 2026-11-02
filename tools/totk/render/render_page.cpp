// SPDX-License-Identifier: GPL-3.0-or-later
// render_page: draws one page of a dual-screen manifest to a PNG with Eden Duo's own parser
// (ParseDualScreenManifest) and renderer (RenderPage). Grown from the critic's T4 render harness.
//
// render_page <manifest.json> <page id> <state.json> <out.png> [--assets DIR] [--allow-unknown-keys]
//   state.json: {"ints": {name: int}, "floats": {name: number}, "texts": {name: string}}.
//   A page_binds point the state publishes is set to the rendered page's `equals` value.
//   Images: "file:" from the package folder; anything else DIR/<key, [^A-Za-z0-9._-] -> _>.png
//   when --assets has it, else a translucent grey checker labelled with the key's last component.
//   Font: DIR/font-atlas.png + DIR/font-metrics.json ({"line_height", "ascent",
//   "first_codepoint", "glyphs": [[x, y, w, h, bearing_x, bearing_y, advance], ...]}), else the
//   built-in one. A widget key no runtime source names fails the run (--allow-unknown-keys lists
//   it instead, for packages that carry generator notes such as BotW's).
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <map>
#include <set>
#include <string>

#include <nlohmann/json.hpp>

#include "common/stb.h"
#include "core/mods/mod_expr.h"
#include "core/mods/mod_ui.h"

namespace Core::Mods { // mod_runtime.h declares it, but that header pulls in the whole core
[[nodiscard]] bool ParseDualScreenManifest(const nlohmann::json& json, Manifest& out) noexcept;
}
using namespace Core::Mods;
using J = nlohmann::json;
namespace fs = std::filesystem;

namespace {

[[noreturn]] void Fail(const std::string& why) {
    std::fprintf(stderr, "render_page: %s\n", why.c_str());
    std::exit(1);
}

J ReadJson(const fs::path& path) {
    std::ifstream in(path);
    if (!in) {
        Fail("cannot read " + path.string());
    }
    return J::parse(in);
}

std::shared_ptr<const Image> LoadPng(const fs::path& path) {
    int w{}, h{}, n{};
    stbi_uc* const px = stbi_load(path.string().c_str(), &w, &h, &n, 4);
    if (px == nullptr) {
        Fail("cannot decode " + path.string());
    }
    auto img = std::make_shared<Image>();
    img->w = static_cast<u32>(w);
    img->h = static_cast<u32>(h);
    img->pixels.resize(img->w * img->h);
    for (size_t i = 0; i < img->pixels.size(); ++i) { // RGBA bytes -> ARGB, as mod_assets.cpp
        const stbi_uc* p = px + i * 4;
        img->pixels[i] = (u32{p[3]} << 24) | (u32{p[0]} << 16) | (u32{p[1]} << 8) | p[2];
    }
    stbi_image_free(px);
    return img;
}

std::shared_ptr<const Image> Placeholder(const std::string& key) {
    const std::string name = key.substr(key.find_last_of("/#:") + 1); // npos + 1 = whole key
    Canvas c;
    c.Resize(64, 64);
    const u32 w = static_cast<u32>(std::max(64, c.MeasureText(name, 1) + 8));
    c.Resize(w, 64);
    for (u32 y = 0; y < 64; y += 16) {
        for (u32 x = 0; x < w; x += 16) { // translucent, so the fallback under it still shows
            c.ClearRect(x, y, 16, 16, ((x + y) / 16) % 2 ? 0x80505050u : 0x80909090u);
        }
    }
    c.DrawTextAligned(static_cast<s32>(w / 2), 29, name, 1, 0xFFFFFFFFu, 1);
    auto img = std::make_shared<Image>();
    img->w = w;
    img->h = 64;
    img->pixels = c.Pixels();
    return img;
}

std::string Sanitize(std::string key) {
    for (char& ch : key) {
        if (!std::isalnum(static_cast<unsigned char>(ch)) && ch != '.' && ch != '-' && ch != '_') {
            ch = '_';
        }
    }
    return key;
}

void LoadState(const J& st, StateSnapshot& s) {
    if (!st.is_object()) {
        Fail("state is not an object");
    }
    for (const auto& [section, values] : st.items()) {
        if ((section != "ints" && section != "floats" && section != "texts") || !values.is_object()) {
            Fail("state: unknown section or not an object: " + section);
        }
        for (const auto& [k, v] : values.items()) {
            if (section == "ints" && v.is_number_integer()) {
                s.ints[k] = v.get<s64>();
            } else if (section == "floats" && v.is_number()) {
                s.floats[k] = v.get<f64>();
            } else if (section == "texts" && v.is_string()) {
                s.texts[k] = v.get<std::string>();
            } else {
                Fail("state: " + section + "." + k + " has the wrong type");
            }
        }
    }
}

// The derived forms the TotK and BotW pages use, as mod_state.cpp's EvaluateDerived computes them.
// shortcut: manifest order, not the runtime's dependency order; upgrade when a derived value
// reads one declared after it.
void EvaluateDerived(const Manifest& man, StateSnapshot& s) {
    const ExprLookup lookup = [&s](const std::string& k) -> std::optional<f64> {
        if (const auto f = s.floats.find(k); f != s.floats.end()) {
            return f->second;
        }
        if (const auto i = s.ints.find(k); i != s.ints.end()) {
            return static_cast<f64>(i->second);
        }
        return std::nullopt;
    };
    const auto publish = [&s](const std::string& name, f64 v) {
        if (std::isfinite(v)) {
            s.floats[name] = v;
            s.ints[name] = static_cast<s64>(std::clamp(v, -9.2e18, 9.2e18));
        }
    };
    const auto rounded = [](const DerivedPoint& d, f64 v) {
        return d.floor ? std::floor(v) : d.round ? std::round(v) : v;
    };
    size_t skipped = 0;
    for (const DerivedPoint& d : man.derived) {
        if (!d.any_eq_array.empty() || !d.select.empty() || !d.hold_last_nonzero.empty() ||
            !d.nonzero_sources.empty() || !d.countdown_now.empty()) {
            ++skipped;
        } else if (!d.cmp_op.empty()) {
            const auto side = [&](const DerivedPoint::CmpOperand& op) {
                return op.is_const ? std::optional<f64>(op.const_value) : lookup(op.name);
            };
            const auto a = side(d.cmp_a);
            const auto b = side(d.cmp_b);
            if (a && b) {
                constexpr f64 Eps = 1e-6;
                const std::map<std::string, bool> r{
                    {"eq", std::fabs(*a - *b) < Eps}, {"ne", std::fabs(*a - *b) >= Eps},
                    {"ge", *a >= *b - Eps},           {"gt", *a > *b + Eps},
                    {"le", *a <= *b + Eps},           {"lt", *a < *b - Eps}};
                const auto it = r.find(d.cmp_op);
                publish(d.name, it != r.end() && it->second ? 1.0 : 0.0);
            }
        } else if (d.expr_program) {
            if (const auto v = EvaluateExpr(*d.expr_program, lookup)) {
                publish(d.name, rounded(d, *v));
            }
        } else {
            f64 sum = d.add;
            bool missing = false;
            for (const auto& [src, factor] : d.terms) {
                const auto v = lookup(src);
                missing = missing || !v;
                sum += v.value_or(0.0) * factor;
            }
            if (!missing) {
                publish(d.name, rounded(d, sum));
            }
        }
    }
    if (skipped != 0) {
        std::fprintf(stderr, "render_page: %zu derived value(s) of other forms left unpublished\n",
                     skipped);
    }
}

} // namespace

int main(int argc, char** argv) try {
    if (argc < 5) {
        Fail("usage: render_page <manifest.json> <page id> <state.json> <out.png> [--assets DIR] "
             "[--allow-unknown-keys]");
    }
    fs::path assets;
    bool allow_unknown = false;
    for (int i = 5; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--assets" && i + 1 < argc) {
            assets = argv[++i];
            if (!fs::is_directory(assets)) {
                Fail("--assets is not a directory: " + assets.string());
            }
        } else if (a == "--allow-unknown-keys") {
            allow_unknown = true;
        } else {
            Fail("unknown argument " + a);
        }
    }
    const fs::path manifest_path = argv[1];
    const std::string page_id = argv[2];
    const J raw = ReadJson(manifest_path);

    static const std::set<std::string> known{
#include "runtime_keys.inc"
    };
    const J* raw_page = nullptr;
    for (const J& p : raw.at("pages")) {
        if (p.value("id", "") == page_id) {
            raw_page = &p;
        }
    }
    if (raw_page == nullptr) {
        Fail("no page '" + page_id + "' in " + manifest_path.string());
    }
    std::set<std::string> unknown;
    for (const J& w : raw_page->value("widgets", J::array())) {
        for (const auto& [k, v] : w.items()) {
            if (!known.contains(k)) {
                unknown.insert(k);
            }
        }
    }
    for (const std::string& k : unknown) {
        std::fprintf(stderr, "render_page: widget key '%s' is not read by the runtime\n", k.c_str());
    }
    if (!unknown.empty() && !allow_unknown) {
        Fail("unknown widget keys on page '" + page_id + "'");
    }

    Manifest man;
    if (!ParseDualScreenManifest(raw, man)) {
        Fail("the runtime's parser rejects " + manifest_path.string());
    }
    const auto page = std::ranges::find(man.pages, page_id, &Page::id);
    if (page == man.pages.end()) {
        Fail("the parsed manifest has no page '" + page_id + "'");
    }

    StateSnapshot s;
    LoadState(ReadJson(argv[3]), s);
    for (const PageBind& b : man.page_binds) {
        if (b.when_equal.page == page_id) {
            if (s.ints.contains(b.point)) {
                s.ints[b.point] = static_cast<s64>(b.equals);
            }
            if (s.floats.contains(b.point)) {
                s.floats[b.point] = b.equals;
            }
        }
    }
    EvaluateDerived(man, s);

    const fs::path package = manifest_path.parent_path();
    std::map<std::string, std::shared_ptr<const Image>> cache;
    const ImageProvider images = [&](const std::string& key) {
        auto [it, fresh] = cache.try_emplace(key);
        if (fresh) {
            const fs::path dumped = assets.empty() ? fs::path{} : assets / (Sanitize(key) + ".png");
            it->second = key.starts_with("file:")      ? LoadPng(package / key.substr(5))
                         : !assets.empty() && fs::exists(dumped) ? LoadPng(dumped)
                                                                 : Placeholder(key);
        }
        return it->second;
    };
    const TextProvider texts = [](const std::string& ref) { // "msbt:alias#label" -> "label"
        return std::make_shared<const std::string>(ref.substr(ref.find('#') + 1));
    };

    const u32 cw = man.canvas_w != 0 ? man.canvas_w : 1240;
    const u32 ch = man.canvas_h != 0 ? man.canvas_h : 1080;
    Canvas c;
    c.Resize(cw, ch);
    std::shared_ptr<const Image> atlas;
    FontMetrics font;
    if (!assets.empty() && fs::exists(assets / "font-atlas.png") &&
        fs::exists(assets / "font-metrics.json")) {
        atlas = LoadPng(assets / "font-atlas.png");
        const J m = ReadJson(assets / "font-metrics.json");
        font.line_height = m.at("line_height").get<u32>();
        font.ascent = m.value("ascent", 0u);
        font.first_codepoint = m.value("first_codepoint", 0x20u);
        for (const J& g : m.at("glyphs")) {
            font.glyphs.push_back({g.at(0).get<u16>(), g.at(1).get<u16>(), g.at(2).get<u16>(),
                                   g.at(3).get<u16>(), g.at(4).get<s16>(), g.at(5).get<s16>(),
                                   g.at(6).get<u16>()});
        }
        if (!font.Valid()) {
            Fail("font-metrics.json has no glyphs or no line height");
        }
        c.SetFont(atlas.get(), &font);
    }
    if (!RenderPage(c, man, *page, s, images, {}, nullptr, {}, {}, nullptr, nullptr, texts)) {
        Fail("RenderPage drew nothing for page '" + page_id + "'");
    }
    std::vector<unsigned char> rgba(static_cast<size_t>(cw) * ch * 4);
    for (size_t i = 0; i < static_cast<size_t>(cw) * ch; ++i) {
        const u32 p = c.Pixels()[i];
        rgba[i * 4] = (p >> 16) & 255;
        rgba[i * 4 + 1] = (p >> 8) & 255;
        rgba[i * 4 + 2] = p & 255;
        rgba[i * 4 + 3] = 255;
    }
    if (stbi_write_png(argv[4], static_cast<int>(cw), static_cast<int>(ch), 4, rgba.data(),
                       static_cast<int>(cw) * 4) == 0) {
        Fail(std::string("cannot write ") + argv[4]);
    }
    std::printf("%s ok\n", argv[4]);
    return 0;
} catch (const std::exception& e) {
    Fail(e.what());
}

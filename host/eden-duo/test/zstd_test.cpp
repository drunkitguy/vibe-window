// SPDX-FileCopyrightText: Copyright 2026 Eden DSMod fork
// SPDX-License-Identifier: GPL-3.0-or-later

// Standalone check of src/core/mods/mod_zstd.cpp (patched eden-duo, patch 0009), built against
// the zstd 1.5.6 single-file library from the zstandard-0.23.0 sdist (run.sh, ZSTD_DIR). Runs
// natively on Linux and as a MinGW exe under wine.

#undef NDEBUG
#include <cassert>
#include <cstdio>
#include <string>
#include <vector>

#include <zdict.h>
#include <zstd.h>

#include "core/mods/mod_zstd.h"

using Core::Mods::Zstd::Dicts;
using Bytes = std::vector<u8>;

namespace {

Bytes Text(const std::string& s) {
    return Bytes(s.begin(), s.end());
}

// A zstd-format dictionary with an explicit id, its content being `seed` repeated.
Bytes MakeDict(unsigned id, const std::string& seed) {
    std::string content;
    while (content.size() < 4096) {
        content += seed;
    }
    std::string samples;
    std::vector<size_t> sizes;
    for (int i = 0; i < 64; ++i) {
        const std::string s = seed + std::to_string(i * 7919) + seed;
        samples += s;
        sizes.push_back(s.size());
    }
    ZDICT_params_t params{};
    params.compressionLevel = 3;
    params.dictID = id;
    Bytes dict(16384);
    const size_t n = ZDICT_finalizeDictionary(dict.data(), dict.size(), content.data(),
                                              content.size(), samples.data(), sizes.data(),
                                              static_cast<unsigned>(sizes.size()), params);
    assert(!ZDICT_isError(n));
    dict.resize(n);
    return dict;
}

// One frame of `src`, with `dict` when not empty, the content size in the header unless `unsized`.
Bytes Compress(const Bytes& src, const Bytes& dict, bool unsized = false) {
    ZSTD_CCtx* const cctx = ZSTD_createCCtx();
    assert(cctx != nullptr);
    assert(!ZSTD_isError(ZSTD_CCtx_setParameter(cctx, ZSTD_c_contentSizeFlag, unsized ? 0 : 1)));
    if (!dict.empty()) {
        assert(!ZSTD_isError(ZSTD_CCtx_loadDictionary(cctx, dict.data(), dict.size())));
    }
    Bytes out(ZSTD_compressBound(src.size()));
    const size_t n = ZSTD_compress2(cctx, out.data(), out.size(), src.data(), src.size());
    assert(!ZSTD_isError(n));
    ZSTD_freeCCtx(cctx);
    out.resize(n);
    return out;
}

bool Fails(const Dicts& dicts, const Bytes& frame, u64 cap, const std::string& want) {
    Bytes out{1, 2, 3};
    std::string error;
    const bool ok = dicts.Decode(frame, cap, out, error);
    std::printf("  refused: %s\n", error.c_str());
    return !ok && out.empty() && error.find(want) != std::string::npos;
}

} // namespace

int main() {
    const Bytes alpha = MakeDict(0x1111, "Weapon_Sword_001 Armor_Head_002 alpha alpha ");
    const Bytes beta = MakeDict(0x2222, "Item_Fruit_A Item_Mushroom_B beta beta beta ");
    assert(ZDICT_getDictID(alpha.data(), alpha.size()) == 0x1111);
    assert(ZDICT_getDictID(beta.data(), beta.size()) == 0x2222);
    const Bytes payload = Text("SARC Weapon_Sword_001 Item_Fruit_A Armor_Head_002 alpha beta");

    Dicts dicts;
    assert(dicts.Add(alpha));
    assert(dicts.Add(beta));
    assert(!dicts.Add(alpha)); // an id already held

    // Two dictionaries: each frame picks the one its header names.
    for (const Bytes* dict : {&alpha, &beta}) {
        const Bytes frame = Compress(payload, *dict);
        assert(ZSTD_getDictID_fromFrame(frame.data(), frame.size()) ==
               ZDICT_getDictID(dict->data(), dict->size()));
        Bytes out;
        std::string error;
        assert(dicts.Decode(frame, payload.size(), out, error));
        assert(out == payload);
    }

    // A plain frame (no dictionary), also through an empty set.
    {
        const Bytes frame = Compress(payload, {});
        Bytes out;
        std::string error;
        assert(Dicts{}.Decode(frame, 1 << 20, out, error) && out == payload);
    }

    // A frame naming a dictionary not added.
    {
        Dicts only_alpha;
        assert(only_alpha.Add(alpha));
        assert(Fails(only_alpha, Compress(payload, beta), 1 << 20,
                     "unknown zstd dictionary 8738"));
    }

    // Not a frame; truncated; unknown content size; content over the cap.
    const Bytes frame = Compress(payload, alpha);
    Bytes bad_magic = frame;
    bad_magic[0] ^= 1;
    assert(Fails(dicts, bad_magic, 1 << 20, "not a zstd frame"));
    assert(Fails(dicts, Bytes(frame.begin(), frame.begin() + 3), 1 << 20, "not a zstd frame"));
    assert(Fails(dicts, Bytes(frame.begin(), frame.end() - 4), 1 << 20, "zstd: "));
    assert(Fails(dicts, Compress(payload, alpha, true), 1 << 20, "without a content size"));
    assert(Fails(dicts, frame, payload.size() - 1, "too large"));
    Bytes two_frames = frame;
    two_frames.insert(two_frames.end(), frame.begin(), frame.end());
    assert(Fails(dicts, two_frames, 1 << 20, "zstd: ")); // more output than the content size

    // A dictionary with id 0 is refused: a zstd-format one whose header id is 0, and raw content.
    Bytes zero_id = alpha;
    zero_id[4] = zero_id[5] = zero_id[6] = zero_id[7] = 0;
    Dicts fresh;
    assert(!fresh.Add(zero_id));
    assert(!fresh.Add(payload));

    std::printf("zstd_test: all checks passed (zstd %s)\n", ZSTD_versionString());
    return 0;
}

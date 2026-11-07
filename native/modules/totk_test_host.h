// SPDX-License-Identifier: GPL-3.0-or-later
// Fake EdenDsmodHostApi for the totk_*_test programs: byte-map guest memory, call counters,
// recorded publishes and logs (with their levels), an in-memory romfs, a write_batch
// (EdenDsmodHostWriteApi) over the same memory; a fake save directory (EdenDsmodHostSaveApi), a
// GameData save builder, SARC/MSBT/BYML/.bfotf builders and system font files for the font tests.
// Test-only; never part of the module.
#pragma once

#ifdef NDEBUG
#error "totk tests need assert(); build them with -UNDEBUG"
#endif

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "core/mods/dsmod_module_abi.h"
#include "core/mods/dsmod_module_extensions.h"
#include "totk_build.h"
#include "totk_byml.h"
#include "totk_gamedata.h"
#include "totk_offsets.h"
#include "totk_travel.h"

namespace totk::test {

struct Host {
    static constexpr std::uint64_t kMainBase = 0x80000000ULL;
    static constexpr std::uint64_t kMainSize = 0x5000000ULL; // holds kOffsets121's main slots

    std::map<std::uint64_t, std::uint8_t> memory;
    unsigned is_mapped_calls = 0, read_calls = 0, write_calls = 0;
    // When non-zero: flip a bit in the bytes returned by the Nth read_memory call (torn read).
    unsigned corrupt_read = 0;
    bool throw_on_publish = false;
    // Transparent maps: re-publishing a known key allocates nothing (the sample allocation test).
    using Ints = std::map<std::string, std::int64_t, std::less<>>;
    using Texts = std::map<std::string, std::string, std::less<>>;
    using Floats = std::map<std::string, double, std::less<>>;
    Ints ints;
    Texts texts;
    Floats floats;
    std::vector<std::string> logs; // read it only while the module's worker is idle
    std::vector<std::uint32_t> levels; // each line's level, in step with `logs`
    std::mutex log_mutex;           // the worker logs too
    // write_batch: every call counted; each op recorded; `before_batch` runs first (the guest
    // changing under the module); then every expect is checked, and only then every op stored.
    struct Op {
        std::uint64_t address;
        std::vector<std::uint8_t> expect, value;
    };
    unsigned batch_calls = 0;
    std::vector<std::vector<Op>> batches;
    std::function<void(Host&)> before_batch;
    EdenDsmodHostWriteApi write{};
    // read_romfs: path (with any "<prefix>:" and "#member" part) -> bytes, the runtime's
    // semantics [S1 mod_module_host.cpp:406-445]: null output -> size; past the end -> 0.
    std::mutex romfs_mutex;
    std::map<std::string, std::vector<std::uint8_t>, std::less<>> romfs;
    unsigned romfs_calls = 0;
    std::vector<std::string> romfs_paths; // every path asked for, in order
    EdenDsmodHostApi api{};

    Host() {
        api.abi_version = EDEN_DSMOD_MODULE_ABI_VERSION;
        api.struct_size = sizeof(EdenDsmodHostApi);
        api.abi_hash = EDEN_DSMOD_MODULE_ABI_HASH;
        api.userdata = this;
        api.title_id = kTitleId;
        std::memcpy(api.build_id, kBuild120Bytes.data(), 32);
        api.main_base = kMainBase;
        api.main_size = kMainSize;
        api.is_mapped = [](void* u, std::uint64_t at, std::uint64_t n) -> EdenDsmodBool {
            auto& h = *static_cast<Host*>(u);
            ++h.is_mapped_calls;
            for (std::uint64_t i = 0; i < n; ++i)
                if (!h.memory.contains(at + i))
                    return false;
            return true;
        };
        api.read_memory = [](void* u, std::uint64_t at, void* out, std::size_t n) -> EdenDsmodBool {
            auto& h = *static_cast<Host*>(u);
            ++h.read_calls;
            for (std::size_t i = 0; i < n; ++i) {
                const auto it = h.memory.find(at + i);
                if (it == h.memory.end())
                    return false;
                static_cast<std::uint8_t*>(out)[i] = it->second;
            }
            if (n && h.read_calls == h.corrupt_read)
                static_cast<std::uint8_t*>(out)[0] ^= 1;
            return true;
        };
        api.write_memory = [](void* u, std::uint64_t, const void*, std::size_t) -> EdenDsmodBool {
            ++static_cast<Host*>(u)->write_calls;
            return false;
        };
        api.publish_i64 = [](void* u, const char* name, std::int64_t value) {
            auto& h = *static_cast<Host*>(u);
            if (h.throw_on_publish)
                throw std::runtime_error("publish failed");
            Set(h.ints, name, value);
        };
        api.publish_text = [](void* u, const char* name, const char* value) {
            Set(static_cast<Host*>(u)->texts, name, value);
        };
        api.publish_f64 = [](void* u, const char* name, double value) {
            Set(static_cast<Host*>(u)->floats, name, value);
        };
        api.log = [](void* u, std::uint32_t level, const char* message) {
            auto& h = *static_cast<Host*>(u);
            std::scoped_lock lock{h.log_mutex};
            h.logs.emplace_back(message);
            h.levels.push_back(level);
        };
        write.version = EDEN_DSMOD_WRITE_EXT_VERSION;
        write.struct_size = sizeof(EdenDsmodHostWriteApi);
        write.abi_hash = EDEN_DSMOD_WRITE_EXT_HASH;
        write.userdata = this;
        write.write_batch = [](void* u, const EdenDsmodWriteOp* ops,
                               std::uint32_t count) -> EdenDsmodBool {
            auto& h = *static_cast<Host*>(u);
            ++h.batch_calls;
            auto& batch = h.batches.emplace_back();
            for (std::uint32_t i = 0; i < count; ++i) {
                const auto* e = static_cast<const std::uint8_t*>(ops[i].expect);
                const auto* v = static_cast<const std::uint8_t*>(ops[i].value);
                batch.push_back({ops[i].address,
                                 e ? std::vector(e, e + ops[i].size) : std::vector<std::uint8_t>{},
                                 std::vector(v, v + ops[i].size)});
            }
            if (h.before_batch)
                h.before_batch(h);
            for (const Op& op : batch)
                for (std::size_t i = 0; i < op.value.size(); ++i)
                    if (!h.memory.contains(op.address + i) ||
                        (!op.expect.empty() && h.memory.at(op.address + i) != op.expect[i]))
                        return false;
            for (const Op& op : batch)
                for (std::size_t i = 0; i < op.value.size(); ++i)
                    h.memory[op.address + i] = op.value[i];
            return true;
        };
        api.read_romfs = [](void* u, const char* path, std::uint64_t offset, void* out,
                            std::size_t size) -> std::size_t {
            auto& h = *static_cast<Host*>(u);
            std::scoped_lock lock{h.romfs_mutex};
            ++h.romfs_calls;
            h.romfs_paths.emplace_back(path);
            const auto it = h.romfs.find(std::string_view(path));
            if (it == h.romfs.end())
                return 0;
            if (!out)
                return it->second.size();
            if (offset >= it->second.size())
                return 0;
            const std::size_t n = std::min<std::size_t>(size, it->second.size() - offset);
            std::memcpy(out, it->second.data() + offset, n);
            return n;
        };
    }
    void PutRomfs(const std::string& path, std::vector<std::uint8_t> bytes) {
        std::scoped_lock lock{romfs_mutex};
        romfs[path] = std::move(bytes);
    }
    template <class Map, class V>
    static void Set(Map& map, std::string_view name, const V& value) {
        if (const auto it = map.find(name); it != map.end())
            it->second = value;
        else
            map.emplace(std::string(name), value);
    }

    Host(const Host&) = delete; // api.userdata points at this object
    Host& operator=(const Host&) = delete;

    template <class T>
    void Put(std::uint64_t at, T value) {
        std::uint8_t bytes[sizeof value];
        std::memcpy(bytes, &value, sizeof value);
        for (std::size_t i = 0; i < sizeof value; ++i)
            memory[at + i] = bytes[i];
    }

    template <class T>
    T Get(std::uint64_t at) const {
        std::uint8_t bytes[sizeof(T)];
        for (std::size_t i = 0; i < sizeof bytes; ++i)
            bytes[i] = memory.at(at + i);
        T value;
        std::memcpy(&value, bytes, sizeof value);
        return value;
    }

    unsigned MemoryCalls() const {
        return is_mapped_calls + read_calls + write_calls;
    }
    void ClearPublished() {
        ints.clear();
        texts.clear();
        floats.clear();
    }
};

// Lays chain `c` out from `base` in h's memory and returns its final address: a slot already
// written is followed (chains share their prefixes, as hearts and max hearts do), else it gets a
// fresh 1 MiB heap block. Synthetic addresses; the hops are whatever `c` holds.
inline std::uint64_t PutChain(Host& h, std::uint64_t base, const Chain& c) {
    static constexpr std::uint64_t kBlock = 0x100000;
    std::uint64_t at = base + c.hop[0];
    for (std::size_t i = 1; i < c.n; ++i) {
        if (!h.memory.contains(at)) {
            std::uint64_t heap = 0x90000000;
            while (h.memory.contains(heap))
                heap += kBlock;
            h.Put<std::uint64_t>(at, heap);
            h.memory[heap] = 0; // claims the block
        }
        at = h.Get<std::uint64_t>(at) + c.hop[i];
    }
    return at;
}

// Live values for a fake 1.2.1 layout; battery false leaves both battery chains unmapped,
// position false the position.
struct Live {
    std::uint32_t hearts = 48, max_hearts = 80, rupees = 1234;
    float stamina = 1500.f, max_stamina = 2000.f;
    bool battery = true;
    float battery_now = 900.f, max_battery = 3000.f;
    bool position = true;
    float x = -1234.5f, y = 150.f, z = 2345.25f; // the Surface
};

// The whole status layout of `o` (kOffsets121 by default) with "Player" as the actor's name.
inline void PutLive(Host& h, const Live& v = {}, const Offsets& o = kOffsets121) {
    const std::uint64_t main = h.api.main_base, actor = PutChain(h, main, o.actor);
    const std::uint64_t name = PutChain(h, actor, o.name);
    for (std::size_t i = 0; i < 7; ++i)
        h.memory[name + i] = std::uint8_t("Player"[i]);
    h.Put(PutChain(h, actor, o.hearts), v.hearts);
    h.Put(PutChain(h, actor, o.max_hearts), v.max_hearts);
    h.Put(PutChain(h, actor, o.stamina), v.stamina);
    h.Put(PutChain(h, main, o.max_stamina), v.max_stamina);
    h.Put(PutChain(h, main, o.rupees), v.rupees);
    if (v.battery) {
        h.Put(PutChain(h, main, o.battery), v.battery_now);
        h.Put(PutChain(h, main, o.max_battery), v.max_battery);
    }
    if (v.position)
        h.Put(PutChain(h, actor, o.position), std::array<float, 3>{v.x, v.y, v.z});
}

// The loading gate of `o` (ReadLoading): the holder's manager slot holds the GameDataMgr pointer,
// the file count `n`, the file's `flags`. Returns the flags' address.
inline std::uint64_t PutLoading(Host& h, std::uint8_t flags = 0, std::uint32_t n = 1,
                                const Offsets& o = kOffsets121) {
    const std::uint64_t main = h.api.main_base, mgr = PutChain(h, main, o.gmd_mgr);
    Chain slot = o.gmd_holder;
    --slot.n; // the holder chain up to the slot that holds the manager pointer
    h.Put(PutChain(h, main, slot), mgr);
    h.Put(PutChain(h, main, o.gmd_count), n);
    const std::uint64_t at = PutChain(h, main, o.gmd_loading);
    h.Put(at, flags);
    return at;
}

// The log lines of a romfs without the LocationArea files (totk_travel.h Load), after `before`.
inline std::vector<std::string> WithTravelMissing(std::vector<std::string> before) {
    for (const char* f : travel::kFiles)
        before.push_back(std::string("totk travel: ") + f + " missing");
    return before;
}

// BYML trees for the travel tests (invented values; no game data) and a little-endian v7 writer,
// adapted from the Wonder catalog's test writer [S2 wonder_catalog_test.cpp:21-110] to write a
// byml::Node instead of JSON.
inline byml::Node Num(float v) {
    byml::Node n;
    n.type = byml::Node::Type::Float;
    n.num.f = v;
    return n;
}
inline byml::Node Int(std::int32_t v) {
    byml::Node n;
    n.type = byml::Node::Type::Int;
    n.num.i = v;
    return n;
}
inline byml::Node Str(std::string v) {
    byml::Node n;
    n.type = byml::Node::Type::String;
    n.str = std::move(v);
    return n;
}
inline byml::Node Arr(std::vector<byml::Node> v) {
    byml::Node n;
    n.type = byml::Node::Type::Array;
    n.array = std::move(v);
    return n;
}
inline byml::Node Dict(std::vector<byml::Entry> v) {
    byml::Node n;
    n.type = byml::Node::Type::Dict;
    n.dict = std::move(v);
    return n;
}

struct BymlWriter {
    std::vector<std::uint8_t> bytes = std::vector<std::uint8_t>(16);
    std::set<std::string> key_set, string_set;
    std::vector<std::string> keys, strings;
    void Put(std::size_t offset, std::uint64_t value, int size = 4) {
        for (int i = 0; i < size; ++i)
            bytes.at(offset + i) = static_cast<std::uint8_t>(value >> (8 * i));
    }
    std::uint32_t Allocate(std::size_t size) {
        while (bytes.size() % 4)
            bytes.push_back(0);
        const auto offset = static_cast<std::uint32_t>(bytes.size());
        bytes.resize(bytes.size() + size);
        return offset;
    }
    void Collect(const byml::Node& n) {
        if (n.type == byml::Node::Type::String)
            string_set.insert(n.str);
        for (const auto& e : n.dict) {
            key_set.insert(e.key);
            Collect(e.value);
        }
        for (const auto& v : n.array)
            Collect(v);
    }
    std::uint32_t Table(const std::vector<std::string>& values) {
        const auto offset = Allocate(4 + (values.size() + 1) * 4);
        Put(offset, 0xC2 | (values.size() << 8));
        for (std::size_t i = 0; i < values.size(); ++i) {
            Put(offset + 4 + i * 4, bytes.size() - offset);
            bytes.insert(bytes.end(), values[i].begin(), values[i].end());
            bytes.push_back(0);
        }
        Put(offset + 4 + values.size() * 4, bytes.size() - offset);
        return offset;
    }
    static std::uint32_t Index(const std::vector<std::string>& list, const std::string& s) {
        return std::uint32_t(std::find(list.begin(), list.end(), s) - list.begin());
    }
    std::pair<std::uint8_t, std::uint32_t> Encode(const byml::Node& n) {
        using T = byml::Node::Type;
        switch (n.type) {
        case T::String:
            return {0xA0, Index(strings, n.str)};
        case T::Float: {
            const float f = float(n.num.f);
            std::uint32_t bits;
            std::memcpy(&bits, &f, 4);
            return {0xD2, bits};
        }
        case T::Int:
            return {0xD1, std::uint32_t(std::int32_t(n.num.i))};
        case T::Bool:
            return {0xD0, n.num.b ? 1U : 0U};
        case T::Array: {
            const auto type_bytes = (n.array.size() + 3) & ~std::size_t{3};
            const auto offset = Allocate(4 + type_bytes + n.array.size() * 4);
            Put(offset, 0xC0 | (n.array.size() << 8));
            for (std::size_t i = 0; i < n.array.size(); ++i) {
                const auto [type, value] = Encode(n.array[i]);
                bytes[offset + 4 + i] = type;
                Put(offset + 4 + type_bytes + i * 4, value);
            }
            return {0xC0, offset};
        }
        case T::Dict: {
            const auto offset = Allocate(4 + n.dict.size() * 8);
            Put(offset, 0xC1 | (n.dict.size() << 8));
            for (std::size_t i = 0; i < n.dict.size(); ++i) {
                const auto [type, value] = Encode(n.dict[i].value);
                Put(offset + 4 + i * 8, Index(keys, n.dict[i].key) | (std::uint32_t(type) << 24));
                Put(offset + 8 + i * 8, value);
            }
            return {0xC1, offset};
        }
        default:
            return {0xFF, 0};
        }
    }
    std::vector<std::uint8_t> Write(const byml::Node& root) {
        Collect(root);
        keys.assign(key_set.begin(), key_set.end());
        strings.assign(string_set.begin(), string_set.end());
        bytes[0] = 'Y';
        bytes[1] = 'B';
        bytes[2] = 7;
        Put(4, Table(keys));
        Put(8, Table(strings));
        Put(12, Encode(root).second);
        assert(byml::Parse(bytes));
        return bytes;
    }
};

// A save directory in memory with the runtime's read_save_file semantics [S1
// mod_module_services.cpp:222-242]: output null -> size; offset past the end -> 0; else
// min(size, file size - offset) bytes. Thread-safe: the module's worker calls it.
struct SaveDir {
    std::mutex mutex;
    std::map<std::string, std::vector<std::uint8_t>> files;
    std::map<std::string, unsigned> size_queries, reads;
    std::map<std::string, std::size_t> last_request; // size asked for by the last read
    // Appended to this file right after its next size query (it changes under the reader).
    std::string grow_after_query;
    // The next data read of this file returns 0 (fails) once; its size stays the same.
    std::string fail_next_read;
    EdenDsmodHostSaveApi api{};

    SaveDir() {
        api.version = EDEN_DSMOD_SAVE_EXT_VERSION;
        api.struct_size = sizeof(EdenDsmodHostSaveApi);
        api.abi_hash = EDEN_DSMOD_SAVE_EXT_HASH;
        api.userdata = this;
        api.read_save_file = [](void* u, const char* path, std::uint64_t offset, void* out,
                                std::size_t size) -> std::size_t {
            auto& d = *static_cast<SaveDir*>(u);
            std::scoped_lock lock{d.mutex};
            const auto it = d.files.find(path);
            if (it == d.files.end())
                return 0;
            auto& bytes = it->second;
            if (!out) {
                ++d.size_queries[path];
                const std::size_t n = bytes.size();
                if (d.grow_after_query == path) {
                    bytes.push_back(0);
                    d.grow_after_query.clear();
                }
                return n;
            }
            ++d.reads[path];
            d.last_request[path] = size;
            if (d.fail_next_read == path) {
                d.fail_next_read.clear();
                return 0;
            }
            if (offset >= bytes.size())
                return 0;
            const std::size_t n = std::min<std::size_t>(size, bytes.size() - offset);
            std::memcpy(out, bytes.data() + offset, n);
            return n;
        };
    }
    SaveDir(const SaveDir&) = delete;
    SaveDir& operator=(const SaveDir&) = delete;

    void Put(const std::string& path, std::vector<std::uint8_t> bytes) {
        std::scoped_lock lock{mutex};
        files[path] = std::move(bytes);
    }
    unsigned Reads(const std::string& path) {
        std::scoped_lock lock{mutex};
        return reads[path];
    }
    void GrowAfterQuery(const std::string& path) {
        std::scoped_lock lock{mutex};
        grow_after_query = path;
    }
    void FailNextRead(const std::string& path) {
        std::scoped_lock lock{mutex};
        fail_next_read = path;
    }
};

inline void PutU32(std::vector<std::uint8_t>& b, std::size_t at, std::uint32_t v) {
    if (b.size() < at + 4)
        b.resize(at + 4);
    std::memcpy(b.data() + at, &v, 4);
}
inline std::uint32_t GetU32(const std::vector<std::uint8_t>& b, std::size_t at) {
    std::uint32_t v;
    std::memcpy(&v, b.data() + at, 4);
    return v;
}

inline void PutU16(std::vector<std::uint8_t>& b, std::size_t at, std::uint16_t v) {
    if (b.size() < at + 2)
        b.resize(at + 2);
    std::memcpy(b.data() + at, &v, 2);
}

// A little-endian SARC of `members` (name, bytes) in order; names 4-byte aligned in SFNT.
inline std::vector<std::uint8_t> Sarc(
    const std::vector<std::pair<std::string, std::vector<std::uint8_t>>>& members) {
    std::vector<std::uint8_t> names, data;
    std::vector<std::uint8_t> b(0x14 + 0xC + 16 * members.size() + 8);
    for (std::size_t i = 0; i < members.size(); ++i) {
        const auto& [name, bytes] = members[i];
        const std::size_t node = 0x20 + 16 * i;
        PutU32(b, node, 0);
        PutU32(b, node + 4, 0x01000000u | std::uint32_t(names.size() / 4));
        PutU32(b, node + 8, std::uint32_t(data.size()));
        data.insert(data.end(), bytes.begin(), bytes.end());
        PutU32(b, node + 12, std::uint32_t(data.size()));
        names.insert(names.end(), name.begin(), name.end());
        names.resize((names.size() + 4) & ~std::size_t(3)); // NUL, then pad to 4
    }
    std::memcpy(b.data(), "SARC", 4);
    PutU16(b, 4, 0x14);
    PutU16(b, 6, 0xFEFF);
    std::memcpy(b.data() + 0x14, "SFAT", 4);
    PutU16(b, 0x18, 0xC);
    PutU16(b, 0x1A, std::uint16_t(members.size()));
    PutU32(b, 0x1C, 0x65);
    const std::size_t sfnt = 0x20 + 16 * members.size();
    std::memcpy(b.data() + sfnt, "SFNT", 4);
    PutU16(b, sfnt + 4, 8);
    b.insert(b.end(), names.begin(), names.end());
    PutU32(b, 0xC, std::uint32_t(b.size()));
    b.insert(b.end(), data.begin(), data.end());
    PutU32(b, 8, std::uint32_t(b.size()));
    return b;
}

// A little-endian UTF-16 MSBT around raw LBL1 and TXT2 bodies (each section padded to 16 with 0xAB).
inline std::vector<std::uint8_t> MsbtFile(const std::vector<std::uint8_t>& lbl,
                                          const std::vector<std::uint8_t>& txt) {
    std::vector<std::uint8_t> b(0x20);
    std::memcpy(b.data(), "MsgStdBn", 8);
    b[8] = 0xFF;
    b[9] = 0xFE;
    b[12] = 1; // UTF-16
    PutU16(b, 14, 2);
    for (const auto& [magic, body] : {std::pair{"LBL1", &lbl}, std::pair{"TXT2", &txt}}) {
        const std::size_t at = b.size();
        b.resize(at + 16);
        std::memcpy(b.data() + at, magic, 4);
        PutU32(b, at + 4, std::uint32_t(body->size()));
        b.insert(b.end(), body->begin(), body->end());
        b.resize((b.size() + 15) & ~std::size_t(15), 0xAB);
    }
    PutU32(b, 18, std::uint32_t(b.size()));
    return b;
}

// An MSBT with one LBL1 bucket holding every label (label i -> text i), then TXT2. Texts are
// UTF-16 code units (tags and surrogates written by the caller), NUL-terminated here.
inline std::vector<std::uint8_t> Msbt(
    const std::vector<std::pair<std::string, std::u16string>>& entries) {
    std::vector<std::uint8_t> lbl(12), txt(4 + 4 * entries.size());
    PutU32(lbl, 0, 1);
    PutU32(lbl, 4, std::uint32_t(entries.size()));
    PutU32(lbl, 8, 12);
    PutU32(txt, 0, std::uint32_t(entries.size()));
    for (std::size_t i = 0; i < entries.size(); ++i) {
        const auto& [label, text] = entries[i];
        lbl.push_back(std::uint8_t(label.size()));
        lbl.insert(lbl.end(), label.begin(), label.end());
        PutU32(lbl, lbl.size(), std::uint32_t(i));
        PutU32(txt, 4 + 4 * i, std::uint32_t(txt.size()));
        for (const char16_t c : text + u'\0')
            PutU16(txt, txt.size(), std::uint16_t(c));
    }
    return MsbtFile(lbl, txt);
}

// caption.sav: the date fields and the autosave flag in the key table from 0x28, then filler for
// the picture that follows in a real file.
inline std::vector<std::uint8_t> Caption(std::uint32_t year, std::uint32_t month, std::uint32_t day,
                                         std::uint32_t hour, std::uint32_t minute, bool autosave) {
    std::vector<std::uint8_t> b(0x400, 0xAB);
    const std::uint32_t entries[][2] = {{0x12345678, 7},      {0x9811A3F7, year},
                                        {0xDFD840D3, month},  {0xBD46F485, day},
                                        {0x23F3D75E, hour},   {0x27853BF7, minute},
                                        {0x25F03CAA, autosave ? 0xFFFFFF01u : 0xFFFFFF00u}};
    std::size_t at = 0x28;
    for (const auto& e : entries) {
        PutU32(b, at, e[0]);
        PutU32(b, at + 4, e[1]);
        at += 8;
    }
    return b;
}

// progress.sav writer for every type the module reads. Sections in type order; array and Vector3
// data after the entry table at the offsets the entries name.
class SaveBuilder {
public:
    SaveBuilder& Bool(std::string_view key, bool v) { return Scalar(GdType::Bool, key, v); }
    SaveBuilder& Int(std::string_view key, std::int32_t v) {
        return Scalar(GdType::Int, key, std::uint32_t(v));
    }
    SaveBuilder& UInt(std::string_view key, std::uint32_t v) { return Scalar(GdType::UInt, key, v); }
    SaveBuilder& Float(std::string_view key, float v) {
        std::uint32_t bits;
        std::memcpy(&bits, &v, 4);
        return Scalar(GdType::Float, key, bits);
    }
    SaveBuilder& Enum(std::string_view key, std::string_view value) {
        return Scalar(GdType::Enum, key, Murmur3(value));
    }
    SaveBuilder& Vector3(std::string_view key, float x, float y, float z) {
        std::vector<std::uint8_t> d(12);
        std::memcpy(d.data(), &x, 4);
        std::memcpy(d.data() + 4, &y, 4);
        std::memcpy(d.data() + 8, &z, 4);
        return Data(GdType::Vector3, key, std::move(d));
    }
    SaveBuilder& IntArray(std::string_view key, const std::vector<std::int32_t>& v) {
        return Words(GdType::IntArray, key, {v.begin(), v.end()});
    }
    SaveBuilder& EnumArray(std::string_view key, const std::vector<std::string_view>& v) {
        std::vector<std::uint32_t> words;
        for (auto s : v)
            words.push_back(Murmur3(s));
        return Words(GdType::EnumArray, key, words);
    }
    SaveBuilder& String64Array(std::string_view key, const std::vector<std::string>& v) {
        std::vector<std::uint8_t> d(4 + 64 * v.size());
        PutU32(d, 0, std::uint32_t(v.size()));
        for (std::size_t i = 0; i < v.size(); ++i)
            std::memcpy(d.data() + 4 + 64 * i, v[i].data(), std::min<std::size_t>(v[i].size(), 64));
        return Data(GdType::String64Array, key, std::move(d));
    }
    // An entry in a section of any type, with a raw value (types the module does not read).
    SaveBuilder& Unread(std::uint32_t type, std::string_view key, std::uint32_t value) {
        sections_[type].push_back({Murmur3(key), value, {}});
        return *this;
    }

    std::vector<std::uint8_t> Build() const {
        std::size_t entries = 0;
        for (const auto& [type, list] : sections_)
            entries += 1 + list.size();
        const std::uint32_t data_offset = std::uint32_t(GameData::kEntriesBegin + 8 * entries);
        std::vector<std::uint8_t> b(data_offset);
        PutU32(b, 0, GameData::kMagic);
        PutU32(b, 4, 0x47e6a2c7); // format version: not checked
        PutU32(b, 8, data_offset);
        std::size_t at = GameData::kEntriesBegin;
        for (const auto& [type, list] : sections_) {
            PutU32(b, at, 0);
            PutU32(b, at + 4, type);
            at += 8;
            for (const auto& e : list) {
                PutU32(b, at, e.hash);
                std::uint32_t value = e.value;
                if (!e.data.empty()) {
                    value = std::uint32_t(b.size());
                    b.insert(b.end(), e.data.begin(), e.data.end());
                }
                PutU32(b, at + 4, value);
                at += 8;
            }
        }
        return b;
    }

private:
    struct E {
        std::uint32_t hash, value;
        std::vector<std::uint8_t> data;
    };
    SaveBuilder& Scalar(GdType t, std::string_view key, std::uint32_t v) {
        sections_[std::uint32_t(t)].push_back({Murmur3(key), v, {}});
        return *this;
    }
    SaveBuilder& Data(GdType t, std::string_view key, std::vector<std::uint8_t> d) {
        sections_[std::uint32_t(t)].push_back({Murmur3(key), 0, std::move(d)});
        return *this;
    }
    SaveBuilder& Words(GdType t, std::string_view key, const std::vector<std::uint32_t>& w) {
        std::vector<std::uint8_t> d(4 + 4 * w.size());
        PutU32(d, 0, std::uint32_t(w.size()));
        if (!w.empty())
            std::memcpy(d.data() + 4, w.data(), 4 * w.size());
        return Data(t, key, std::move(d));
    }
    std::map<std::uint32_t, std::vector<E>> sections_;
};

// The full fixture save the save and module tests share (see totk_save_test for the expected
// snapshot). `weapon_equip` is Pouch.Weapon.EquipIndex[0]; nullopt leaves the key out.
inline SaveBuilder FixtureSave(std::optional<std::int32_t> weapon_equip = 2) {
    SaveBuilder b;
    if (weapon_equip)
        b.IntArray("Pouch.Weapon.EquipIndex", {*weapon_equip});
    b.Int("PlayerStatus.Life", 12)
        .UInt("PlayerStatus.MaxLife", 40)
        .Int("PlayerStatus.MaxStamina", 1000)
        .Int("PlayerStatus.CurrentRupee", 4321)
        .Float("PlayerStatus.MaxEnergy", 1.5f)
        .Vector3("PlayerStatus.SavePos", 100.5f, 20.f, -300.25f)
        .String64Array("Pouch.Weapon.Content.Name", {"Weapon_Sword_001", "", "Weapon_Spear_002"})
        .IntArray("Pouch.Weapon.Content.Life", {30, 0, 12})
        .String64Array("Pouch.Weapon.Content.Combined.Name", {"", "", "Item_Ruby"})
        .EnumArray("Pouch.Weapon.Content.Effect.Type", {"None", "None", "AttackUpPlus"})
        .String64Array("Pouch.Bow.Content.Name", {"Weapon_Bow_001"})
        .IntArray("Pouch.Bow.Content.Life", {40})
        .EnumArray("Pouch.Bow.Content.Effect.Type", {"RapidFire"})
        .IntArray("Pouch.Bow.EquipIndex", {0})
        .String64Array("Pouch.Shield.Content.Name", {"Weapon_Shield_001"})
        .IntArray("Pouch.Shield.Content.Life", {10})
        .EnumArray("Pouch.Shield.Content.Effect.Type", {"GuardUp"})
        .String64Array("Pouch.Shield.Content.Combined.Name", {""})
        .IntArray("Pouch.Shield.EquipIndex", {-1})
        .String64Array("Pouch.Armor.Content.Name", {"Armor_001_Head", "Armor_001_Upper"})
        .IntArray("Pouch.Armor.EquipIndex", {0})       // not read: armor's equip key is UNKNOWN
        .IntArray("Pouch.Armor.Content.StockNum", {1, 1}) // not read: armor has no value
        .String64Array("Pouch.Arrow.Content.Name", {"NormalArrow", "FireArrow"})
        .IntArray("Pouch.Arrow.Content.StockNum", {50, 7})
        .IntArray("Pouch.Arrow.EquipIndex", {1})
        .String64Array("Pouch.Material.Content.Name", {"Item_Fruit_A"})
        .IntArray("Pouch.Material.Content.StockNum", {5})
        .String64Array("Pouch.Food.Content.Name", {"Item_Cook_A"})
        .IntArray("Pouch.Food.Content.StockNum", {1})
        .IntArray("Pouch.Food.Content.LifeRecover", {20})
        .EnumArray("Pouch.Food.Content.Effect.Type", {"AttackUp"}) // food effects get no label yet
        .String64Array("Pouch.SpecialParts.Content.Name", {"SpecialParts_Fan"})
        .IntArray("Pouch.SpecialParts.Content.StockNum", {3})
        .String64Array("Pouch.KeyItem.Content.Name", {"Obj_KeyItem_X"})
        .IntArray("Pouch.KeyItem.Content.StockNum", {1})
        .Bool("IsGet.Obj_UltraHand", true)
        .Bool("IsGetAnyway.Obj_UltraHand", true)
        .Bool("IsGet.Obj_OneTouchBond", true) // IsGetAnyway missing: not owned
        .Bool("IsGet.Obj_Tooreroof", true)
        .Bool("IsGetAnyway.Obj_Tooreroof", true)
        .Bool("IsGet.Obj_ReverseRecorder", false)
        .Bool("IsGetAnyway.Obj_ReverseRecorder", true)
        .Bool("IsGetAnyway.Obj_AutoBuilder", true) // IsGet missing: not owned
        .Bool("IsGet.Obj_Camera", true)
        .Bool("IsGetAnyway.Obj_Camera", true)
        .Bool("IsGet.Obj_Warp", true)
        .Bool("IsGetAnyway.Obj_Warp", true);
    return b;
}

// Byte offset of the entry for `key` in a built save (asserts it exists).
inline std::size_t EntryAt(const std::vector<std::uint8_t>& b, std::string_view key) {
    const std::uint32_t hash = Murmur3(key);
    for (std::size_t at = GameData::kEntriesBegin; at + 8 <= GetU32(b, 8); at += 8)
        if (GetU32(b, at) == hash)
            return at;
    assert(!"key not in save");
    return 0;
}

// A .bfotf of `plain` under `key` (the header totk_font.h decodes).
inline std::vector<std::uint8_t> Bfotf(const std::vector<std::uint8_t>& plain, std::uint32_t key) {
    std::vector<std::uint8_t> b(8);
    const auto be = [&b](std::size_t at, std::uint32_t v) {
        for (int i = 0; i < 4; ++i)
            b[at + i] = std::uint8_t(v >> (24 - 8 * i));
    };
    be(0, 0x7F9A0218u ^ key);
    be(4, std::uint32_t(plain.size()) ^ key);
    for (std::size_t i = 0; i < plain.size(); ++i)
        b.push_back(plain[i] ^ std::uint8_t(key >> (24 - 8 * (i & 3))));
    return b;
}

// The first of `paths` that exists (also as Z:<path>, wine's view of /), or empty: font tests
// use a system font found at test time and skip without one.
inline std::vector<std::uint8_t> SystemFile(std::initializer_list<const char*> paths) {
    for (const char* path : paths)
        for (const std::string& p : {std::string{path}, "Z:" + std::string{path}})
            if (std::FILE* f = std::fopen(p.c_str(), "rb")) {
                std::vector<std::uint8_t> bytes;
                for (int c; (c = std::fgetc(f)) != EOF;)
                    bytes.push_back(std::uint8_t(c));
                std::fclose(f);
                return bytes;
            }
    return {};
}
// A CFF (OTTO) face like the game's text face, else TrueType; and a TrueType face for External.
inline std::vector<std::uint8_t> SystemTextFont() {
    return SystemFile({"/usr/share/fonts/opentype/inter/Inter-Regular.otf",
                       "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                       "/usr/share/fonts/TTF/DejaVuSans.ttf"});
}
inline std::vector<std::uint8_t> SystemExtFont() {
    return SystemFile({"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                       "/usr/share/fonts/TTF/DejaVuSans.ttf"});
}

} // namespace totk::test

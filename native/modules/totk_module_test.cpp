// SPDX-License-Identifier: GPL-3.0-or-later
// The module's C ABI surface, compiled in from the module source.
#include "0100F2C0115B6000.cpp"
#include "totk_test_host.h"

#include <cstdio>

using totk::test::Host;

int main() {
    // eden_dsmod_get_module: version and hash gate.
    assert(!eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION + 1, EDEN_DSMOD_MODULE_ABI_HASH));
    assert(!eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH ^ 1));
    const EdenDsmodModuleApi* m =
        eden_dsmod_get_module(EDEN_DSMOD_MODULE_ABI_VERSION, EDEN_DSMOD_MODULE_ABI_HASH);
    assert(m);
    assert(m->abi_version == EDEN_DSMOD_MODULE_ABI_VERSION);
    assert(m->abi_hash == EDEN_DSMOD_MODULE_ABI_HASH);
    assert(m->struct_size == sizeof(EdenDsmodModuleApi));
    assert(m->title_id == 0x0100F2C0115B6000ULL);
    assert(m->capabilities == 0);
    assert(m->name && *m->name);
    assert(m->supports_build && m->create && m->destroy && m->sample && m->tick);

    // supports_build: only the exact target string.
    assert(m->supports_build(totk::kTargetBuild));
    assert(!m->supports_build(nullptr));
    assert(!m->supports_build(""));
    assert(!m->supports_build("9B4E43650501A4D4"));
    assert(!m->supports_build("277178B7DBA1B6D4949A84778D4ABC58B31B34F5000000000000000000000000"));
    assert(!m->supports_build("8E9978D50BDD20B4C8395A106C27FFDE00000000000000000000000000000000"));

    // create: every gate. A fresh host per case; none of them may touch guest memory.
    const auto refused = [m](void (*mutate)(EdenDsmodHostApi&)) {
        Host h;
        mutate(h.api);
        const bool ok = m->create(&h.api, "{}") == nullptr;
        return ok && h.MemoryCalls() == 0;
    };
    assert(!m->create(nullptr, "{}"));
    assert(refused([](EdenDsmodHostApi& a) { a.abi_hash ^= 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.abi_version += 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.struct_size = sizeof(EdenDsmodHostApi) - 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.title_id = 0x01007EF00011E000ULL; }));
    assert(refused([](EdenDsmodHostApi& a) { a.build_id[31] ^= 1; }));
    assert(refused([](EdenDsmodHostApi& a) { a.build_id[0] ^= 0x80; }));

    Host h;
    void* instance = m->create(&h.api, nullptr);
    assert(instance);

    // sample/tick with the default (all UNKNOWN) offsets: no memory access, ready + invalid only.
    m->sample(instance, &h.api);
    m->tick(instance, &h.api);
    m->sample(instance, &h.api);
    assert(h.MemoryCalls() == 0);
    assert(h.ints.size() == 2);
    assert(h.ints.at("totk.ready") == 1);
    assert(h.ints.at("totk.status.valid") == 0);

    // Null instance or host: nothing happens, nothing crashes.
    m->sample(nullptr, &h.api);
    m->sample(instance, nullptr);
    m->tick(nullptr, nullptr);
    m->tick(instance, nullptr);
    m->destroy(nullptr);

    // A throwing publish callback does not escape sample.
    h.throw_on_publish = true;
    m->sample(instance, &h.api);
    h.throw_on_publish = false;

    m->destroy(instance);

    std::puts("totk_module_test: ok");
    return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
// The Legend of Zelda: Tears of the Kingdom companion module (skeleton).
// Three build gates: the loader's build_ids prefix, supports_build and create (all 32 bytes).
// Every callback catches all exceptions: nothing may unwind into the host.

#include <new>

#include "core/mods/modules/dsmod_module_sdk.h"
#include "totk_build.h"
#include "totk_offsets.h"
#include "totk_status.h"

namespace {

struct Instance {
    totk::Offsets offsets; // all 0 (UNKNOWN) until T3
};

EdenDsmodBool SupportsBuild(const char* build_id) {
    try {
        return totk::IsTargetBuild(build_id);
    } catch (...) {
        return false;
    }
}

void* Create(const EdenDsmodHostApi* host, const char*) {
    try {
        if (!dsmod_sdk::HostAbiMatches(host) || host->title_id != totk::kTitleId ||
            !totk::IsTargetBuild(host->build_id))
            return nullptr;
        return new (std::nothrow) Instance;
    } catch (...) {
        return nullptr;
    }
}

void Destroy(void* instance) {
    delete static_cast<Instance*>(instance);
}

void Sample(void* instance, const EdenDsmodHostApi* host) {
    try {
        if (!instance || !host || !host->publish_i64)
            return;
        const totk::Status s = totk::ReadStatus(*host, static_cast<Instance*>(instance)->offsets);
        host->publish_i64(host->userdata, "totk.ready", 1);
        host->publish_i64(host->userdata, "totk.status.valid", s.valid);
        if (!s.valid)
            return;
        host->publish_i64(host->userdata, "totk.status.hearts", s.hearts);
        host->publish_i64(host->userdata, "totk.status.max_hearts", s.max_hearts);
        host->publish_i64(host->userdata, "totk.status.stamina", s.stamina);
        host->publish_i64(host->userdata, "totk.status.max_stamina", s.max_stamina);
        host->publish_i64(host->userdata, "totk.status.rupees", s.rupees);
    } catch (...) {
    }
}

void Tick(void*, const EdenDsmodHostApi*) {}

const EdenDsmodModuleApi kModule{
    EDEN_DSMOD_MODULE_ABI_VERSION,
    sizeof(EdenDsmodModuleApi),
    0,
    EDEN_DSMOD_MODULE_ABI_HASH,
    totk::kTitleId,
    "Tears of the Kingdom companion",
    0,
    SupportsBuild,
    Create,
    Destroy,
    Sample,
    Tick,
};

} // namespace

DSMOD_SDK_EXPORT_MODULE(kModule)

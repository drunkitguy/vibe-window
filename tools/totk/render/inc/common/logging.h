// SPDX-License-Identifier: GPL-3.0-or-later
// Stands in for the runtime's common/logging.h, which needs the runtime's own fmt version; the
// render tool does not log.
#pragma once
#include <fmt/format.h>
#define LOG_TRACE(c, ...) ((void)0)
#define LOG_DEBUG(c, ...) ((void)0)
#define LOG_INFO(c, ...) ((void)0)
#define LOG_WARNING(c, ...) ((void)0)
#define LOG_ERROR(c, ...) ((void)0)
#define LOG_CRITICAL(c, ...) ((void)0)

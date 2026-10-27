#include "moonlight_wasm.hpp"

#include <iostream>
#include <array>
#include <utility>
#include <sstream>
#include <chrono>
#include <thread>
#include <cmath>
#include <map>
#include <cstring>
#include <cctype>
#include <string>
#include <algorithm>
#include <vector>
#include <cstddef>
#include <cstdint>
#include <limits>

#include <Limelight.h>
#include <emscripten/emscripten.h>

// Maximum number of controllers supported by the Moonlight protocol
static constexpr int kMaxControllers = 16;

// Bitmask for gamepad combo buttons to stop the streaming session
const short STOP_STREAM_BUTTONS = BACK_FLAG | PLAY_FLAG | LB_FLAG | RB_FLAG;

// Bitmask for gamepad combo buttons to toggle the performance stats overlay
const short PERF_STATS_BUTTONS = BACK_FLAG | LB_FLAG | RB_FLAG | X_FLAG;

// Flag for gamepad to track controller rumble state
bool rumbleFeedbackSwitch = false;

// Flags for gamepad to track mouse emulation state
bool mouseEmulationSwitch = false;
bool mouseEmulationActive = false;

// Flags for gamepad to track face buttons state
bool flipABfaceButtonsSwitch = false;
bool flipXYfaceButtonsSwitch = false;

// For explanation on ordering, see: https://www.w3.org/TR/gamepad/#remapping
// Enumeration for gamepad buttons
enum GamepadButton {
  A, B, X, Y,
  LeftBumper, RightBumper,
  LeftTrigger, RightTrigger,
  Back, Play,
  LeftStick, RightStick,
  Up, Down, Left, Right,
  Special,
  Count,
};

// For explanation on ordering, see: https://www.w3.org/TR/gamepad/#remapping
// Enumeration for gamepad axis
enum GamepadAxis {
  LeftX = 0,
  LeftY = 1,
  RightX = 2,
  RightY = 3,
};

// Snapshot of one browser gamepad slot, written in one main-thread call by vwSampleGamepads()
// in platform/gamepad.js. The byte offsets are fixed: keep them in sync with that function.
struct PadSnapshot {
  double timestamp;     // 0: Gamepad.timestamp
  double sampleAgeMs;   // 8: performance.now() - Gamepad.timestamp at sampling time
  int32_t connected;    // 16
  int32_t standard;     // 20: 1 when Gamepad.mapping is "standard"
  int32_t numButtons;   // 24
  int32_t numAxes;      // 28
  uint32_t pressedMask; // 32: bit i set when buttons[i].pressed (first 32 buttons)
  int32_t idSerial;     // 36: bumped whenever the id below is rewritten
  float leftTrigger;    // 40: buttons[6].value
  float rightTrigger;   // 44: buttons[7].value
  float axes[10];       // 48: axes[0..9], 0 when missing
  char id[64];          // 88: Gamepad.id, ASCII, NUL terminated, only rewritten when it changes
};
static_assert(sizeof(PadSnapshot) == 152, "PadSnapshot must match VW_PAD_STRIDE in gamepad.js");
static_assert(offsetof(PadSnapshot, pressedMask) == 32, "PadSnapshot layout changed");
static_assert(offsetof(PadSnapshot, leftTrigger) == 40, "PadSnapshot layout changed");
static_assert(offsetof(PadSnapshot, axes) == 48, "PadSnapshot layout changed");
static_assert(offsetof(PadSnapshot, id) == 88, "PadSnapshot layout changed");

alignas(8) static PadSnapshot s_PadSnapshots[kMaxControllers];

// The controller state as last sent to the host, after the conversion to protocol integers
struct SentControllerState {
  short buttonFlags = 0;
  unsigned char leftTrigger = 0;
  unsigned char rightTrigger = 0;
  short leftStickX = 0;
  short leftStickY = 0;
  short rightStickX = 0;
  short rightStickY = 0;

  bool operator==(const SentControllerState& other) const {
    return buttonFlags == other.buttonFlags &&
      leftTrigger == other.leftTrigger && rightTrigger == other.rightTrigger &&
      leftStickX == other.leftStickX && leftStickY == other.leftStickY &&
      rightStickX == other.rightStickX && rightStickY == other.rightStickY;
  }

  bool IsNeutral() const {
    return buttonFlags == 0 && leftTrigger == 0 && rightTrigger == 0 &&
      leftStickX == 0 && leftStickY == 0 && rightStickX == 0 && rightStickY == 0;
  }
};

// Per-slot state used for Tizen placeholder filtering, id tracking, arrival announcements
// and send-on-change
struct ControllerSlotState {
  bool seenTimestamp = false; // true once a non-zero timestamp was observed for this id
  bool announced = false; // true after LiSendControllerArrivalEvent succeeded
  std::string id; // last known Gamepad.id (used to detect replacements)
  bool forceSend = true; // send the full state on the next poll (after an arrival)
  SentControllerState sent; // what the host last received
  std::chrono::steady_clock::time_point lastSend; // when the state was last sent
  double lastTimestamp = 0.0; // Gamepad.timestamp of the previous poll
  bool hatSeen = false; // the last axis has shown a D-pad hat's neutral value (non-standard pads)
  bool layoutReported = false; // the non-standard layout was logged and shown once for this id
  int type = LI_CTYPE_UNKNOWN; // controller family, detected once at arrival
};

static ControllerSlotState slotState[kMaxControllers];

// Mouse buttons last sent while mouse emulation is active, so only changes are sent
static bool s_MouseButtonsDown[3] = { false, false, false };

// While any input is held, send the full state again at this interval, so a host that missed
// a packet or reconnected still sees what is held. Neutral pads send nothing until they change.
static constexpr std::chrono::milliseconds kHeldStateResendInterval{1000};

InputStats g_InputStats;

static void UpdateMax(std::atomic<uint32_t>& target, uint32_t value) {
  uint32_t previous = target.load(std::memory_order_relaxed);
  while (value > previous && !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {
  }
}

void ResetInputStats(bool resetStalls) {
  g_InputStats.pollCount.store(0);
  g_InputStats.pollIntervalTotalUs.store(0);
  g_InputStats.pollIntervalMaxUs.store(0);
  g_InputStats.sampleAgeCount.store(0);
  g_InputStats.sampleAgeTotalUs.store(0);
  g_InputStats.sampleAgeMaxUs.store(0);
  g_InputStats.packetsSent.store(0);
  if (resetStalls) {
    g_InputStats.sendStalls.store(0);
    g_InputStats.padType.store(-1);
    g_InputStats.padStandard.store(-1);
  }
}

void RecordPollInterval(uint32_t intervalUs) {
  g_InputStats.pollCount.fetch_add(1, std::memory_order_relaxed);
  g_InputStats.pollIntervalTotalUs.fetch_add(intervalUs, std::memory_order_relaxed);
  UpdateMax(g_InputStats.pollIntervalMaxUs, intervalUs);
}

static void RecordSampleAge(double ageMs) {
  // Gamepad.timestamp may use another time base on some TVs: ignore values that make no sense
  if (ageMs < 0.0 || ageMs > 1000.0) {
    return;
  }
  const uint32_t ageUs = static_cast<uint32_t>(ageMs * 1000.0);
  g_InputStats.sampleAgeCount.fetch_add(1, std::memory_order_relaxed);
  g_InputStats.sampleAgeTotalUs.fetch_add(ageUs, std::memory_order_relaxed);
  UpdateMax(g_InputStats.sampleAgeMaxUs, ageUs);
}

static const char* ControllerTypeName(int type) {
  switch (type) {
    case LI_CTYPE_XBOX: return "Xbox";
    case LI_CTYPE_PS: return "PlayStation";
    case LI_CTYPE_NINTENDO: return "Nintendo";
    default: return "Generic";
  }
}

// Append the input lines of the stats overlay. Called about once per second by the video
// decoder thread; the per-window counters are reset here.
int FormatInputStats(char* output, int length) {
  using Clock = std::chrono::steady_clock;
  static Clock::time_point lastFormat;
  const Clock::time_point now = Clock::now();
  double windowSeconds = std::chrono::duration<double>(now - lastFormat).count();
  if (lastFormat == Clock::time_point() || windowSeconds <= 0.0 || windowSeconds > 10.0) {
    windowSeconds = 1.0;
  }
  lastFormat = now;

  const uint32_t polls = g_InputStats.pollCount.exchange(0);
  const uint32_t pollTotalUs = g_InputStats.pollIntervalTotalUs.exchange(0);
  const uint32_t pollMaxUs = g_InputStats.pollIntervalMaxUs.exchange(0);
  const uint32_t ages = g_InputStats.sampleAgeCount.exchange(0);
  const uint32_t ageTotalUs = g_InputStats.sampleAgeTotalUs.exchange(0);
  const uint32_t ageMaxUs = g_InputStats.sampleAgeMaxUs.exchange(0);
  const uint32_t packets = g_InputStats.packetsSent.exchange(0);
  const uint32_t stalls = g_InputStats.sendStalls.load();
  const int type = g_InputStats.padType.load();
  const int standard = g_InputStats.padStandard.load();

  char pollString[48];
  if (polls > 0) {
    snprintf(pollString, sizeof(pollString), "%.1f/%.1f ms", pollTotalUs / 1000.0 / polls, pollMaxUs / 1000.0);
  } else {
    snprintf(pollString, sizeof(pollString), "n/a");
  }
  char ageString[48];
  if (ages > 0) {
    snprintf(ageString, sizeof(ageString), "%.1f/%.1f ms", ageTotalUs / 1000.0 / ages, ageMaxUs / 1000.0);
  } else {
    snprintf(ageString, sizeof(ageString), "n/a");
  }

  int ret;
  if (type < 0) {
    ret = snprintf(output, length,
      "Gamepad: none\n"
      "Input send stalls (>10 ms): %u\n",
      stalls);
  } else {
    ret = snprintf(output, length,
      "Gamepad: %s, %s mapping, poll interval avg/max: %s\n"
      "Gamepad sample age avg/max: %s\n"
      "Controller packets sent: %.0f/s\n"
      "Input send stalls (>10 ms): %u\n",
      ControllerTypeName(type), standard == 1 ? "standard" : "non-standard", pollString,
      ageString,
      packets / windowSeconds,
      stalls);
  }
  if (ret < 0 || ret >= length) {
    return 0;
  }
  return ret;
}

bool AnyGamepadAnnounced() {
  for (int i = 0; i < kMaxControllers; ++i) {
    if (slotState[i].announced) {
      return true;
    }
  }
  return false;
}

// Read every gamepad slot with one synchronous call to the main thread.
// Returns the number of slots read, or -1 if the sampler is missing.
static int SampleGamepads() {
  return MAIN_THREAD_EM_ASM_INT({
    return (typeof vwSampleGamepads === 'function') ? vwSampleGamepads($0, $1, HEAPF64, HEAP32, HEAPF32, HEAPU8) : -1;
  }, s_PadSnapshots, kMaxControllers);
}

// Decode a D-pad hat reported as an axis, as Chromium does on Linux for DirectInput/HID pads:
// -1 is up, then steps of 2/7 clockwise to 5/7 (up-left); values above 1 mean centered.
static short DecodeHatAxis(float value) {
  if (value > 1.05f || value < -1.05f) {
    return 0;
  }
  const float position = (value + 1.0f) * 3.5f;
  const int step = static_cast<int>(std::lround(position));
  if (step < 0 || step > 7 || std::fabs(position - step) > 0.2f) {
    return 0;
  }
  static const short kHatFlags[8] = {
    UP_FLAG, UP_FLAG | RIGHT_FLAG, RIGHT_FLAG, DOWN_FLAG | RIGHT_FLAG,
    DOWN_FLAG, DOWN_FLAG | LEFT_FLAG, LEFT_FLAG, UP_FLAG | LEFT_FLAG
  };
  return kHatFlags[step];
}

static short ToStickValue(float axis) {
  if (axis > 1.0f) {
    axis = 1.0f;
  } else if (axis < -1.0f) {
    axis = -1.0f;
  }
  return static_cast<short>(axis * std::numeric_limits<short>::max());
}

static unsigned char ToTriggerValue(float value) {
  if (value > 1.0f) {
    value = 1.0f;
  } else if (value < 0.0f) {
    value = 0.0f;
  }
  return static_cast<unsigned char>(value * std::numeric_limits<unsigned char>::max());
}

// Detect controller family from Gamepad.id (PS / Xbox / Nintendo / Unknown)
static uint8_t GetControllerType(const char* id) {
  // Guard against null or empty id strings
  if (!id || !*id) {
    return LI_CTYPE_UNKNOWN;
  }

  // Lower-case the id once so all substring checks are case-insensitive
  std::string lower(id);
  for (char& c : lower) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }

  // Helper for simple substring search on the lower-cased id
  const auto contains = [&](const char* needle) {
    return lower.find(needle) != std::string::npos;
  };

  // PlayStation (DualSense, DualShock, Sony vendor 054c)
  if (contains("vendor: 054c") || contains("vendor:054c") ||
      lower.find("054c-") == 0 ||
      contains("dualsense") || contains("dualshock") ||
      contains("playstation") ||
      contains("sony interactive entertainment") ||
      contains("sony computer entertainment")) {
    return LI_CTYPE_PS;
  }

  // Xbox (vendor 045e)
  if (contains("vendor: 045e") || contains("vendor:045e") ||
      lower.find("045e-") == 0 || contains("xbox")) {
    return LI_CTYPE_XBOX;
  }

  // Nintendo (vendor 057e, Joy-Con, Pro Controller)
  if (contains("vendor: 057e") || contains("vendor:057e") ||
      lower.find("057e-") == 0 ||
      contains("nintendo") || contains("joy-con")) {
    return LI_CTYPE_NINTENDO;
  }

  return LI_CTYPE_UNKNOWN;
}

// Build the supported-buttons mask advertised in the arrival event (respects face-button flips)
static uint32_t GetSupportedButtons(int numButtons, bool flipAB, bool flipXY) {
  // Standard Gamepad layout; triggers are analog so they contribute no flags
  static constexpr uint32_t kButtons[] = {
    A_FLAG, B_FLAG, X_FLAG, Y_FLAG,
    LB_FLAG, RB_FLAG,
    0, 0, // triggers are analog, not flags
    BACK_FLAG, PLAY_FLAG,
    LS_CLK_FLAG, RS_CLK_FLAG,
    UP_FLAG, DOWN_FLAG, LEFT_FLAG, RIGHT_FLAG,
    SPECIAL_FLAG
  };

  uint32_t flags = 0;
  const int limit = std::min(numButtons, 17);

  // Iterate through the buttons and set the appropriate flags
  for (int i = 0; i < limit; ++i) {
    auto flag = kButtons[i];
    // Mirror the same swaps used by GetButtonFlags()
    if (flipAB && i < 2) {
      flag = kButtons[1 - i];       // swap A ↔ B
    } else if (flipXY && i >= 2 && i < 4) {
      flag = kButtons[5 - i];       // swap X ↔ Y
    }
    flags |= flag;
  }

  return flags;
}

// Function to map gamepad buttons to flags
static short GetButtonFlags(uint32_t pressedMask, int numButtons) {
  // Triggers are considered analog buttons in the "Emscripten API", however they need
  // to be passed in separate arguments for "Limelight" (it even lacks flags for them).

  const int* buttonMasks = nullptr;
  int buttonMasksSize = 0;

  // Define button mapping with A/B and X/Y swapped
  static const int buttonMasksABXY[] = {
    B_FLAG, A_FLAG, Y_FLAG, X_FLAG,
    LB_FLAG, RB_FLAG,
    0 /* LT */, 0 /* RT */,
    BACK_FLAG, PLAY_FLAG,
    LS_CLK_FLAG, RS_CLK_FLAG,
    UP_FLAG, DOWN_FLAG, LEFT_FLAG, RIGHT_FLAG,
    SPECIAL_FLAG,
  };
  // Define button mapping with A/B swapped
  static const int buttonMasksAB[] = {
    B_FLAG, A_FLAG, X_FLAG, Y_FLAG,
    LB_FLAG, RB_FLAG,
    0 /* LT */, 0 /* RT */,
    BACK_FLAG, PLAY_FLAG,
    LS_CLK_FLAG, RS_CLK_FLAG,
    UP_FLAG, DOWN_FLAG, LEFT_FLAG, RIGHT_FLAG,
    SPECIAL_FLAG,
  };
  // Define button mapping with X/Y swapped
  static const int buttonMasksXY[] = {
    A_FLAG, B_FLAG, Y_FLAG, X_FLAG,
    LB_FLAG, RB_FLAG,
    0 /* LT */, 0 /* RT */,
    BACK_FLAG, PLAY_FLAG,
    LS_CLK_FLAG, RS_CLK_FLAG,
    UP_FLAG, DOWN_FLAG, LEFT_FLAG, RIGHT_FLAG,
    SPECIAL_FLAG,
  };
  // Define default button mapping
  static const int buttonMasksDefault[] = {
    A_FLAG, B_FLAG, X_FLAG, Y_FLAG,
    LB_FLAG, RB_FLAG,
    0 /* LT */, 0 /* RT */,
    BACK_FLAG, PLAY_FLAG,
    LS_CLK_FLAG, RS_CLK_FLAG,
    UP_FLAG, DOWN_FLAG, LEFT_FLAG, RIGHT_FLAG,
    SPECIAL_FLAG,
  };

  // Check if the A/B or X/Y face buttons switches are checked
  if (flipABfaceButtonsSwitch && flipXYfaceButtonsSwitch) {
    // Swap both A/B and X/Y buttons
    buttonMasks = buttonMasksABXY;
    buttonMasksSize = sizeof(buttonMasksABXY) / sizeof(buttonMasksABXY[0]);
  } else if (flipABfaceButtonsSwitch) { // Check if the A/B face buttons switch is checked
    // Swap A and B buttons
    buttonMasks = buttonMasksAB;
    buttonMasksSize = sizeof(buttonMasksAB) / sizeof(buttonMasksAB[0]);
  } else if (flipXYfaceButtonsSwitch) { // Check if the X/Y face buttons switch is checked
    // Swap X and Y buttons
    buttonMasks = buttonMasksXY;
    buttonMasksSize = sizeof(buttonMasksXY) / sizeof(buttonMasksXY[0]);
  } else {
    // Default buttons layout
    buttonMasks = buttonMasksDefault;
    buttonMasksSize = sizeof(buttonMasksDefault) / sizeof(buttonMasksDefault[0]);
  }

  short result = 0;

  // Iterate through the gamepad buttons and set the corresponding flags
  for (int i = 0; i < numButtons && i < buttonMasksSize; ++i) {
    if (pressedMask & (1u << i)) {
      result |= buttonMasks[i];
    }
  }

  return result;
}

// Called when gamepad settings change; clears announced flags so arrival is re-sent with updated data
void MoonlightInstance::HandleGamepadInputState(bool rumbleFeedback, bool mouseEmulation, bool flipABfaceButtons, bool flipXYfaceButtons) {
  // Force re-announce on next poll so the host gets the updated button map / capabilities
  for (int i = 0; i < kMaxControllers; ++i) {
    slotState[i].announced = false;
  }
  for (int i = 0; i < 3; ++i) {
    s_MouseButtonsDown[i] = false;
  }
  mouseEmulationActive = false;

  // A new stream starts with fresh input stats
  ResetInputStats(true);

  // Store the new settings for use during polling
  rumbleFeedbackSwitch = rumbleFeedback;
  mouseEmulationSwitch = mouseEmulation;
  flipABfaceButtonsSwitch = flipABfaceButtons;
  flipXYfaceButtonsSwitch = flipXYfaceButtons;
}

// Send one controller state, counting it for the stats overlay. Returns true when it was queued.
static bool SendControllerState(int gamepadID, short activeGamepadMask, const SentControllerState& state) {
  if (LiSendMultiControllerEvent(gamepadID, activeGamepadMask, state.buttonFlags, state.leftTrigger,
      state.rightTrigger, state.leftStickX, state.leftStickY, state.rightStickX, state.rightStickY) != 0) {
    return false;
  }
  g_InputStats.packetsSent.fetch_add(1, std::memory_order_relaxed);
  return true;
}

static void SendMouseButton(int index, bool down, int button) {
  if (s_MouseButtonsDown[index] != down) {
    if (LiSendMouseButtonEvent(down ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, button) == 0) {
      s_MouseButtonsDown[index] = down;
    }
  }
}

static void ReleaseMouseButtons() {
  SendMouseButton(0, false, BUTTON_LEFT);
  SendMouseButton(1, false, BUTTON_MIDDLE);
  SendMouseButton(2, false, BUTTON_RIGHT);
}

// Function to poll gamepad input
void MoonlightInstance::PollGamepads() {
  // Read all slots with one main-thread hop (the old code needed 2 + one per slot)
  const int numGamepads = SampleGamepads();
  if (numGamepads < 0) {
    static bool loggedMissingSampler = false;
    if (!loggedMissingSampler) {
      loggedMissingSampler = true;
      ClLogMessage("Gamepad sampler is missing, gamepad input is disabled!\n");
    }
    return;
  }

  const auto now = std::chrono::steady_clock::now();

  // Prevent repeated trigger while the button combo is held down
  static bool comboTriggered[kMaxControllers] = { false };

  // Snapshot slots, build physical-index mask, and collect removals / arrivals
  short activeGamepadMask = 0;
  int removed[kMaxControllers];
  int numRemoved = 0;
  int arrived[kMaxControllers];
  int numArrived = 0;

  // Cap at the protocol maximum of 16 controllers
  const int limit = std::min(numGamepads, kMaxControllers);

  // See matching logic in "[utils.js] getConnectedGamepadMask()" – these must stay in sync!

  // Decide per slot: still real, removed, or newly arrived
  for (int i = 0; i < kMaxControllers; ++i) {
    ControllerSlotState& state = slotState[i];
    const PadSnapshot& pad = s_PadSnapshots[i];

    const bool connected = i < limit && pad.connected != 0; // slot exists and reports connected
    const char* currentId = connected ? pad.id : ""; // current Gamepad.id (or empty)
    const double timestamp = connected ? pad.timestamp : 0.0; // current timestamp (0 if disconnected)
    const bool sameDevice = (state.id == currentId); // true when the id has not changed

    // Real = connected and (non-zero timestamp, or previously seen real with same id)
    // This keeps the Tizen placeholder workaround while detecting replacements
    const bool real = connected && (timestamp != 0.0 || (sameDevice && state.seenTimestamp));

    // Previously announced but gone or replaced → queue removal
    if (state.announced && (!real || !sameDevice)) {
      removed[numRemoved++] = i;
      state.announced = false;
    }

    // Not a real controller (placeholder or disconnected) → clear state and skip
    if (!real) {
      state = ControllerSlotState(); // clear so a future different device starts fresh
      continue;
    }

    // A different device in the same slot starts with fresh state
    if (!sameDevice) {
      state = ControllerSlotState();
    }

    // Record identity and mark this physical bit in the active mask
    state.id = currentId;
    state.seenTimestamp = true;
    activeGamepadMask |= static_cast<short>(1 << i);

    // Queue an arrival event if we have not announced this device yet
    if (!state.announced) {
      arrived[numArrived++] = i;
    }
  }

  // Send zeroed events for removed / replaced controllers so the host cleans up
  for (int r = 0; r < numRemoved; ++r) {
    const int i = removed[r];
    if (LiSendMultiControllerEvent(i, activeGamepadMask & ~(1 << i), 0, 0, 0, 0, 0, 0, 0) == 0) {
      g_InputStats.packetsSent.fetch_add(1, std::memory_order_relaxed);
    }
  }

  // Announce new (or re-arrived) controllers so Sunshine can pick the right profile
  for (int a = 0; a < numArrived; ++a) {
    const int i = arrived[a];
    const PadSnapshot& pad = s_PadSnapshots[i];
    const uint8_t type = GetControllerType(pad.id);
    const uint32_t buttons = GetSupportedButtons(pad.numButtons, flipABfaceButtonsSwitch, flipXYfaceButtonsSwitch);

    // Only advertise capabilities the browser actually provides
    uint16_t capabilities = (pad.numButtons > 7) ? LI_CCAP_ANALOG_TRIGGERS : 0;

    // The browser Gamepad API does not expose motion, battery, RGB or touchpad
    // data. Only advertise rumble when our existing feedback path can use it.
    if (rumbleFeedbackSwitch) {
      const int hasRumble = MAIN_THREAD_EM_ASM_INT({
        var gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
        var gamepad = gamepads[$0];
        return !!(gamepad && gamepad.vibrationActuator && typeof gamepad.vibrationActuator.playEffect === 'function');
      }, i);
      // Advertise rumble only when the actuator is actually available
      if (hasRumble) {
        capabilities |= LI_CCAP_RUMBLE;
      }
    }

    // On failure leave un-announced; next poll will retry
    if (LiSendControllerArrivalEvent(i, activeGamepadMask, type, buttons, capabilities) == 0) {
      ControllerSlotState& state = slotState[i];
      state.announced = true;
      state.type = type;
      // Send the full state right after every arrival, even if it looks unchanged
      state.forceSend = true;
      g_InputStats.packetsSent.fetch_add(1, std::memory_order_relaxed);

      // Pads without the standard mapping (often 2.4 GHz dongles in DInput/HID mode) may have
      // buttons and sticks in other places: log the layout and tell the user once
      if (!pad.standard && !state.layoutReported) {
        state.layoutReported = true;
        ClLogMessage("Gamepad %d has a non-standard mapping: id \"%s\", %d buttons, %d axes\n",
          i, pad.id, pad.numButtons, pad.numAxes);
        PostToJsAsync(std::string("gamepadLayoutWarning: ") + pad.id);
      }
    }
  }

  // Remember the first active pad for the stats overlay
  int statsType = -1;
  int statsStandard = -1;

  // Process live input for every real, already-announced controller
  for (int gamepadID = 0; gamepadID < limit; ++gamepadID) {
    // Skip slots that are inactive or still waiting for a successful arrival
    if (!(activeGamepadMask & (1 << gamepadID)) || !slotState[gamepadID].announced) {
      continue;
    }

    ControllerSlotState& state = slotState[gamepadID];
    const PadSnapshot& pad = s_PadSnapshots[gamepadID];

    if (statsType < 0) {
      statsType = state.type;
      statsStandard = pad.standard ? 1 : 0;
    }

    // Measure how old the browser's sample was when it was read, for every new sample
    if (pad.timestamp != state.lastTimestamp) {
      state.lastTimestamp = pad.timestamp;
      RecordSampleAge(pad.sampleAgeMs);
    }

    // Convert to protocol values once, exactly as they will be sent
    SentControllerState current;
    current.buttonFlags = GetButtonFlags(pad.pressedMask, pad.numButtons);
    if (!pad.standard && pad.numAxes > 0) {
      // A D-pad hat on the last axis; only trusted after its neutral value (above 1) was seen,
      // so a real analog axis there is never read as a D-pad
      const float hat = pad.axes[std::min(pad.numAxes, 10) - 1];
      if (hat > 1.05f) {
        state.hatSeen = true;
      }
      if (state.hatSeen) {
        current.buttonFlags |= DecodeHatAxis(hat);
      }
    }
    current.leftTrigger = ToTriggerValue(pad.leftTrigger);
    current.rightTrigger = ToTriggerValue(pad.rightTrigger);
    current.leftStickX = ToStickValue(pad.axes[GamepadAxis::LeftX]);
    current.leftStickY = ToStickValue(-pad.axes[GamepadAxis::LeftY]);
    current.rightStickX = ToStickValue(pad.axes[GamepadAxis::RightX]);
    current.rightStickY = ToStickValue(-pad.axes[GamepadAxis::RightY]);
    const short buttonFlags = current.buttonFlags;

    // Check if the current button flags match the defined button combination on the gamepad
    if (buttonFlags == STOP_STREAM_BUTTONS) {
      // Terminate the connection
      stopStream();
      return;
    } else if (buttonFlags == PERF_STATS_BUTTONS) {
      if (!comboTriggered[gamepadID]) {
        // Toggle performance stats overlay
        toggleStats();
        // Mark combo as triggered until buttons are released
        comboTriggered[gamepadID] = true;
      }
    } else {
      // Reset when buttons are released
      comboTriggered[gamepadID] = false;
    }

    // Check if the mouse emulation switch is checked
    const bool wasMouseEmulationActive = mouseEmulationActive;
    if (mouseEmulationSwitch) {
      static std::chrono::time_point<std::chrono::steady_clock> activatePressTimes[kMaxControllers];
      static bool activatePressed[kMaxControllers] = { false };
      // Toggle mouse emulation on and off based on how long the PLAY/START button is pressed
      if (buttonFlags & PLAY_FLAG) {
        if (!activatePressed[gamepadID]) {
          activatePressed[gamepadID] = true;
          activatePressTimes[gamepadID] = now;
        }
        // Calculate the duration in milliseconds since the PLAY/START button was pressed
        auto durationTime = std::chrono::duration_cast<std::chrono::milliseconds>(now - activatePressTimes[gamepadID]).count();
        // If the button has been pressed for at least 1000 milliseconds (1 second)
        if (durationTime >= 1000) {
          // Toggle mouse emulation state and notify the user without blocking this thread
          mouseEmulationActive = !mouseEmulationActive;
          PostToJsAsync(std::string(mouseEmulationActive ? "mouseEmulationOn" : "mouseEmulationOff"));
          // Reset the PLAY/START press time to the current time after toggling
          activatePressTimes[gamepadID] = now;
        }
      } else {
        // If the PLAY/START button is not pressed, reset PLAY/START press time
        activatePressed[gamepadID] = false;
      }
    } else {
      // Deactivate mouse emulation if the mouse emulation switch is unchecked
      mouseEmulationActive = false;
    }

    if (mouseEmulationActive && !wasMouseEmulationActive) {
      // Entering mouse mode: let the host see a released controller instead of a held START
      SentControllerState neutral;
      if (SendControllerState(gamepadID, activeGamepadMask, neutral)) {
        state.sent = neutral;
        state.lastSend = now;
      }
    } else if (!mouseEmulationActive && wasMouseEmulationActive) {
      // Leaving mouse mode: release the mouse buttons and send the controller state again
      ReleaseMouseButtons();
      state.forceSend = true;
    }

    // If mouse emulation is active, then send mouse input to the desired handler (acts as a mouse)
    if (mouseEmulationActive) {
      const float leftStickX = current.leftStickX;
      const float leftStickY = current.leftStickY;
      const float rightStickX = current.rightStickX;
      const float rightStickY = current.rightStickY;
      // Left Stick values are mapped to horizontal and vertical mouse movements
      const float baseMouseSpeed = 10.0f;
      const float leftStickMagnitude = std::sqrt(leftStickX * leftStickX + leftStickY * leftStickY) / std::numeric_limits<short>::max();
      const float mouseSpeed = baseMouseSpeed * leftStickMagnitude;
      const int mouseXDelta = static_cast<int>(leftStickX / std::numeric_limits<short>::max() * mouseSpeed);
      const int mouseYDelta = static_cast<int>(-leftStickY / std::numeric_limits<short>::max() * mouseSpeed);

      // Only send movement and scrolling when there is any
      if (mouseXDelta != 0 || mouseYDelta != 0) {
        LiSendMouseMoveEvent(mouseXDelta, mouseYDelta);
      }

      // Right Stick values are mapped to horizontal and vertical mouse scrolls
      const float baseScrollSpeed = 1.0f;
      const float rightStickMagnitude = std::sqrt(rightStickX * rightStickX + rightStickY * rightStickY) / std::numeric_limits<short>::max();
      const float scrollSpeed = baseScrollSpeed * rightStickMagnitude;
      const int scrollXDelta = static_cast<int>(rightStickX / std::numeric_limits<short>::max() * scrollSpeed);
      const int scrollYDelta = static_cast<int>(rightStickY / std::numeric_limits<short>::max() * scrollSpeed);
      if (scrollXDelta != 0) {
        LiSendHScrollEvent(scrollXDelta);
      }
      if (scrollYDelta != 0) {
        LiSendScrollEvent(scrollYDelta);
      }

      // Face Buttons values are mapped to control mouse buttons, sent only when they change
      SendMouseButton(0, (buttonFlags & (A_FLAG | LB_FLAG)) != 0, BUTTON_LEFT);
      SendMouseButton(1, (buttonFlags & (X_FLAG | Y_FLAG)) != 0, BUTTON_MIDDLE);
      SendMouseButton(2, (buttonFlags & (B_FLAG | RB_FLAG)) != 0, BUTTON_RIGHT);
    } else {
      // Acts as a gamepad: send only when the state changed, after an arrival, or once per
      // second while anything is held
      const bool changed = state.forceSend || !(current == state.sent);
      const bool resendHeld = !current.IsNeutral() && (now - state.lastSend >= kHeldStateResendInterval);
      if (changed || resendHeld) {
        if (SendControllerState(gamepadID, activeGamepadMask, current)) {
          state.sent = current;
          state.forceSend = false;
          state.lastSend = now;
        }
      }
    }
  }

  g_InputStats.padType.store(statsType, std::memory_order_relaxed);
  g_InputStats.padStandard.store(statsStandard, std::memory_order_relaxed);
}

// Function to send controller rumble feedback for gamepad
void MoonlightInstance::ClControllerRumble(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) {
  const float weakMagnitude = static_cast<float>(highFreqMotor) / static_cast<float>(UINT16_MAX);
  const float strongMagnitude = static_cast<float>(lowFreqMotor) / static_cast<float>(UINT16_MAX);

  // Check if the rumble feedback switch is checked
  if (rumbleFeedbackSwitch) {
    std::ostringstream ss;
    ss << controllerNumber << "," << weakMagnitude << "," << strongMagnitude;
    // Do not block the control stream thread on the main thread
    PostToJsAsync(std::string("controllerRumble: ") + ss.str());
  }
}

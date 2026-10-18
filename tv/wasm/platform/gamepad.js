// UI gamepad watcher for menu navigation, plus the sampler the stream's input thread uses.
//
// The UI watcher only runs outside a stream (index.js stops it in showStreamMode), so it
// never competes with the input thread for the main thread. Both pollers are allocation free
// per poll: navigator.getGamepads() is the only call that may create objects, and that is
// inside the browser.
const Controller = (function() {
  // UI navigation does not need more than the display rate
  const POLL_INTERVAL_MS = 16;
  const MAX_PADS = 16;
  const MAX_TRACKED = 32;

  let pollingInterval = null;
  let listenersAdded = false;

  // Per slot: the last seen pressed state of each button and value of each axis, reused across polls
  const slots = [];
  for (let i = 0; i < MAX_PADS; ++i) {
    slots.push({
      active: false,
      numButtons: 0,
      numAxes: 0,
      pressed: new Array(MAX_TRACKED).fill(false),
      axes: new Float64Array(MAX_TRACKED),
    });
  }

  function resetSlot(slot, gamepad) {
    slot.active = true;
    slot.numButtons = Math.min(gamepad.buttons.length, MAX_TRACKED);
    slot.numAxes = Math.min(gamepad.axes.length, MAX_TRACKED);
    for (let b = 0; b < slot.numButtons; ++b) {
      slot.pressed[b] = gamepad.buttons[b].pressed;
    }
    for (let a = 0; a < slot.numAxes; ++a) {
      slot.axes[a] = gamepad.axes[a];
    }
  }

  function analyzeGamepad(gamepad) {
    const index = gamepad.index;
    if (index < 0 || index >= MAX_PADS) {
      return;
    }
    const slot = slots[index];
    const buttons = gamepad.buttons;
    const axes = gamepad.axes;
    if (!slot.active || slot.numButtons !== Math.min(buttons.length, MAX_TRACKED) || slot.numAxes !== Math.min(axes.length, MAX_TRACKED)) {
      // First sight of this pad, or its layout changed: take it as the new baseline
      resetSlot(slot, gamepad);
      return;
    }

    // Only build a change list when something actually changed
    let changes = null;
    for (let b = 0; b < slot.numButtons; ++b) {
      const pressed = buttons[b].pressed;
      if (slot.pressed[b] !== pressed) {
        slot.pressed[b] = pressed;
        (changes || (changes = [])).push({ type: 'button', index: b, pressed: pressed });
      }
    }
    for (let a = 0; a < slot.numAxes; ++a) {
      const value = axes[a];
      if (slot.axes[a] !== value) {
        slot.axes[a] = value;
        (changes || (changes = [])).push({ type: 'axis', index: a, value: value });
      }
    }

    if (changes) {
      window.dispatchEvent(new CustomEvent('gamepadinputchanged', {
        detail: { changes },
      }));
    }
  }

  function pollGamepads() {
    const gamepads = navigator.getGamepads ? navigator.getGamepads() : null;
    if (!gamepads) {
      return;
    }
    for (let i = 0; i < gamepads.length; ++i) {
      const gamepad = gamepads[i];
      if (gamepad) {
        analyzeGamepad(gamepad);
      }
    }
  }

  // Count connected pads and tell the Wasm module, so its input thread only samples when needed
  function reportGamepadCount() {
    let count = 0;
    const gamepads = navigator.getGamepads ? navigator.getGamepads() : null;
    if (gamepads) {
      for (let i = 0; i < gamepads.length; ++i) {
        if (gamepads[i] && gamepads[i].connected) {
          ++count;
        }
      }
    }
    if (typeof Module !== 'undefined' && Module && typeof Module.setGamepadCount === 'function') {
      try {
        Module.setGamepadCount(count);
      } catch (e) {
        console.warn('%c[gamepad.js, reportGamepadCount]', 'color: gray;', 'Warning: Failed to report the gamepad count!', e);
      }
    }
    return count;
  }

  function addListeners() {
    if (listenersAdded) {
      return;
    }
    listenersAdded = true;
    window.addEventListener('gamepadconnected', function(e) {
      const index = e.gamepad.index;
      if (index >= 0 && index < MAX_PADS) {
        resetSlot(slots[index], e.gamepad);
      }
      reportGamepadCount();
    });
    window.addEventListener('gamepaddisconnected', function(e) {
      const index = e.gamepad.index;
      if (index >= 0 && index < MAX_PADS) {
        slots[index].active = false;
      }
      reportGamepadCount();
    });
  }

  function startWatching() {
    addListeners();
    if (!pollingInterval) {
      // Take the current state as the baseline, so a button held while a stream ended
      // does not count as a new press
      for (let i = 0; i < MAX_PADS; ++i) {
        slots[i].active = false;
      }
      pollingInterval = setInterval(pollGamepads, POLL_INTERVAL_MS);
    }
  }

  function stopWatching() {
    if (pollingInterval) {
      clearInterval(pollingInterval);
      pollingInterval = null;
    }
  }

  return {
    startWatching,
    stopWatching,
    reportGamepadCount,
  };
})();

// Gamepad sampler for the stream's input thread (gamepad.cpp, PollGamepads).
//
// One call reads every pad once and writes a compact snapshot into a C buffer, so the input
// thread needs a single main-thread hop per poll. The layout must match PadSnapshot in
// gamepad.cpp. It allocates nothing per call: the heap views are passed in by the caller
// and the per-slot id cache is a preallocated array.
const VW_PAD_STRIDE = 152;
const VW_PAD_ID_BYTES = 64;
const vwLastPadIds = new Array(16).fill(null);

// Returns the number of browser gamepad slots that were read (at most maxPads)
function vwSampleGamepads(ptr, maxPads, heapF64, heap32, heapF32, heapU8) {
  const pads = navigator.getGamepads ? navigator.getGamepads() : null;
  const count = pads ? Math.min(pads.length, maxPads) : 0;
  const now = performance.now();
  for (let i = 0; i < maxPads; ++i) {
    const base = ptr + i * VW_PAD_STRIDE;
    const gp = i < count ? pads[i] : null;
    if (!gp) {
      heap32[(base + 16) >> 2] = 0; // connected
      vwLastPadIds[i] = null;
      continue;
    }
    const buttons = gp.buttons;
    const axes = gp.axes;
    const numButtons = buttons.length;
    const numAxes = axes.length;
    heapF64[base >> 3] = gp.timestamp;
    heapF64[(base + 8) >> 3] = now - gp.timestamp; // sample age in ms
    heap32[(base + 16) >> 2] = gp.connected ? 1 : 0;
    heap32[(base + 20) >> 2] = gp.mapping === 'standard' ? 1 : 0;
    heap32[(base + 24) >> 2] = numButtons;
    heap32[(base + 28) >> 2] = numAxes;
    let mask = 0;
    for (let b = 0; b < numButtons && b < 32; ++b) {
      if (buttons[b].pressed) {
        mask |= (1 << b);
      }
    }
    heap32[(base + 32) >> 2] = mask;
    heapF32[(base + 40) >> 2] = numButtons > 6 ? buttons[6].value : 0;
    heapF32[(base + 44) >> 2] = numButtons > 7 ? buttons[7].value : 0;
    for (let a = 0; a < 10; ++a) {
      heapF32[(base + 48 + a * 4) >> 2] = a < numAxes ? axes[a] : 0;
    }
    // The id rarely changes, so only copy it when it does (ASCII, NUL terminated)
    const id = gp.id;
    if (id !== vwLastPadIds[i]) {
      vwLastPadIds[i] = id;
      const idBase = base + 88;
      const length = Math.min(id.length, VW_PAD_ID_BYTES - 1);
      for (let c = 0; c < length; ++c) {
        const code = id.charCodeAt(c);
        heapU8[idBase + c] = (code >= 32 && code < 127) ? code : 63; // '?'
      }
      heapU8[idBase + length] = 0;
      heap32[(base + 36) >> 2] = (heap32[(base + 36) >> 2] + 1) | 0; // id serial
    }
  }
  return count;
}

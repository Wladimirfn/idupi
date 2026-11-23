/*
 * IDUPI interactive terminal bridge (xterm.js 5.5.0, vendored).
 *
 * Kotlin owns the authenticated HTTP transport; this page only renders bytes
 * and reports keystrokes. The bearer token never reaches JavaScript.
 *
 * Public API consumed from Kotlin via evaluateJavascript:
 *   window.idupi = { write(b64), reset(), focus(), setFontSize(px),
 *                    setModifiers(ctrl, alt), setMinCols(cols), fit() }
 *
 * setMinCols(0) fits the grid to the container width (default). setMinCols(n)
 * with n in 20..300 pins at least n columns and lets #terminal scroll
 * horizontally ("wide mode"): a phone in portrait fits only ~46 columns while
 * agent TUIs are laid out for 80+.
 *
 * Bridge methods exported to Kotlin via @JavascriptInterface:
 *   AndroidBridge.onReady(cols, rows)
 *   AndroidBridge.onInput(data)
 *   AndroidBridge.onResize(cols, rows)
 *   AndroidBridge.onModifiersConsumed()
 */
(function () {
  "use strict";

  var term = null;
  var fitAddon = null;
  var ctrlArmed = false;
  var altArmed = false;
  var readyNotified = false;
  var resizeTimer = null;

  // Wide mode: 0 fits the container width, 20..300 pins a minimum column count.
  var minCols = 0;
  var containerEl = null;

  // One-finger touch scrolling state. A second finger abandons the gesture
  // instead of pretending to support pinch or two-finger scroll.
  var touchAxis = null;
  var touchStartX = 0;
  var touchStartY = 0;
  var lastTouchX = 0;
  var lastTouchY = 0;
  var touchSamples = [];
  var inertia = null;

  var TOUCH_SLOP_PX = 8;
  var VELOCITY_WINDOW_MS = 100;
  var INERTIA_START_VELOCITY = 0.3; // px/ms of finger travel
  var INERTIA_STOP_VELOCITY = 0.05; // px/ms of wheel travel

  var THEME = {
    background: "#020617",
    foreground: "#e2e8f0",
    cursor: "#38bdf8",
    cursorAccent: "#020617",
    selectionBackground: "#334155",
    black: "#0f172a",
    red: "#f87171",
    green: "#4ade80",
    yellow: "#fbbf24",
    blue: "#60a5fa",
    magenta: "#c084fc",
    cyan: "#22d3ee",
    white: "#e2e8f0",
    brightBlack: "#64748b",
    brightRed: "#fca5a5",
    brightGreen: "#86efac",
    brightYellow: "#fde68a",
    brightBlue: "#93c5fd",
    brightMagenta: "#d8b4fe",
    brightCyan: "#67e8f9",
    brightWhite: "#f8fafc"
  };

  function bridge() {
    return typeof AndroidBridge !== "undefined" ? AndroidBridge : null;
  }

  function notify(name) {
    var target = bridge();
    if (!target) return;
    var args = Array.prototype.slice.call(arguments, 1);
    try {
      // Call with exactly the arguments the Kotlin @JavascriptInterface
      // declares: onModifiersConsumed() takes none.
      target[name].apply(target, args);
    } catch (e) {
      /* Page opened outside the app (dev preview): bridge calls are optional. */
    }
  }

  function base64ToBytes(b64) {
    var binary = atob(b64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  /*
   * C0 control-code rule, mirrored in Kotlin as viewmodel/PtyTerminalLogic.kt
   * (ptyControlCode) so the transform stays pinned by JVM unit tests:
   *   a-z / @ [ \ ] ^ _  -> codepoint & 0x1f
   *   space              -> NUL
   *   anything else      -> unchanged
   */
  function controlCode(data) {
    if (data.length !== 1) return data;
    var lower = data.toLowerCase();
    var code = lower.charCodeAt(0);
    var mapped =
      (code >= 97 && code <= 122) ||
      lower === "@" ||
      lower === "[" ||
      lower === "\\" ||
      lower === "]" ||
      lower === "^" ||
      lower === "_";
    if (mapped) return String.fromCharCode(code & 0x1f);
    if (lower === " ") return "\u0000";
    return data;
  }

  function applyModifiers(data) {
    var out = data;
    if (ctrlArmed && data.length === 1) out = controlCode(out);
    if (altArmed) out = "\u001b" + out;
    return out;
  }

  function fit() {
    if (!fitAddon || !term) return;
    try {
      var dims = fitAddon.proposeDimensions();
      if (dims && isFinite(dims.cols) && isFinite(dims.rows)) {
        // Wide mode only raises the floor; the grid still grows on a wider
        // container (rotation, tablet, landscape).
        var cols = Math.max(Math.floor(dims.cols), minCols);
        var rows = Math.floor(dims.rows);
        if (cols !== term.cols || rows !== term.rows) {
          // Same pre-resize step FitAddon.fit performs: drop the stale cell
          // metrics so the renderer recomputes the grid for the new size.
          try {
            term._core._renderService.clear();
          } catch (e) {
            /* Pinned xterm internals; resize still works without the clear. */
          }
          term.resize(cols, rows);
        }
      }
      layout();
    } catch (e) {
      /* The view can be detached for a frame while the WebView is recreated. */
    }
  }

  // The grid is a whole number of cells, so up to one cell of width is left
  // over; split it evenly instead of leaving it all on the right edge. When
  // the grid is wider than the container (wide mode), panning needs real
  // overflow: pin the element width and keep its left edge at 0.
  // Reads xterm 5.5.0 internals (vendored, pinned); a missing field is a no-op.
  function layout() {
    var core = term && term._core;
    var dims = core && core._renderService && core._renderService.dimensions;
    var cell = dims && dims.css && dims.css.cell && dims.css.cell.width;
    var element = term && term.element;
    var parent = element && element.parentElement;
    if (!cell || !element || !parent) return;
    var spare = parent.clientWidth - term.cols * cell;
    if (spare >= 0) {
      element.style.marginLeft = Math.floor(spare / 2) + "px";
      element.style.width = "";
    } else {
      element.style.marginLeft = "0px";
      element.style.width = Math.ceil(term.cols * cell) + "px";
    }
  }

  // xterm's own wheel path already does the right thing in every buffer:
  // scrolls the scrollback, emits wheel reports when the app enabled mouse
  // tracking, and turns into Up/Down in the alternate buffer (OpenCode and
  // other full-screen TUIs). Its pixel-mode path keeps a sub-row accumulator,
  // so many small per-move deltas still add up to smooth scrolling.
  function dispatchWheel(deltaY, clientX, clientY) {
    if (!term || !term.element || !deltaY) return;
    var screen = term.element.querySelector(".xterm-screen");
    if (!screen) return;
    screen.dispatchEvent(
      new WheelEvent("wheel", {
        deltaY: deltaY,
        deltaMode: 0,
        clientX: clientX,
        clientY: clientY,
        bubbles: true,
        cancelable: true
      })
    );
  }

  function cancelInertia() {
    if (inertia && inertia.raf) cancelAnimationFrame(inertia.raf);
    inertia = null;
  }

  function inertiaStep(now) {
    if (!inertia) return;
    var frameMs = inertia.last ? now - inertia.last : 16;
    if (!(frameMs > 0) || frameMs > 64) frameMs = 16;
    inertia.last = now;
    dispatchWheel(inertia.velocity * frameMs, inertia.x, inertia.y);
    inertia.velocity *= Math.pow(0.95, frameMs / 16);
    if (Math.abs(inertia.velocity) < INERTIA_STOP_VELOCITY) {
      inertia = null;
      return;
    }
    inertia.raf = requestAnimationFrame(inertiaStep);
  }

  function startInertia(wheelVelocity, x, y) {
    cancelInertia();
    inertia = { velocity: wheelVelocity, x: x, y: y, last: 0, raf: 0 };
    inertia.raf = requestAnimationFrame(inertiaStep);
  }

  function recordTouchSample(x, y, t) {
    touchSamples.push({ t: t, x: x, y: y });
    while (touchSamples.length > 2 && t - touchSamples[0].t > VELOCITY_WINDOW_MS) {
      touchSamples.shift();
    }
  }

  // Finger velocity along y over the last ~100 ms, in px/ms. Samples older
  // than the window are dropped, so a finger that rests before lifting does
  // not fling.
  function touchVelocity(now) {
    var newest = null;
    var oldest = null;
    for (var i = touchSamples.length - 1; i >= 0; i--) {
      var sample = touchSamples[i];
      if (now - sample.t > VELOCITY_WINDOW_MS) break;
      if (!newest) newest = sample;
      oldest = sample;
    }
    if (!newest || !oldest) return 0;
    var dt = newest.t - oldest.t;
    if (!(dt > 0)) return 0;
    return (newest.y - oldest.y) / dt;
  }

  function onTouchStart(e) {
    cancelInertia();
    touchAxis = null;
    touchSamples = [];
    if (e.touches.length !== 1) return;
    var touch = e.touches[0];
    touchStartX = lastTouchX = touch.clientX;
    touchStartY = lastTouchY = touch.clientY;
    recordTouchSample(touch.clientX, touch.clientY, e.timeStamp);
    // No preventDefault here: a tap must still focus xterm and open the
    // keyboard, and the axis is unknown until the finger actually moves.
  }

  function onTouchMove(e) {
    if (e.touches.length !== 1) {
      // Two-finger gesture: leave it alone.
      touchAxis = null;
      return;
    }
    if (!containerEl) return;
    var touch = e.touches[0];
    if (!touchAxis) {
      var dx = touch.clientX - touchStartX;
      var dy = touch.clientY - touchStartY;
      if (Math.abs(dx) <= TOUCH_SLOP_PX && Math.abs(dy) <= TOUCH_SLOP_PX) return;
      touchAxis = Math.abs(dx) > Math.abs(dy) ? "x" : "y";
      // The slop distance is not intent yet: restart the deltas and the
      // velocity window at the lock point so the grid does not jump.
      lastTouchX = touch.clientX;
      lastTouchY = touch.clientY;
      touchSamples = [];
      recordTouchSample(touch.clientX, touch.clientY, e.timeStamp);
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    var stepX = touch.clientX - lastTouchX;
    var stepY = touch.clientY - lastTouchY;
    lastTouchX = touch.clientX;
    lastTouchY = touch.clientY;
    recordTouchSample(touch.clientX, touch.clientY, e.timeStamp);
    // Capture phase, so stopping propagation here keeps xterm's own viewport
    // touch handler from scrolling a second time.
    e.preventDefault();
    e.stopPropagation();
    if (touchAxis === "y") {
      dispatchWheel(-stepY, touch.clientX, touch.clientY);
    } else {
      // Only meaningful in wide mode, where #terminal has horizontal overflow.
      containerEl.scrollLeft -= stepX;
    }
  }

  function onTouchEnd(e) {
    if (touchAxis === "y" && e.touches.length === 0) {
      var velocity = touchVelocity(e.timeStamp);
      if (Math.abs(velocity) > INERTIA_START_VELOCITY) {
        // Wheel deltas run opposite to the finger's travel.
        startInertia(-velocity, lastTouchX, lastTouchY);
      }
    }
    touchAxis = null;
    touchSamples = [];
  }

  function attachTouchScrolling(container) {
    container.addEventListener("touchstart", onTouchStart, {
      passive: true,
      capture: true
    });
    container.addEventListener("touchmove", onTouchMove, {
      passive: false,
      capture: true
    });
    container.addEventListener("touchend", onTouchEnd, {
      passive: true,
      capture: true
    });
    container.addEventListener("touchcancel", onTouchEnd, {
      passive: true,
      capture: true
    });
  }

  // Soft keyboards must not autocorrect, capitalize or suggest: a terminal
  // needs every key as typed, not a composed word replaced on space.
  function hardenTextarea() {
    var textarea = term && term.textarea;
    if (!textarea) return;
    textarea.setAttribute("autocomplete", "off");
    textarea.setAttribute("autocorrect", "off");
    textarea.setAttribute("autocapitalize", "off");
    textarea.setAttribute("spellcheck", "false");
  }

  function createTerminal() {
    var container = document.getElementById("terminal");
    containerEl = container;
    term = new Terminal({
      convertEol: false,
      cursorBlink: true,
      scrollback: 5000,
      fontFamily: "monospace",
      fontSize: 13,
      letterSpacing: 0,
      theme: THEME
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(container);
    hardenTextarea();
    attachTouchScrolling(container);
    fit();

    term.onData(function (data) {
      if (!data) return;
      var modifiersWereArmed = ctrlArmed || altArmed;
      var out = modifiersWereArmed ? applyModifiers(data) : data;
      // One-shot modifiers: they apply to exactly one keystroke.
      ctrlArmed = false;
      altArmed = false;
      if (modifiersWereArmed) notify("onModifiersConsumed");
      notify("onInput", out);
    });

    // Fires for every fitted size change, including the initial fit and the
    // IME/window resize path. Kotlin debounces before POSTing /resize.
    term.onResize(function (size) {
      notify("onResize", size.cols, size.rows);
    });

    // Refit whenever the container itself changes size (IME, Compose layout,
    // rotation); window "resize" alone misses some WebView layout passes.
    if (typeof ResizeObserver !== "undefined") {
      new ResizeObserver(function () {
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(fit, 60);
      }).observe(container);
    }

    // Report the fitted viewport only once the container has a real height,
    // so the PTY is created at the size the terminal actually renders at.
    // Before the first Compose layout pass the WebView can be ~0 px tall and
    // xterm would fit a single row.
    var attempts = 0;
    function reportReady() {
      if (readyNotified) return;
      fit();
      attempts++;
      if (term.rows < MIN_READY_ROWS && attempts < MAX_READY_ATTEMPTS) {
        setTimeout(reportReady, 50);
        return;
      }
      readyNotified = true;
      notify("onReady", term.cols, term.rows);
    }
    requestAnimationFrame(reportReady);
  }

  var MIN_READY_ROWS = 5;
  var MAX_READY_ATTEMPTS = 40; // ~2 s, then report whatever fits

  window.idupi = {
    write: function (b64) {
      if (!term || !b64) return;
      // xterm keeps an incremental UTF-8 decoder for Uint8Array input, so a
      // multi-byte character split across two chunks still renders correctly.
      term.write(base64ToBytes(b64));
    },
    reset: function () {
      if (term) term.reset();
    },
    focus: function () {
      if (term) term.focus();
    },
    setFontSize: function (px) {
      if (!term) return;
      var size = Number(px);
      if (!isFinite(size) || size < 8 || size > 40) return;
      term.options.fontSize = size;
      fit();
    },
    setModifiers: function (ctrl, alt) {
      ctrlArmed = !!ctrl;
      altArmed = !!alt;
    },
    setMinCols: function (cols) {
      var value = Number(cols);
      if (!isFinite(value)) return;
      value = Math.floor(value);
      if (value !== 0 && (value < 20 || value > 300)) return;
      if (value === 0 && minCols > 0 && containerEl) {
        // Leaving wide mode: the grid fits again, so the old pan is stale.
        // layout() clears the explicit element width.
        containerEl.scrollLeft = 0;
      }
      minCols = value;
      fit();
    },
    fit: fit
  };

  window.addEventListener("resize", function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(fit, 120);
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", createTerminal);
  } else {
    createTerminal();
  }
})();

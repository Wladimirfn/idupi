/*
 * IDUPI interactive terminal bridge (xterm.js 5.5.0, vendored).
 *
 * Kotlin owns the authenticated HTTP transport; this page only renders bytes
 * and reports keystrokes. The bearer token never reaches JavaScript.
 *
 * Public API consumed from Kotlin via evaluateJavascript:
 *   window.idupi = { write(b64), reset(), focus(), setFontSize(px),
 *                    setModifiers(ctrl, alt), fit() }
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
    if (!fitAddon) return;
    try {
      fitAddon.fit();
      centerHorizontally();
    } catch (e) {
      /* The view can be detached for a frame while the WebView is recreated. */
    }
  }

  // The grid is a whole number of cells, so up to one cell of width is left
  // over; split it evenly instead of leaving it all on the right edge.
  // Reads xterm 5.5.0 internals (vendored, pinned); a missing field is a no-op.
  function centerHorizontally() {
    var core = term && term._core;
    var dims = core && core._renderService && core._renderService.dimensions;
    var cell = dims && dims.css && dims.css.cell && dims.css.cell.width;
    var parent = term && term.element && term.element.parentElement;
    if (!cell || !parent) return;
    var spare = parent.clientWidth - term.cols * cell;
    term.element.style.marginLeft = Math.max(0, Math.floor(spare / 2)) + "px";
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
    term.open(document.getElementById("terminal"));
    hardenTextarea();
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
    var container = document.getElementById("terminal");
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

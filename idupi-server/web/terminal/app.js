/*
 * Browser view of the shared PTY terminals (live-shared-terminals, T2).
 *
 * The page is a thin client: the server owns the PTYs, the ring-buffer replay
 * and the bearer-token check. Here we only render bytes and report input,
 * exactly like the Android WebView bridge does.
 *
 * Token handling: it arrives in the URL fragment (#token=...), which browsers
 * never send to the server. It is moved to sessionStorage and the fragment is
 * stripped from the address bar immediately; it is never logged and never put
 * in a request URL, only in the Authorization header.
 */
(function () {
  "use strict";

  var TOKEN_KEY = "idupi.terminal.token";
  var POLL_MS = 3000;
  var INPUT_FLUSH_MS = 10;
  // Characters per input POST. UTF-8 is at most 4 bytes per code point, so
  // 16K characters stay under the 64 KiB chunk the spec asks for and far under
  // the server's 256 KiB body cap, even for CJK or emoji pastes.
  var INPUT_CHUNK_CHARS = 16384;
  var RECONNECT_MIN_MS = 500;
  var RECONNECT_MAX_MS = 4000;

  // Copied verbatim from app/src/main/assets/terminal/terminal.js (THEME) so
  // both clients render the same palette.
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

  // --- elements (the script runs at the end of <body>) ---------------------
  var tokenView = document.getElementById("token-view");
  var tokenForm = document.getElementById("token-form");
  var tokenInput = document.getElementById("token-input");
  var tokenError = document.getElementById("token-error");
  var appView = document.getElementById("app-view");
  var tabsEl = document.getElementById("tabs");
  var statusEl = document.getElementById("status");
  var noticeEl = document.getElementById("notice");
  var newButton = document.getElementById("new-terminal");
  var closeButton = document.getElementById("close-terminal");
  var terminalEl = document.getElementById("terminal");

  // --- state --------------------------------------------------------------
  var token = "";
  var terminals = [];
  var activeId = null;
  var term = null;
  var fitAddon = null;

  var streamGeneration = 0;
  var streamAbort = null;
  var reconnectTimer = null;
  var reconnectDelay = RECONNECT_MIN_MS;

  var pendingInput = "";
  var inputTimer = null;
  var inputBusy = false;

  var resizeTimer = null;
  var noticeTimer = null;
  var pollingStarted = false;
  var listBusy = false;

  // --- token gate ---------------------------------------------------------

  function tokenFromHash() {
    var raw = window.location.hash || "";
    if (raw.length < 2) return "";
    var params = new URLSearchParams(raw.slice(1));
    return (params.get("token") || "").trim();
  }

  function storedToken() {
    try {
      return (window.sessionStorage.getItem(TOKEN_KEY) || "").trim();
    } catch (err) {
      return ""; /* storage disabled: the form asks again */
    }
  }

  function persistToken(value) {
    try {
      window.sessionStorage.setItem(TOKEN_KEY, value);
    } catch (err) {
      /* storage disabled: keep it in memory for this page only */
    }
  }

  function clearStoredToken() {
    try {
      window.sessionStorage.removeItem(TOKEN_KEY);
    } catch (err) {
      /* nothing persisted to remove */
    }
  }

  function showTokenForm(message) {
    appView.hidden = true;
    tokenView.hidden = false;
    if (message) {
      tokenError.textContent = message;
      tokenError.hidden = false;
    } else {
      tokenError.hidden = true;
    }
    tokenInput.focus();
  }

  function showApp() {
    tokenView.hidden = true;
    appView.hidden = false;
    ensureTerminal();
    startPolling();
    if (activeId) {
      void activate(activeId);
    }
  }

  function forgetToken(message) {
    token = "";
    clearStoredToken();
    stopStream();
    activeId = null;
    pendingInput = "";
    renderTabs();
    setStatus("");
    if (term) term.reset();
    showTokenForm(message);
  }

  function xtermReady() {
    return typeof window.Terminal === "function" &&
      !!window.FitAddon &&
      typeof window.FitAddon.FitAddon === "function";
  }

  // --- HTTP helpers -------------------------------------------------------

  async function apiFetch(path, options) {
    var opts = options || {};
    var headers = {};
    var key;
    for (key in opts.headers || {}) headers[key] = opts.headers[key];
    headers.Authorization = "Bearer " + token;
    var res = await fetch(path, {
      cache: "no-store",
      method: opts.method || "GET",
      headers: headers,
      body: opts.body
    });
    if (res.status === 401) {
      forgetToken("Token rechazado. Intenta de nuevo.");
      throw new Error("unauthorized");
    }
    return res;
  }

  async function listTerminals() {
    var res = await apiFetch("/api/v1/pty");
    if (!res.ok) throw new Error("list failed: " + res.status);
    var data = await res.json();
    return Array.isArray(data.terminals) ? data.terminals : [];
  }

  async function fetchInfo(id) {
    var list = await listTerminals();
    for (var i = 0; i < list.length; i += 1) {
      if (list[i].id === id) return list[i];
    }
    return null;
  }

  // --- terminal instance --------------------------------------------------

  function ensureTerminal() {
    if (term || !xtermReady()) return;
    term = new window.Terminal({
      convertEol: false,
      cursorBlink: true,
      scrollback: 5000,
      fontFamily: "Cascadia Mono, Consolas, monospace",
      fontSize: 14,
      letterSpacing: 0,
      theme: THEME
    });
    fitAddon = new window.FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(terminalEl);
    // Soft keyboards are irrelevant on a PC, but the textarea is also what
    // receives every keystroke: keep the browser from composing or correcting.
    if (term.textarea) {
      term.textarea.setAttribute("autocomplete", "off");
      term.textarea.setAttribute("autocorrect", "off");
      term.textarea.setAttribute("autocapitalize", "off");
      term.textarea.setAttribute("spellcheck", "false");
    }
    term.onData(queueInput);
  }

  function fitTerminal() {
    if (!fitAddon) return;
    try {
      fitAddon.fit();
    } catch (err) {
      /* the container can be hidden for a frame */
    }
  }

  // --- status / notices ---------------------------------------------------

  function setStatus(text) {
    statusEl.textContent = text;
    if (text === "En vivo") statusEl.dataset.state = "live";
    else if (text === "Finalizada") statusEl.dataset.state = "ended";
    else if (text) statusEl.dataset.state = "connecting";
    else statusEl.dataset.state = "";
  }

  function showNotice(text) {
    noticeEl.textContent = text;
    noticeEl.hidden = false;
    if (noticeTimer) clearTimeout(noticeTimer);
    noticeTimer = setTimeout(function () {
      noticeTimer = null;
      noticeEl.hidden = true;
    }, 4000);
  }

  // --- tabs ---------------------------------------------------------------

  function shortId(id) {
    return String(id || "").replace(/^pty-/, "").slice(0, 6);
  }

  function shellName(shell) {
    var first = String(shell || "").trim().split(/\s+/)[0] || "shell";
    return first.replace(/^.*[\\/]/, "");
  }

  function renderTabs() {
    tabsEl.textContent = "";
    for (var i = 0; i < terminals.length; i += 1) {
      var info = terminals[i];
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "tab" + (info.id === activeId ? " active" : "") +
        (info.alive ? "" : " exited");
      btn.dataset.alive = info.alive ? "true" : "false";
      btn.title = info.id + (info.shell ? " · " + info.shell : "");

      var name = document.createElement("span");
      name.className = "tab-name";
      name.textContent = shortId(info.id) + " · " + shellName(info.shell);

      var tabState = document.createElement("span");
      tabState.className = "tab-state";
      tabState.textContent = info.alive ? "En vivo" : "Finalizada";

      btn.appendChild(name);
      btn.appendChild(tabState);
      btn.addEventListener("click", makeActivate(info.id));
      tabsEl.appendChild(btn);
    }
    closeButton.disabled = !activeId;
  }

  function makeActivate(id) {
    return function () {
      void activate(id);
    };
  }

  // --- list polling -------------------------------------------------------

  async function refreshList() {
    if (!token || listBusy) return;
    listBusy = true;
    try {
      terminals = await listTerminals();
    } catch (err) {
      listBusy = false;
      return;
    }
    listBusy = false;

    var activeStillThere = false;
    for (var i = 0; i < terminals.length; i += 1) {
      if (terminals[i].id === activeId) activeStillThere = true;
    }
    if (activeId && !activeStillThere) {
      // The PTY was killed from the phone or reaped after exiting.
      stopStream();
      activeId = null;
      pendingInput = "";
      if (term) term.reset();
      setStatus("");
    }
    renderTabs();
    if (!activeId && terminals.length > 0) {
      // First load, or the active one disappeared: attach to the oldest
      // terminal so the page is useful without an extra click.
      void activate(terminals[0].id);
    }
  }

  function startPolling() {
    void refreshList();
    if (pollingStarted) return;
    pollingStarted = true;
    setInterval(function () {
      // No point refreshing a page nobody is looking at; visibilitychange
      // refreshes on the way back.
      if (!document.hidden) void refreshList();
    }, POLL_MS);
  }

  // --- activation ---------------------------------------------------------

  async function activate(id) {
    if (!id || !token) return;
    var switched = activeId !== id;
    activeId = id;
    ensureTerminal();
    // Switching tabs abandons the old stream and its pending keystrokes: a
    // character typed for one terminal must never land in another.
    stopStream();
    pendingInput = "";
    if (inputTimer) {
      clearTimeout(inputTimer);
      inputTimer = null;
    }
    if (switched && term) term.reset();
    renderTabs();

    setStatus("Conectando");
    fitTerminal();
    if (term) {
      void sendResize(id, term.cols, term.rows);
      term.focus();
    }
    startStream(id);
  }

  // --- stream -------------------------------------------------------------

  function stopStream() {
    streamGeneration += 1;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (streamAbort) {
      try {
        streamAbort.abort();
      } catch (err) {
        /* already aborted */
      }
      streamAbort = null;
    }
  }

  function startStream(id) {
    var generation = streamGeneration;
    void runStream(id, generation);
  }

  async function runStream(id, generation) {
    if (generation !== streamGeneration) return;
    var controller = new AbortController();
    streamAbort = controller;
    var res;
    try {
      res = await fetch("/api/v1/pty/" + encodeURIComponent(id) + "/stream", {
        cache: "no-store",
        headers: { Authorization: "Bearer " + token },
        signal: controller.signal
      });
    } catch (err) {
      if (generation !== streamGeneration || controller.signal.aborted) return;
      scheduleReconnect(id, generation);
      return;
    }
    if (generation !== streamGeneration) return;
    if (res.status === 401) {
      forgetToken("Token rechazado. Intenta de nuevo.");
      return;
    }
    if (res.status === 404) {
      setStatus("Finalizada");
      return;
    }
    if (!res.ok || !res.body) {
      scheduleReconnect(id, generation);
      return;
    }

    reconnectDelay = RECONNECT_MIN_MS;
    setStatus("En vivo");
    var reader = res.body.getReader();
    try {
      for (;;) {
        var step = await reader.read();
        if (generation !== streamGeneration) {
          try {
            await reader.cancel();
          } catch (err) {
            /* the abort already closed it */
          }
          return;
        }
        if (step.done) break;
        if (step.value && step.value.byteLength > 0) {
          term.write(step.value);
        }
      }
    } catch (err) {
      /* aborted or the connection dropped: decided below */
    }
    if (generation !== streamGeneration) return;
    // The server ends a stream when the PTY exits, and also when a consumer
    // falls behind its cap. The list says which: reconnect only while the
    // terminal is still alive.
    scheduleReconnect(id, generation);
  }

  function scheduleReconnect(id, generation) {
    if (generation !== streamGeneration) return;
    setStatus("Conectando");
    var delay = reconnectDelay;
    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function () {
      reconnectTimer = null;
      void reconnectIfAlive(id, generation);
    }, delay);
  }

  async function reconnectIfAlive(id, generation) {
    if (generation !== streamGeneration) return;
    var info;
    try {
      info = await fetchInfo(id);
    } catch (err) {
      // Transient failure (server restart, unauthorized handled elsewhere):
      // keep retrying with the backoff instead of declaring it dead.
      if (generation !== streamGeneration) return;
      void runStream(id, generation);
      return;
    }
    if (generation !== streamGeneration) return;
    if (!info || !info.alive) {
      setStatus("Finalizada");
      return;
    }
    void runStream(id, generation);
  }

  // --- input --------------------------------------------------------------

  function queueInput(data) {
    if (!data || !activeId || !token) return;
    pendingInput += data;
    if (pendingInput.length >= INPUT_CHUNK_CHARS) {
      void flushInput();
      return;
    }
    if (!inputTimer) {
      inputTimer = setTimeout(function () {
        inputTimer = null;
        void flushInput();
      }, INPUT_FLUSH_MS);
    }
  }

  function takeInputChunk() {
    if (pendingInput.length <= INPUT_CHUNK_CHARS) {
      var whole = pendingInput;
      pendingInput = "";
      return whole;
    }
    var cut = INPUT_CHUNK_CHARS;
    var last = pendingInput.charCodeAt(cut - 1);
    // Never split a surrogate pair: a lone half would reach the PTY as U+FFFD.
    if (last >= 0xd800 && last <= 0xdbff) cut -= 1;
    var chunk = pendingInput.slice(0, cut);
    pendingInput = pendingInput.slice(cut);
    return chunk;
  }

  async function flushInput() {
    if (inputTimer) {
      clearTimeout(inputTimer);
      inputTimer = null;
    }
    if (inputBusy || !pendingInput) return;
    var id = activeId;
    if (!id) return;
    var chunk = takeInputChunk();
    inputBusy = true;
    try {
      var res = await apiFetch("/api/v1/pty/" + encodeURIComponent(id) + "/input", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data: chunk })
      });
      if (res.status === 404) setStatus("Finalizada");
    } catch (err) {
      // Keystrokes are not replayable: a failed chunk is dropped rather than
      // resent out of order or duplicated.
    }
    inputBusy = false;
    if (pendingInput) void flushInput();
  }

  // --- resize -------------------------------------------------------------

  async function sendResize(id, cols, rows) {
    if (!id || !token) return;
    if (!Number.isInteger(cols) || !Number.isInteger(rows) || cols < 1 || rows < 1) return;
    try {
      await apiFetch("/api/v1/pty/" + encodeURIComponent(id) + "/resize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cols: cols, rows: rows })
      });
    } catch (err) {
      /* best effort: the next focus/resize retries */
    }
  }

  // One PTY has one size and the last client to resize wins (tmux "latest").
  // The PC reclaims it whenever the user comes back to this page.
  function resizeActive() {
    if (!activeId || !term) return;
    fitTerminal();
    void sendResize(activeId, term.cols, term.rows);
  }

  // --- actions ------------------------------------------------------------

  async function createTerminal() {
    if (!token) return;
    fitTerminal();
    var cols = term && term.cols ? term.cols : 120;
    var rows = term && term.rows ? term.rows : 30;
    newButton.disabled = true;
    try {
      var res = await apiFetch("/api/v1/pty", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cols: cols, rows: rows })
      });
      if (!res.ok) {
        showNotice(res.status === 429 ? "Límite de terminales" : "No se pudo crear");
        return;
      }
      var info = await res.json();
      await refreshList();
      if (info && info.id) await activate(info.id);
    } catch (err) {
      showNotice("No se pudo crear");
    } finally {
      newButton.disabled = false;
    }
  }

  async function closeActive() {
    var id = activeId;
    if (!id) return;
    if (!window.confirm("¿Cerrar esta terminal?")) return;
    stopStream();
    try {
      await apiFetch("/api/v1/pty/" + encodeURIComponent(id), { method: "DELETE" });
    } catch (err) {
      /* already gone: the list refresh settles the UI */
    }
    activeId = null;
    pendingInput = "";
    if (term) term.reset();
    setStatus("");
    await refreshList();
  }

  // --- wiring -------------------------------------------------------------

  tokenForm.addEventListener("submit", function (event) {
    event.preventDefault();
    var value = tokenInput.value.trim();
    if (!value) return;
    token = value;
    persistToken(value);
    tokenInput.value = "";
    tokenError.hidden = true;
    showApp();
  });

  newButton.addEventListener("click", function () {
    void createTerminal();
  });

  closeButton.addEventListener("click", function () {
    void closeActive();
  });

  window.addEventListener("resize", function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      resizeTimer = null;
      resizeActive();
    }, 150);
  });

  window.addEventListener("focus", function () {
    resizeActive();
  });

  document.addEventListener("visibilitychange", function () {
    if (document.hidden) return;
    resizeActive();
    if (token) void refreshList();
  });

  function boot() {
    if (!xtermReady()) {
      showTokenForm("No se pudo cargar xterm.js.");
      return;
    }
    var fromHash = tokenFromHash();
    if (fromHash) {
      token = fromHash;
      persistToken(token);
    } else {
      token = storedToken();
    }
    // The fragment never belongs in a URL that gets copied, bookmarked or
    // screenshotted: strip it as soon as the value is in memory.
    if (window.location.hash) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
    if (token) showApp();
    else showTokenForm("");
  }

  boot();
})();

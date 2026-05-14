const listEl = document.getElementById("list");
const formEl = document.getElementById("add");
const inputEl = document.getElementById("text");
const statusEl = document.getElementById("status");
const actionsEl = document.getElementById("actions");
const clearDoneEl = document.getElementById("clear-done");
const clearAllEl = document.getElementById("clear-all");
const toastEl = document.getElementById("toast");
const toastTextEl = document.getElementById("toast-text");
const toastUndoEl = document.getElementById("toast-undo");
const DRAG_THRESHOLD = 6;

const state = {
  items: [],
  online: true,
  longPressTimer: null,
  longPressFired: false,
};

// Custom header required on every write so a malicious cross-origin page can't
// forge state-changing requests: the custom header forces a preflight, which the
// server (no CORS plugin) refuses. Same-origin PWA fetches still work fine.
const CLIENT_HEADERS = { "X-Grocery-Client": "1" };
const JSON_WRITE_HEADERS = { "content-type": "application/json", ...CLIENT_HEADERS };

const api = {
  async list() {
    const r = await fetch("/api/items", { cache: "no-store" });
    if (!r.ok) throw new Error("list failed");
    return r.json();
  },
  async add(text, signal) {
    const r = await fetch("/api/items", {
      method: "POST",
      headers: JSON_WRITE_HEADERS,
      body: JSON.stringify({ text }),
      signal,
    });
    if (!r.ok) throw new Error("add failed");
    return r.json();
  },
  async patch(id, body) {
    const r = await fetch(`/api/items/${id}`, {
      method: "PATCH",
      headers: JSON_WRITE_HEADERS,
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error("patch failed");
    return r.json();
  },
  async remove(id) {
    const r = await fetch(`/api/items/${id}`, { method: "DELETE", headers: CLIENT_HEADERS });
    if (!r.ok && r.status !== 404) throw new Error("delete failed");
  },
  async clearDone() {
    const r = await fetch("/api/clear-done", { method: "POST", headers: CLIENT_HEADERS });
    if (!r.ok) throw new Error("clear-done failed");
  },
  async clearAll() {
    const r = await fetch("/api/clear-all", { method: "POST", headers: CLIENT_HEADERS });
    if (!r.ok) throw new Error("clear-all failed");
  },
  async reorder(ids) {
    const r = await fetch("/api/reorder", {
      method: "POST",
      headers: JSON_WRITE_HEADERS,
      body: JSON.stringify({ ids }),
    });
    if (!r.ok) throw new Error("reorder failed");
  },
  async undo() {
    const r = await fetch("/api/undo", { method: "POST", headers: CLIENT_HEADERS });
    if (!r.ok) throw new Error("undo failed");
    return r.json();
  },
};

function setOnline(online) {
  state.online = online;
  statusEl.textContent = online ? "" : "offline";
  statusEl.classList.toggle("offline", !online);
}

function render() {
  listEl.replaceChildren();
  if (state.items.length === 0) {
    const li = document.createElement("li");
    li.className = "item empty";
    li.textContent = "list is empty";
    listEl.append(li);
    updateActions();
    return;
  }
  for (const item of state.items) {
    const li = document.createElement("li");
    li.className = "item" + (item.done ? " done" : "");
    li.dataset.id = item.id;
    li.setAttribute("role", "listitem");
    li.innerHTML =
      `<span class="handle handle-left" aria-label="drag to reorder">⋮⋮</span>` +
      `<span class="check" aria-hidden="true">✓</span>` +
      `<span class="text"></span>` +
      `<span class="handle handle-right" aria-hidden="true">⋮⋮</span>`;
    li.querySelector(".text").textContent = item.text;
    attachRowHandlers(li);
    listEl.append(li);
  }
  updateActions();
}

function updateActions() {
  const real = state.items.filter((x) => !x.id.startsWith("tmp-"));
  const doneCount = real.filter((x) => x.done).length;
  const hasAny = real.length > 0;
  actionsEl.hidden = !hasAny;
  if (doneCount > 0) {
    clearDoneEl.hidden = false;
    clearDoneEl.textContent = `Clear ${doneCount} done`;
  } else {
    clearDoneEl.hidden = true;
  }
  if (!confirmingClearAll) {
    clearAllEl.textContent = "Clear all";
    clearAllEl.classList.remove("confirm");
  }
}

function attachRowHandlers(li) {
  const id = li.dataset.id;
  let pressX = null;
  let pressY = null;
  let timer = null;
  let consumed = false; // true once long-press fired or a drag started

  const cancel = () => {
    li.classList.remove("pending-delete");
    if (timer) { clearTimeout(timer); timer = null; }
    pressX = pressY = null;
  };

  const onDown = (ev) => {
    // Handles: visual hint that this is a drag spot, and they don't toggle/delete.
    if (ev.target.closest(".handle")) {
      pressX = pressY = null;
      consumed = false;
      return;
    }
    pressX = ev.clientX;
    pressY = ev.clientY;
    consumed = false;
    li.classList.add("pending-delete");
    timer = setTimeout(() => {
      if (consumed || pressX === null) return;
      consumed = true;
      li.classList.remove("pending-delete");
      timer = null;
      // Don't delete outright — show a confirmation toast and require an
      // explicit second tap on the Delete button. Optimistic rows are skipped
      // (their tmp- ids would 404 on the API call).
      const item = state.items.find((x) => x.id === id);
      if (!item || isPending(id)) return;
      showDeleteConfirm(id, item.text);
    }, 600);
  };

  const onMove = (ev) => {
    if (pressX === null) return;
    if (Math.abs(ev.clientX - pressX) > DRAG_THRESHOLD || Math.abs(ev.clientY - pressY) > DRAG_THRESHOLD) {
      // Movement = drag intent. Drop the pending toggle/delete; SortableJS will take it from here.
      cancel();
    }
  };

  const onUp = (ev) => {
    if (consumed) { consumed = false; pressX = pressY = null; return; }
    if (pressX === null) return;
    if (ev.target.closest(".handle")) { pressX = pressY = null; return; }
    const moved =
      Math.abs(ev.clientX - pressX) > DRAG_THRESHOLD ||
      Math.abs(ev.clientY - pressY) > DRAG_THRESHOLD;
    cancel();
    if (!moved) onToggle(id);
  };

  // Sortable fires this when it decides a drag has started — abandon any pending press.
  const onAbort = () => cancel();

  li.addEventListener("pointerdown", onDown);
  li.addEventListener("pointermove", onMove);
  li.addEventListener("pointerup", onUp);
  li.addEventListener("pointercancel", cancel);
  li.addEventListener("pointerleave", cancel);
  li.addEventListener("row-abort", onAbort);
}

// Optimistic rows have a "tmp-…" id until the POST returns and reconcile() swaps
// in the real id. PATCHing or DELETEing the tmp- id would 404, and (worse) for
// DELETE we treat 404 as success — so the user would see a "Deleted" toast for
// an item the server is about to add via the still-in-flight POST.
function isPending(id) { return typeof id === "string" && id.startsWith("tmp-"); }

async function onToggle(id) {
  if (isPending(id)) return;
  const item = state.items.find((x) => x.id === id);
  if (!item) return;
  item.done = !item.done;
  render();
  try {
    await api.patch(id, { done: item.done });
  } catch {
    item.done = !item.done;
    render();
  }
}

async function onDelete(id) {
  if (isPending(id)) return;
  const idx = state.items.findIndex((x) => x.id === id);
  if (idx < 0) return;
  const removed = state.items[idx];
  state.items.splice(idx, 1);
  render();
  try {
    await api.remove(id);
    showUndoToast(`Deleted "${removed.text}"`);
  } catch {
    state.items.splice(idx, 0, removed);
    render();
  }
}

// Strip in order: indent, then any one of {dash/bullet/dot/checklist-glyph, bracket-checkbox, numbered}.
// Repeated until the line stops shrinking, so "- [ ] milk" collapses through both layers.
const BULLET_RE = /^\s*(?:[-*•·‣◦☐☑☒□▢▣◯○●]|\[\s?[xX✓✔ ]?\s?\]|\d+[.)])\s*/;

function parseList(raw) {
  return raw
    .split(/[\r\n]+/)
    .map((s) => {
      let prev;
      let cur = s;
      do { prev = cur; cur = cur.replace(BULLET_RE, "").trim(); } while (cur !== prev);
      return cur;
    })
    .filter((s) => s.length > 0);
}

function reconcile(optimisticId, saved) {
  const optIdx = state.items.findIndex((x) => x.id === optimisticId);
  const realIdx = state.items.findIndex((x) => x.id === saved.id);
  if (optIdx >= 0 && realIdx === -1) {
    state.items[optIdx] = saved;
    render();
  } else if (optIdx >= 0 && realIdx >= 0 && realIdx !== optIdx) {
    state.items.splice(optIdx, 1);
    render();
  } else if (optIdx === -1 && realIdx === -1) {
    state.items.push(saved);
    render();
  }
  // else: SSE already merged this item under the optimistic's slot — no change, no render
}

// If the POST stalls (server hung, network mid-flight), the optimistic row
// would otherwise sit as "tmp-…" forever — wrong UI state and (with the
// optimistic-interaction gate above) untoggleable / undeletable. Abort the
// fetch after this deadline so the catch arm removes the row.
const ADD_TIMEOUT_MS = 8_000;

function newTmpId() {
  return `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

async function postAddWithTimeout(text) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ADD_TIMEOUT_MS);
  try {
    return await api.add(text, ac.signal);
  } finally {
    clearTimeout(t);
  }
}

async function addOne(text) {
  const optimistic = { id: newTmpId(), text, done: false };
  state.items.push(optimistic);
  render();
  try {
    const saved = await postAddWithTimeout(text);
    reconcile(optimistic.id, saved);
    return saved;
  } catch {
    state.items = state.items.filter((x) => x.id !== optimistic.id);
    render();
    throw new Error("add failed");
  }
}

async function addMany(items) {
  const tmps = items.map((text) => ({ id: newTmpId(), text, done: false }));
  for (const t of tmps) state.items.push(t);
  render();
  for (let i = 0; i < items.length; i++) {
    try {
      const saved = await postAddWithTimeout(items[i]);
      reconcile(tmps[i].id, saved);
    } catch {
      const idx = state.items.findIndex((x) => x.id === tmps[i].id);
      if (idx >= 0) { state.items.splice(idx, 1); render(); }
    }
  }
}

async function onAdd(e) {
  e.preventDefault();
  const items = parseList(inputEl.value);
  if (items.length === 0) return;
  inputEl.value = "";
  if (items.length === 1) {
    try { await addOne(items[0]); } catch { inputEl.value = items[0]; }
  } else {
    await addMany(items);
  }
}

const PASTE_LIMIT = 500;

function onPaste(e) {
  const raw = (e.clipboardData || window.clipboardData)?.getData("text") || "";
  if (!/[\r\n]/.test(raw)) return; // single line — let the browser handle it
  e.preventDefault();
  let items = parseList(raw);
  if (items.length === 0) return;
  let truncated = 0;
  if (items.length > PASTE_LIMIT) {
    truncated = items.length;
    items = items.slice(0, PASTE_LIMIT);
  }
  inputEl.value = "";
  if (items.length === 1) addOne(items[0]).catch(() => { inputEl.value = items[0]; });
  else addMany(items);
  if (truncated > 0) showUndoToast(`Pasted ${PASTE_LIMIT} of ${truncated} lines`);
}

function applyChange(ev) {
  if (ev.type === "added") {
    if (state.items.find((x) => x.id === ev.item.id)) return;
    // Match a still-pending optimistic tmp by text so paste order is preserved
    const tmpIdx = state.items.findIndex(
      (x) => x.id.startsWith("tmp-") && x.text === ev.item.text,
    );
    if (tmpIdx >= 0) {
      state.items[tmpIdx] = ev.item;
    } else {
      state.items.push(ev.item);
    }
    render();
  } else if (ev.type === "updated") {
    const i = state.items.findIndex((x) => x.id === ev.item.id);
    if (i >= 0) {
      state.items[i] = ev.item;
      render();
    }
  } else if (ev.type === "removed") {
    const before = state.items.length;
    state.items = state.items.filter((x) => x.id !== ev.id);
    if (state.items.length !== before) render();
  } else if (ev.type === "reordered") {
    const order = ev.ids;
    const pos = new Map(order.map((id, i) => [id, i]));
    state.items.sort((a, b) => {
      const ai = pos.has(a.id) ? pos.get(a.id) : Number.MAX_SAFE_INTEGER;
      const bi = pos.has(b.id) ? pos.get(b.id) : Number.MAX_SAFE_INTEGER;
      return ai - bi;
    });
    render();
  }
}

let confirmingClearAll = false;
let confirmTimer = null;

async function onClearDone() {
  const doneIds = state.items.filter((x) => x.done && !x.id.startsWith("tmp-")).map((x) => x.id);
  if (doneIds.length === 0) return;
  const snapshot = state.items.slice();
  state.items = state.items.filter((x) => !x.done);
  render();
  try {
    await api.clearDone();
    showUndoToast(`Cleared ${doneIds.length} done item${doneIds.length === 1 ? "" : "s"}`);
  } catch {
    state.items = snapshot;
    render();
  }
}

async function onClearAll() {
  if (!confirmingClearAll) {
    confirmingClearAll = true;
    clearAllEl.textContent = "Tap again to clear ALL";
    clearAllEl.classList.add("confirm");
    confirmTimer = setTimeout(() => {
      confirmingClearAll = false;
      clearAllEl.textContent = "Clear all";
      clearAllEl.classList.remove("confirm");
    }, 2500);
    return;
  }
  if (confirmTimer) { clearTimeout(confirmTimer); confirmTimer = null; }
  confirmingClearAll = false;
  const snapshot = state.items.slice();
  const n = snapshot.length;
  state.items = [];
  render();
  try {
    await api.clearAll();
    if (n > 0) showUndoToast(`Cleared ${n} item${n === 1 ? "" : "s"}`);
  } catch {
    state.items = snapshot;
    render();
  }
}

let toastTimer = null;
// Toast doubles as both undo (post-delete) and delete-confirm (pre-delete). When
// pendingDeleteId is set, the toast's action button means "confirm delete"; when
// null, it means "undo the last action". Mutually exclusive: confirming or
// dismissing a delete clears it; the subsequent undo toast then takes over.
let pendingDeleteId = null;
const DELETE_CONFIRM_MS = 3000;
function showUndoToast(message) {
  pendingDeleteId = null;
  toastTextEl.textContent = message;
  toastUndoEl.textContent = "Undo";
  toastUndoEl.classList.remove("danger");
  toastEl.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, 5000);
}
function showDeleteConfirm(id, text) {
  pendingDeleteId = id;
  toastTextEl.textContent = `Delete "${text}"?`;
  toastUndoEl.textContent = "Delete";
  toastUndoEl.classList.add("danger");
  toastEl.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, DELETE_CONFIRM_MS);
}
function hideToast() {
  pendingDeleteId = null;
  toastEl.hidden = true;
  if (toastTimer) { clearTimeout(toastTimer); toastTimer = null; }
}
async function onToastAction() {
  if (pendingDeleteId !== null) {
    const id = pendingDeleteId;
    pendingDeleteId = null;
    hideToast();
    await onDelete(id);  // onDelete itself shows the post-delete undo toast
    return;
  }
  hideToast();
  try { await api.undo(); } catch {}
  // SSE will broadcast Added events back to us
}

let sortable = null;
function initSortable() {
  if (sortable) return;
  // eslint-disable-next-line no-undef
  sortable = new Sortable(listEl, {
    animation: 150,
    forceFallback: true,        // unified pointer handling on mouse + touch
    fallbackTolerance: DRAG_THRESHOLD,
    delay: 80,                  // hold time before drag activates (touch)
    delayOnTouchOnly: true,
    // Tolerate up to 8px of finger wobble during the 80ms hold without aborting.
    // Default is 0, which means the tiniest tremor while the user settles their
    // finger on the dot glyph kills the drag attempt — symptom: "grabbing the
    // dots exactly does nothing, but grabbing around them works".
    touchStartThreshold: 8,
    // Restrict drag initiation to the visible handles. Without this, drag could
    // start from anywhere on the row, which conflicts with the row's own
    // toggle/long-press handlers and made the handles' purpose ambiguous.
    handle: ".handle",
    ghostClass: "sortable-ghost",
    chosenClass: "sortable-chosen",
    filter: ".empty",
    // Drag only starts from .handle, so the row's pointer handlers (toggle on tap,
    // delete on long-press) own everywhere else. onStart still aborts any pending
    // row gesture as belt-and-suspenders for the case where the same handle press
    // also happened to start a press timer somehow.
    onStart: (evt) => {
      evt.item.dispatchEvent(new CustomEvent("row-abort"));
    },
    onEnd: async (evt) => {
      if (evt.oldIndex === evt.newIndex) return;
      const ids = Array.from(listEl.querySelectorAll("li.item[data-id]"))
        .map((li) => li.dataset.id)
        .filter((id) => id && !id.startsWith("tmp-"));
      if (ids.length === 0) return;
      const pos = new Map(ids.map((id, i) => [id, i]));
      state.items.sort((a, b) => {
        const ai = pos.has(a.id) ? pos.get(a.id) : Number.MAX_SAFE_INTEGER;
        const bi = pos.has(b.id) ? pos.get(b.id) : Number.MAX_SAFE_INTEGER;
        return ai - bi;
      });
      try { await api.reorder(ids); } catch { refresh(); }
    },
  });
}

// Reconnect state machine
//
// Idle Android radios silently drop established TCP sockets; HTTP errors during
// WiFi handovers stutter for several seconds; an EventSource `error` event also
// fires during the browser's *own* successful auto-retry. The naive "reconnect
// after 2s on any error" loop hammers the server during outages and flickers the
// offline pill on every blip. This block replaces it with:
//   - exponential backoff with jitter on every retry, reset on the next success
//   - a debounce before flipping `online → offline`, so transient errors are silent
//   - a heartbeat-driven watchdog that force-reopens the EventSource if the
//     server-side `event: keepalive` frames stop arriving (catches dead sockets
//     the browser hasn't noticed yet)
const RECONNECT_MAX_MS = 30_000;
const OFFLINE_DEBOUNCE_MS = 500;
const SSE_WATCHDOG_MS = 30_000;
let reconnectAttempts = 0;
let offlineDebounceTimer = null;
let sseWatchdogTimer = null;
let reconnectTimer = null;

function nextBackoffMs() {
  const base = Math.min(RECONNECT_MAX_MS, 1_000 * Math.pow(2, reconnectAttempts));
  // Jitter dampens the thundering-herd case when multiple clients reconnect after
  // the same network event. Test hook below reads window.__lastBackoffMs so the
  // Playwright reconnect spec can assert "gap2 > gap1 * 1.5".
  const jitter = Math.floor(Math.random() * 500);
  const total = base + jitter;
  window.__lastBackoffMs = total;
  return total;
}

function markOnline() {
  reconnectAttempts = 0;
  if (offlineDebounceTimer) { clearTimeout(offlineDebounceTimer); offlineDebounceTimer = null; }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  setOnline(true);
}

function deferOffline() {
  // EventSource fires `error` even during successful internal retries — flipping
  // to offline immediately would make the pill flicker on every WiFi blip. Only
  // commit to offline if the failed state outlasts the debounce window.
  if (offlineDebounceTimer || !state.online) return;
  offlineDebounceTimer = setTimeout(() => {
    offlineDebounceTimer = null;
    setOnline(false);
  }, OFFLINE_DEBOUNCE_MS);
}

function feedWatchdog() {
  if (sseWatchdogTimer) clearTimeout(sseWatchdogTimer);
  sseWatchdogTimer = setTimeout(() => {
    sseWatchdogTimer = null;
    // Heartbeat went silent: the TCP socket is probably dead even though the
    // browser hasn't dispatched an error yet. Force a fresh connection.
    if (es) { try { es.close(); } catch {} es = null; }
    document.body.dataset.sse = "closed";
    deferOffline();
    scheduleReconnect();
  }, SSE_WATCHDOG_MS);
}

function clearWatchdog() {
  if (sseWatchdogTimer) { clearTimeout(sseWatchdogTimer); sseWatchdogTimer = null; }
}

function scheduleReconnect() {
  if (reconnectTimer) return; // already pending
  const delay = nextBackoffMs();
  // Test hook: Playwright reads __reconnectLog to assert exponential growth
  // across consecutive failures without depending on wall-clock timing.
  (window.__reconnectLog ||= []).push({ at: Date.now(), delay });
  reconnectAttempts++;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    refresh();
  }, delay);
}

let es = null;
function openEvents() {
  if (es) { try { es.close(); } catch {} }
  try {
    es = new EventSource("/api/events");
  } catch {
    deferOffline();
    scheduleReconnect();
    return;
  }
  feedWatchdog();
  es.addEventListener("open", () => {
    markOnline();
    feedWatchdog();
    // Test hook: lets a second page wait until its SSE subscription is live before
    // expecting to receive broadcasts. Cheap to set, harmless in production.
    document.body.dataset.sse = "open";
  });
  es.addEventListener("change", (e) => {
    feedWatchdog();
    try { applyChange(JSON.parse(e.data)); } catch {}
  });
  es.addEventListener("keepalive", () => {
    // Server emits these on a 15s cadence so we can prove the socket is alive
    // even when no list edits are happening. Comment frames would feed the byte
    // stream but be invisible to EventSource — this is dispatchable on purpose.
    feedWatchdog();
    markOnline();
  });
  es.addEventListener("error", () => {
    document.body.dataset.sse = "closed";
    deferOffline();
    clearWatchdog();
    scheduleReconnect();
  });
}

async function refresh() {
  try {
    const remote = await api.list();
    // Preserve in-flight optimistics: their POST hasn't returned yet, so the
    // server doesn't know about them. Wiping them here (initial-refresh-vs-fast-
    // user-input race, or SSE-error-triggered re-sync mid-paste) would erase rows
    // the user just added and would orphan the still-pending addOne's reconcile.
    const pending = state.items.filter((x) => x.id.startsWith("tmp-"));
    state.items = [...remote, ...pending];
    markOnline();
    render();
    if (!es || es.readyState === 2) openEvents();
  } catch {
    deferOffline();
    scheduleReconnect();
  }
}

formEl.addEventListener("submit", onAdd);
inputEl.addEventListener("paste", onPaste);
clearDoneEl.addEventListener("click", onClearDone);
clearAllEl.addEventListener("click", onClearAll);
toastUndoEl.addEventListener("click", onToastAction);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") refresh();
});
// Defensive periodic resync. Visibility/online/SSE handlers above cover the
// happy paths, but in rare cases (Android WebView quirks where visibilitychange
// doesn't fire after a long background period, SSE silently dropping events
// before the watchdog notices) the page can drift from the server. A cheap
// 30s pull keeps it within one interval of authoritative server state.
const PERIODIC_REFRESH_MS = 30_000;
setInterval(() => {
  if (document.visibilityState === "visible") refresh();
}, PERIODIC_REFRESH_MS);
// `pageshow` fires when the WebView is restored from BFCache, which doesn't
// always trigger `visibilitychange`. Without this, a backgrounded Android
// WebView could resume with a long-dead EventSource and never reconnect until
// the user touched the input.
window.addEventListener("pageshow", (e) => {
  if (e.persisted || (es && es.readyState === 2)) refresh();
});
window.addEventListener("online", () => {
  // Chromium can keep an EventSource in readyState=OPEN across a network drop
  // even though the underlying TCP is dead — `refresh()`'s readyState check
  // would then skip reopening. Force a fresh SSE on every regain-network event
  // so we never trust a "looks open" socket that just survived a network blip.
  if (es) { try { es.close(); } catch {} es = null; }
  refresh();
});
window.addEventListener("offline", () => setOnline(false));

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

initSortable();
refresh();

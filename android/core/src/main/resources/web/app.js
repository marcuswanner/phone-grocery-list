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
  async add(text) {
    const r = await fetch("/api/items", {
      method: "POST",
      headers: JSON_WRITE_HEADERS,
      body: JSON.stringify({ text }),
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
      onDelete(id);
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

async function onToggle(id) {
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

async function addOne(text) {
  const optimistic = { id: `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, text, done: false };
  state.items.push(optimistic);
  render();
  try {
    const saved = await api.add(text);
    reconcile(optimistic.id, saved);
    return saved;
  } catch {
    state.items = state.items.filter((x) => x.id !== optimistic.id);
    render();
    throw new Error("add failed");
  }
}

async function addMany(items) {
  const tmps = items.map((text) => ({
    id: `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    text,
    done: false,
  }));
  for (const t of tmps) state.items.push(t);
  render();
  for (let i = 0; i < items.length; i++) {
    try {
      const saved = await api.add(items[i]);
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
function showUndoToast(message) {
  toastTextEl.textContent = message;
  toastEl.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, 5000);
}
function hideToast() {
  toastEl.hidden = true;
  if (toastTimer) { clearTimeout(toastTimer); toastTimer = null; }
}
async function onUndo() {
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
    ghostClass: "sortable-ghost",
    chosenClass: "sortable-chosen",
    filter: ".empty",
    // Whole row is a drag target. onStart fires only AFTER movement past fallbackTolerance,
    // so a plain tap still reaches our pointerup handler and toggles. The row's own
    // pointermove also calls cancel() on movement as a belt-and-suspenders.
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

let es = null;
function openEvents() {
  if (es) es.close();
  try {
    es = new EventSource("/api/events");
  } catch {
    setOnline(false);
    return;
  }
  es.addEventListener("open", () => setOnline(true));
  es.addEventListener("change", (e) => {
    try { applyChange(JSON.parse(e.data)); } catch {}
  });
  es.addEventListener("error", () => {
    setOnline(false);
    setTimeout(refresh, 2000);
  });
}

async function refresh() {
  try {
    state.items = await api.list();
    setOnline(true);
    render();
    if (!es || es.readyState === 2) openEvents();
  } catch {
    setOnline(false);
    setTimeout(refresh, 3000);
  }
}

formEl.addEventListener("submit", onAdd);
inputEl.addEventListener("paste", onPaste);
clearDoneEl.addEventListener("click", onClearDone);
clearAllEl.addEventListener("click", onClearAll);
toastUndoEl.addEventListener("click", onUndo);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") refresh();
});
window.addEventListener("online", refresh);
window.addEventListener("offline", () => setOnline(false));

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

initSortable();
refresh();

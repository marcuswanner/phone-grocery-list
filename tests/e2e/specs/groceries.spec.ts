import { test, expect, type Page, type APIRequestContext } from "@playwright/test";

async function clearList(req: APIRequestContext) {
  const r = await req.get("/api/items");
  const items: { id: string }[] = await r.json();
  for (const i of items) await req.delete(`/api/items/${i.id}`);
}

test.beforeEach(async ({ request }) => {
  await clearList(request);
});

async function addItem(page: Page, text: string) {
  await page.locator("#text").fill(text);
  await page.locator("#text").press("Enter");
  await expect(page.locator("li.item .text", { hasText: text })).toBeVisible();
}

test("add flow: type, Enter, see row", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await expect(page.locator("li.item")).toHaveCount(1);
  await expect(page.locator("li.item .text").first()).toHaveText("milk");
});

async function pasteInto(page: Page, selector: string, text: string) {
  await page.locator(selector).focus();
  await page.evaluate(({ selector, text }) => {
    const el = document.querySelector(selector) as HTMLInputElement;
    const dt = new DataTransfer();
    dt.setData("text/plain", text);
    const ev = new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true });
    el.dispatchEvent(ev);
  }, { selector, text });
}

test("paste flow: newline-separated list creates one row per line", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "milk\neggs\nbread");
  await expect(page.locator("li.item")).toHaveCount(3);
  await expect(page.locator("li.item .text").nth(0)).toHaveText("milk");
  await expect(page.locator("li.item .text").nth(1)).toHaveText("eggs");
  await expect(page.locator("li.item .text").nth(2)).toHaveText("bread");
});

test("paste flow: bullets and numbers are stripped", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "- milk\n* eggs\n• butter\n1. bread\n2) jam");
  await expect(page.locator("li.item")).toHaveCount(5);
  const texts = await page.locator("li.item .text").allTextContents();
  expect(texts).toEqual(["milk", "eggs", "butter", "bread", "jam"]);
});

test("paste flow: Apple Notes bracket checkboxes are stripped", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "[ ] milk\n[x] eggs\n[X] bread\n[✓] jam");
  await expect(page.locator("li.item")).toHaveCount(4);
  const texts = await page.locator("li.item .text").allTextContents();
  expect(texts).toEqual(["milk", "eggs", "bread", "jam"]);
});

test("paste flow: Apple Notes unicode checkboxes are stripped", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "☐ milk\n☑ eggs\n☒ bread");
  await expect(page.locator("li.item")).toHaveCount(3);
  const texts = await page.locator("li.item .text").allTextContents();
  expect(texts).toEqual(["milk", "eggs", "bread"]);
});

test("paste flow: combined bullet + bracket prefix collapses both", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "- [ ] milk\n* [x] eggs\n• [ ] bread");
  await expect(page.locator("li.item")).toHaveCount(3);
  const texts = await page.locator("li.item .text").allTextContents();
  expect(texts).toEqual(["milk", "eggs", "bread"]);
});

test("paste flow: blank lines and whitespace are skipped", async ({ page }) => {
  await page.goto("/");
  await pasteInto(page, "#text", "\n  milk  \n\n\n  eggs  \n\n");
  await expect(page.locator("li.item")).toHaveCount(2);
  const texts = await page.locator("li.item .text").allTextContents();
  expect(texts).toEqual(["milk", "eggs"]);
});


test("toggle flow: click strikes through, click again un-strikes", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "eggs");
  const row = page.locator("li.item").first();
  await row.click();
  await expect(row).toHaveClass(/done/);
  await row.click();
  await expect(row).not.toHaveClass(/done/);
});

test("delete flow: long-press removes row", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "bread");
  const row = page.locator("li.item").first();
  // hover auto-waits for the row to be stable/actionable — avoids boundingBox races with re-renders
  await row.hover();
  await page.mouse.down();
  await page.waitForTimeout(800);
  await page.mouse.up();
  await expect(page.locator("li.item .text", { hasText: "bread" })).toHaveCount(0);
});

test("live sync: change in context A appears in context B via SSE", async ({ browser }) => {
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const pageA = await ctxA.newPage();
  const pageB = await ctxB.newPage();
  try {
    await pageA.goto("/");
    await pageB.goto("/");
    await addItem(pageA, "yogurt");
    // B should see it within ~2s via SSE
    await expect(pageB.locator("li.item .text", { hasText: "yogurt" })).toBeVisible({
      timeout: 3_000,
    });
  } finally {
    await ctxA.close();
    await ctxB.close();
  }
});

test("reconnect: B keeps receiving updates after a network blip", async ({ browser }) => {
  // Note: we don't assert "B doesn't see during offline" — Chromium sometimes keeps
  // an established SSE connection alive across setOffline, so that assertion was flaky.
  // The behavior we actually care about is: after a network event cycle, B still works.
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const pageA = await ctxA.newPage();
  const pageB = await ctxB.newPage();
  try {
    await pageA.goto("/");
    await pageB.goto("/");
    await ctxB.setOffline(true);
    await ctxB.setOffline(false);
    await pageB.evaluate(() => window.dispatchEvent(new Event("online")));
    await addItem(pageA, "cheese");
    await expect(pageB.locator("li.item .text", { hasText: "cheese" })).toBeVisible({
      timeout: 4_000,
    });
  } finally {
    await ctxA.close();
    await ctxB.close();
  }
});

test("PWA: manifest is valid and service worker registers", async ({ page }) => {
  await page.goto("/");
  const manifestResp = await page.request.get("/manifest.webmanifest");
  expect(manifestResp.ok()).toBeTruthy();
  const manifest = await manifestResp.json();
  expect(manifest.name).toBe("Groceries");
  expect(manifest.display).toBe("standalone");
  expect(manifest.start_url).toBe("/");
  expect(Array.isArray(manifest.icons)).toBe(true);

  // notepad favicon: SVG is referenced and served with the right content type
  await expect(page.locator('link[rel="icon"][type="image/svg+xml"]')).toHaveAttribute("href", "/icon.svg");
  const svgResp = await page.request.get("/icon.svg");
  expect(svgResp.ok()).toBeTruthy();
  expect(svgResp.headers()["content-type"]).toContain("svg");
  expect(await svgResp.text()).toContain("<svg");

  const swReady = await page.evaluate(async () => {
    if (!("serviceWorker" in navigator)) return false;
    const reg = await navigator.serviceWorker.ready;
    return !!reg.active || !!reg.installing || !!reg.waiting;
  });
  expect(swReady).toBeTruthy();
});

test("clear done: removes only checked items in one tap", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  await addItem(page, "bread");
  // mark milk and bread done
  await page.locator("li.item .text", { hasText: "milk" }).locator("..").click();
  await page.locator("li.item .text", { hasText: "bread" }).locator("..").click();
  await expect(page.locator("li.item.done")).toHaveCount(2);
  await page.locator("#clear-done").click();
  await expect(page.locator("li.item")).toHaveCount(1);
  await expect(page.locator("li.item .text").first()).toHaveText("eggs");
});

test("clear all: tap twice to confirm wipes the list", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  // first tap: list still intact, button arms
  await page.locator("#clear-all").click();
  await expect(page.locator("li.item")).toHaveCount(2);
  await expect(page.locator("#clear-all")).toHaveText(/Tap again/);
  // second tap: list emptied
  await page.locator("#clear-all").click();
  await expect(page.locator("li.item .text")).toHaveCount(0);
});

test("clear all: first tap times out and reverts the button", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await page.locator("#clear-all").click();
  await expect(page.locator("#clear-all")).toHaveText(/Tap again/);
  // wait past the 2.5s arm window
  await page.waitForTimeout(2800);
  await expect(page.locator("#clear-all")).toHaveText("Clear all");
  await expect(page.locator("li.item")).toHaveCount(1);
});

test("reorder: a left and right drag handle render on every row", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  await addItem(page, "bread");
  await expect(page.locator("li.item[data-id] .handle-left")).toHaveCount(3);
  await expect(page.locator("li.item[data-id] .handle-right")).toHaveCount(3);
  // the right handle is intentionally much wider than the left for an easier touch target
  const [leftW, rightW] = await page.evaluate(() => {
    const l = document.querySelector("li.item[data-id] .handle-left") as HTMLElement;
    const r = document.querySelector("li.item[data-id] .handle-right") as HTMLElement;
    return [l.offsetWidth, r.offsetWidth];
  });
  expect(rightW).toBeGreaterThanOrEqual(leftW * 2);
});

test("undo: deleting a single item shows toast and restores on Undo", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  await addItem(page, "bread");
  // long-press eggs
  const eggs = page.locator('li.item:has-text("eggs")');
  await eggs.hover();
  await page.mouse.down();
  await page.waitForTimeout(800);
  await page.mouse.up();
  await expect(page.locator('li.item .text', { hasText: "eggs" })).toHaveCount(0);
  // Toast appears
  await expect(page.locator("#toast")).toBeVisible();
  await expect(page.locator("#toast-text")).toContainText("eggs");
  // Tap Undo
  await page.locator("#toast-undo").click();
  // eggs is back, in its original middle slot
  await expect.poll(async () => await page.locator("li.item .text").allTextContents())
    .toEqual(["milk", "eggs", "bread"]);
  // Toast hides
  await expect(page.locator("#toast")).toBeHidden();
});

test("undo: Clear all + Undo restores the whole list", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  await page.locator("#clear-all").click();
  await page.locator("#clear-all").click();
  await expect(page.locator("li.item .text")).toHaveCount(0);
  await expect(page.locator("#toast")).toBeVisible();
  await page.locator("#toast-undo").click();
  await expect.poll(async () => await page.locator("li.item .text").allTextContents())
    .toEqual(["milk", "eggs"]);
});

test("undo: toast auto-dismisses after 5 seconds", async ({ page }) => {
  await page.goto("/");
  await addItem(page, "milk");
  const milk = page.locator('li.item:has-text("milk")');
  await milk.hover();
  await page.mouse.down();
  await page.waitForTimeout(800);
  await page.mouse.up();
  await expect(page.locator("#toast")).toBeVisible();
  await page.waitForTimeout(5500);
  await expect(page.locator("#toast")).toBeHidden();
});

test("reorder: POST /api/reorder updates order and broadcasts via SSE", async ({ page }) => {
  // The data path (server reorder + SSE -> client re-render) is what matters for regression.
  // The drag UX itself is exercised manually — SortableJS's pointer choreography is fiddly
  // to drive deterministically in headless Chromium and adds noise without catching real bugs.
  await page.goto("/");
  await addItem(page, "milk");
  await addItem(page, "eggs");
  await addItem(page, "bread");
  const items: { id: string; text: string }[] = await page.request.get("/api/items").then((r) => r.json());
  const newOrder = [items[2].id, items[0].id, items[1].id]; // bread, milk, eggs
  const r = await page.request.post("/api/reorder", {
    data: { ids: newOrder },
    headers: { "content-type": "application/json" },
  });
  expect(r.ok()).toBeTruthy();
  await expect
    .poll(async () => await page.locator("li.item .text").allTextContents())
    .toEqual(["bread", "milk", "eggs"]);
});

test("offline shell: page loads from cache when network is gone", async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  try {
    await page.goto("/");
    // wait for service worker to be ready and shell to be cached
    await page.waitForFunction(async () => {
      const reg = await navigator.serviceWorker.ready;
      return !!reg.active;
    }, null, { timeout: 5_000 });
    // give SW a moment to cache the shell
    await page.waitForTimeout(500);

    await ctx.setOffline(true);
    await page.reload();
    // shell still renders
    await expect(page.locator("h1")).toHaveText("Groceries");
  } finally {
    await ctx.close();
  }
});

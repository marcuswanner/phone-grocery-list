# Groceries

A silly little shared grocery list.

The Android phone *is* the server. A web UI is bundled inside the app and served over your local WiFi. From a Mac browser on the same network you open `http://groceries.local:8080`, add things, mark them done; the phone (and any other browser pointed at the same URL) sees the change live via Server-Sent Events. The list lives in one JSON file on the phone — no cloud, no account, no sync service.

When the phone leaves the WiFi, the Mac can't see the list. That's fine: you're the one at the store with the phone.

## Repository layout

```
android/
  core/    Pure Kotlin/JVM — Store, REST + SSE, PWA assets. Runs standalone for tests.
  app/     Android wrapper — Activity, foreground service, mDNS announcement.
tests/
  e2e/     Playwright tests driving the PWA against a real :core server.
```

Two-module split exists so `:core` can be tested headlessly on the JVM and driven by Playwright without needing an emulator. The Android app is a thin shell on top.

## Build & install on a phone

Prereqs (already on this Mac, see `~/.claude/projects/-Users-marcus-notes/memory/android_toolchain.md`):
- Android Studio at `/Applications/Android Studio.app` (provides the JBR JDK 21 used to build)
- Android SDK at `~/Library/Android/sdk`

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
# install on a connected phone with USB-debugging on
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

## First-launch flow on the phone

1. Open the app. It will ask for **notification permission** — say yes (without it the foreground-service notification can't show, and Android may kill the server).
2. Tap **Disable battery optimization** and confirm — otherwise Android Doze will kill the server when the screen is off.
3. Tap **Start server**. A persistent notification appears with the URL. The screen shows:
   - `http://groceries.local:8080` (Bonjour name — best to bookmark on the Mac)
   - `http://<phone-IP>:8080` (raw LAN IP — survives Bonjour failures)
4. On the phone, open Chrome → `http://localhost:8080` → menu → **Add to Home Screen**. You now have a standalone PWA.
5. On the Mac, open Safari/Chrome → `http://groceries.local:8080`. Bookmark.

## Use

- Type, hit Enter → adds an item.
- Tap a row → marks done (strikethrough). Tap again → un-done.
- Long-press a row (~600ms) → deletes.
- Updates from any client appear live in others (SSE), within ~1s on the same WiFi.
- Pull-to-refresh works as a manual sync.

## Testing

All tests are split into four layers, in roughly increasing cost:

### Layer 1+2 — JVM unit + integration tests
```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test
```
Runs in ~5s after the first build. Covers Store (17 tests) and the full REST + SSE API (13 tests, including three SSE scenarios against a real ephemeral port).

### Layer 3 — Playwright e2e against the real PWA
```sh
cd tests/e2e
npm install              # first time only; also installs playwright browsers below
npx playwright install   # downloads headless Chromium
npm test
```
Playwright boots `:core` via `start-server.sh` on port 38080, runs the seven scenarios (add, toggle, delete, live sync, reconnect, manifest+SW registration, offline shell render), then tears the server down.

### Layer 4 — Android instrumented tests
Not yet wired up; only needed for the foreground-service lifecycle and mDNS registration. Verified manually for now (see "First-launch flow" above).

### Manual verification
Some behavior is not worth automating:
- Real Bonjour resolution from Mac → phone over real WiFi.
- Android battery-optimization exemption flow (system UI).
- "Add to Home Screen" Chrome UX on a real device.

## Plan

The original plan, including the architecture rationale, gotchas, and scope decisions, lives at `~/.claude/plans/i-need-a-silly-goofy-snail.md`.

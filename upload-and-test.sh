#!/usr/bin/env bash
# Build :app, install on the connected phone, start the server, then run the
# Playwright e2e suite against the phone over WiFi. Backs up and restores the
# phone's items.json so the test run doesn't eat the user's real grocery list.
#
# Requires: USB-debugging phone on the same WiFi as this Mac, debuggable build
# (which `installDebug` produces — needed for `run-as` to read/write items.json).
set -euo pipefail

PORT=8080
PKG="net.wanners.groceries"
ACTIVITY="$PKG/.MainActivity"
MDNS_HOST="groceries.local"
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$HERE/tmp"
BACKUP="$TMP_DIR/items-backup-$(date +%s).json"
mkdir -p "$TMP_DIR"

# Prefer the SDK adb (matches the SDK we built against). Fall back to PATH.
if [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  echo "adb not found (looked in ~/Library/Android/sdk/platform-tools and PATH)" >&2
  exit 1
fi

DEVICE_LINES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LINES" | grep -c . || true)"
if [ "$DEVICE_COUNT" -ne 1 ]; then
  echo "expected exactly one authorised adb device, got $DEVICE_COUNT:" >&2
  "$ADB" devices >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

backup_items() {
  if "$ADB" shell "run-as $PKG test -f files/items.json" 2>/dev/null; then
    "$ADB" exec-out "run-as $PKG cat files/items.json" > "$BACKUP"
    echo "   backed up $(wc -c < "$BACKUP" | tr -d ' ') bytes → $BACKUP"
  else
    : > "$BACKUP"
    echo "   no existing items.json (first install or empty)"
  fi
}

restore_items() {
  echo "==> Restore items.json"
  # force-stop kills the in-memory Store so the next launch re-reads disk.
  "$ADB" shell "am force-stop $PKG" >/dev/null 2>&1 || true
  if [ -s "$BACKUP" ]; then
    if cat "$BACKUP" | "$ADB" exec-out "run-as $PKG sh -c 'cat > files/items.json'" >/dev/null; then
      echo "   restored from $BACKUP — re-open the app on the phone to bring the server back"
    else
      echo "   WARN: restore failed; backup kept at $BACKUP" >&2
    fi
  else
    "$ADB" shell "run-as $PKG rm -f files/items.json" >/dev/null 2>&1 || true
    echo "   wiped (pre-test list was empty)"
  fi
}

trap restore_items EXIT

echo "==> Build & install (gradle :app:installDebug)"
( cd "$HERE/android" && ./gradlew -q :app:installDebug )

echo "==> Back up phone's items.json"
backup_items

echo "==> Reset list & launch app (which starts the server)"
"$ADB" shell "am force-stop $PKG" >/dev/null 2>&1 || true
"$ADB" shell "run-as $PKG rm -f files/items.json" >/dev/null 2>&1 || true
# -S force-stops the package and -W blocks until the activity is up; without -S
# Android may "bring task to the front" without re-running onCreate, in which
# case ServerService.start never fires.
"$ADB" shell "am start -W -S -n $ACTIVITY" >/dev/null

echo "==> Resolve phone URL"
URL=""
if curl -fsS --max-time 3 -H "X-Grocery-Client: 1" "http://$MDNS_HOST:$PORT/api/items" >/dev/null 2>&1; then
  URL="http://$MDNS_HOST:$PORT"
  echo "   mDNS reachable: $URL"
else
  IP="$("$ADB" shell ip route 2>/dev/null | awk '/wlan0/ {for (i=1;i<=NF;i++) if ($i=="src") { print $(i+1); exit }}' | tr -d '\r')"
  if [ -z "$IP" ]; then
    IP="$("$ADB" shell ip -4 addr show wlan0 2>/dev/null | awk '/inet / {sub("/.*","",$2); print $2; exit}' | tr -d '\r')"
  fi
  if [ -z "$IP" ]; then
    echo "could not discover phone WiFi IP via adb" >&2
    exit 1
  fi
  URL="http://$IP:$PORT"
  echo "   fallback to LAN IP: $URL"
fi

echo "==> Wait for server"
DEADLINE=$(( $(date +%s) + 30 ))
until curl -fsS --max-time 2 -H "X-Grocery-Client: 1" "$URL/api/items" >/dev/null 2>&1; do
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "server did not respond at $URL within 30s" >&2
    echo "(if you got 403, the Mac may be on a different subnet than the phone)" >&2
    exit 1
  fi
  sleep 0.5
done
echo "   ready"

echo "==> Run Playwright against $URL"
( cd "$HERE/tests/e2e" && PLAYWRIGHT_BASE_URL="$URL" npm test )

echo "==> Tests passed"

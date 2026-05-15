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

count_online() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -c . || true; }
count_offline() { "$ADB" devices | awk 'NR>1 && $2=="offline" {print $1}' | grep -c . || true; }
count_total() { "$ADB" devices | awk 'NR>1 && NF>0 {print $1}' | grep -c . || true; }

# A fresh adb daemon hasn't yet discovered wireless-paired devices via mDNS.
# Poke mDNS so the daemon starts scanning, then wait up to ~10s for it to
# auto-attach the TLS-paired device. Cheap when a USB device is already present.
if [ "$(count_total)" -eq 0 ]; then
  echo "==> No adb devices yet — probing mDNS for a paired wireless device"
  "$ADB" mdns services >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ "$(count_total)" -ge 1 ] && break
    sleep 1
  done
fi

# Wireless adb (TLS) connections frequently go to "offline" between sessions —
# `adb reconnect offline` re-handshakes without making the user re-pair.
if [ "$(count_online)" -eq 0 ] && [ "$(count_offline)" -ge 1 ]; then
  echo "==> adb device is offline — running 'adb reconnect offline'"
  "$ADB" reconnect offline >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6; do
    [ "$(count_online)" -ge 1 ] && break
    sleep 1
  done
fi

DEVICE_COUNT="$(count_online)"
if [ "$DEVICE_COUNT" -ne 1 ]; then
  echo "expected exactly one authorised adb device, got $DEVICE_COUNT:" >&2
  "$ADB" devices >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

backup_items() {
  # base64 round-trips the bytes through stdout cleanly. The previous
  # `adb exec-out cat > $BACKUP` was unreliable on wireless adb — it would
  # silently truncate to 0 bytes when the pipe stalled, and the restore
  # branch then wipes the user's list because $BACKUP looks empty.
  local b64
  b64=$("$ADB" shell "run-as $PKG sh -c 'test -f files/items.json && base64 -w 0 files/items.json'" 2>/dev/null | tr -d '\r')
  if [ -n "$b64" ]; then
    printf '%s' "$b64" | base64 --decode > "$BACKUP"
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
    # base64-on-the-command-line: 100% reliable on wireless adb where
    # `cat $BACKUP | adb exec-out 'cat > …'` periodically hangs forever.
    # Items.json is bounded to ~64KiB so the encoded payload fits comfortably
    # within the shell command-line limit.
    local b64
    b64=$(base64 -i "$BACKUP" | tr -d '\n')
    if "$ADB" shell "run-as $PKG sh -c 'echo $b64 | base64 -d > files/items.json'" >/dev/null 2>&1; then
      # Verify the bytes actually landed — the shell command above can succeed
      # even when run-as silently swallows the write (e.g. SELinux quirks).
      local on_device
      on_device=$("$ADB" shell "run-as $PKG sh -c 'wc -c < files/items.json'" 2>/dev/null | tr -d '\r ')
      local local_size
      local_size=$(wc -c < "$BACKUP" | tr -d ' ')
      if [ "$on_device" = "$local_size" ]; then
        echo "   restored $on_device bytes from $BACKUP"
        # Force-stopped above; the app is in a clean stopped state and the
        # user's next launch will load the restored items.json fresh.
      else
        echo "   WARN: restore size mismatch (device=$on_device local=$local_size); backup kept at $BACKUP" >&2
      fi
    else
      echo "   WARN: restore failed; backup kept at $BACKUP" >&2
    fi
  else
    "$ADB" shell "run-as $PKG rm -f files/items.json" >/dev/null 2>&1 || true
    echo "   wiped (pre-test list was empty)"
  fi
}

trap restore_items EXIT

echo "==> Force-stop old app (so install doesn't wait on the running service)"
"$ADB" shell "am force-stop $PKG" >/dev/null 2>&1 || true

echo "==> Build & install (gradle :app:installDebug)"
( cd "$HERE/android" && ./gradlew :app:installDebug )

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

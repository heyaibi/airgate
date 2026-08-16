#!/usr/bin/env bash
# Start the Android emulator if no device is connected, and wait for boot.
#
# A physical phone (or already-running emulator) takes precedence — this is a
# no-op when any device is connected. Only when nothing is available does it
# boot the given AVD and block until sys.boot_completed.
#
# Usage: start-emulator.sh <adb> <emulator> <avd>
set -euo pipefail

ADB="${1:?usage: start-emulator.sh <adb> <emulator> <avd>}"
EMU="${2:?usage: start-emulator.sh <adb> <emulator> <avd>}"
AVD="${3:?usage: start-emulator.sh <adb> <emulator> <avd>}"

has_device() {
  "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {found=1} END {exit found ? 0 : 1}'
}

booted() {
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

if has_device; then
  echo "start-emulator: device already connected; nothing to do"
  exit 0
fi

"$ADB" start-server >/dev/null 2>&1 || true
echo "start-emulator: no device connected — booting AVD '$AVD'"
nohup "$EMU" -avd "$AVD" >/dev/null 2>&1 &
disown || true

timeout=180
while [ "$timeout" -gt 0 ]; do
  if booted; then
    "$ADB" shell input keyevent 82 >/dev/null 2>&1 || true
    echo "start-emulator: AVD '$AVD' booted"
    exit 0
  fi
  sleep 2
  timeout=$((timeout - 2))
done

echo "start-emulator: AVD '$AVD' did not boot within 180s" >&2
exit 1

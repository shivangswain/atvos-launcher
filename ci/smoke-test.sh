#!/usr/bin/env bash
# Smoke test of atvOS Launcher on a running emulator. It installs the debug APK, skips setup, starts atvOS as the home
# screen and uses it with remote keys. It fails if a step does not get the expected focus, or if atvOS crashes.
#
# Usage: ci/smoke-test.sh APK [OUTPUT_DIR]
set -euo pipefail

APK="$1"
OUT="${2:-smoke-output}"
PKG=app.atvos.launcher
FAILED=0
mkdir -p "$OUT"

log() { printf '%s\n' "$*" | tee -a "$OUT/steps.txt"; }

# Sends remote keys, with a short pause after each.
key() {
    for k in "$@"; do
        if [ "$k" = LONG_CENTER ]; then adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
        else adb shell input keyevent "KEYCODE_$k"; fi
        sleep 0.7
    done
}

# Writes the accessibility tree of the screen to $OUT/ui.xml.
dump() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2> /dev/null || true
}

# Prints the label of the focused item.
focused() {
    dump
    grep -o '<node [^>]*focused="true"[^>]*>' "$OUT/ui.xml" | grep -o 'text="[^"]\+"' | head -1 | sed 's/^text="//; s/"$//'
}

# Checks that the label of the focused item is one of the arguments.
expect_focus() {
    local got
    got="$(focused)"
    for want in "$@"; do
        if [ "$got" = "$want" ]; then log "ok: focus on \"$got\""; return 0; fi
    done
    log "FAIL: focus on \"$got\", expected: $*"
    FAILED=1
}

# Checks that an item with this label is on the screen.
expect_item() {
    dump
    if grep -q "text=\"$1\"" "$OUT/ui.xml"; then log "ok: \"$1\" on the screen"
    else log "FAIL: \"$1\" not on the screen"; FAILED=1; fi
}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

home() {
    adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.HomeActivity" > /dev/null
    sleep 2
}

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
log "device: Android $(adb shell getprop ro.build.version.release | tr -d '\r'), $(adb shell getprop ro.product.name | tr -d '\r')"

adb install -r -g "$APK" > /dev/null
log "ok: installed $APK"

# Setup is complete and the recommendations prompt is answered, so the home screen shows immediately.
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
    '    <boolean name="set_up" value="true" />' '    <boolean name="asked_tv_listings" value="true" />' '</map>' |
    adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/home.xml'"

adb logcat -c
adb logcat -b crash -c
home
sleep 3
# topResumedActivity from Android 13, mResumedActivity on Android 12.
if adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -q "$PKG/"; then
    log "ok: atvOS is in front"
else log "FAIL: atvOS is not in front"; FAILED=1; fi
shot 1-home

# The dock and the grid.
key DPAD_RIGHT DPAD_RIGHT DPAD_LEFT
[ -n "$(focused)" ] && log "ok: a dock tile has the focus" || { log "FAIL: no focus in the dock"; FAILED=1; }
key DPAD_DOWN
shot 2-grid
key DPAD_UP

# The clock pill and the Control Center.
for _ in 1 2 3; do [ "$(focused)" = "Control Center" ] && break; key DPAD_UP; done
expect_focus "Control Center"
key DPAD_CENTER
sleep 1
expect_focus "Power Off"
shot 3-control-center

# atvOS Settings, from the switches in the status row.
for _ in 1 2 3; do [ "$(focused)" = "atvOS Launcher Settings" ] && break; key DPAD_UP; done
expect_focus "atvOS Launcher Settings"
key DPAD_CENTER
sleep 1
expect_focus "Home Screen" "General"
key DPAD_DOWN DPAD_DOWN DPAD_UP
shot 4-settings
key BACK
sleep 1
home

# The long-press menu of a dock tile.
key DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT LONG_CENTER
sleep 1
expect_item "Edit Home Screen"
expect_item "Move to…"
shot 5-menu
key BACK
home

# Results.
if [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then log "ok: atvOS is running"
else log "FAIL: atvOS is not running"; FAILED=1; fi
adb logcat -b crash -d > "$OUT/crash.txt"
if grep -q "$PKG" "$OUT/crash.txt"; then log "FAIL: atvOS crashed (see crash.txt)"; FAILED=1
else log "ok: no crash"; fi
adb logcat -d > "$OUT/logcat.txt"

# Frame statistics. A CI emulator draws with a software GPU, and the test turns animations off, so the frame and GPU
# times measure the emulator, not atvOS on a TV (gfxinfo.txt keeps them). The UI thread time is the CPU work of atvOS.
adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT/gfxinfo.txt"
python3 - "$OUT/gfxinfo.txt" <<'PY' | tee -a "$OUT/steps.txt"
import re, sys
rows, header = [], None
for line in open(sys.argv[1]):
    if line.startswith("Flags,"):
        header = line.strip().rstrip(",").split(",")
    elif header and re.match(r"^\d+,", line):
        values = line.strip().rstrip(",").split(",")
        rows.append(dict(zip(header, map(int, values))))
ui = sorted((r["SyncQueued"] - r["HandleInputStart"]) / 1e6 for r in rows if r["Flags"] == 0 and r["SyncQueued"] > 0)
if ui:
    pick = lambda p: ui[min(len(ui) - 1, int(len(ui) * p))]
    print(f"UI thread per frame ({len(ui)} frames): p50 {pick(0.5):.1f} ms, p90 {pick(0.9):.1f} ms, max {ui[-1]:.1f} ms")
print("Frame and GPU times: software GPU on this emulator, not a TV measure (see gfxinfo.txt).")
PY

[ "$FAILED" = 0 ] && log "PASSED" || log "FAILED"
exit "$FAILED"

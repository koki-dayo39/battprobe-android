#!/bin/sh
# Collects everything needed to debug Battery Status on a real device over adb.
# Usage:  sh tools/adb-diag.sh > diag.txt   (then paste / attach diag.txt)
# adb shell runs as the same `shell` uid that Shizuku (adb mode) gives the app,
# so what this script can read is exactly what the app can read.
set -u
APP=com.kokidayo.batterystatus

section() { echo; echo "===== $* ====="; }

section device
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk

section packages
adb shell pm list packages | grep -E "shizuku|$APP"
adb shell dumpsys package $APP | grep -E "versionName|versionCode" | head -2

section "dumpsys battery"
adb shell dumpsys battery

section "power_supply uevent (fast path)"
adb shell 'for d in /sys/class/power_supply/*/; do echo "@$d"; cat "$d/uevent" 2>&1; done'

section "power_supply file count + full scan timing (slow path)"
adb shell 'ls /sys/class/power_supply/*/ | wc -l'
adb shell 'time sh -c "for d in /sys/class/power_supply/*/; do for f in \$d*; do [ -f \"\$f\" ] && head -c 200 \"\$f\" >/dev/null 2>&1; done; done"' 2>&1

section "power_supply full scan values"
adb shell 'for d in /sys/class/power_supply/*/; do n=${d%/}; n=${n##*/}; for f in "$d"*; do [ -f "$f" ] || continue; b=${f##*/}; [ "$b" = uevent ] && continue; v=$(timeout 1 head -c 200 "$f" 2>/dev/null | tr "\n" " "); [ -n "$v" ] && echo "$n|$b|$v"; done; done'

section "batterystats excerpt + timing"
adb shell 'time sh -c "dumpsys batterystats >/dev/null"' 2>&1
adb shell 'dumpsys batterystats | grep -E -m 20 "battery capacity|Time on battery|Discharge: |Screen on discharge|Screen off discharge"'

section "app logcat (last 400 lines, errors + Shizuku)"
adb logcat -d -t 400 -v time ShizukuManager:V ShellService:V AndroidRuntime:E System.err:W '*:S'

section "app process crash / ANR"
adb logcat -d -b crash -t 200 2>/dev/null | grep -A 30 "$APP" | head -80

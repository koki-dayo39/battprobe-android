# Runs inside the Shizuku shell process. $FULL_SYSFS is prepended by the app.
# Fast path (every tick): dumpsys battery + one `cat uevent` per power supply.
T=""
command -v timeout >/dev/null 2>&1 && T="timeout 1"
echo '###DUMPSYS'
if [ -n "$T" ]; then timeout 5 dumpsys battery 2>&1; else dumpsys battery 2>&1; fi
echo '###UEVENT'
for d in /sys/class/power_supply/*/; do
  [ -f "$d/uevent" ] || continue
  n=${d%/}; n=${n##*/}
  echo "@$n"
  $T cat "$d/uevent" 2>/dev/null
done
# Slow path (every ~15 s): every file under each supply, for vendor-specific nodes.
# One background subshell per supply so a slow node bounds, not adds, total time.
if [ "$FULL_SYSFS" = "1" ]; then
  echo '###SYSFS'
  # Prefer the shell builtin `read -t` (mksh): no process per file, 1 s cap per read.
  FAST=0
  if ( read -r -t 1 _x </dev/null ) 2>/dev/null; then FAST=1; fi
  for d in /sys/class/power_supply/*/; do
    [ -d "$d" ] || continue
    (
      n=${d%/}; n=${n##*/}
      for f in "$d"*; do
        [ -f "$f" ] || continue
        b=${f##*/}
        [ "$b" = "uevent" ] && continue
        if [ "$FAST" = "1" ]; then
          v=""
          { IFS= read -r -t 1 l1 && v="$l1"; IFS= read -r -t 1 l2 && v="$v $l2"; IFS= read -r -t 1 l3 && v="$v $l3"; } <"$f" 2>/dev/null
        else
          v=$($T head -c 200 "$f" 2>/dev/null | tr '\n' ' ')
        fi
        [ -n "$v" ] && echo "$n|$b|${v%% }"
      done
    ) &
  done
  wait
fi
exit 0

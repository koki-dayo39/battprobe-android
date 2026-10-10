# Runs inside the Shizuku shell process. Fast path: dumpsys battery + sysfs only.
T=""
command -v timeout >/dev/null 2>&1 && T="timeout 1"
echo '###DUMPSYS'
if [ -n "$T" ]; then timeout 5 dumpsys battery 2>&1; else dumpsys battery 2>&1; fi
echo '###SYSFS'
# One background subshell per power supply so a single slow sysfs node
# bounds the total time instead of adding up across supplies.
for d in /sys/class/power_supply/*/; do
  [ -d "$d" ] || continue
  (
    n=$(basename "$d")
    for f in "$d"*; do
      [ -f "$f" ] || continue
      b=$(basename "$f")
      [ "$b" = "uevent" ] && continue
      v=$($T head -c 200 "$f" 2>/dev/null | tr '\n' ' ')
      [ -n "$v" ] && echo "$n|$b|$v"
    done
  ) &
done
wait
exit 0

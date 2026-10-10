# Runs inside the Shizuku shell process. $WITH_STATS is prepended by the app.
echo '###DUMPSYS'
dumpsys battery 2>&1
echo '###SYSFS'
T=""
command -v timeout >/dev/null 2>&1 && T="timeout 1"
for d in /sys/class/power_supply/*/; do
  [ -d "$d" ] || continue
  n=$(basename "$d")
  for f in "$d"*; do
    [ -f "$f" ] || continue
    b=$(basename "$f")
    [ "$b" = "uevent" ] && continue
    v=$($T head -c 200 "$f" 2>/dev/null | tr '\n' ' ')
    [ -n "$v" ] && echo "$n|$b|$v"
  done
done
if [ "$WITH_STATS" = "1" ]; then
  echo '###STATS'
  dumpsys batterystats 2>/dev/null | grep -E -m 20 'battery capacity|Time on battery|Discharge: |Screen on discharge|Screen off discharge|Screen doze discharge|Start clock time|Amount discharged'
fi
exit 0

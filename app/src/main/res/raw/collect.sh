# Runs inside the privileged shell. Fast path only: dumpsys battery + one uevent per supply.
# "@@T label seconds" lines are timing markers (mksh exposes $EPOCHREALTIME).
echo "@@T start $EPOCHREALTIME"
command -v dumpsys >/dev/null 2>&1 || echo "@@ERR dumpsys not found (PATH=$PATH)"
echo '###DUMPSYS'
dumpsys battery 2>&1
echo "@@T dumpsys $EPOCHREALTIME"
echo '###UEVENT'
for d in /sys/class/power_supply/*/; do
  [ -f "$d/uevent" ] || continue
  n=${d%/}; n=${n##*/}
  echo "@$n"
  cat "$d/uevent" 2>/dev/null
done
echo "@@T uevent $EPOCHREALTIME"
exit 0

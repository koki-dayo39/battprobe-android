# Runs inside the privileged shell. Slow path: every file under each power supply.
# Prefers the shell builtin `read -t` (mksh): no process per file, 1 s cap per read.
echo "@@T start $EPOCHREALTIME"
FAST=0
if echo probe | ( IFS= read -r -t 1 _x ) 2>/dev/null; then FAST=1; fi
echo "@@FAST $FAST"
echo '###SYSFS'
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
        { IFS= read -r -t 1 l1 && v="$l1"; IFS= read -r -t 1 l2 && v="$v $l2"; IFS= read -r -t 1 l3 && v="$v $l3"; } 2>/dev/null <"$f"
      else
        v=$(head -c 200 "$f" 2>/dev/null | tr '\n' ' ')
      fi
      [ -n "$v" ] && echo "$n|$b|${v%% }"
    done
  ) &
done
wait
echo "@@T scan $EPOCHREALTIME"
exit 0

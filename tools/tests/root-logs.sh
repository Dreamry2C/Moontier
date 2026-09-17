#!/system/bin/sh
set -eu
D=/data/local/tmp/moontier-log-test
P="$D/production.sh"
F="$D/fast.sh"
L="$D/core.log"
PID="$D/core.pid"
fail() { echo "FAIL: $*"; exit 1; }
cleanup() { sh "$F" stop /system/bin/sh "$L" "$PID" || true; }
trap cleanup EXIT
rm -f "${PID}.log-limit"
sh -n "$P"
grep -q '^CHECK_INTERVAL_SECONDS=3600$' "$P"
truncate -s 10485760 "$L"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 10485760 ] || fail threshold
truncate -s 12582912 "$L"
echo TAIL-MARKER >> "$L"
INODE=$(stat -c %i "$L")
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 5242880 ] || fail retention
[ "$(stat -c %i "$L")" = "$INODE" ] || fail inode
tail -c 12 "$L" | grep -q TAIL-MARKER || fail tail
# The production copy checks a 4 GiB sparse file using stat + a bounded tail.
truncate -s 4294967296 "$L"
echo FOUR-GIB-TAIL >> "$L"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 5242880 ] || fail huge_file
tail -c 14 "$L" | grep -q FOUR-GIB-TAIL || fail huge_tail
echo 'PASS: exact threshold, 4 GiB -> 5 MiB, tail and inode preserved'
# Custom capacities use 64-bit byte values; zero/empty disable truncation.
echo 4294967296 > "${PID}.log-limit"
truncate -s 12582912 "$L"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 12582912 ] || fail large_custom_limit
echo 0 > "${PID}.log-limit"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 12582912 ] || fail unlimited
: > "${PID}.log-limit"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 12582912 ] || fail empty_limit
echo 2097152 > "${PID}.log-limit"
echo CUSTOM-TAIL >> "$L"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 1048576 ] || fail custom_half
tail -c 12 "$L" | grep -q CUSTOM-TAIL || fail custom_tail
rm "${PID}.log-limit"
echo 'PASS: custom capacity, half retention, zero/empty unlimited and 4 GiB limit'
# Export holds the same lock; the trimmer must skip it.
truncate -s 12582912 "$L"
mkdir "$L.lock"
echo $$ > "$L.lock/pid"
sed 's/.*) //' /proc/$$/stat | awk '{print $20}' > "$L.lock/start"
sh "$P" trim /system/bin/sh "$L" "$PID"
[ "$(stat -c %s "$L")" = 12582912 ] || fail export_lock
rm "$L.lock/pid" "$L.lock/start"
rmdir "$L.lock"
echo 'PASS: active export lock prevents trimming'
sh "$F" start /system/bin/sh "$L" "$PID" "$D/writer.sh"
grep -q '^--- MoonTier manager start .*+08:00 ---$' "$L" || fail session_timezone
WATCH=$(cat "$PID.watch")
CORE_PID=$(cat "$PID")
truncate -s 12582912 "$L"
sleep 4
[ "$(stat -c %s "$L")" -lt 5300000 ] || fail periodic_trim
tail -c 200 "$L" | grep -q LIVE-WRITER || fail active_writer
echo 0 > "${PID}.log-limit"
truncate -s 12582912 "$L"
sleep 4
[ "$(stat -c %s "$L")" -ge 12582912 ] || fail live_unlimited
echo 2097152 > "${PID}.log-limit"
sleep 4
[ "$(stat -c %s "$L")" -lt 1100000 ] || fail live_custom_limit
tail -c 200 "$L" | grep -q LIVE-WRITER || fail live_custom_writer
rm "${PID}.log-limit"
echo 'PASS: existing watcher applies changed capacity at the next check'
kill "$CORE_PID"
sleep 2
! kill -0 "$WATCH" 2>/dev/null || fail orphan_watch
[ ! -e "$PID.watch" ] || fail watcher_pid_file
echo 'PASS: periodic trim keeps writer alive; Core exit cleans watcher'
sh "$F" start /system/bin/sh "$L" "$PID" "$D/writer.sh"
WATCH=$(cat "$PID.watch")
sh "$F" stop /system/bin/sh "$L" "$PID"
sleep 1
! kill -0 "$WATCH" 2>/dev/null || fail stop_orphan
[ ! -e "$PID" ] || fail stale_pid
echo 'PASS: explicit stop cleans Core, watcher and PID files'

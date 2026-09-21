#!/system/bin/sh
PATH=/data/adb/ksu/bin:/data/adb/magisk:/sbin:/system/sbin:/system/bin:/system/xbin:$PATH
export PATH
# POSIX TZ signs are reversed: UTC-8 means fixed UTC+08:00, including official Core.
TZ=UTC-8
export TZ
umask 022

ACTION="$1"
CORE="$2"
LOG="$3"
PID_FILE="$4"
shift 4
CHECK_INTERVAL_SECONDS=3600

process_start() {
    # Strip comm (which may contain spaces) before reading starttime, field 22.
    sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'
}

claim_lock() {
    LOCK_DIR="$1"
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        LOCK_OWNER=$(cat "$LOCK_DIR/pid" 2>/dev/null)
        LOCK_START=$(cat "$LOCK_DIR/start" 2>/dev/null)
        case "$LOCK_OWNER" in
            ''|*[!0-9]*)
                LOCK_TIME=$(stat -c %Y "$LOCK_DIR" 2>/dev/null)
                NOW=$(date +%s)
                [ -n "$LOCK_TIME" ] && [ $((NOW - LOCK_TIME)) -gt 30 ] || return 1
                ;;
            *)
                if kill -0 "$LOCK_OWNER" 2>/dev/null; then
                    [ -n "$LOCK_START" ] && [ "$(process_start "$LOCK_OWNER")" != "$LOCK_START" ] || return 1
                fi
                ;;
        esac
        rm -f "$LOCK_DIR/pid" "$LOCK_DIR/start"
        rmdir "$LOCK_DIR" 2>/dev/null || return 1
        mkdir "$LOCK_DIR" 2>/dev/null || return 1
    fi
    echo $$ > "$LOCK_DIR/pid"
    process_start $$ > "$LOCK_DIR/start"
}

release_lock() {
    rm -f "$1/pid" "$1/start"
    rmdir "$1" 2>/dev/null
}

trim_log() {
    TRIM_LOG="$1"
    [ "$TRIM_LOG" != /dev/null ] && [ -f "$TRIM_LOG" ] || return 0
    SIZE=$(stat -c %s "$TRIM_LOG" 2>/dev/null)
    # Android mksh integer comparisons can wrap at 4 GiB; awk compares as 64-bit doubles.
    [ -n "$SIZE" ] && awk -v size="$SIZE" -v limit="$MAX_LOG_BYTES" 'BEGIN { exit !(size > limit) }' || return 0
    claim_lock "${TRIM_LOG}.lock" || return 0
    TRIM_TEMP="${TRIM_LOG}.trim"
    if tail -c "$KEEP_LOG_BYTES" "$TRIM_LOG" > "$TRIM_TEMP"; then
        # Preserve inode and append semantics of the running Core's stdout FD.
        : > "$TRIM_LOG"
        cat "$TRIM_TEMP" >> "$TRIM_LOG"
    fi
    rm -f "$TRIM_TEMP"
    release_lock "${TRIM_LOG}.lock"
}

trim_logs() {
    MAX_LOG_BYTES=10485760
    if [ -f "${PID_FILE}.log-limit" ]; then
        MAX_LOG_BYTES=$(cat "${PID_FILE}.log-limit" 2>/dev/null)
        # Treat unreadable/invalid settings conservatively: never truncate data.
        case "$MAX_LOG_BYTES" in ''|*[!0-9]*) return 0 ;; esac
    fi
    [ "$MAX_LOG_BYTES" != 0 ] || return 0
    # Avoid Android mksh's 32-bit arithmetic for custom limits larger than 2 GiB.
    KEEP_LOG_BYTES=$(awk -v limit="$MAX_LOG_BYTES" 'BEGIN { printf "%.0f", int(limit / 2) }')
    trim_log "$LOG"
    # Old versions could also leave the official file logger's files behind.
    for EXTRA_LOG in "$(dirname "$CORE")"/easytier.log*; do
        [ -f "$EXTRA_LOG" ] && trim_log "$EXTRA_LOG"
    done
}

alive() {
    PID=$(cat "$PID_FILE" 2>/dev/null)
    case "$PID" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$PID" 2>/dev/null || return 1
    # /data/data and /data/user/0 can be bind-mount aliases, not symlinks.
    # Compare device/inode identity instead of the spelling of readlink output.
    [ "/proc/$PID/exe" -ef "$CORE" ] || return 1
    EXPECTED_START=$(cat "${PID_FILE}.start" 2>/dev/null)
    [ -z "$EXPECTED_START" ] || [ "$(process_start "$PID")" = "$EXPECTED_START" ]
}

case "$ACTION" in
    probe)
        CONFIG_DIR="$1"
        ALLOW_DISCOVERY="$2"
        is_manager() {
            MANAGER_ARGS=$(tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null)
            case " $MANAGER_ARGS " in *" --daemon "*) ;; *) return 1 ;; esac
            MANAGER_CONFIG=$(tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null | awk 'last=="--config-dir" {print; exit} {last=$0}')
            [ "$MANAGER_CONFIG" -ef "$CONFIG_DIR" ] || return 1
            case " $MANAGER_ARGS " in *" --rpc-portal 127.0.0.1:14999 "*) ;; *) return 1 ;; esac
        }
        if alive && is_manager "$PID"; then echo "RUNNING $PID"; exit 0; fi
        if [ "$ALLOW_DISCOVERY" = 1 ]; then
            for PROC in /proc/[0-9]*; do
                [ "$PROC/exe" -ef "$CORE" ] || continue
                FOUND_PID=${PROC##*/}
                is_manager "$FOUND_PID" || continue
                echo "$FOUND_PID" > "$PID_FILE"
                process_start "$FOUND_PID" > "${PID_FILE}.start"
                echo "RUNNING $FOUND_PID"
                exit 0
            done
        fi
        rm -f "$PID_FILE" "${PID_FILE}.start" "${PID_FILE}.watch"
        echo STOPPED
        exit 0
        ;;
    trim)
        trim_logs
        exit 0
        ;;
    watch)
        SLEEP_PID=
        trap '[ -z "$SLEEP_PID" ] || kill "$SLEEP_PID" 2>/dev/null; exit 0' TERM INT
        while alive; do
            sleep "$CHECK_INTERVAL_SECONDS" &
            SLEEP_PID=$!
            wait "$SLEEP_PID"
            SLEEP_PID=
            alive || break
            trim_logs
        done
        exit 0
        ;;
    supervise)
        if [ "$LOG" != /dev/null ]; then
            trim_logs
            printf '\n--- MoonTier manager start %s ---\n' "$(date '+%Y-%m-%dT%H:%M:%S+08:00')" >> "$LOG"
        fi
        "$CORE" "$@" >> "$LOG" 2>&1 &
        CORE_PID=$!
        echo "$CORE_PID" > "$PID_FILE"
        process_start "$CORE_PID" > "${PID_FILE}.start"
        WATCH_PID=
        if [ "$LOG" != /dev/null ]; then
            sh "$0" watch "$CORE" "$LOG" "$PID_FILE" &
            WATCH_PID=$!
            WATCH_START=$(process_start "$WATCH_PID")
            echo "$WATCH_PID" > "${PID_FILE}.watch"
        fi
        wait "$CORE_PID"
        CORE_EXIT=$?
        if [ -n "$WATCH_PID" ] && [ "$(process_start "$WATCH_PID")" = "$WATCH_START" ]; then
            kill "$WATCH_PID" 2>/dev/null
            wait "$WATCH_PID" 2>/dev/null
        fi
        if [ "$(cat "$PID_FILE" 2>/dev/null)" = "$CORE_PID" ]; then
            rm -f "$PID_FILE" "${PID_FILE}.start" "${PID_FILE}.watch"
        fi
        exit "$CORE_EXIT"
        ;;
    start)
        claim_lock "${PID_FILE}.launch" || { echo 'Core is already starting'; exit 1; }
        trap 'release_lock "${PID_FILE}.launch"' EXIT
        if alive; then echo "$PID"; exit 0; fi
        mkdir -p /dev/net
        if [ ! -e /dev/net/tun ] && [ -e /dev/tun ]; then ln -s /dev/tun /dev/net/tun; fi
        chmod 755 "$CORE" || exit 1
        rm -f "$PID_FILE" "${PID_FILE}.start"
        cd "$(dirname "$CORE")" || exit 1
        nohup sh "$0" supervise "$CORE" "$LOG" "$PID_FILE" "$@" </dev/null >/dev/null 2>&1 &
        sleep 1
        if alive; then echo "$PID"; exit 0; fi
        tail -c 16384 "$LOG" 2>/dev/null
        exit 1
        ;;
    stop)
        if alive; then
            STOP_PID="$PID"
            kill "$STOP_PID" 2>/dev/null
            sleep 1
            if alive && [ "$PID" = "$STOP_PID" ]; then kill -9 "$STOP_PID" 2>/dev/null; fi
            sleep 0.1
            if alive && [ "$PID" = "$STOP_PID" ]; then echo 'Core process could not be stopped'; exit 1; fi
        fi
        exit 0
        ;;
    status)
        if alive; then echo "$PID"; exit 0; fi
        exit 1
        ;;
esac
exit 1

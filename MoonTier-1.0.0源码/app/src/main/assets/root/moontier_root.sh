#!/system/bin/sh
PATH=/data/adb/ksu/bin:/data/adb/magisk:/sbin:/system/sbin:/system/bin:/system/xbin:$PATH
export PATH

ACTION="$1"
CORE="$2"
LOG="$3"
PID_FILE="$4"
shift 4

ensure_tun() {
    mkdir -p /dev/net
    if [ ! -e /dev/net/tun ]; then
        if [ -e /dev/tun ]; then
            ln -s /dev/tun /dev/net/tun
        fi
    fi
}

alive() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

case "$ACTION" in
    start)
        ensure_tun
        chmod 755 "$CORE" 2>/dev/null
        if alive; then
            cat "$PID_FILE"
            exit 0
        fi
        rm -f "$PID_FILE"
        cd "$(dirname "$CORE")" || exit 1
        : > "$LOG"
        nohup "$CORE" "$@" >>"$LOG" 2>&1 &
        PID=$!
        echo "$PID" > "$PID_FILE"
        sleep 1
        if kill -0 "$PID" 2>/dev/null; then
            echo "$PID"
            exit 0
        fi
        cat "$LOG" 2>/dev/null
        exit 1
        ;;
    stop)
        if [ -f "$PID_FILE" ]; then
            PID=$(cat "$PID_FILE" 2>/dev/null)
            if [ -n "$PID" ]; then
                kill "$PID" 2>/dev/null
                sleep 1
                kill -9 "$PID" 2>/dev/null
            fi
            rm -f "$PID_FILE"
        fi
        exit 0
        ;;
    status)
        if alive; then
            cat "$PID_FILE"
            exit 0
        fi
        exit 1
        ;;
esac
exit 1

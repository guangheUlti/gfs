#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - systemd foreground supervisor (internal)
#  Started by gfs.service. Brings up the cache service and MySQL, then runs
#  the application watchdog in the foreground. systemd stops the whole
#  control group on `systemctl stop`, which shuts all three down gracefully.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

FORCE=0
STOPPING=0
WATCHDOG_PID=""

ensure_dirs
ensure_runtimes
ensure_configs
check_binaries || exit 1
check_system_libs || exit 1
cd "$PKG_ROOT"

if [ ! -f "$DB_INIT_FLAG" ]; then
    "$SELF_DIR/init.sh" /silent || exit 1
fi

start_redis_bg || exit 1
start_mysql_bg || exit 1

on_term() {
    STOPPING=1
    if [ -n "$WATCHDOG_PID" ]; then
        kill -TERM "$WATCHDOG_PID" 2>/dev/null || true
    fi
}
trap on_term TERM INT

"$SELF_DIR/app-watchdog.sh" &
WATCHDOG_PID=$!
wait "$WATCHDOG_PID"
rc=$?
trap - TERM INT

# The watchdog exits only when the JVM stopped without asking for a restart.
# Tear the children down so the unit restarts from a clean state.
stop_redis || true
stop_mysql || true

if [ "$STOPPING" -eq 1 ]; then
    exit 0
fi
exit "$rc"

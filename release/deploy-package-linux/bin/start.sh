#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  GFS - start the cache service, MySQL and the application with one command
#
#  Usage:  bin/start.sh [port] [fg]
#          port   override the HTTP port (default 80)
#          fg     keep the application in this terminal (Ctrl+C stops it)
#
#  First run unpacks the bundled runtimes and initialises the database.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PORT_ARG="${1:-}"
MODE="${2:-}"
if [ -n "$PORT_ARG" ]; then
    SERVER_PORT="$PORT_ARG"
fi

# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

ensure_dirs
ensure_runtimes
ensure_configs
check_binaries
check_system_libs
cd "$PKG_ROOT"

echo "============================================"
echo " GFS starting"
echo "============================================"
echo " HTTP    : $SERVER_PORT"
echo " MySQL   : $MYSQL_PORT   (data: $DATA_DIR/mysql)"
echo " Redis   : $REDIS_PORT   (valkey)"
echo " Logs    : $LOG_DIR"
echo

# --- first run: initialise the database ------------------------------------
if [ ! -f "$DB_INIT_FLAG" ]; then
    echo "[setup ] Database not initialised yet - running init ..."
    "$SELF_DIR/init.sh" /silent
    echo
fi

# --- application already running? ------------------------------------------
if port_listening "$SERVER_PORT"; then
    PID_OWNER="$(port_pid "$SERVER_PORT")"
    if [ -n "$PID_OWNER" ] && pid_is_ours "$PID_OWNER"; then
        echo "[app   ] already running - port $SERVER_PORT, PID $PID_OWNER"
        exit 0
    fi
    if [ -z "$PID_OWNER" ]; then
        echo "[app   ] FAILED - port $SERVER_PORT is in use (run as root to see the PID)"
    else
        echo "[app   ] FAILED - port $SERVER_PORT is used by an unrelated PID $PID_OWNER"
    fi
    exit 1
fi

# --- cache service + MySQL --------------------------------------------------
start_redis_bg
start_mysql_bg

# --- application ------------------------------------------------------------
if [ "$MODE" = "fg" ]; then
    echo "[app   ] running in foreground - press Ctrl+C to stop."
    exec "$SELF_DIR/app-watchdog.sh"
fi

echo "[app   ] starting ..."
nohup "$SELF_DIR/app-watchdog.sh" >> "$LOG_DIR/watchdog.log" 2>&1 &
if ! wait_port "$SERVER_PORT" 120; then
    echo "[app   ] did not answer within 120s - check $LOG_DIR/gfs.log"
    exit 1
fi
echo "[app   ] up on port $SERVER_PORT"

echo
echo "============================================"
echo " GFS is up:  $FS_FRONTEND_DOMAIN"
echo " Admin login : admin / admin"
echo
echo " Stop everything with:  bin/stop.sh"
echo " Show status with:      bin/status.sh"
echo "============================================"

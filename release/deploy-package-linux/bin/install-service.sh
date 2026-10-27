#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  GFS - install / refresh the systemd service
#
#  Usage:  sudo bin/install-service.sh
#
#  Registers /etc/systemd/system/gfs.service (name from GFS_SERVICE_NAME),
#  enabled for every boot, and starts the stack now. The unit runs
#  bin/service-run.sh as a supervisor: cache service + MySQL are children of
#  the same unit, so one `systemctl stop` shuts everything down gracefully.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

require_root "$@"

if ! command -v systemctl >/dev/null 2>&1; then
    echo "[ERROR] systemctl not found - this host does not use systemd."
    echo "        Use bin/start.sh / bin/stop.sh instead."
    exit 1
fi

ensure_dirs
ensure_runtimes
ensure_configs
check_binaries
check_system_libs

echo "============================================"
echo " GFS service installation"
echo "============================================"
echo " service name : $GFS_SERVICE_NAME"
echo " package root : $PKG_ROOT"
echo " HTTP port    : $SERVER_PORT"
echo "============================================"
echo

# --- the stack has to be initialised before systemd owns it ----------------
if [ ! -f "$DB_INIT_FLAG" ]; then
    echo "[setup] First install - creating the MySQL data directory ..."
    "$SELF_DIR/init.sh" /silent
    echo
fi

# --- refuse to mix with unrelated listeners --------------------------------
for spec in "app:$SERVER_PORT" "mysql:$MYSQL_PORT" "cache:$REDIS_PORT"; do
    label="${spec%%:*}"
    port="${spec##*:}"
    if port_listening "$port"; then
        PID_OWNER="$(port_pid "$port")"
        if [ -z "$PID_OWNER" ] || ! pid_is_ours "$PID_OWNER"; then
            echo "[ERROR] $label port $port is used by PID ${PID_OWNER:-unknown} (not this package)."
            echo "        Stop that process first - the service needs the port."
            exit 1
        fi
    fi
done

# --- replace whatever is running now ---------------------------------------
# Re-running this script on an installed host has to go through systemd, since
# bin/stop.sh refuses to fight an active unit.
if systemctl is-active --quiet "$GFS_SERVICE_NAME" 2>/dev/null; then
    echo "[....] Stopping the running '$GFS_SERVICE_NAME' unit first ..."
    systemctl stop "$GFS_SERVICE_NAME"
elif port_listening "$SERVER_PORT" || port_listening "$MYSQL_PORT" || port_listening "$REDIS_PORT"; then
    echo "[....] Stopping the manually started stack first ..."
    "$SELF_DIR/stop.sh"
    echo
fi

# --- write the unit ---------------------------------------------------------
UNIT_FILE="/etc/systemd/system/$GFS_SERVICE_NAME.service"
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=GFS file management service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory="$PKG_ROOT"
ExecStart="$SELF_DIR/service-run.sh"
Restart=on-failure
RestartSec=5
TimeoutStopSec=120
KillMode=control-group
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$GFS_SERVICE_NAME"
systemctl restart "$GFS_SERVICE_NAME"

echo "[....] Waiting for the application to answer on port $SERVER_PORT ..."
if wait_port "$SERVER_PORT" 150; then
    echo
    echo "============================================"
    echo " Installed and running."
    echo " URL         : $FS_FRONTEND_DOMAIN"
    echo " Admin login : admin / admin"
    echo
    echo " Status      : systemctl status $GFS_SERVICE_NAME"
    echo " Logs        : journalctl -u $GFS_SERVICE_NAME -f"
    echo " Stop        : systemctl stop $GFS_SERVICE_NAME"
    echo " Remove      : sudo bin/uninstall-service.sh"
    echo "============================================"
else
    echo
    echo "[WARN] The service did not answer yet. Check:"
    echo "         journalctl -u $GFS_SERVICE_NAME -n 100 --no-pager"
    echo "         tail -n 100 $LOG_DIR/gfs.log"
    exit 1
fi

#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - remove the systemd service
#
#  Usage:  sudo bin/uninstall-service.sh
#
#  Stops and disables the unit registered by install-service.sh. Data is never
#  touched: data/, storage/ and conf/ stay where they are.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

require_root "$@" || exit 1

if ! command -v systemctl >/dev/null 2>&1; then
    echo "[SKIP] systemctl not found - nothing to remove."
    exit 0
fi

UNIT_FILE="/etc/systemd/system/$GFS_SERVICE_NAME.service"
if [ ! -f "$UNIT_FILE" ]; then
    echo "[SKIP] Service '$GFS_SERVICE_NAME' is not installed."
else
    echo "Removing service '$GFS_SERVICE_NAME' ..."
    systemctl stop "$GFS_SERVICE_NAME" 2>/dev/null || true
    systemctl disable "$GFS_SERVICE_NAME" 2>/dev/null || true
    rm -f "$UNIT_FILE"
    systemctl daemon-reload
    echo "[OK] Removed. It will no longer start at boot."
fi

echo
echo " If the stack is still running you can stop it with:  bin/stop.sh"
echo " Status of everything:                               bin/status.sh"
echo
echo " Data is untouched: $DATA_DIR and $STORAGE_DIR were not modified."

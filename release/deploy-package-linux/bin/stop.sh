#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - stop the application, then the cache service, then MySQL
#
#  Usage:  bin/stop.sh [/force]
#          /force  skip the graceful waits and kill whatever is left
#
#  Only processes that live inside this package are touched - other Java,
#  MySQL or cache instances on the machine are left alone.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

FORCE=0
case "${1:-}" in
    /force|--force|-f) FORCE=1 ;;
esac

# Under systemd the unit would restart whatever this script kills.
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "$GFS_SERVICE_NAME" 2>/dev/null; then
    echo "[app   ] '$GFS_SERVICE_NAME' is managed by systemd - use instead:"
    echo "         sudo systemctl stop $GFS_SERVICE_NAME"
    exit 1
fi

rc=0
stop_app || rc=1
stop_redis || rc=1
stop_mysql || rc=1

echo
if [ "$rc" -eq 0 ]; then
    echo "All GFS services stopped."
else
    echo "Some components were left running - see the messages above."
    echo "Hint: run as root (sudo bin/stop.sh) to manage the whole stack."
fi
exit "$rc"

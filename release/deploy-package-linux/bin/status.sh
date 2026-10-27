#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - show what is running
#  Usage: bin/status.sh
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

echo "============================================"
echo " GFS status"
echo "============================================"
echo " package root : $PKG_ROOT"
echo

echo " -- bundled runtimes --"
if [ -x "$JAVA_EXE" ]; then
    echo "   jdk    : $("$JAVA_EXE" -version 2>&1 | head -n1)"
else
    echo "   jdk    : not extracted yet (first start.sh run unpacks it)"
fi
if [ -x "$MYSQLD_EXE" ]; then
    echo "   mysql  : $("$MYSQLD_EXE" --version 2>&1 | head -n1)"
else
    echo "   mysql  : not extracted yet (first start.sh run unpacks it)"
fi
if [ -x "$REDIS_SERVER" ]; then
    echo "   cache  : $("$REDIS_SERVER" --version 2>&1 | head -n1)"
else
    echo "   cache  : not extracted yet (first start.sh run unpacks it)"
fi
if [ ! -f "$JAR_FILE" ]; then
    echo "   [!] lib/fs-admin.jar is missing"
fi
echo

echo " -- processes --"
if port_listening "$SERVER_PORT"; then
    PID_OWNER="$(port_pid "$SERVER_PORT")"
    echo "   app     : RUNNING  port $SERVER_PORT${PID_OWNER:+  PID $PID_OWNER}"
else
    echo "   app     : stopped  port $SERVER_PORT"
fi
if port_listening "$MYSQL_PORT"; then
    PID_OWNER="$(port_pid "$MYSQL_PORT")"
    echo "   mysql   : RUNNING  port $MYSQL_PORT${PID_OWNER:+  PID $PID_OWNER}"
else
    echo "   mysql   : stopped  port $MYSQL_PORT"
fi
if port_listening "$REDIS_PORT"; then
    PID_OWNER="$(port_pid "$REDIS_PORT")"
    echo "   cache   : RUNNING  port $REDIS_PORT${PID_OWNER:+  PID $PID_OWNER}"
else
    echo "   cache   : stopped  port $REDIS_PORT"
fi
echo

echo " -- data --"
if [ -f "$DB_INIT_FLAG" ]; then
    echo "   database : initialised ($MYSQL_DB)"
else
    echo "   database : NOT initialised - it is created on the first start"
fi
echo "   uploads  : $STORAGE_DIR"
echo

echo " -- systemd service --"
if command -v systemctl >/dev/null 2>&1; then
    UNIT_FILE="/etc/systemd/system/$GFS_SERVICE_NAME.service"
    if [ -f "$UNIT_FILE" ]; then
        echo "   '$GFS_SERVICE_NAME' : installed - $(systemctl is-active "$GFS_SERVICE_NAME" 2>/dev/null || true) - starts at boot"
    else
        echo "   '$GFS_SERVICE_NAME' : not installed - run sudo bin/install-service.sh"
    fi
else
    echo "   systemd not available - use bin/start.sh / bin/stop.sh"
fi
echo
echo " logs: $LOG_DIR"
echo "============================================"
exit 0

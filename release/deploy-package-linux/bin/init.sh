#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  GFS - one-time initialisation
#  Creates a private MySQL data directory inside the package, sets the root
#  password, creates the schema and imports sql/init.sql.
#  Safe to re-run: it exits early once the data directory is initialised.
#
#  Usage:  bin/init.sh [/silent]
#          /silent  non-interactive (start.sh and install-service.sh use this)
#
#  Ports and credentials come from bin/env.sh. Override them for one run with:
#          MYSQL_PORT=3307 bin/init.sh
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

SILENT="${1:-}"
if [ -n "${GFS_QUIET:-}" ]; then
    SILENT=1
fi

SETUP_PID=""

fail() {
    if [ -n "$SETUP_PID" ] && kill -0 "$SETUP_PID" 2>/dev/null; then
        "$MYSQLADMIN" -u root -h 127.0.0.1 --protocol=TCP -P "$MYSQL_PORT" shutdown >/dev/null 2>&1 || true
        wait_pid_free "$SETUP_PID" 30 || kill -KILL "$SETUP_PID" 2>/dev/null || true
    fi
    echo
    echo "Initialization aborted - fix the problem above and run bin/init.sh again."
    exit 1
}

ensure_dirs
ensure_runtimes
ensure_configs
check_binaries || exit 1
check_system_libs || exit 1

echo "============================================"
echo " GFS initialization"
echo "============================================"
echo " MySQL home   : $MYSQL_HOME"
echo " MySQL data   : $DATA_DIR/mysql"
echo " MySQL port   : $MYSQL_PORT"
echo " Database     : $MYSQL_DB"
echo " Redis config : $REDIS_CONF"
echo

if [ -f "$DB_INIT_FLAG" ]; then
    echo "[SKIP] Database already initialised."
    echo "       Delete $DATA_DIR/mysql to start over."
    exit 0
fi

# --- refuse to run against a port something else already owns --------------
if port_listening "$MYSQL_PORT"; then
    PID_OWNER="$(port_pid "$MYSQL_PORT")"
    echo "[ERROR] Port $MYSQL_PORT is already in use${PID_OWNER:+ by PID $PID_OWNER}."
    echo "        Stop that MySQL instance first, or pick another port:"
    echo "            MYSQL_PORT=3307 bin/init.sh"
    exit 1
fi

# --- create the private data directory (bootstrap with empty password) -----
# mysqld refuses to initialise a non-empty directory. A previous attempt that
# failed halfway leaves one behind, so say what to do instead of failing blind.
if [ -n "$(ls -A "$DATA_DIR/mysql" 2>/dev/null)" ]; then
    echo "[ERROR] $DATA_DIR/mysql is not empty, but it was never initialised."
    echo "        A previous attempt probably failed. Remove it and run again:"
    echo "            rm -rf $DATA_DIR/mysql && bin/init.sh"
    exit 1
fi

echo "[1/4] Initialising MySQL data directory ..."
mysql_args
if ! "$MYSQLD_EXE" "${MYSQLD_ARGS[@]}" --initialize-insecure; then
    echo "[ERROR] mysqld --initialize-insecure failed. See $LOG_DIR/mysql.log"
    fail
fi

# --- boot the server temporarily -------------------------------------------
echo "[2/4] Starting MySQL for setup ..."
nohup "$MYSQLD_EXE" "${MYSQLD_ARGS[@]}" >> "$LOG_DIR/mysql-console.log" 2>&1 &
SETUP_PID=$!
if ! wait_port "$MYSQL_PORT" 60; then
    echo "[ERROR] MySQL did not come up within 60s. See $LOG_DIR/mysql.log"
    fail
fi

# --- set the root password and create the database -------------------------
echo "[3/4] Setting root password, creating database '$MYSQL_DB' ..."
# 127.0.0.1 gets its own row: the application and every client call below use
# TCP, and MySQL matches such a connection as 'localhost' only when reverse DNS
# resolves the address to "localhost" - not guaranteed on every host.
if ! "$MYSQL_CLI" -u root --protocol=TCP -P "$MYSQL_PORT" \
    -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';
        CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$MYSQL_PASSWORD';
        GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
        FLUSH PRIVILEGES;"; then
    echo "[ERROR] Could not set the MySQL root password."
    fail
fi
if ! "$MYSQL_CLI" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" --protocol=TCP -P "$MYSQL_PORT" \
    -e "CREATE DATABASE IF NOT EXISTS \`$MYSQL_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"; then
    echo "[ERROR] Could not create database $MYSQL_DB."
    fail
fi

# --- import schema + seed data ---------------------------------------------
echo "[4/4] Importing $SQL_DIR/init.sql ..."
if ! "$MYSQL_CLI" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" --protocol=TCP -P "$MYSQL_PORT" \
    "$MYSQL_DB" < "$SQL_DIR/init.sql"; then
    echo "[ERROR] SQL import failed."
    fail
fi

# --- stop the temporary server ---------------------------------------------
# The next start must not race this process: the port is released before
# mysqld lets go of the InnoDB file locks, so wait on the PID as well.
"$MYSQLADMIN" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -h 127.0.0.1 \
    --protocol=TCP -P "$MYSQL_PORT" shutdown >/dev/null 2>&1 || true
if ! wait_pid_free "$SETUP_PID" 60; then
    kill -KILL "$SETUP_PID" 2>/dev/null || true
fi
wait_port_free "$MYSQL_PORT" 15 || true
SETUP_PID=""
printf 'initialized\n' > "$DB_INIT_FLAG"

echo
echo "============================================"
echo " Initialization complete."
echo " Admin login : admin / admin"
if [ -z "$SILENT" ]; then
    echo " Next step   : bin/start.sh"
fi
echo "============================================"

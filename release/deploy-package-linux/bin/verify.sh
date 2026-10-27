#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - pre-flight and health check
#
#  Usage:  bin/verify.sh
#
#  Run it before the first start - it reports whether the package is complete,
#  whether the host meets the runtime requirements, how much room the disk has
#  and whether the three ports are free - and run it afterwards to have the
#  cache service pinged, MySQL queried and the home page fetched over HTTP.
#  It starts nothing, extracts no runtime archive and writes nothing but the
#  two generated config files.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

# the two generated config files are part of the checklist, so create them
# first (they are written by the same function the start scripts use)
ensure_configs >/dev/null

PASS=0
WARN=0
FAIL=0

ok()   { echo "   [OK]   $*"; PASS=$((PASS + 1)); }
warn() { echo "   [ ]    $*"; WARN=$((WARN + 1)); }
bad()  { echo "   [FAIL] $*"; FAIL=$((FAIL + 1)); }

check_file() {
    local path="$1" label="$2"
    if [ -f "$path" ]; then
        ok "$label ($(wc -c < "$path") bytes)"
    else
        bad "$label  MISSING"
    fi
}

check_runtime() {
    local name="$1" archive="$2" binary="$3"
    if [ -x "$binary" ]; then
        ok "lib/$name  (extracted)"
    elif [ -f "$archive" ]; then
        ok "lib/runtime/$(basename "$archive")  (unpacks on first start)"
    else
        bad "lib/$name  MISSING (neither extracted nor lib/runtime/$(basename "$archive"))"
    fi
}

echo "============================================================"
echo " GFS verification"
echo "============================================================"
echo " package root : $PKG_ROOT"
echo " ports        : app $SERVER_PORT / mysql $MYSQL_PORT / redis $REDIS_PORT"
echo

# ----------------------------------------------------------------------------
echo " -- package contents --"
check_runtime jdk   "$RUNTIME_DIR/jdk.tar.gz"      "$JAVA_EXE"
check_runtime mysql "$RUNTIME_DIR/mysql.tar.xz"    "$MYSQLD_EXE"
check_runtime redis "$RUNTIME_DIR/valkey.tar.gz"   "$REDIS_SERVER"
check_file "$JAR_FILE"                         "lib/fs-admin.jar"
check_file "$SQL_DIR/init.sql"                 "sql/init.sql"
check_file "$CONF_DIR/application.yml"         "conf/application.yml"
check_file "$CONF_DIR/application-prod.yml"    "conf/application-prod.yml"
check_file "$MY_CNF"                           "conf/my.cnf             - generated"
check_file "$REDIS_CONF"                       "conf/redis.conf         - generated"
echo

# ----------------------------------------------------------------------------
echo " -- host requirements --"
if [ "$(uname -m)" = "x86_64" ]; then
    ok "architecture: x86_64"
else
    bad "architecture: $(uname -m) - this package only ships x86_64 runtimes"
fi

if command -v ldd >/dev/null 2>&1; then
    GLIBC_VER="$(ldd --version 2>/dev/null | head -n1 | grep -oE '[0-9]+\.[0-9]+$')"
    if [ -n "$GLIBC_VER" ]; then
        if awk -v v="$GLIBC_VER" 'BEGIN{split(v,a,".");exit !((a[1]>2)||(a[1]==2&&a[2]>=34))}'; then
            ok "glibc $GLIBC_VER (>= 2.34)"
        else
            bad "glibc $GLIBC_VER - the bundled cache service needs >= 2.34"
        fi
    else
        warn "glibc version could not be detected"
    fi
else
    warn "ldd not found - cannot verify glibc"
fi

if command -v ldconfig >/dev/null 2>&1; then
    for lib in libssl.so.3 libcrypto.so.3 libsystemd.so.0 libaio.so.1 libnuma.so.1; do
        if ldconfig -p 2>/dev/null | grep -q "$lib"; then
            ok "system library: $lib"
        else
            case "$lib" in
                libaio.so.1|libnuma.so.1) bad "system library: $lib (apt install libaio1 libnuma1 / dnf install libaio numactl-libs)" ;;
                *) bad "system library: $lib (apt install libssl3 libsystemd0 / dnf install openssl-libs systemd-libs)" ;;
            esac
        fi
    done
else
    warn "ldconfig not found - cannot verify OpenSSL / libaio / libnuma"
fi

if command -v xz >/dev/null 2>&1; then
    ok "xz available (needed to unpack MySQL)"
else
    bad "xz missing (apt install xz-utils / dnf install xz)"
fi
echo

# ----------------------------------------------------------------------------
echo " -- versions --"
if [ -x "$JAVA_EXE" ]; then
    ok "jdk: $("$JAVA_EXE" -version 2>&1 | head -n1)"
else
    warn "jdk: not extracted yet"
fi
if [ -x "$MYSQLD_EXE" ]; then
    ok "mysql: $("$MYSQLD_EXE" --version 2>&1 | head -n1)"
else
    warn "mysql: not extracted yet"
fi
if [ -x "$REDIS_SERVER" ]; then
    ok "cache: $("$REDIS_SERVER" --version 2>&1 | head -n1)"
else
    warn "cache: not extracted yet"
fi
if [ -f "$DB_INIT_FLAG" ]; then
    ok "schema: database initialised"
else
    warn "schema: not initialised - the first start does it"
fi
echo

# ----------------------------------------------------------------------------
echo " -- ports --"
for port in "$SERVER_PORT" "$MYSQL_PORT" "$REDIS_PORT"; do
    if port_listening "$port"; then
        PID_OWNER="$(port_pid "$port")"
        if [ -n "$PID_OWNER" ] && pid_is_ours "$PID_OWNER"; then
            ok "port $port: used by this package (PID $PID_OWNER)"
        else
            warn "port $port: used by PID ${PID_OWNER:-unknown}"
        fi
    else
        ok "port $port: free"
    fi
done
echo

# ----------------------------------------------------------------------------
echo " -- free space for data & uploads --"
FREE_GB="$(df -Pk "$PKG_ROOT" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1024/1024}')"
if [ -z "$FREE_GB" ]; then
    warn "free space: could not be read - check it manually"
elif [ "$FREE_GB" -lt 5 ]; then
    warn "free space: ${FREE_GB} GB - runtimes need ~1.5 GB and uploads grow from there"
else
    ok "free space: ${FREE_GB} GB"
fi
echo

# ----------------------------------------------------------------------------
echo " -- running services --"
if [ -x "$REDIS_CLI" ] && port_listening "$REDIS_PORT"; then
    if [ -n "$REDIS_PASSWORD" ]; then
        REDIS_REPLY="$("$REDIS_CLI" -h 127.0.0.1 -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null || true)"
    else
        REDIS_REPLY="$("$REDIS_CLI" -h 127.0.0.1 -p "$REDIS_PORT" ping 2>/dev/null || true)"
    fi
    if [ "$REDIS_REPLY" = "PONG" ]; then
        ok "cache: replies on $REDIS_PORT"
    else
        warn "cache: listening on $REDIS_PORT but did not answer PING"
    fi
else
    warn "cache: not answering on $REDIS_PORT"
fi

if [ -x "$MYSQL_CLI" ] && port_listening "$MYSQL_PORT"; then
    TABLES="$("$MYSQL_CLI" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -h 127.0.0.1 -P "$MYSQL_PORT" \
        --protocol=TCP -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=database()" \
        "$MYSQL_DB" 2>/dev/null || true)"
    if [ -n "$TABLES" ]; then
        ok "mysql: replies on $MYSQL_PORT, database $MYSQL_DB has $TABLES tables"
    else
        warn "mysql: not answering on $MYSQL_PORT"
    fi
else
    warn "mysql: not answering on $MYSQL_PORT"
fi

if port_listening "$SERVER_PORT" && command -v curl >/dev/null 2>&1; then
    HTTP_CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$SERVER_PORT/" 2>/dev/null || true)"
    if [ "$HTTP_CODE" = "200" ]; then
        ok "app: GET / on $SERVER_PORT -> 200"
    else
        warn "app: GET / on $SERVER_PORT -> ${HTTP_CODE:-no response}"
    fi
else
    warn "app: not listening on $SERVER_PORT"
fi

if command -v systemctl >/dev/null 2>&1 && [ -f "/etc/systemd/system/$GFS_SERVICE_NAME.service" ]; then
    ok "systemd: service '$GFS_SERVICE_NAME' installed"
else
    warn "systemd: service '$GFS_SERVICE_NAME' not installed - run sudo bin/install-service.sh"
fi
echo

# ----------------------------------------------------------------------------
echo "============================================================"
echo " $PASS checks passed, $WARN to be aware of, $FAIL missing"
if [ "$FAIL" -ne 0 ]; then
    echo " The package or host is incomplete - see the [FAIL] lines above."
else
    echo " Package is complete. Next: bin/start.sh   (then re-run this script)"
fi
echo "============================================================"
exit "$FAIL"

#!/usr/bin/env bash
set -uo pipefail

# ============================================================================
#  GFS - application watchdog (internal)
#  Runs the JVM in the foreground and re-launches it when the application
#  writes conf/jvm-restart.flag after a memory limit change. start.sh uses it
#  in the background; systemd runs it through service-run.sh.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
. "$SELF_DIR/env.sh"

mkdir -p "$LOG_DIR"
cd "$PKG_ROOT"
spring_args

while :; do
    # consume any flag left from a previous cycle, so a normal stop is never
    # seen as a restart request
    rm -f "$GFS_JVM_FLAG"
    read_xmx "$GFS_JVM_CONF"
    EFFECTIVE_OPTS="${JVM_MEM_OPTS:-$JAVA_OPTS}"
    echo "[app   ] $(date '+%F %T') starting - memory opts: $EFFECTIVE_OPTS"

    # shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
    if [ -t 1 ]; then
        "$JAVA_EXE" $EFFECTIVE_OPTS -jar "$JAR_FILE" "${SPRING_ARGS[@]}"
    else
        "$JAVA_EXE" $EFFECTIVE_OPTS -jar "$JAR_FILE" "${SPRING_ARGS[@]}" \
            >> "$LOG_DIR/gfs.log" 2>&1
    fi
    rc=$?
    echo "[app   ] $(date '+%F %T') exited with code $rc"

    if [ -f "$GFS_JVM_FLAG" ]; then
        echo "[app   ] restart flag found - restarting with the new memory setting"
        continue
    fi
    exit "$rc"
done

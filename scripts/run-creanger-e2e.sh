#!/usr/bin/env bash
#
# run-creanger-e2e.sh — reproducible E2E run of CreangerRealServerE2ETest
# against a live Creanger Auth Server.
#
# Lifecycle:
#   1. Ensure the auth server is running (start it if not already).
#   2. Wait for its /healthz endpoint.
#   3. Run the Gradle task `:TMessagesProj:testCreangerE2E`.
#   4. Tear down any server WE started (leaves an externally-running
#      server untouched).
#
# The E2E Java test self-skips (JUnit Assume) when the server is not
# reachable, so a broken server should surface as a task failure, not a
# silent "pass".
#
# Usage:
#   scripts/run-creanger-e2e.sh [--server-dir <path>] [--port <port>]
#
# Env overrides: CREANGER_SERVER_DIR, CREANGER_PORT, GRADLE_TASK
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
ANDROID_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

SERVER_DIR="${CREANGER_SERVER_DIR:-}"
PORT="${CREANGER_PORT:-3000}"
GRADLE_TASK="${GRADLE_TASK:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-dir) SERVER_DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --task) GRADLE_TASK="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: scripts/run-creanger-e2e.sh [--server-dir <path>] [--port <port>] [--task <gradle-task>]"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

BASE_URL="http://localhost:${PORT}"

# If no server dir given, fail with a clear message rather than guessing.
if [[ -z "$SERVER_DIR" ]]; then
  echo "ERROR: auth server directory not specified." >&2
  echo "  Pass --server-dir <path> or set CREANGER_SERVER_DIR." >&2
  exit 2
fi
if [[ ! -d "$SERVER_DIR" ]]; then
  echo "ERROR: auth server directory does not exist: $SERVER_DIR" >&2
  exit 2
fi

log() { printf '[run-creanger-e2e] %s\n' "$*"; }

server_running() {
  curl -sf -o /dev/null "$BASE_URL/healthz" &>/dev/null
}

start_server() {
  log "starting auth server (port $PORT)"
  (cd "$SERVER_DIR" && npm run dev >/tmp/creanger-e2e-server.log 2>&1 & disown)
}

wait_for_server() {
  log "waiting for $BASE_URL/healthz"
  for _ in $(seq 1 60); do
    if server_running; then
      log "auth server is up"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: auth server did not become healthy within 60s (see /tmp/creanger-e2e-server.log)" >&2
  exit 1
}

WE_STARTED=false
if server_running; then
  log "auth server already running at $BASE_URL"
else
  if [[ ! -d "$SERVER_DIR/node_modules" ]]; then
    log "installing auth server dependencies (npm ci)"
    (cd "$SERVER_DIR" && npm ci)
  fi
  start_server
  WE_STARTED=true
  wait_for_server
fi

cleanup() {
  if [[ "$WE_STARTED" == true ]]; then
    log "stopping the auth server we started"
    # Match the `npm run dev` process tree we spawned (tsx watch child).
    pkill -f "src/server.ts" 2>/dev/null || true
  fi
}
trap cleanup EXIT

LOG_FILE="/tmp/creanger-e2e-server.log"
if [[ "$WE_STARTED" == true ]]; then
  log "server log: $LOG_FILE"
fi

cd "$ANDROID_DIR"
TASK="${GRADLE_TASK:-:TMessagesProj:testCreangerE2E}"
log "running: ./gradlew $TASK"
./gradlew "$TASK"
log "E2E task finished (exit $?)"
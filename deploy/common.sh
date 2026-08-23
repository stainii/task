#!/bin/bash
# Shared by deploy.sh, backup.sh and restore.sh. Sourced, never executed.
#
# It exists so that "which compose file, which env file, where the archives go, which Postgres
# image" has one answer rather than three that agree today.

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
COMPOSE_FILE="$DEPLOY_DIR/compose.yaml"
ENV_FILE="${TASK_ENV_FILE:-$DEPLOY_DIR/production.env}"

# Under /home/stijn on purpose: backup-server.sh on the MacBook zips that directory weekly, so
# ADR-0008's third copy — the cold one, that survives a compromised box — costs nothing extra.
ARCHIVE_DIR="${TASK_ARCHIVE_DIR:-$HOME/task-backups}"
LOG_FILE="$ARCHIVE_DIR/backup.log"

# ADR-0008: 7 here, 30 in the cloud. Anything broken and unnoticed for a month is unrecoverable, and
# that is written down rather than discovered.
LOCAL_KEEP_DAYS=7
CLOUD_KEEP_DAYS=30
RCLONE_REMOTE="${TASK_RCLONE_REMOTE:-google-drive:task-backups}"

say() {
    printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
    printf '%s  FAILED: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
    exit 1
}

require_env_file() {
    [ -f "$ENV_FILE" ] || die "No $ENV_FILE. Copy deploy/production.example.env to it and fill it in."
    # ADR-0010: this file carries the tunnel credential and the VAPID private key.
    #
    # Both stat flavours, because ADR-0008's restore drill happens ON THE LAPTOP — "on a machine
    # that is not the server" is the pass criterion — so these scripts have to run on macOS too.
    local mode
    mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")"
    [ "$mode" = "600" ] || die "$ENV_FILE is mode $mode, not 600. chmod 600 it."
}

# Reads one value out of the env file without sourcing it, so a stray line cannot execute.
env_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

compose() {
    docker compose --file "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

# Compose derives volume names from the project name, which this stack fixes with `name:` rather
# than leaving to the directory it happens to be cloned into. restore.sh needs the data volume by
# name, and guessing it from $PWD is how a restore silently rebuilds a different stack's database.
project_name() {
    sed -n 's/^name:[[:space:]]*//p' "$COMPOSE_FILE" | head -n 1
}

# The Postgres image is written once, in deploy/compose.yaml, and read from there by everything that
# needs a throwaway instance. A second literal in a script is how the verification restore ends up
# proving that a dump loads into a version the stack no longer runs.
postgres_image() {
    sed -n 's/^[[:space:]]*image:[[:space:]]*\(postgres:.*\)$/\1/p' "$COMPOSE_FILE" | head -n 1
}

# Waiting for a freshly created Postgres container is not "wait until pg_isready says yes".
#
# The image's entrypoint runs initdb against a TEMPORARY server that listens on the unix socket
# only, runs the init scripts, and then SHUTS IT DOWN and starts the real one. `docker exec
# pg_isready` talks over that socket, so it answers yes during the temporary phase — and a restore
# started there is cut off mid-stream with "terminating connection due to administrator command".
# Found by drilling; the failure looks like a corrupt dump, which is the worst possible false
# accusation for this script to make.
#
# Asking over TCP is what distinguishes them: the temporary server does not listen on it.
wait_for_postgres() {
    local container="$1" user="$2" waited=0
    until docker exec "$container" pg_isready --host 127.0.0.1 --username "$user" --quiet; do
        waited=$((waited + 1))
        [ "$waited" -lt 90 ] || die "$container never finished starting"
        sleep 1
    done
}

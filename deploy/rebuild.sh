#!/bin/bash
# Rebuilding the box from nothing (#24, ADR-0008).
#
#     deploy/rebuild.sh <archive.zip>
#
# ADR-0008 refused a disk image and an Ansible playbook, on the grounds that a large moving part kept
# for an event that may never happen spends its life stale. That argument is about *size*, and it
# does not reach this file: everything here already exists and is exercised — `restore.sh` runs every
# night inside `backup.sh`, and the checks below are the same ones the deploy makes. What this adds
# is that they happen in the right ORDER, and that what is missing is said OUT LOUD at the moment you
# are standing in front of a bare machine, rather than discovered one step at a time.
#
# WHAT THIS CANNOT DO, and why the manual still has a step 1 and a step 2:
#
#   - install Docker on a bare machine (root, network, and a distribution's opinions);
#   - fetch the archive. You have no box, so its rclone credential is gone with it — the archive
#     comes from a browser logged into Drive, or from ~/server_backup_<date>.zip on the laptop.
#
# And one thing it deliberately does not silently fix: the **rclone configuration**, which is NOT in
# the archive. It is an OAuth credential for the whole of Google Drive, and putting it inside the
# Drive it unlocks would mean anyone holding one backup holds the account. It lives in the laptop's
# weekly zip instead. This script tells you when it is absent, because otherwise a rebuilt box runs
# perfectly and simply stops having backups.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

main() {
    local archive="${1:-}"
    [ -n "$archive" ] || { echo "usage: deploy/rebuild.sh <archive.zip>" >&2; exit 64; }
    [ -f "$archive" ] || die "no such archive: $archive"

    say "rebuild: checking what this machine has"
    check_tools
    check_archive "$archive"

    restore_env "$archive"
    warn_about_rclone

    say "rebuild: restoring the data, the realm and the epoch"
    TASK_RESTORE_ASSUME_YES=yes "$DEPLOY_DIR/restore.sh" live "$archive"

    install_timer
    verify

    cat <<EOF

  Rebuilt. What is running: the app, its database, Keycloak with your realm and users, and the
  tunnel — the token came back with production.env, which is why there is nothing to reissue.

  Left to you, and only these:
$(rclone_todo)    - open the app and log in. That is the only proof that counts (ADR-0008).

EOF
}

check_tools() {
    local missing=()
    for tool in docker git unzip zip; do
        command -v "$tool" >/dev/null || missing+=("$tool")
    done
    docker info >/dev/null 2>&1 || missing+=("a running Docker daemon")
    [ ${#missing[@]} -eq 0 ] || die "this machine is missing: ${missing[*]}"
    say "rebuild: docker, git, zip, unzip — all present"
}

# The archive is three things (ADR-0008) and a rebuild needs all three. Finding out which one is
# absent now beats finding out after the cluster has been discarded.
check_archive() {
    local archive="$1" name
    for name in dump.sql.gz compose.yaml production.env; do
        unzip -l "$archive" "$name" 2>/dev/null | grep -q "$name" \
            || die "$archive has no $name in it. This is not a complete archive — try another."
    done
    say "rebuild: the archive holds the dump, the compose file and production.env"
}

# production.env is the only place production configuration exists, and the archive is the only place
# a rebuilt box can get it. Never silently overwritten: on a machine that already has one, that file
# is more likely to be the right one than the archive's.
restore_env() {
    local archive="$1"
    if [ -f "$ENV_FILE" ]; then
        say "rebuild: keeping the $ENV_FILE already here (the archive's copy is NOT being used)"
    else
        # Unpacked to a temporary directory and then moved, because ENV_FILE is not necessarily
        # deploy/production.env — the drill on the laptop points it elsewhere, and unzipping to a
        # fixed path would restore the file to somewhere nothing reads.
        local unpacked
        unpacked="$(mktemp -d)"
        unzip -o -q -j "$archive" production.env -d "$unpacked"
        mkdir -p "$(dirname "$ENV_FILE")"
        mv "$unpacked/production.env" "$ENV_FILE"
        rmdir "$unpacked"
        chmod 600 "$ENV_FILE"
        say "rebuild: production.env restored from the archive, mode 600"
    fi
    require_env_file
    check_env_complete
}

warn_about_rclone() {
    [ -n "$(rclone_todo)" ] || return 0
    say "rebuild: WARNING — no rclone configuration at $RCLONE_CONFIG."
    say "         The app will run. Backups will not, from tonight onwards."
}

rclone_todo() {
    command -v rclone >/dev/null 2>&1 && return 0
    [ -f "$RCLONE_CONFIG" ] && return 0
    cat <<EOF
    - put the rclone configuration back at $RCLONE_CONFIG, from the laptop's
      server_backup_<date>.zip (home/stijn/rclone/config/rclone.conf). It is deliberately not in
      this archive: it unlocks the whole Drive, including this archive. Until it is back, the
      nightly backup fails — loudly, in $LOG_FILE, but only at 02:30.
EOF
}

install_timer() {
    if systemctl list-unit-files task-deploy.timer >/dev/null 2>&1 \
        && systemctl is-enabled task-deploy.timer >/dev/null 2>&1; then
        say "rebuild: the deploy timer is already installed"
        return 0
    fi
    if sudo -n true 2>/dev/null; then
        sudo systemctl link "$DEPLOY_DIR/systemd/task-deploy.service" >/dev/null
        sudo systemctl enable --now "$DEPLOY_DIR/systemd/task-deploy.timer" >/dev/null
        say "rebuild: deploy timer linked and enabled"
    else
        say "rebuild: the timer needs root. Run:"
        say "           sudo systemctl link $DEPLOY_DIR/systemd/task-deploy.service"
        say "           sudo systemctl enable --now $DEPLOY_DIR/systemd/task-deploy.timer"
    fi
}

verify() {
    say "rebuild: waiting for the application"
    local waited=0
    until [ "$(docker inspect --format '{{.State.Health.Status}}' task-back-end-1 2>/dev/null)" = "healthy" ]; do
        waited=$((waited + 5))
        [ "$waited" -lt 300 ] || die "the back-end never became healthy. Its logs: docker logs task-back-end-1"
        sleep 5
    done
    say "rebuild: back-end healthy; $(docker run --rm --network "$(project_name)_default" \
        curlimages/curl:latest --silent http://front-end/api/config)"
}

main "$@"

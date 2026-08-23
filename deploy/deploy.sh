#!/bin/bash
# The deploy (#24, ADR-0007). Runs on the box, from a timer, at night.
#
#     back up (and prove it)  ->  git pull  ->  compose pull  ->  compose up -d
#
# NOTHING REACHES INTO THIS MACHINE. GitHub holds no credential to the house, and because the repo
# is public the GHCR package is public too, so the box holds no registry credential either — zero
# secrets on the deploy path in both directions. That asymmetry is worth more here than on a normal
# project: #31 knowingly accepted a public repo whose issue tracker catalogues this system's
# defects, and a leaked Actions secret granting shell on the machine holding years of real personal
# data is the one failure that would make that trade look bad in hindsight.
#
# ONE UNIT, NOT TWO TIMERS. A backup timer at 02:00 and a deploy timer at 02:15 is a race dressed as
# a sequence: the night pg_dumpall runs long, the deploy starts mid-dump and migrates against a
# backup that does not exist yet. Flyway runs automatically at startup, and the dump immediately
# before it in the same script is the entire reason that is safe.
#
# THIS SCRIPT IS UPDATED BY THE `git pull` IT PERFORMS. Bash reads a script incrementally, so a
# pull that changes the lines below while they are still unread would run a spliced-together file.
# Everything is inside functions, called on the last line, which forces bash to read the whole file
# before executing any of it. Do not move work to the top level.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

DEPLOY_LOG="$ARCHIVE_DIR/deploy.log"
LOCK_FILE="/tmp/task-deploy.lock"

main() {
    mkdir -p "$ARCHIVE_DIR"
    exec > >(tee -a "$DEPLOY_LOG") 2>&1

    # A manual run at the wrong moment must not collide with the timer's.
    exec 9>"$LOCK_FILE"
    flock --nonblock 9 || die "another deploy is already running"

    require_env_file
    say "deploy: starting"

    # ADR-0007: better to skip a night than to migrate unbacked. A broken backup therefore blocks
    # deploys, which is the correct priority — and a silent stall unless someone reads the log, which
    # is what ADR-0008's recurring "check the backup" task is for.
    "$DEPLOY_DIR/backup.sh" || die "the backup did not pass. Nothing was deployed."

    say "deploy: git pull"
    git -C "$REPO_DIR" pull --ff-only

    local version
    version="$(target_version)"
    if [ -n "$(env_value TASK_VERSION)" ]; then
        say "deploy: PINNED to $version by production.env — the nightly deploy is not advancing."
    fi
    say "deploy: version $version"

    say "deploy: pulling images"
    # If CI is still running, or `main` is red, these tags do not exist yet. Failing here leaves the
    # running stack exactly as it was, which is the right outcome: a deploy that half-happens is
    # worse than one that does not.
    TASK_VERSION="$version" compose pull --quiet back-end front-end \
        || die "no published images for $version yet (CI still running, or main is red).
     The running stack is untouched; tonight simply deploys nothing."

    say "deploy: up"
    TASK_VERSION="$version" compose up --detach --remove-orphans

    report_health
    say "deploy: done"
}

# ADR-0009 gave "did the deploy actually happen" to the app itself — a pull-based deploy has no
# acknowledgement by construction, and the front-end compares its own build date against the
# back-end's. This is not that. It is the local record, so that a morning spent wondering starts from
# a log line rather than from nothing.
report_health() {
    local waited=0 state
    while [ "$waited" -lt 180 ]; do
        state="$(docker inspect --format '{{.State.Health.Status}}' task-back-end-1 2>/dev/null || echo unknown)"
        case "$state" in
            healthy) say "deploy: back-end is healthy"; return 0 ;;
            unhealthy) die "the back-end came up unhealthy. It is running the new version and failing." ;;
        esac
        waited=$((waited + 5))
        sleep 5
    done
    die "the back-end did not report healthy within 180s (last state: $state)"
}

main "$@"

#!/bin/bash
# The nightly backup (#24, ADR-0008), and the first half of the deploy unit (ADR-0007).
#
#     dump  ->  restore it into a throwaway Postgres and assert something real  ->  archive  ->
#     push to the cloud  ->  prune
#
# EVERY BACKUP RESTORES ITSELF BEFORE IT IS KEPT. That is the one decision here that buys more than
# it costs. A glance at a directory catches an ABSENT backup; nothing catches a backup that is
# present and useless — a truncated dump, a pg_dumpall that wrote a header and hit a full disk, an
# archive that zips cleanly around a broken .sql. Those look exactly like success from outside and
# are discovered on the one evening they matter. So the restore path, which is the mechanism most
# likely to be broken when needed and least likely to be exercised, runs every night on a box with
# nothing else to do.
#
# AND IT CAN SAY NO. The script it replaces logged `echo "Backup completed"` unconditionally, with
# no set -e and no status check anywhere — so for years it reported success while archiving zero
# databases (ADR-0008 has the forensics). `set -euo pipefail` plus the trap below is the whole
# difference between a check and a ritual.
#
# Run by deploy.sh before it touches anything, and abortive: a failure here stops the deploy, which
# is the correct priority. Detection is a recurring task named "check the backup" (ADR-0008), so the
# artifact this writes is meant to be READ — that is what LOG_FILE is for.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

VERIFY_CONTAINER=task-backup-verify

main() {
    require_env_file
    mkdir -p "$ARCHIVE_DIR"
    exec > >(tee -a "$LOG_FILE") 2>&1

    local stamp archive staging
    stamp="$(date -u '+%Y-%m-%dT%H%M%SZ')"
    staging="$ARCHIVE_DIR/.staging-$stamp"
    archive="$ARCHIVE_DIR/task-backup-$stamp.zip"

    trap 'cleanup' EXIT
    mkdir -p "$staging"
    STAGING="$staging"

    say "backup: starting ($stamp)"

    dump "$staging/dump.sql.gz"
    verify "$staging/dump.sql.gz"
    package "$staging" "$archive"
    upload "$archive"
    prune

    say "backup: OK -> $archive ($(du -h "$archive" | cut -f1))"
}

cleanup() {
    local status=$?
    docker rm --force "$VERIFY_CONTAINER" >/dev/null 2>&1 || true
    [ -n "${STAGING:-}" ] && rm -rf "$STAGING"
    if [ "$status" -ne 0 ]; then
        say "backup: FAILED (exit $status). The deploy will not run, and nothing was uploaded."
    fi
    return "$status"
}

# One pg_dumpall, not a copy of PGDATA. A file copy only restores into the same major Postgres — and
# Renovate proposes image bumps — so the day the image goes to 19, every file backup ever taken
# becomes unrestorable, discovered at the moment one is needed. This also covers Keycloak's realm,
# which lives in its own database in the same instance (ADR-0008).
dump() {
    local target="$1"
    local user
    user="$(env_value POSTGRES_USER)"
    [ -n "$user" ] || die "POSTGRES_USER is not set in $ENV_FILE"

    # A stack that is not running is not a backup that failed; it is a machine in a state nobody
    # meant it to be in. Saying which one it is here saves reading a compose error at breakfast.
    compose ps --status running --services 2>/dev/null | grep -qx postgres \
        || die "the postgres container is not running, so there is nothing to dump. Bring the stack
     up first (docker compose --file deploy/compose.yaml --env-file deploy/production.env up -d).
     On a box being rebuilt from nothing, see docs/operating-manual.md."

    say "dump: pg_dumpall as '$user'"
    # No pipefail escape hatch: if pg_dumpall fails, the gzip of its partial output must not be
    # mistaken for a backup.
    compose exec -T postgres pg_dumpall --username "$user" --clean --if-exists \
        | gzip --best > "$target"

    gzip --test "$target" || die "the dump is not a complete gzip stream"
    say "dump: $(du -h "$target" | cut -f1)"
}

# The heart of ADR-0008: prove the dump by loading it, not by looking at it.
verify() {
    local dump_file="$1"
    local image database
    image="$(postgres_image)"
    database="$(env_value POSTGRES_DB)"

    say "verify: restoring into a throwaway $image"
    docker rm --force "$VERIFY_CONTAINER" >/dev/null 2>&1 || true
    docker run --detach --name "$VERIFY_CONTAINER" \
        --env POSTGRES_PASSWORD="verify-$RANDOM$RANDOM" \
        "$image" >/dev/null

    wait_for_postgres "$VERIFY_CONTAINER" postgres

    gunzip --stdout "$dump_file" \
        | docker exec --interactive "$VERIFY_CONTAINER" psql --username postgres --quiet \
              --set ON_ERROR_STOP=1 --dbname postgres >/dev/null \
        || die "the dump did not restore. This backup is being thrown away, which is the point."

    local tasks patches
    tasks="$(count "$database" task)"
    patches="$(count "$database" task_patch)"
    say "verify: restored $tasks tasks and $patches patches"

    compare_with_yesterday "$tasks" "$patches"
}

count() {
    docker exec "$VERIFY_CONTAINER" psql --username postgres --dbname "$1" --tuples-only \
        --no-align --command "SELECT count(*) FROM $2" \
        || die "table '$2' is missing from the restored database"
}

# ADR-0008 asks for "non-zero and within sight of yesterday's". The non-zero half cannot be asserted
# yet and saying so beats writing a check that is switched off: until #17's cutover imports the real
# history, this database is legitimately empty, and a hard non-zero rule would block every deploy
# until then — the shape of gate that gets commented out and never restored.
#
# So: a drop of more than 10% against the last recorded run is a failure, and the floor arms itself
# the moment there is data to protect. Growth is never suspicious; an append-only patch log does not
# shrink on its own.
compare_with_yesterday() {
    local tasks="$1" patches="$2"
    local state="$ARCHIVE_DIR/counts.state"

    if [ -f "$state" ]; then
        local previous_tasks previous_patches
        read -r previous_tasks previous_patches < "$state"
        check_drop task "$tasks" "$previous_tasks"
        check_drop patch "$patches" "$previous_patches"
    fi

    if [ "$tasks" -eq 0 ] && [ "$patches" -eq 0 ]; then
        say "verify: the database is still empty. Nothing to compare until #17 imports the history."
    fi

    printf '%s %s\n' "$tasks" "$patches" > "$state"
}

check_drop() {
    local what="$1" now="$2" before="$3"
    [ "$before" -gt 0 ] || return 0
    if [ $((now * 10)) -lt $((before * 9)) ]; then
        die "the ${what} count fell from $before to $now. That is not a backup failure by itself, but
     it is exactly what a half-written dump looks like, so this archive is not being kept.
     If the drop is real (a restore, a deliberate deletion), edit $ARCHIVE_DIR/counts.state."
    fi
}

# ADR-0008: the archive is three things and there is no fourth. `task` has no uploaded files —
# photos and notes died with portal-social — so the whole thing is text, in the low megabytes.
package() {
    local staging="$1" archive="$2"
    cp "$COMPOSE_FILE" "$staging/compose.yaml"
    # The only place production configuration exists: the tunnel credential, the VAPID pair, the
    # database password. It travels with the rest deliberately — splitting the archive to protect
    # one file would trade "one download, one restore" for very little.
    cp "$ENV_FILE" "$staging/production.env"

    say "package: zipping"
    ( cd "$staging" && zip --quiet --junk-paths "$archive" dump.sql.gz compose.yaml production.env )
    unzip -qq -t "$archive" || die "the archive does not test clean"
}

# ADR-0008's second copy: the one that survives the box dying. rclone was already configured on this
# machine, so this needs no new account and no new credential — the thing that would otherwise have
# been the first secret ever to live on the deploy path.
upload() {
    local archive="$1"
    say "upload: $RCLONE_REMOTE"
    rclone_upload "$archive" || die "the upload failed; the local copy is kept"
}

prune() {
    say "prune: $LOCAL_KEEP_DAYS days here, $CLOUD_KEEP_DAYS in the cloud"
    find "$ARCHIVE_DIR" -maxdepth 1 -name 'task-backup-*.zip' -mtime "+$LOCAL_KEEP_DAYS" -delete
    rclone_prune || true
}

main "$@"

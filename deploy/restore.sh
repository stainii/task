#!/bin/bash
# Getting the data back (#24, ADR-0008).
#
#     restore.sh scratch <archive|dump.sql.gz>   load into a throwaway Postgres and leave it up
#     restore.sh live    <archive>               restore over the running stack
#
# A SCRIPT, NOT A RUNBOOK, and for one reason: step four is invisible and has no immediate symptom.
# Restoring rewinds `sequence`, so every device that synced before the restore holds a cursor ahead
# of the server. Skip the epoch bump and everything looks perfect — the app comes up, the tasks are
# there — while each of those devices concludes it is up to date PERMANENTLY and the server reissues
# the same numbers to different patches. It surfaces weeks later as two devices quietly disagreeing.
# A ceremony performed at 21:00 on a bad evening is a ceremony that loses its invisible step, so the
# step is in here instead (ADR-0004's amendment, via ADR-0007).
#
# `scratch` is the same code path, which is what keeps it warm: backup.sh proves every dump this way
# nightly, and the pre-merge migration dry-run in docs/operating-manual.md uses it to run a candidate
# Flyway migration against real data — the gap ADR-0007 named when it refused a staging environment.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SCRATCH_CONTAINER=task-restore-scratch

usage() {
    sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
    exit 64
}

main() {
    local mode="${1:-}" source_file="${2:-}"
    [ -n "$mode" ] && [ -n "$source_file" ] || usage
    [ -f "$source_file" ] || die "no such file: $source_file"

    case "$mode" in
        scratch) scratch "$source_file" ;;
        live)    live "$source_file" ;;
        *)       usage ;;
    esac
}

# Accepts either the zip ADR-0008 keeps or a bare dump, because on a bad evening you may have either.
extract_dump() {
    local source_file="$1" into="$2"
    if [[ "$source_file" == *.zip ]]; then
        unzip -o -q -j "$source_file" dump.sql.gz -d "$into" \
            || die "no dump.sql.gz inside $source_file"
        printf '%s\n' "$into/dump.sql.gz"
    else
        printf '%s\n' "$source_file"
    fi
}

scratch() {
    local source_file="$1"
    local work dump_file image
    work="$(mktemp -d)"
    dump_file="$(extract_dump "$source_file" "$work")"
    image="$(postgres_image)"

    local password="scratch-$RANDOM$RANDOM"
    say "scratch: starting $image"
    docker rm --force "$SCRATCH_CONTAINER" >/dev/null 2>&1 || true
    docker run --detach --name "$SCRATCH_CONTAINER" \
        --env POSTGRES_PASSWORD="$password" \
        --publish 127.0.0.1:0:5432 \
        "$image" >/dev/null

    wait_for_postgres "$SCRATCH_CONTAINER" postgres

    gunzip --stdout "$dump_file" \
        | docker exec --interactive "$SCRATCH_CONTAINER" psql --username postgres --quiet \
              --set ON_ERROR_STOP=1 --dbname postgres >/dev/null \
        || die "the dump did not restore"
    rm -rf "$work"

    local port
    port="$(docker port "$SCRATCH_CONTAINER" 5432/tcp | head -n 1 | sed 's/.*://')"
    say "scratch: up. Nothing here is connected to production."
    cat <<EOF

    jdbc:postgresql://localhost:$port/$(env_value POSTGRES_DB)
    user postgres, password $password

    Run a candidate migration against it, poke at it, then throw it away:
        docker rm --force $SCRATCH_CONTAINER

EOF
}

# The real thing. Deliberately noisy, deliberately not something a timer can trigger.
live() {
    local source_file="$1"
    require_env_file
    [[ "$source_file" == *.zip ]] || die "a live restore takes the archive, so that production.env
     comes back with the data. Point this at task-backup-<stamp>.zip."

    local database user
    database="$(env_value POSTGRES_DB)"
    user="$(env_value POSTGRES_USER)"

    cat <<EOF

  About to restore $source_file OVER THE RUNNING STACK.

  Everything written since that archive was taken is gone, and every device that synced in the
  meantime will hard-reset to this state and refetch (that is the epoch bump doing its job — let it).

EOF
    if [ "${TASK_RESTORE_ASSUME_YES:-}" != "yes" ]; then
        read -r -p "  Type the archive's date stamp to continue: " answer
        [[ "$source_file" == *"$answer"* ]] && [ -n "$answer" ] || die "not confirmed; nothing was touched"
    fi

    local work dump_file
    work="$(mktemp -d)"
    dump_file="$(extract_dump "$source_file" "$work")"

    # THE CLUSTER IS REPLACED, NOT OVERWRITTEN — and that is not tidiness, it is the only way this
    # works. A pg_dumpall begins by dropping the roles it is about to recreate, and the role it
    # drops first is the one the application connects as. Loading it into the running cluster means
    # connecting AS that role, and Postgres refuses: "current user cannot be dropped". The restore
    # then stops half-way, having already dropped the database it was going to recreate.
    #
    # That is not a hypothetical. It is what this script did on its first drill, and the state it
    # left behind — stack down, task database gone, dump not loaded — is exactly the position you
    # would least like to discover the flaw from.
    #
    # So the dump is loaded into a BRAND-NEW cluster, bootstrapped under a throwaway superuser name
    # that appears nowhere in the dump, which then creates every role and both databases with no
    # conflict at all. It is also the same path a rebuilt box takes, which is the path ADR-0008's
    # drill actually tests.
    local volume
    volume="$(project_name)_postgres-data"

    say "restore: stopping the stack"
    compose stop back-end front-end keycloak postgres
    compose rm --force --stop postgres >/dev/null

    # Absence is not failure here: on a rebuilt machine there is no old cluster to discard, and that
    # is the whole case rebuild.sh exists for. Only a volume that EXISTS and will not go is a problem
    # — that means something still has it open, and loading a dump underneath it would half-work.
    if docker volume inspect "$volume" >/dev/null 2>&1; then
        say "restore: discarding the old cluster ($volume)"
        docker volume rm "$volume" >/dev/null \
            || die "could not remove $volume; something still has it open"
    else
        say "restore: no existing cluster to discard"
    fi
    # Let compose create the empty volume, so it carries compose's own labels. Loading into a volume
    # that `docker run` created works, but compose then warns at every subsequent `up` that the
    # volume is not its own — a permanent piece of alarming noise from a restore that went fine.
    compose create postgres >/dev/null

    say "restore: loading the dump into a fresh cluster"
    local loader=task-restore-loader
    docker rm --force "$loader" >/dev/null 2>&1 || true
    docker run --detach --name "$loader" \
        --env POSTGRES_USER=restorer \
        --env POSTGRES_PASSWORD="restore-$RANDOM$RANDOM" \
        --volume "$volume:/var/lib/postgresql" \
        "$(postgres_image)" >/dev/null

    wait_for_postgres "$loader" restorer

    gunzip --stdout "$dump_file" \
        | docker exec --interactive "$loader" psql --username restorer --quiet \
              --set ON_ERROR_STOP=1 --dbname postgres >/dev/null \
        || { docker rm --force "$loader" >/dev/null; die "the dump did not load. The stack is down and the
     cluster is empty — nothing has been lost that the archive does not still hold. Fix the cause
     and run this again with the same archive."; }

    docker rm --force "$loader" >/dev/null
    rm -rf "$work"

    say "restore: starting Postgres on the restored cluster"
    # --wait, because the next step talks to it. Postgres answers "the database system is starting
    # up" for a second or two after the container exists, and the epoch bump is not a step to have
    # fail on a race.
    compose up -d --wait postgres

    # STEP FOUR. Everything above is visible; this is the one that is not.
    say "restore: bumping the epoch"
    compose exec -T postgres psql --username "$user" --dbname "$database" --quiet \
        --set ON_ERROR_STOP=1 --command 'UPDATE sync_epoch SET epoch = epoch + 1' \
        || die "the data is back but the epoch was NOT bumped. Do not start the application:
     clients ahead of this restore would sync into silence. Bump it by hand:
       docker compose exec postgres psql -U $user -d $database -c 'UPDATE sync_epoch SET epoch = epoch + 1'"

    local epoch
    epoch="$(compose exec -T postgres psql --username "$user" --dbname "$database" \
        --tuples-only --no-align --command 'SELECT epoch FROM sync_epoch')"
    say "restore: epoch is now $epoch"

    say "restore: bringing the stack up"
    compose up -d
    say "restore: done. Expect every device to refetch on its next sync."
}

main "$@"

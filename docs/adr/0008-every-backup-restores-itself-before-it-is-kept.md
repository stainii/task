# 8. Every backup restores itself before it is kept

Date: 2026-08-04

## Status

Accepted. Resolves [#26](https://github.com/stainii/task/issues/26).

Amends [ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md) — Keycloak's persistence, which that
ADR left undecided, and the `.env` list, which grows by one variable. See that ADR's *Amendments*
section.

Discharges the gate on [#17](https://github.com/stainii/task/issues/17): cutover requires a passed
restore drill, and this ADR defines what passing means.

Constrains [#24](https://github.com/stainii/task/issues/24) (the backup and restore scripts join its
list of artifacts), [#29](https://github.com/stainii/task/issues/29) (which inherits a required
*rebuilding the box* section), and [#27](https://github.com/stainii/task/issues/27) (which does
**not** need to alert on backup failure — see *Detection is a recurring task*).

## Context

Nothing on the map owned this, and it is the largest risk in the whole migration:
[#17](https://github.com/stainii/task/issues/17) makes the new database the only copy of years of
real personal data. [#8](https://github.com/stainii/task/issues/8) migrates that data once;
[#24](https://github.com/stainii/task/issues/24) rolls back a deploy; neither protects the data six
months after cutover.

Four facts were established rather than assumed.

**Portal has no backup mechanism in version control.** Every file in `../portal` was searched for
`pg_dump`, `mongodump` and `backup`; the only hits are unrelated Java in `portal-social`.
`_for_myself_server_setup_instructions.txt` documents Jenkins, Nexus, Docker and which ports to open,
and says nothing about restoring anything. Whatever protects the data today exists only outside
version control.

**What actually exists is a weekly pull from the MacBook.** `backup-server.sh` on the laptop `ssh`es
into the box, zips `/home/stijn` to `/tmp`, `scp`s it back and deletes the remote copy. Three
properties matter:

- **It is a pull.** The laptop holds the SSH key and initiates. The server holds no credential to the
  laptop and cannot reach it — so that copy survives a fully compromised box. This direction is kept.
- **Its log cannot report failure.** There is no `set -e` and no status check anywhere: the `scp`
  runs whether or not the zip succeeded, the cleanup runs regardless, and the final
  `echo "Backup completed"` is unconditional. The one artifact that exists to say the backup is
  healthy has never been capable of saying anything else.
- **It has never contained a database.** Production does use bind mounts under the home directory
  rather than the named Docker volumes `docker-compose-for-swarm.yml` declares — but the files inside
  them are mode `600`, owned by the containers' uid, and unreadable by the user the backup runs as.
  `zip` skipped all 311 of them and exited 18; the missing status check turned that into
  `Backup completed`. Verified against an archive already held on the laptop: every `db/data` entry in
  `server_backup_2026-07-31.zip` is a zero-byte **directory** entry, fourteen of them, with **no
  database files at all** — 611 MB of application files, nginx configuration and images. This was
  true for `portal-todo`'s Mongo, and for every other service.

  **So this ADR is not hardening an existing backup. It is designing the first one.** Detection
  mattering more than mechanism, below, is a conclusion drawn from this, not a preference: the backup
  that was believed to exist failed at the only step nobody checked.

**A file copy of a live database is defensible here but still loses.** With a handful of writes a
day the directory is quiescent almost all the time, so the torn-copy risk is low rather than a coin
flip. The argument against it is not corruption — see *A logical dump, not a file copy*.

**ADR-0007 had already spent part of this ticket's answer**: the nightly `pg_dump` is on the deploy
path, in one unit, and a failed dump aborts the deploy.

## Decision

### Three copies, one artifact

| Where | Cadence | Kept | Direction | Survives |
| --- | --- | --- | --- | --- |
| The box | nightly | 7 | — | your mistakes |
| Cloud object storage | nightly | 30 | box pushes | the box dying |
| MacBook | weekly | unpruned | **laptop pulls** | the box being compromised |

The recovery point is **24 hours in both directions** — for "I broke it" and for "the disk died". The
weekly cadence that existed before gave 7 days for the second case, and the fix was nearly free: the
nightly dump is a few megabytes of text, so uploading it nightly costs seconds.

Tighter than 24 hours was rejected. WAL archiving buys minutes instead of hours at the cost of a WAL
archive, a retention policy that understands it, and a restore with two steps instead of one — for a
personal task app where losing an afternoon means re-ticking a few boxes from memory.

The MacBook copy is **kept, not replaced**. An always-awake destination means, for the first time, a
credential on the box; whatever can write the backups can usually delete them, so a compromised
server — or a bad loop at 02:00 uploading a zero-byte dump over seven good nights — takes the cloud
copy with it. The laptop pull has the opposite properties: cold, lagging, and unreachable from the
server. Three copies fall out of what already exists.

### A logical dump, not a file copy

`pg_dumpall`, not an archive of `PGDATA`. The reason is not corruption:

- **A `PGDATA` copy only restores into the same major Postgres.** This stack is deliberately bleeding
  edge and [#25](https://github.com/stainii/task/issues/25) will have Renovate proposing image bumps.
  The day the image goes to 19, every file backup held becomes unrestorable — discovered at the
  moment one is needed.
- **A file copy can only be restored whole.** ADR-0007's honest gap — *CI proves a migration against
  an empty database, never yours* — is answered by [#29](https://github.com/stainii/task/issues/29)'s
  pre-flight: restore last night's dump locally, run the candidate migration against it. That needs a
  dump that can be loaded casually on a laptop.
- **The dump already exists.** ADR-0007 produces it nightly to make automatic Flyway safe. Carrying
  it is *less* machinery than carrying the raw files beside it.

**The database's data directory is excluded from the archive.** With a dump inside it, including
`PGDATA` too would put **two answers to "what was in the database"** in one file — one consistent,
one possibly torn — and the wrong one can be restored on a bad evening. Same reasoning ADR-0007 used
against two timers: the ambiguity is the bug, not the bytes.

### Unencrypted, deliberately

Backups reach the cloud in the clear.

The recommendation was asymmetric encryption with the private key off the box, and it was overruled
with a better argument: the threat model for encrypting is the storage provider, and the same
provider already holds photos — strictly more sensitive than a list of chores. Encryption there
would be ritual, not protection, and it would add the one secret in the system whose loss makes every
off-site copy permanently unreadable.

[#31](https://github.com/stainii/task/issues/31)'s "the code isn't the secret, the data is" does
**not** stretch to cover this. That ruling was about a *public* repository readable by anyone on the
internet. A private account at a storage provider is a different exposure, and the appeal to #31 was
an overreach.

The archive does carry `.env`, which is genuinely credential-shaped rather than chore-shaped. It
travels with the rest anyway, because splitting the archive to protect one file would trade a real
property — **one download, one restore** — for a marginal one.

### Keycloak shares the Postgres instance, in its own database

ADR-0007 put Keycloak in the production stack and never said where its data lives.
[`compose.yaml`](../../task-back-end/compose.yaml) runs `start-dev --import-realm` with no database
configured at all — correct for a dev fixture, which is exactly what
[`compose/keycloak/README.md`](../../task-back-end/compose/keycloak/README.md) says it is. Carried
into production unchanged, that puts the realm on a file store inside a container and turns *losing
the realm locks you out of your own data* from a risk into a scheduled event.

Production Keycloak gets a **database in the same Postgres instance**, separate from `task`'s. One
container to run, patch and pin; and **the backup becomes one artifact** — a single `pg_dumpall`
covers tasks, patch history and the realm together, restored by one command. Two engines would mean
two dump paths, two restore procedures, and a way to restore an auth server to a different point in
time than the data it guards.

This sits one level below the tension ADR-0007 already accepted with
[#15](https://github.com/stainii/task/issues/15)'s ruling that Keycloak is shared infrastructure. A
shared *schema* would be the coupling that hurts; a shared instance is a landlord relationship, and
ADR-0007's tripwire — the second app splits the stack — is then executed by dumping one database and
restoring it elsewhere.

### The archive is three things

- **the production compose file** — committed anyway, included so the archive is self-sufficient
- **`.env`** — the only place production configuration exists, per #31, and now also the
  `cloudflared` tunnel credential, since the tunnel is itself a container configured from `.env`
- **the `pg_dumpall`** — tasks, patch history, Keycloak's realm and its users

There is no fourth item. Portal's backup set included uploaded user files; `task` has none —
photos and notes died with `portal-social` ([#13](https://github.com/stainii/task/issues/13): a
person becomes a recurring template named after them), and no row in the 142-row ledger introduces a
file upload. The whole archive is text, in the low megabytes.

### Every backup restores itself before it is kept

After the dump and before the upload, the nightly job **restores the dump into a throwaway Postgres
container and asserts something real** — row counts on tasks and patches, non-zero and within sight
of yesterday's. A dump that fails to restore never reaches the cloud and never satisfies the deploy's
precondition.

This is the one decision here that buys more than it costs. The manual check catches *absence* — no
new file. It cannot catch **a file that is present and useless**: a truncated dump, a `pg_dumpall`
that wrote a header and hit a full disk, an archive that zips cleanly around a broken `.sql`. Those
look exactly like success from outside, and would be discovered on the one evening they matter.

It is the same principle ADR-0007 used to bound SSE connection lifetime — **choose the design that
makes the rarely-used path self-testing**. The restore path is the mechanism most likely to be broken
when needed and least likely to be exercised. So it is exercised nightly, on a box with nothing else
to do, and [#17](https://github.com/stainii/task/issues/17)'s gate becomes a continuously held
property rather than a one-time event.

Accepted cost: another moving part on the nightly critical path, where a flaky verifier would block
deploys for a reason that is not real.

### `restore.sh`, not a runbook

Restoring is five steps — fetch the archive, unzip, load the dump, **bump the epoch**, bring the
stack up — and the fourth is invisible and has no immediate symptom. Skip it and everything looks
perfect: the app comes up, the tasks are there. Meanwhile every device that synced before the restore
holds a cursor ahead of the rewound `sequence`, concludes it is up to date **permanently**, and the
server reissues those numbers to different patches (see ADR-0004's *Amendments*). It surfaces weeks
later as two devices quietly disagreeing.

So the restore is **a committed script, not a documented procedure** — the same "one unit, not two
timers" ADR-0007 applied to the deploy, applied to the reverse direction, and for the same evidence:
[#19](https://github.com/stainii/task/issues/19) found a year of drift caused by exactly one manual
gap. A ceremony performed at 21:00 on a bad evening is a ceremony that loses its invisible step.

`restore.sh` lives in this repo beside the deploy script, and takes a mode that targets a scratch
container rather than the live stack — the same code path the nightly verification and #29's
migration pre-flight both need.

**Whether `resync` itself works is not the drill's business.** That is ADR-0004's contract and belongs
in the test suite, where it runs on every push. The drill proves the *procedure* bumps the epoch.

### Detection is a recurring task, not a heartbeat

An external dead-man's switch was recommended — the box pings a URL on success only, silence is the
alarm — on the grounds that no mechanism on the box can observe the failures that matter (the box
off, the timer never fired, the disk full). It was rejected in favour of a recurring task named
*check the backup*, which is the same escalation everything else in this life gets:
[#13](https://github.com/stainii/task/issues/13) collapsed reminders into a task going overdue.

Two consequences are accepted knowingly:

- **The reminder lives in the system being backed up.** If the app is down, the reminder is down. In
  practice a dead task app is noticed within a day.
- **The worst case is a quiet stall.** Backup fails → ADR-0007 aborts the deploy → both stay stuck
  until the recurring task next comes due. That window is the recurrence interval, not minutes.

But the check only works if the artifact it looks at **can say no**. The current script's
unconditional `Backup completed` is a ritual that produces confidence without information, which is
worse than no check at all. So the nightly job runs under `set -euo pipefail`, records the real
outcome, and the check is a glance rather than an investigation.

This is why [#27](https://github.com/stainii/task/issues/27) does not inherit backup alerting, despite
ADR-0007 handing it exactly that. It inherits the weaker obligation of deciding whether the deploy and
the scheduler ride the same rails.

### The repo holds *how*, the archive holds *what*

The pass criterion below — restore onto a machine that is not the server — exposes what an archive of
data and config cannot contain: Docker itself, the systemd timer and unit, the tunnel's plumbing, the
shell to run it in.

The split is deliberate rather than inherited from wherever each tool defaults:

- **Committed to this repo**: the deploy script, `restore.sh`, the systemd unit and timer, and the
  production compose file (which ADR-0007 already requires to be committed, since the box gets it via
  `git pull`). Backed up by being in git.
- **In the archive**: everything stateful or secret — `.env`, the dump.
- **Written down, not built**: install Docker, fetch the archive, clone the repo, run `restore.sh`.
  That is a short recipe and it belongs to [#29](https://github.com/stainii/task/issues/29), which
  gains a required *rebuilding the box from nothing* section — the document portal never had.

**Rejected: a disk image or an Ansible playbook.** A large moving part to keep current for an event
that may never happen, which would spend its life stale — the argument ADR-0007 used to refuse
staging.

The consequence is accepted: **rebuilding from a dead disk is an evening's work, not fifteen
minutes.** The archive guarantees that nothing is lost, not that recovery is fast. The daily driver
degrades gracefully in the meantime — ADR-0004's PWA renders from IndexedDB with no server at all.

### 30 dailies, and nothing else

No monthly line, no yearly. The recommendation was monthly-forever on the grounds that a patch-replay
model can be corrupted subtly and noticed late, and that keeping text forever is cheaper than the
pruning logic. Overruled: 30 days is enough.

Stated plainly, because it is now a property of the design: **anything broken and not noticed within
a month is unrecoverable.** Two things soften it, one by design and one by accident — 
[#35](https://github.com/stainii/task/issues/35) freezes portal's data permanently, so everything
before cutover has an archive that never expires; and `backup-server.sh` has never pruned, so the
weekly zips accumulate on the laptop for as long as they are not tidied away.

### Portal is out of scope — except that it currently has no backup at all

This ADR was written to change nothing about the old stack: ADR-0005 says portal is archived, not
maintained, and improving a system on its way out spends effort where it earns nothing.

**That reasoning assumed portal had a backup. It does not** — see *What actually exists* above. The
conclusion inverts: until cutover, portal holds every task, patch and template that exists, and
nothing protects it. [#35](https://github.com/stainii/task/issues/35) was scheduled as cutover
housekeeping — pull and archive the dump set — and is now the only thing that would produce a first
real backup, so it is **urgent rather than eventual**, and its output is a *live safety net* rather
than a historical archive.

This does not reopen portal's backup *design*; a one-off dump set, refreshed until cutover, is
proportionate for a system being switched off. It does mean the migration currently runs with its
source data unprotected, which was not a risk anyone had accepted.

### The drill happens once, by hand, before cutover

Nightly self-verification proves the database half continuously. It does not prove the parts that
only exist end to end, so the gate on #17 shrinks rather than disappears.

**Pass criterion:** *starting from nothing but the cloud archive, on a machine that is not the
server, reach a running app showing your real task list — through a login screen you actually get
through.* Not "the dump loaded without errors"; that is the easy part and the part the nightly job
already covers.

The Keycloak half is why the drill survives: an intact `pg_dumpall` says nothing about whether the
realm came back well enough to authenticate, and no quantity of correct Postgres rows rescues you
from being locked out of them. `.env` being complete enough to bring up a stack on a machine that
never had one is the same class of unknown.

Drilled on the laptop with the production compose file — meaningful because [#20](https://github.com/stainii/task/issues/20)
pinned dev-compose and the test suite to the same images — and **from the cloud copy**, not the local
one, because the cloud copy is the one that survives the scenario. Restoring over live production to
prove a restore works is how a drill becomes an incident.

After that, #29's pre-flight keeps the path warm; no scheduled re-drill.

## Consequences

- **[#17](https://github.com/stainii/task/issues/17)'s gate is now defined and mostly automatic.** The
  drill is one deliberate evening before cutover, not a standing obligation.
- **[#24](https://github.com/stainii/task/issues/24) inherits two more artifacts**: the nightly
  backup script (dump → verify by restore → zip → upload → prune, under `set -euo pipefail`, with the
  deploy hanging off a dump that has proved itself) and `restore.sh` with its scratch mode.
- **[#29](https://github.com/stainii/task/issues/29) inherits a required section**: rebuilding the box
  from nothing.
- **[#27](https://github.com/stainii/task/issues/27) sheds an obligation** ADR-0007 gave it — backup
  failure is detected by a recurring task, not an alert.
- **A dump failure now stalls two things silently**, and the detection latency is a recurrence
  interval rather than minutes. Accepted.
- **Postgres becomes the auth server's dependency**, one level under #15's ruling. ADR-0007's
  second-app tripwire now also means separating two databases.
- **The nightly job is slower and can fail for a new reason** — the verification restore. A flaky
  verifier blocks deploys for a reason that is not real.
- **[#35](https://github.com/stainii/task/issues/35) is now urgent.** Until it runs, the data this
  entire migration exists to preserve has no backup of any kind.
- **This is the fourth defect of the "a guarantee broken by something outside the code" shape** on
  this map, after #15's `ngsw-config.json`, ADR-0007's restore-rewinds-`sequence`, and its nginx SSE
  defaults — and the only one that had already caused real, unnoticed harm rather than being caught
  in design. It is also the purest: a backup script whose success log is a string literal, standing
  in for a backup that never contained anything.

## Amendments

### A live restore replaces the cluster; it does not load over it

Amended by [Set up continuous deployment](https://github.com/stainii/task/issues/24), 2026-08-23.

This ADR described restoring as "load the dump" and specified `restore.sh`'s steps. The first drill
found that the obvious implementation of that step cannot work: a `pg_dumpall` begins by dropping the
roles it is about to recreate, and the first role it drops is the one the application connects as.
Loading it means connecting *as* that role, and Postgres refuses — `current user cannot be dropped` —
leaving the stack down with the database already dropped and the dump not loaded. Precisely the
position from which one would least like to discover the flaw.

So `restore.sh live` discards the data volume and loads the dump into a **brand-new cluster**,
bootstrapped under a throwaway superuser name that appears nowhere in the dump. Every role and both
databases are then created with no conflict, and — usefully — it is the same path a rebuilt box
takes, so the *rebuilding the box* recipe and the everyday restore exercise one mechanism rather
than two.

Two smaller findings from the same drill, both of the shape this ADR is about:

- **Waiting for a new Postgres means asking over TCP.** The image's entrypoint runs `initdb` against
  a temporary server that listens on the unix socket only, then shuts it down and starts the real
  one. `pg_isready` over the socket answers yes during that window, and a restore started there is
  cut off mid-stream — which looks exactly like a corrupt dump, the worst false accusation this
  script could make.
- **rclone is handed its config directory, writable.** It refreshes the Drive token and saves it by
  renaming the old file aside, which cannot be done to a bind-mounted file. Uploads worked while
  every run logged `Failed to save config: device or resource busy` — a token it could not persist,
  which fails for real months later, at night.

### The nightly row-count assertion is a drop check until cutover

Amended by [Set up continuous deployment](https://github.com/stainii/task/issues/24), 2026-08-23.

This ADR asked the verification to assert row counts "non-zero and within sight of yesterday's". The
non-zero half cannot hold yet: until [#17](https://github.com/stainii/task/issues/17) imports the
real history, this database is legitimately empty, and a hard non-zero rule would block every deploy
between now and then — the shape of gate that gets commented out and never restored.

`backup.sh` therefore fails on a **drop of more than 10%** against the last recorded run, and the
floor arms itself the moment there is data to protect. Growth is never suspicious; an append-only
patch log does not shrink on its own. Proven both ways on 2026-08-22: a seeded previous count made
the run refuse to keep the archive, and the deploy that depends on it stopped.

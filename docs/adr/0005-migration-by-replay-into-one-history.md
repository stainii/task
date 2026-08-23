# 5. Migration by replay into one history

Date: 2026-08-02

## Status

Accepted. Resolves [#8](https://github.com/stainii/task/issues/8).

Reinterprets [#4](https://github.com/stainii/task/issues/4)'s "history must be imported verbatim"
as *not collapsed*, rather than *byte-identical*. See *Blobs are translated, not copied*.

Resolves a conflict between [#13](https://github.com/stainii/task/issues/13)'s REC-002 ("keep the
full execution log") and [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md), which
deleted the `Execution` entity. See *An execution is a task that fired and closed at once*.

Constrains [#33](https://github.com/stainii/task/issues/33), which is amended to cover the
out-of-band completion case that REC-005's endpoint became.

## Context

The old portal is live and used daily, and holds years of the author's real personal data. Cutover
makes the new database the only copy, so this decision is about not losing it — and about noticing
if it is lost.

The sources are heterogeneous:

- **`portal-todo`** — MongoDB (port 27018), collections `task`, `taskPatch`, `subscription`,
  `taskTemplate`. `Task.history` is a `@DBRef` list into `taskPatch`.
- **`portal-recurring-tasks`** — **four** separate Postgres databases, one per deployment
  (housagotchi, setlist, health, social-recurring-tasks), each with the same Liquibase changelog:
  `recurring_task(id, name, min_number_of_days_between_executions,
  max_number_of_days_between_executions)` and `execution(id, date, recurring_task_id)`.
- **`portal-social`** — Postgres (5438), `person(...)`, whose only surviving content is a name and a
  cross-database foreign key.

The target is one Postgres schema, and the model it must satisfy was decided elsewhere:
[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) (one `Task` aggregate, one
`TaskTemplate` with a sealed `Trigger`, `Execution` deleted, occurrences derived),
[ADR-0003](0003-two-modules-with-package-visibility-as-the-boundary.md) (module boundaries) and
[ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) (the sorted fold, two clocks, void
patches).

Several verdicts in the feature triage were written *before* those ADRs landed, and had gone stale.
Finding and resolving those was most of the work here.

Nothing in any source carries a user identity — `Task` has no owner, `recurring_task` is
`(id, name, min, max)`, `person` has no user column. It is a single-user system throughout, so the
Keycloak realm question is not coupled to the migration at all.

## Decision

### An execution is a task that fired and closed at once

[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) removed `Execution`: an occurrence
is derived from a task and its patch history, not stored. But REC-002 kept portal's execution log
deliberately — it is the only record of *when did I last actually do this*, and portal let you log
one **without any todo task existing** (housagotchi's entire UI was tapping a creature to say
"done").

Those rows migrate as **tasks created and completed on the execution date**, with a creation patch
and a completing patch.

This is not a migration-shaped compromise. REC-005 kept the register-an-execution endpoint, and once
`Execution` is deleted the only thing that endpoint can mean is *create a task for this template and
complete it in the same breath*. So a migrated execution and a future out-of-band completion produce
**identical rows**, and "when did I last do this" is one query over one history rather than two
half-answers.

The fire date is set **equal to** the completion date. Portal only ever recorded when something was
done, never when it became due. An obviously degenerate lead time is preferable to a plausible
invented one, and it changes no behaviour: ADR-0001's min/max clock reads completions.

### A task is what its history folds to

Portal stores both current state (the `Task` document) and the patches that produced it. They do not
always agree, because portal's merge **is** the defect ADR-0004 named **D2**: `Task.patch()` applies
in arrival order and then repairs itself by reapplying only the *first* newer patch
(`.findFirst().ifPresent`), not all of them. Any task that received an out-of-order patch during
offline use is likely sitting in a state its own history would never produce.

**The stored document is discarded.** Every task is recomputed by folding its migrated patches
through the real fold.

The importer emits a **field-by-field diff of stored versus folded state**, listing every task that
differs, and **fails loudly** on any task it cannot reproduce at all — a missing creation patch, an
unparseable change. It never falls back to the stored row.

The alternative — import the stored state and carry the patches alongside as decoration — was
rejected because the inconsistency would be permanent: every future patch folds onto a base the
history does not justify, and the fold becomes untrustworthy forever.

### Blobs are translated, not copied

Portal's `changes` maps cannot survive a byte-for-byte copy into the new vocabulary:

- keys **`flowId`** and **`expectedDurationInHours`** are dropped everywhere
  ([#12](https://github.com/stainii/task/issues/12)), and the creation patch carries them *always* —
  it is built by reflection over every field, so every task's first patch has them;
- **`id`** appears as a change key, and is now the row's identity;
- `startDateTime`/`dueDateTime` values are **`LocalDateTime` strings**, and the target parses
  `LocalDate` (TODO-001). `2024-03-05T23:30` does not parse as a date.

So the blobs are **rewritten into the new vocabulary**: dead keys dropped, values narrowed. Patch
count, order and timestamps are untouched.

A patch left with nothing to say — one whose only content was `expectedDurationInHours` — **stays as
an empty patch**. The timestamp is still a true fact, deleting it is the collapsing
[#4](https://github.com/stainii/task/issues/4) forbade, and *patches in equals patches out* is a
checkable invariant for the dry run.

Teaching the fold to tolerate unknown keys and parse dates two ways was rejected: it makes the
parser permanently lenient to serve data that stops arriving the day after cutover, and a lenient
parser eventually swallows a real bug.

### `flowId` rebuilds provenance, one last time

`portal-recurring-tasks` builds every event's flow id as **`<deploymentName>-<recurringTaskId>`**
(`ExecutionEventMapper`, `CancellationEventMapper`), and `portal-todo` copies it straight onto the
generated task. REC-011 turns `deploymentName` into **`context`**.

So `flowId` — dropped from the model, confirmed dead — is **alive in the source data as the only
provenance link that exists**. It is used at import time and then discarded:

1. A migrated task whose `flowId` matches `<context>-<id>` gets a real **`taskTemplateId`**,
   satisfying REC-007's persisted "this template created these tasks" requirement retroactively, for
   years of history.
2. An `execution` row matching such a task — same template, completion within **±1 day** —
   contributes nothing. In portal a single occurrence wrote *both* a todo task and an execution row,
   so synthesising unconditionally would put a phantom beside every real task.
3. Only executions with **no matching task** are synthesised.
4. Ambiguous and unmatched rows go in the diff report for review. The importer **reports**
   ambiguity; it never resolves it silently.

This is available exactly once. After cutover the dump is archived and the field denotes nothing, so
an unlinked historical task is an orphan permanently.

A free consequence: the distinct `flowId` prefixes in the Mongo dump **name the deployments that
actually existed**, closing most of DB-003's "names inferred, never verified" gap without touching
the server.

### The importer writes through the app

A **one-shot Spring Boot importer**, living in this repo, reading Mongo and the four Postgres
sources, with three required properties:

- **It writes through the app's own fold and model, not raw SQL.** This is the whole argument. If the
  importer has its own private write path, the claim that a task *is* its folded history is untested
  exactly where it matters. Writing through the real fold makes every migrated task one the app could
  have produced itself.
- **It is re-runnable and idempotent**, truncating and rebuilding rather than appending, so dry runs
  are free.
- **It emits the diff report** — stored versus folded, the ambiguity list, and the arithmetic
  (tasks in/out, patches in/out, executions in → synthetic tasks out).

It is **kept in the repo permanently**, not deleted after cutover: it is the only executable record
of how a portal row became a `task` row, and it is what a later repair would be built from.

Flyway cannot read Mongo, so an in-app migration was never available for the half of the data that
matters most. A two-stage export-transform-load was rejected: the inspectable intermediate is
obtainable from the diff report, and it would force the fold to be re-derived outside the app.

Every dry run goes against a **restored dump, never live portal** — which makes
[#26](https://github.com/stainii/task/issues/26)'s restore drill a prerequisite of this ticket's own
rehearsal rather than a ceremony.

The importer cannot be written yet: ADR-0001 merges `recurring_task_template` into `task_template`
with a sealed `Trigger`, and today's V2/V3 schema still has them separate.

### Cutover is big-bang, and the risk is not downtime

The author can stop using the apps for a day, so downtime is cheap. The hazards are writes nobody
knows about:

- **Portal is offline-first too.** A device holding an unsynced queue at the moment of the final dump
  loses those writes permanently. A quiet day does **not** drain an outbox — a device drains by being
  *opened while portal is still up*.
- **Portal is an installed PWA** (RES-013, `ngsw-config.json`). It keeps launching, accepting input
  and queueing into a void long after the server is gone. A stale install is a silent data-loss
  channel that outlives the service.

The sequence:

1. Open **every device** once, confirm the outbox is empty.
2. Freeze portal's front end, leave the databases up, take the final dump.
3. Run the importer; read the diff report; **decide**.
4. Hard-unregister the portal service worker on every device before installing the new app —
   the per-device procedure is [`portal-service-worker-removal.md`](../portal-service-worker-removal.md),
   written by [#62](https://github.com/stainii/task/issues/62). It is manual, it is not code in this
   repo, and installing `task` does **not** do it: the two apps are on different hostnames, so
   `task`'s worker can neither see nor replace portal's.
5. Stop portal's stack.

No dual-run — two writable systems over the same data manufacture exactly the divergence this map
exists to prevent. No read-only portal either: a second UI showing stale data that looks
authoritative, on a phone, months later, is worse than no portal.

### Archived, not maintained

"How long does portal stay runnable" is the wrong question, because time is not the variable:

- **Before the first write in `task`**, rollback is free and total.
- **After it**, there is no rollback at any price. Reverting means abandoning everything done since,
  and there is no path to replay it backwards — the schemas do not correspond and portal has no
  importer.

So the decision is made **once, on cutover day, before the new app is used**, and the diff report is
what informs it. Keeping portal bootable for thirty days buys a museum.

What is kept instead:

- the **raw dumps, archived permanently and off-server** — not for reverting, for reconstructing;
- the **importer source**, forever.

A defect discovered in month three is fixed by dump plus importer plus a targeted repair. That is
impossible if either half is gone.

The dumps are pulled **now**, ahead of the build work
([#35](https://github.com/stainii/task/issues/35)). Today the only copy of years of personal data is
a running server with no verified backup. Archiving a dump set needs nothing else on this map to be
decided, and turns "the data is safe" from an assumption into a fact.

## Consequences

- **The four `recurring_task` tables become one table of templates**, each row's old deployment name
  becoming its `context`. `taskTemplate` documents become templates with **`Trigger.Manual`**;
  `recurring_task` rows become **`Trigger.MinMax`**. The `subscription` collection does not migrate
  ([#12](https://github.com/stainii/task/issues/12)). `person` contributes nothing but a join
  ([#13](https://github.com/stainii/task/issues/13)).
- **[#33](https://github.com/stainii/task/issues/33) inherits a constraint rather than a free
  choice.** A live out-of-band completion must produce the same rows a migrated execution does,
  otherwise migrated history and new history stop being the same thing — which was the entire
  argument for treating an execution as a closed task.
- **REC-005 was a stale verdict**, not a missing feature: the endpoint survived while the question
  behind it evaporated with `Execution`. [#16](https://github.com/stainii/task/issues/16) would have
  ticked it as covered. Other verdicts written before ADR-0001 (REC-002, REC-004's client-minted
  `Execution` ids) deserve the same suspicion.
- **The diff report is a real gate.** Cutover can be called off on the day, on its evidence. That is
  the abort mechanism; there is no other.
- **The device roll-call is a runbook item** for
  [#29](https://github.com/stainii/task/issues/29) — "which devices am I logged in on" must be
  answered in writing before cutover day, not from memory.
- **The auth fog decouples.** No data carries an identity, so the Keycloak realm question belongs to
  [#22](https://github.com/stainii/task/issues/22)/[#28](https://github.com/stainii/task/issues/28)
  and is removed from the map's *Not yet specified*.
- **`Task.undoPatch` sets no id**, so undo patches in production carry Mongo `ObjectId`s rather than
  UUIDs and need minting. Everything else is already a UUID: the front end mints `Guid.raw()` for
  tasks and patches, and `TaskMapper`/`TaskPatchMapper` use `UUID.randomUUID()`.
- **Migrated patches occupy sequences 1..N** in `dateTime` order
  ([ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md)). Every client's first sync after
  cutover is a full snapshot regardless, since no client carries a usable cursor across the switch.
- **Time-of-day is lost** on start and due dates, by TODO-001's decision that dates are `LocalDate`.
  Portal's container JVM ran UTC while the browser ran Brussels; truncation is applied at import and
  the discrepancy is not reconstructable afterwards.

## Amendments

### The `flowId` prefix is not in this repo, and is not the database name

Raised by [#15](https://github.com/stainii/task/issues/15) while triaging RES-011 (the portal
READMEs, which this ledger had never read).

The decision above matches a migrated task's `flowId` against `<context>-<id>`, treating
`deploymentName` as known. It is not. `deployment-name` exists **only as a deployment-time
environment variable** — `DEPLOYMENT_NAME`, passed through `portal-recurring-tasks/Dockerfile` — and
appears in **no committed configuration anywhere in portal**. The only sample of a real value is the
README's own example, `Housagotchi-1001`, which is **capitalised**, whereas DB-002/003's database
names are lowercase (`portal-housagotchi`).

So there are two distinct namespaces that this ADR previously conflated:

- the **database name**, which is what [#35](https://github.com/stainii/task/issues/35) enumerates on
  the server, and
- the **`deployment-name` value**, which is what actually appears as a `flowId` prefix in the Mongo
  data.

They may differ in case, and nothing guarantees they differ *only* in case.

**The importer must therefore derive the prefix set from the data, never from a table in this
repo.** A hardcoded map that guesses wrong does not fail loudly — it silently fails to match, and a
whole deployment's history migrates as orphaned tasks with no `taskTemplateId`, which is exactly the
permanent, one-shot loss step 1 exists to prevent. Concretely:

1. Enumerate the distinct `flowId` prefixes present in the Mongo `task` collection. This set — not
   any list written by hand — defines the deployments that existed.
2. Match prefixes to the dumped recurring-tasks databases **case-insensitively**, and report any
   prefix with no database, or database with no prefix, in the diff report. Either is an abort
   signal, not a warning.
3. The `context` a migrated task receives is derived from the prefix, so its **casing is a visible
   product decision** (`Housagotchi` vs `housagotchi`) that lands in the UI as a card label
   ([ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md)). Normalise deliberately rather
   than inheriting whatever the env var happened to say.

This strengthens rather than contradicts the "free consequence" above: the prefixes in the dump do
name the deployments that actually existed — and they are now the **only** source that does.
[#35](https://github.com/stainii/task/issues/35) is amended to record them alongside its row counts.

### Half the templates are gone, so provenance is a policy, not a promise

Established by [#35](https://github.com/stainii/task/issues/35), which pulled the dump set and
counted it. Three corrections to the section above, all from data rather than from reasoning.

**The prefix set is a table, not a derivation.** The amendment above told the importer to enumerate
distinct `flowId` prefixes because nothing else names the deployments. Something else does:
`todo.subscription` has four rows, and `origin` holds exactly the four prefixes —
`Housagotchi`, `Health`, `Setlist`, `social-recurring-tasks`. Three capitalised, one not, none equal
to its database name, confirming the case mismatch this ADR predicted. Every prefix has a database
and every in-scope database has a prefix, so the abort signal in step 2 above is checkable and
currently clean. Read the four rows; keep the derivation as the cross-check.

**Neither obvious parse of `flowId` is correct.** `social-recurring-tasks-7` puts hyphens inside the
prefix, so splitting on the first hyphen is wrong. One task in 11855 carries a `Todo-<uuid>` whose
final UUID segment is all digits, so splitting on the last hyphen is wrong too — it invents a
deployment named `Todo-f660a98a-5fa5-4393-a614`. **Match against the four known prefixes, longest
first; everything else is `Todo-<uuid>`.** Both wrong rules fail silently.

**49% of recurring-generated tasks reference a template that no longer exists.** Tasks name 115
distinct template ids; 43 of those templates survive. Deleting a recurring template took its
`execution` rows with it via the foreign key, but left its tasks standing in Mongo, and six years of
pruning accumulated: 3954 of 8086 tasks are orphaned this way (Setlist worst — 4 surviving templates
against 2054 tasks, 1258 orphaned; Housagotchi 1583 of 2534; Health 969 of 2734;
social-recurring-tasks 144 of 764). One surviving Housagotchi template was never referenced by any
task.

Step 1 above therefore cannot mean what it says for half the corpus. **A task whose `flowId` names a
missing template imports with a null `taskTemplateId`** — it is still a complete, foldable task,
because [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) derives occurrences from
tasks rather than from templates, and nothing downstream requires the link to exist. The importer
**counts** these in the diff report. It does not synthesise a template to hang them on: a
reconstructed template is a claim about a rule that ran for years, invented from the tasks it
happened to leave behind.

This is a loss that predates the migration and no dump taken at any date could have recovered. What
changes is the claim: "satisfying REC-007 retroactively, for years of history" holds for 51% of the
recurring corpus, and the rest arrives as history without provenance.

## Amendments

### REC-005 keeps its shape and loses its endpoint

Amended by [How does a template learn one of its occurrences was done?](https://github.com/stainii/task/issues/33),
2026-08-07. See [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md).

*An execution is a task that fired and closed at once* argued that once `Execution` was deleted, the
register-an-execution endpoint REC-005 kept "can only mean *create a task for this template and
complete it in the same breath*". **The meaning is confirmed. The endpoint is not.**

ADR-0011 makes the out-of-band completion **client-minted**, written through the patch outbox rather
than to a server endpoint, because "I already did this" must work offline — it was housagotchi's
entire interaction, performed away from a desk. There is no `POST .../execution` successor.

This ADR's guarantee is undamaged, because it never rested on the route: a migrated execution and a
live out-of-band completion still produce **identical rows** — a task created and completed on the
same date, with a creating patch and a completing patch. That is now guaranteed by the shape both
paths build, not by their sharing a code path.

REC-005's verdict should be read accordingly: **transform**, into a client affordance, not a kept
endpoint. The affordance itself belongs to [#36](https://github.com/stainii/task/issues/36).

### The importer sets `completedOn`

Amended by the same ticket. ADR-0011 adds a `completedOn` date to `Task`, set on every completion.
The importer sets it from the portal execution date — and, for ordinary completed tasks, from the
completing patch's own date — rather than letting it default. Portal's `ExecutionDto` carried exactly
this date and its UI made it required, so the value exists in the corpus and does not need inventing.

### The prefix check aborts on a prefix that is correct

Amended by [Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42),
2026-08-10.

Step 1 above — *enumerate the distinct `flowId` prefixes present in the Mongo `task` collection* —
was run for the first time against the archive. It yields five: `Health` (2,729), `Housagotchi`
(2,528), `Setlist` (2,049), `social` (762) and **`Todo` (744)**.

Step 2 treats a prefix with no recurring-tasks database as an **abort signal**. `Todo` is exactly
that, and it is entirely correct: it is not a deployment but portal-todo's own task-template
`flowId`. As specified, the importer would refuse to run on healthy data.

**The shape is the discriminator**: deployment flowIds are `<Name>-<numeric id>`, task-template
flowIds are `Todo-<uuid>`. The `Todo` prefix is excluded from the deployment set before the
database match, and only the remaining four take part in step 2's reconciliation.

The casing decision this ADR anticipated is now measured rather than hypothetical: **three of the
four deployment prefixes are capitalised and one (`social`) is not**, so normalising is a real
choice with a visible product consequence, not a theoretical one.

### Migrated tasks with no importance

Amended by the same ticket. [ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md)
makes `importance` non-nullable. The importer maps a missing importance to `NOT_SO_IMPORTANT`.
17 hand-made tasks in the archive are affected.

### What the first real import found

Established by [The importer, part 1](https://github.com/stainii/task/issues/52), 2026-08-12, by
building the importer and running it against the frozen archive. Six corrections, every one measured
rather than reasoned, and every one a value this ADR states or implies.

**Portal wrote dates in two shapes, and this ADR only ever saw one.** *Blobs are translated, not
copied* treats `startDateTime`/`dueDateTime` as `LocalDateTime` strings and prescribes truncation.
**9,662 of them carry a trailing `Z`** — they are instants, written by the browser — and **99.7% sit
at exactly 22:00 or 23:00 UTC**, which is midnight in `Europe/Brussels` on either side of the
daylight-saving boundary. Truncating those *as written* yields **the day before, for every one of
them**. So the rule is now: **a value that names an instant is converted to the application's zone
before its date is taken; a value that names a local time already is one.** §248's "truncation is
applied at import" was right about the loss of time-of-day and wrong about the day.

**"Everything else is already a UUID" is false.** §241 predicted that undo patches carry Mongo
`ObjectId`s and need minting, then exempted everything else. **115 patch ids are `ObjectId`s** — the
shape was right, the reason was not: they are ordinary `name`, `dueDateTime` and `status` edits, not
undos — and **11 task ids are not UUIDs at all**. Those eleven are portal's earliest generated tasks,
from before the UUID scheme, and their **id is the flow id** (`Health-1`, `Housagotchi-52`, plus one
stray `Healthy`). All 126 are minted **deterministically** from the portal id, because a random id
would make two dry runs disagree and this ADR's *re-runnable and idempotent* is the property the diff
report rests on.

**Ten tasks have provenance that a count of the `flowId` field cannot see.** [#35](https://github.com/stainii/task/issues/35)
measured 8,086 deployment-generated tasks by reading the field. Ten of the eleven above name a real
deployment and a real recurring task *in their id*, and **four of them still have a surviving
template**. The corrected split is **8,096 deployment-generated / 3,013 hand-made**, and 3,960 rather
than 3,954 import with a null link.

**49 patches are in no task's `history` array**, which this ADR never contemplated. 32 name a task
that really exists; 17 name two task ids that exist nowhere. The importer therefore groups patches by
their own **`taskId`**, not by portal's array: the array is the same document *A task is what its
history folds to* discards, while a patch naming a real task is real history. That recovers the 32.
The 17 are the live API's orphan case, and are **dropped and counted**.

**Four tasks had their start date cleared, and `startDate` is not nullable.** `Task.foldOf` throws
rather than produce a task without one, so four real tasks could not be represented as they stand.
They are **translated, not dropped**: a cleared start date meant *no constraint on when this starts*,
which the new model expresses as **starting the day it was created** — exactly what
`Task.builderForInitialTask` defaults to. This is the only value the importer invents, and it is
counted and listed in the report.

**One task's name is 286 characters, and `task.name` was `VARCHAR(255)`.** Widened to `TEXT` by
`V8__task_text_columns.sql`, along with `context`. The limit was undefended — `description` beside it
is already `TEXT` and #47 gave `task_template.name` `TEXT` without comment — and the inconsistency
was a **latent production defect independent of this migration**: a template name is `TEXT` and the
task it produces was `VARCHAR(255)`, so a template whose *rendered* name crossed 255 characters would
throw inside the hourly firing job, once an hour, forever, with an ERROR line as the only trace.

Two things the archive confirmed rather than corrected: **every one of the 11,855 tasks has a
creation patch carrying all four fields the fold requires**, so *fails loudly on a missing creation
patch* has zero occurrences; and the four deployment prefixes reconcile against the four databases
with **no orphans in either direction**, so step 2's abort signal is checkable and clean.

The arithmetic of the first full dry run: **11,855 tasks and 38,211 patches in; 12,483 tasks and
39,450 patches out** — 38,211 less the 17 orphans, plus two patches for each of **628 synthesised
executions**. Of 5,620 executions, 3,619 are matched by a migrated task and **1,373 match more than
one**; those are reported and not synthesised, and are the largest thing
[#53](https://github.com/stainii/task/issues/53) inherits.

### `context` is normalised, and the deployment name wins

Also #52. REC-011 turns `deployment-name` into `context`, but all **8,086** recurring tasks already
carry a `context` of `Personal`, hardcoded by the four subscriptions' `mappingOfContext`. The
deployment name **overwrites** it, or housagotchi, setlist, health and social collapse into one card
in [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md)'s overview and the `flowId` that
could have told them apart is gone the moment the import finishes.

The 3,013 hand-made tasks carry 24 further values, and three are one context spelled two ways. They
are **trimmed and folded** — `Personal ` into `Personal`, `Scholencoordinatie` (1,293) and
`Scholencoordinatie ` into `Scholencoördinatie`, `Medisch huis` into `Medisch Huis` — because each
would otherwise become a second card. The accented spelling is kept over the 1,293-strong majority
because a card label is read by a person and that is the correct Dutch. Every fold is a named entry
in a table, never a general rule that rewrites labels it was not told about, and the other 21 values
pass through exactly as written.

One more thing read from a subscription before it dies: **`mappingOfImportance` is the only record of
which deployment's tasks were urgent.** `recurring_task` has no importance column, and three of the
four subscriptions map a plain quoted literal — `Setlist` produced `NOT_SO_IMPORTANT` tasks and the
other three `IMPORTANT`, for years. The importer honours a simple quoted literal and evaluates
nothing else; without it every migrated setlist template would start producing more urgent tasks than
it ever did.

## Amendments

### The report does not abort. It attributes, and a person decides

[#53](https://github.com/stainii/task/issues/53). This ADR called the diff report **the cutover
abort mechanism** — "cutover can be called off on the day, on its evidence. That is the abort
mechanism; there is no other." That stands as *where the evidence comes from* and is wrong about
*who acts on it*. **The importer never aborts on the diff.** It bins every difference, prints what
it could not explain first, and stops. The author continues, or opens a coding session, changes the
mapping and re-runs — which is free, because the import is idempotent.

The reason is that a count of divergent tasks carries almost no signal here. **Divergence is
expected**: portal's merge *is* D2, so a task whose patches arrived out of order is *supposed* to
fold to something other than what portal stored — that is the entire argument for discarding the
stored document. Four thousand divergent tasks, all attributable, is health. **One divergence with
no explanation is a defect in the fold or the translation**, and no percentage threshold would
catch it.

So the thing written down before the numbers is not a threshold. It is **the closed list of
explanations we are willing to be satisfied by**, fixed while the counts are still unknown — which
is what "a threshold decided while looking at the result is not a threshold" was protecting. A diff
fitting none of them is **unexplained**, and unexplained diffs are the report's first page.

**The acceptable causes.** Committed 2026-08-13, before the first stored-versus-folded run:

| # | cause | shape |
|---|---|---|
| 1 | **Out-of-order arrival** | two patches touch the field, and their order in the stored `history` array differs from their `dateTime` order. D2 itself; expected to dominate |
| 2 | **Duplicated history entry** | portal's repair recursion re-`add`s patches, so one appears twice in the array and can re-apply a stale value |
| 3 | **Context overwritten by the deployment** | REC-011: stored `Personal`, folded `Housagotchi` / `Setlist` / `Health` / `social-recurring-tasks` |
| 4 | **Context normalised** | trimmed, or folded onto a canonical spelling by `Contexts` |
| 5 | **Importance defaulted** | ADR-0018: stored null, folded `NOT_SO_IMPORTANT` |
| 6 | **Cleared start date defaulted** | stored null, folded the task's creation date |
| 7 | **Date-time narrowed to a date** | same day, time of day dropped. Only fires when the **day** agrees; a differing day is cause 1 or unexplained |

**One field is a canary: `creationDateTime`.** It is written once, by the creation patch, and
copied rather than computed — no ordering of anything can move it — so a difference there means the
translation is broken and it is unexplained **by construction**.

The list first written here also made canaries of `name`, `description` and `status`, on the
argument that they are carried through verbatim and so have no benign story. **That was wrong, and
it was corrected before the first run** — while the counts were still unknown, which is the whole
property the pre-commitment was written down to have. Causes 1 and 2 are properties of *ordering*,
not of a field: two `status` patches arriving out of order is the likeliest D2 case in this corpus,
and two `name` edits behave identically. Had the canary rule shipped as written, several thousand
perfectly attributable D2 differences would have landed in the unexplained bucket and hidden the one
defect it exists to expose.

What survives from that instinct is narrower and better aimed: **a `status` difference on a task
that is *currently open* is escalated to the report's front page whatever its cause.** Those are the
tasks the author wakes up to on cutover morning, and a task portal shows as open that we fold to
`COMPLETED` vanishes on day one.

**Arrival order is read from the stored `history` array — for the report only.** The importer
refuses to follow that `@DBRef` list when *building* a task, and rightly: grouping by `taskId`
recovered 32 patches the array had lost. But array position **is** insertion order, and insertion
order is the only record of arrival that survives anywhere in the corpus. So it is the only thing
that can distinguish cause 1 from a bug. Two channels, never crossed: `taskId` grouping builds the
task, array order explains the diff. It exposes cause 2 for free.

**The report is written outside the repository**, next to the archive, because it quotes real task
names and descriptions and [#31](https://github.com/stainii/task/issues/31) keeps this repo public
on the rule that *the code is not the secret, the data is*. A gitignored path inside the repo is one
`git add -f` from being the incident #31 was written to prevent; outside it, there is no mechanism.
A machine-readable sidecar rides alongside the prose, one row per difference, so that a later
session can act on an unexplained diff by opening a file rather than by restoring 5 MB of Mongo.

**Two bars, not one.** *Fit to dogfood* ([#39](https://github.com/stainii/task/issues/39)) is loose:
the loop works and the open tasks are right. *Fit to become the only copy*
([#17](https://github.com/stainii/task/issues/17)) is strict: nothing unexplained is left
un-adjudicated. #53 clears the first and writes the second down.

### What the first stored-versus-folded run found

The rehearsal ran through `MigrationRunner` on the `migration` profile — the path cutover executes,
not the JUnit dry run — against the archive restored into throwaway containers: **50,073 documents,
0 failures**. Of **9,421 differences, four are unexplained**, and all four are portal being wrong
rather than the fold:

- **Three stored values are not derivable from any patch in the task's own history.** `e8029ea0…`
  has exactly two patches — a creation with `dueDateTime` 2020-02-27, and a status change — and
  portal stored 2020-01-27. Nothing wrote that. This ADR's central claim, *portal's rows are already
  unjustifiable by their own history*, arrives here as a measurement instead of an argument.
- **One is a due-date edit portal's `history` array dropped** (`c8380233…`, four patches by `taskId`
  and two in the array). One of the 32 patches #52 recovered, doing exactly what it was recovered
  for — and the attribution rules correctly refused to explain it away.

**D2's mechanism is present and never corrupted anything.** Twelve tasks have a `history` array out
of clock order and 81 carry a duplicated entry, so the preconditions are real — and in none of them
did the mis-ordering change one of the eight compared fields. The two order-based causes scored
zero. Two devices belonging to one person, it turns out, rarely raced.

**A correction to the comparison, not to the list.** The first run reported 8,895 `creationDateTime`
differences, every one of the form `…310Z` against `…310607Z`: **a BSON date holds milliseconds and
portal's patch strings hold microseconds**, so the stored document is a truncated copy of the value
the fold reads. Comparing at millisecond precision is not a tolerance — it is the precision at which
the stored document can express anything at all. No cause was added; a measurement error was fixed.

**And a defect the report is structurally blind to.** The imported `context` column still held
`Scholencoordinatie` (1,293), `Medisch huis` (7) and `Personal ` (1) beside their canonical
spellings: `Contexts` was written, tested, and recorded in *`context` is normalised, and the
deployment name wins* — and then only ever called for deployment names that were already canonical.
`PatchTranslator` passed a hand-made task's own context straight through. 1,302 tasks, each stray
spelling its own card under [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md).

The diff report could never have caught it, and the reason generalises: **stored and folded agreed,
because they were wrong in the same way.** The report proves *fidelity to portal*, not *conformance
to the decisions* — so a decision this ADR records and the code does not execute is invisible to it,
and only a test beside the mapping rule can hold that line. Fixed, with a test, and confirmed by a
second run: 25 contexts instead of 29, unexplained still four.

**The dogfooding bar is cleared.** 11,855 tasks and 38,211 patches in; 12,483 tasks and 39,450
patches out; 47 templates; 628 executions synthesised; every #52 figure unchanged. **28 tasks are
open** against 12,455 completed, 13 of them overdue — the number
[#39](https://github.com/stainii/task/issues/39) needs, or the dogfooding copy looks empty and reads
as a bug. The cutover bar is written above and is [#17](https://github.com/stainii/task/issues/17)'s
to apply.

## Amendments

### The import is an epoch-advancing operation

[#72](https://github.com/stainii/task/issues/72). This ADR made the importer **re-runnable and
idempotent** — *truncate and rebuild, never append* — and treated that as a property of the
importer's own convenience: a dry run is free. It is also, unremarked until now, a **rewind of
`sequence`**, and therefore the same operation
[ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md)'s epoch amendment (added via
[ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md)) was written for.

`TRUNCATE task_patch, task CASCADE` followed by `ALTER SEQUENCE task_patch_sequence RESTART WITH 1`
**is** a new lineage of history, by ADR-0004's own definition. `V5__sync_epoch.sql` seeds the epoch
at 1 and nothing on the import path moved it, so ADR-0004's promise — *N means the same patch
forever within one epoch* — was broken silently by the one path that reissues every N. That is
identical to the hazard `deploy/restore.sh` exists as a script rather than a runbook to prevent, and
the reason it went unnoticed for so long is the same one: **the step is invisible and has no
immediate symptom.** The app comes up, the tasks are there, and a device that synced beforehand
concludes it is up to date permanently.

**The importer therefore advances the epoch itself.** `TaskImport#startNewLineage` — the method that
used to be called `deleteAllTasks` — truncates, restarts the counter and bumps `sync_epoch` in **one
transaction**.

Two things about the shape are load-bearing:

- **It is the import's first act, not its last.** The load that follows is thousands of separate
  transactions and can fail halfway, or the process can be killed. A bump at the end would leave
  every partial import sitting in a new lineage under the old epoch, which is the silent failure
  itself. Bumping first can only ever cost a resync nobody needed, and clients hard-reset cheaply.
- **The name says lineage rather than delete.** A caller reading `deleteAllTasks()` has no reason to
  think about cursors, and this defect *is* the invisibility of the step.

The boundary test is `ImportEpochIntegrationTest`, in CI. It is deliberately **not** part of
`PortalArchiveImportIntegrationTest`, which is skipped wherever the archive is not restored — that
is, everywhere except one machine. The epoch step needs no corpus, and the assertion standing
between a partial import and permanent silent divergence should not be one only the author's laptop
ever runs.

**Not discharged by the dogfooding route.** [#39](https://github.com/stainii/task/issues/39) settled
that its round trip goes local scratch import → `pg_dumpall` → `restore.sh live`, which gets a bump
for free from `restore.sh`. This makes the fix belt-and-braces *for that path* and load-bearing for
any direct run of the importer — which is what this ADR's own cutover wording describes.

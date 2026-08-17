# 4. One write verb, two clocks: the offline sync contract

Date: 2026-08-02

## Status

Accepted. Resolves [#7](https://github.com/stainii/task/issues/7).

Amends [#12](https://github.com/stainii/task/issues/12)'s verdict on TODO-005 — undo becomes a
**void patch** rather than a compensating forward patch. See *Undo is a void, not a reversal*.

## Context

Offline use on several devices at once is a requirement, not a feature. The app is a daily driver:
tasks get ticked off on a train, on a phone with no signal, on a laptop that has been shut for a
week. Two clients open at once must stay in sync the whole time, across connection drops — a
requirement stated by the author while resolving [#12](https://github.com/stainii/task/issues/12),
and one the *current* system does not meet: two open clients are observed drifting apart.

The `TaskPatch` model exists to serve this. Clients send timestamped patches over changed fields
rather than whole tasks, and the server replays them. Several pieces of the contract were already
settled before this decision:

- client-minted task **and** patch ids ([#12](https://github.com/stainii/task/issues/12), TODO-009/011)
- a **sorted fold** — last writer wins per field, ordered by the patch's own timestamp, not by
  arrival (TODO-004)
- patch timestamps back to **`Instant`** (TODO-006)
- SSE retained, with merged replay-then-tail on one connection, `Last-Event-ID` resume and id-less
  heartbeats (TODO-012/013/018)
- the creation patch carries **every** field (TODO-046)
- tasks are never deleted ([ADR-0001](0001-one-task-aggregate-with-triggered-templates.md))
- patch history is never compacted ([#4](https://github.com/stainii/task/issues/4))

What was missing was the contract *between* the two sides: the wire, the cursor, the outbox, and
what happens when any of it fails. Three silent-divergence defects were found while writing it —
each one invisible to the user, each one a plausible cause of the drift symptom.

The current front end has **no outbound queue at all**. `TaskStore` caches tasks in `localStorage`
for reading; every write path is missing.

## Decision

### One write verb

**`POST /api/task-patches` is the only way a client writes.** `POST /api/tasks` and
`DELETE /api/task-patches/{id}` are removed.

TODO-046 already requires the creation patch to carry every field, so the creation patch *is* a
complete task and the whole-task endpoint carried no information the patch did not. Collapsing them
gives the client **one outbox** — a single time-ordered list with one item shape — instead of two
kinds of item plus a dependency rule ("never send a patch before its task's create succeeded"),
which is exactly the sort of rule that breaks on a flaky reconnect. Replaying a write is idempotent
by client-minted patch id.

**The first patch for a task id creates the task; a non-first patch for an unknown task id is a
`404`.** No `create` flag on the wire, and no materialising tasks out of arbitrary patches. The
first patch is validated for completeness, so a malformed create is a `400` at the door rather than
a half-built row in the database.

Orphan patches are treated as a client bug rather than a legitimate state, because no legitimate
path produces one: within a device the outbox drains in order, so the create is always first; a
second device cannot patch a task it has never heard of, since it can only learn of a task from the
server, which means the create already landed.

### Undo is a void, not a reversal

**Undo appends a patch carrying `voids: <patchId>`.** The fold skips voided patches and recomputes.

[#12](https://github.com/stainii/task/issues/12)'s compensating-patch undo (TODO-005) derives its
*content* from local knowledge but competes on a *global* clock, and offline undo is precisely when
local knowledge is least complete:

> Patch **P** on the phone sets importance to `IMPORTANT`. The laptop — which has not received P —
> sets importance to `VERY_IMPORTANT` at time T. You undo P on the phone. It sees only `create` and
> `P`, computes "back to `NOT_SO_IMPORTANT`", and stamps it `now`, which is later than T. The fold
> replays create → P → laptop's edit → undo. **The undo wins and the laptop's deliberate edit is
> silently gone.**

A void marker removes *that patch's* contribution and nothing else's, whatever arrived in between
and in whatever order. It is idempotent, order-independent, and keeps history append-only and
truthful — history records "this was undone" rather than "someone set it back", which matters
because [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) makes patch history
load-bearing for the min/max clock anchor and occurrence close dates.

The client needs only the **id** of the patch to undo, so undo still works fully offline; the server
remains the authority on the result. Undo-of-undo is voiding the void.

[#12](https://github.com/stainii/task/issues/12)'s special case survives: **voiding the creation
patch completes the task instead**, since a task cannot un-exist. One semantic change is accepted —
undoing a patch that touched three fields now removes all three contributions, because it undoes the
*action*, not one column.

### Two clocks

A patch carries two timestamps, answering different questions, and neither may do the other's job.

| field | minted by | orders | never used for |
| --- | --- | --- | --- |
| `dateTime` (`Instant`) | the client | the **fold** | delivery |
| `sequence` (monotonic) | the server, on receipt | **delivery** | the fold |

`dateTime` must be client-minted: an offline device is the only thing present when the edit happens.
A server-stamped time would let a patch made offline on Monday and uploaded on Wednesday beat an
online edit made on Tuesday, which is backwards.

But that same property broke the read side. `TaskPatchService.tail()` caught clients up with
`findByDateTimeAfter(since)` — querying by the *client-minted* timestamp:

> The phone is offline all Monday and edits a task. The laptop is online and in sync, so its cursor
> advances to Tuesday. On Wednesday the phone reconnects and uploads its Monday-stamped patch. The
> laptop asks for everything after Tuesday. **The Monday patch is never delivered to the laptop —
> permanently.** The laptop looks connected and is quietly wrong.

`?since=<timestamp>` is therefore replaced by `?since=<sequence>`. A monotonic sequence rather than a
server `Instant`, so it is immune to ties and to the server clock being adjusted, and so the client
can persist its cursor as a single number.

**Consequence**: a late-arriving patch can land *behind* patches the client has already folded, so
the client must **re-fold** a task from its history rather than only applying forward.

**No future-dating guard.** With the client clock deciding who wins, a badly skewed device could win
every field invisibly. The risk is consciously accepted: there are few clients and their clocks are
NTP-synced.

### Reads

**`GET /api/tasks` is a rare, explicit operation — first run and hard reset only.** It returns open
tasks with their history, plus **the sequence watermark it was read at**. The snapshot is what makes
a cold start cheap; rebuilding from patches alone would mean replaying every patch ever written, a
set that only grows since [#4](https://github.com/stainii/task/issues/4) ruled out compaction.

The normal boot path is **not** snapshot-then-stream:

- **local state exists** → render from local storage immediately, before any network, then open the
  stream from the stored sequence
- **no local state (first run)** → snapshot + watermark, then stream from the watermark
- **hard reset** → snapshot + watermark, then stream

Today's client does `fetchTasks()` then `initStreaming()`, which opens a window between the `GET`
completing and the stream attaching in which **every patch is lost forever**. The watermark closes
it. Overlap is deliberately preferred over gaps: re-receiving a patch is a no-op (idempotent by id),
whereas a gap is unrecoverable.

The client is authoritative for **display** at all times. Neither the stream nor the snapshot is a
precondition for the app being usable.

### The stream

- SSE events carry the **`sequence`** as the event id, so `Last-Event-ID` is a cursor the browser
  maintains for free.
- **Heartbeats carry no id**, every 15s — they keep proxies from idling the connection out.
  (Stamping them with a random UUID clobbered the browser's stored `Last-Event-ID`; found in
  [#12](https://github.com/stainii/task/issues/12).)
- The server honours **`Last-Event-ID` first, `?since=<sequence>` second**. Both exist because they
  serve different callers: the browser's automatic reconnect sends the header, while a client
  booting from local storage sends the parameter deliberately.
- A client **sees its own patches echo back**. That is the acknowledgement that a patch is durable
  and has a sequence. Dedupe by patch id.
- **The server bounds connection lifetime** (15–30 minutes) and closes cleanly, forcing the client to
  reconnect with a freshly-minted token.
- A cursor the server cannot serve gets an explicit **resync** signal, telling the client to drop
  local state and re-snapshot — the server pulling the hard-reset lever the user already has.

Bounded lifetime is chosen for two reasons. An SSE connection authenticates once at open and nothing
re-checks it, so an unbounded stream keeps delivering data on a token that expired hours ago. More
importantly, **it makes the resume path self-testing**: reconnect-and-resume is the most load-bearing
and least observable mechanism in this contract — both of [#12](https://github.com/stainii/task/issues/12)'s
SSE defects lived there — and if it only runs when wifi drops it can rot for months undetected.
Running it every 20 minutes on every device turns a regression into today's bug.

### The outbox

The outbox drains **strictly in order**, which is what makes "the first patch is the create" safe.
Strict ordering has a sharp edge: one undeliverable patch would otherwise block everything behind it
forever, freezing all sync on that device while the app still looks fine, because local state is
authoritative for display.

| outcome | meaning | client action |
| --- | --- | --- |
| `2xx` | accepted and durable | remove from outbox, advance |
| `400` | malformed or incomplete create | **drop, continue**, surface it |
| `404` | unknown task (orphan) | **drop, continue** |
| `5xx` / network / offline | server or link is down | **stall in place**, retry with backoff |

The rule underneath: **`4xx` means the patch is permanently wrong, so drop it and keep going; `5xx`
and network mean the patch is fine and the world is not, so stop and preserve order.**

A dropped patch is data loss the user must be able to see: the client keeps dropped patches in a
visible **failed-to-sync** list rather than discarding them silently.

**A retry re-reads the radio; it never trusts what it was last told about it.** The browser's
`online` event is what makes coming back *prompt*, and it is the one signal here that is a courtesy
rather than a contract — a loaded machine drops it, and a phone leaving a tunnel is the same shape.
If it is also the only thing that can lift the offline stall, then missing it once is permanent: the
outbox goes on retrying to schedule and every retry is turned away by a belief that is merely stale,
so the backoff looks like a safety net and is not one. Both loops therefore ask `navigator.onLine`
again before each attempt, which makes the event an optimisation and caps the cost of missing one at
a single backoff interval ([#69](https://github.com/stainii/task/issues/69)).

The client then reacts to **the radio being back**, not to whichever of the three — event, outbox
retry, stream retry — happened to find out. They race, and a hand-off from the discoverer leaves the
other two looking at a fact that is already true and concluding there is nothing to do, so work that
rides the same moment (the template fetch) belongs to nobody.

**`409` never reaches the client.** `Task` and `TaskPatch` carry `@Version`, so two devices patching
the same task in the same instant produce an optimistic-locking failure — today a `500`, which under
the table above would stall the queue. The server retries internally instead, keeping the state out
of the contract entirely.

### The fold exists twice

The client must fold — that is what makes offline use possible and optimistic edits instant. The
server must fold — it is the authority. So the algorithm exists in **Java and in TypeScript**, and
every rule is a rule both must implement identically: sort by `dateTime`, replay from the creation
patch, last writer wins per field, skip voided patches, voiding the creation patch completes the
task, re-fold on late arrival, ignore duplicate patch ids.

If the two drift by one rule, the device shows one thing and the server believes another, **with no
error anywhere** — the same silent-divergence class as the three defects above.

**The fold is pinned by shared golden fixtures**: JSON files in the repo, each a patch history plus
the exact expected resulting task, executed as test cases by both the Java suite and the Angular
suite. **No fold rule without a fixture.** This makes divergence a build failure rather than a field
report, and gives the contract an executable definition instead of only a prose one.

### Templates are online-write-only

**Task templates are read-cached offline and written online only** — plain CRUD, no patches, no
outbox, no sequence, no stream.

Full parity would mean a second patch model, a second outbox, a second stream and a second fold,
roughly doubling the sync surface. And a template edit has no sound offline semantics: the server is
the only thing that can fire a template, so a rule edited offline on Saturday competes with tasks the
old rule generated on Sunday, and last-writer-wins says nothing useful about that.

The asymmetry is real rather than incidental: **patching works for tasks precisely because tasks are
inert.** Nothing happens to a task while you are away, so replaying a late patch onto one is always
sound. A template is a rule that keeps running in your absence.

Offline, template editing is **visibly unavailable** rather than silently failing. Template firings
still reach the client as ordinary task patches on the task stream. This is reversible: adding
patches to templates later invalidates nothing decided here.

### What the client keeps

The client keeps closed tasks and their history for **1 day**, then discards them locally. The server
keeps everything forever regardless.

`GET /api/tasks` returns open tasks only, and there is no endpoint to fetch a closed task back
(reporting is out of scope). So a client that drops closed tasks immediately loses the patch id undo
needs, breaking undo for the thing you just did — the one undo that matters most. One day is chosen
deliberately: **undo is the immediate "oh no, that is not what I meant", not an archive.**

**Undo therefore has a client-side horizon**, and a hard reset erases it. That is the deal, not a
defect to fix later.

Local storage is **IndexedDB**, holding tasks, history and a durable outbox that survives a browser
kill. `localStorage` — today's mechanism, synchronous and rewriting the whole blob on every change —
is not adequate for history plus an outbox.

Safari/iOS deletes script-writable storage after 7 days without site interaction, and an evicted
outbox is silent loss of edits made offline. **The risk is accepted**: PWA installation, which exempts
the origin, is the intended mitigation on the author's own devices. `navigator.storage.persist()`
should be requested regardless. The service worker and installability belong to
[#9](https://github.com/stainii/task/issues/9); portal had a service worker and `task-front-end` has
none, so today the app cannot cold-start offline at all.

## Consequences

- `POST /api/tasks`, `CreateTaskDto`, `DELETE /api/task-patches/{id}` and `Task.undoPatch`'s
  reverse-engineering logic are deleted. `TaskService.create` survives only for
  template-generated tasks.
- `TaskPatch` gains `sequence` (server-assigned, monotonic) and `voids`. `TaskPatchDto` carries
  `id`, `dateTime`, `sequence`, `changes` and `voids` in both directions; SSE stops emitting the raw
  domain object.
- `findByDateTimeAfter` is replaced by a sequence-based query. `?since=` changes type from
  `ZonedDateTime` to a sequence, a **breaking API change** — acceptable, as the only client is being
  rewritten.
- The fold rebuild (TODO-004) absorbs voids while it is being written. Doing it later is expensive;
  doing it now is nearly free.
- Golden fixtures are a new, shared test asset, and a rule
  [#10](https://github.com/stainii/task/issues/10) inherits.
- Optimistic-lock retry becomes a server-side concern with no wire representation.
- [#22](https://github.com/stainii/task/issues/22): whatever proxy fronts the app must not idle-kill
  the stream inside the 15s heartbeat window, and must tolerate a connection closing every 15–30
  minutes by design.
- [#28](https://github.com/stainii/task/issues/28): bounded stream lifetime is the mechanism that
  stops an expired token from streaming data indefinitely.
- [#9](https://github.com/stainii/task/issues/9) owns: IndexedDB store, the outbox, the failed-to-sync
  list, per-reconnect token refresh, the service worker and PWA installability, and disabling
  template editing while offline.

## Amendments

### `401` / `403` stall the outbox; they do not drop from it

Amended by [Feature triage: portal front-end features](https://github.com/stainii/task/issues/14),
2026-08-03.

This ADR's outbox rule is `4xx` drop-and-continue into a visible failed-to-sync list, `5xx` and
network errors stall-and-preserve-order. `401` and `403` are `4xx`, and under the rule as written
they drop.

That is wrong, and the failure is the silent kind this ADR exists to prevent. `4xx` drops because
the patch will never be accepted — it is malformed, or its task does not exist. `401`/`403` say
nothing about the patch: it is fine, and **the client is not authenticated yet**. Under the
unamended rule, coming back online after a week away with an expired refresh token discards the
entire week of queued work into a failed-to-sync pile, one patch at a time, without ever naming
authentication as the cause.

`401` and `403` are therefore **stall-and-preserve-order**, like `5xx`, with one addition: when the
client is online, the stall **raises a visible login prompt**. Offline there is nothing to prompt
for and the outbox simply waits. Once authentication succeeds the queue drains in order behind it.

This is the same behaviour portal's retry interceptor had in cruder form — FE-028 re-logged in on an
expired token before retrying.

The prompt is distinct from Keycloak's `onLoad: 'login-required'`, which
[#14](https://github.com/stainii/task/issues/14) deletes. That setting gates the app at boot and
makes an offline cold start impossible. This prompt is raised *because a sync needs it*, at the
moment it is needed.

### The auth rule: authenticate to sync, not to see

Same amendment. First run requires authentication — `GET /api/tasks` is first-run-only, so there is
nothing to render before it. Every cold boot after that renders from IndexedDB with no token and no
network. Reads and writes both work offline; writes queue. An expired token degrades the client to
offline mode rather than bouncing it to a login screen.

The consequence is that task data is readable on an unlocked device without a login. Accepted as the
right trade for a personal app behind a device passcode — the alternative is an offline-first app
that does not work offline. Handed to [#28](https://github.com/stainii/task/issues/28) as a security
posture decision made here rather than there.

### The epoch: `sequence` is only monotonic within one lineage of history

Amended by [Where does this deploy, and what does CD look like?](https://github.com/stainii/task/issues/22),
2026-08-04. See [ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md).

This ADR makes `sequence` the only sync cursor and rests the whole read side on one promise:
**number N always means the same patch, forever.** That promise assumed the server's history only
ever moves forward. ADR-0007 introduces an operation that moves it backwards — **restoring a
database backup**, which is the schema half of rollback.

The failure, with the phone and the laptop both in sync at sequence 40:

| Time | Event | Server | Phone believes | Laptop believes |
| --- | --- | --- | --- | --- |
| 02:00 | nightly dump taken, last patch in it is 40 | 40 | 40 | 40 |
| 02:05 | deploy runs, Flyway migrates, the new version is bad | 40 | 40 | 40 |
| 07:00 | three patches made on the phone | 41, 42, 43 | 43 | 40 |
| 09:00 | rollback: previous digest pinned, 02:00 dump restored | **40** | 43 | 40 |
| 09:05 | a due date changed on the laptop | assigns **41** | 43 | 41 |
| 09:10 | phone reconnects: *"I have up to 43, send me 44+"* | nothing past 41 | **43** | 41 |

Losing patches 41–43 is expected — that is what restoring a backup means, and three changes get
re-entered by hand. The damage is what happens afterwards:

- The phone is told nothing and concludes it is **up to date, permanently**. It never receives the
  laptop's change, and nothing about it looks broken. This is the same silent-divergence class as
  the `?since=<dateTime>` defect above.
- **41 now means two different things** — "renamed a task" on the phone, "changed a due date" on the
  server and laptop. Two devices holding contradictory records of the same number, forever.

**The server therefore carries an `epoch`: one integer naming which lineage of history it is on.**
Clients persist it alongside their cursor and present both on every reconnect. The restore procedure
increments it. A client presenting a stale epoch is answered with a **resync** — the lever this ADR
already defines — rather than a stream, so it reloads from a fresh snapshot and lands in the current
lineage.

`sequence` is unchanged: still server-assigned, still monotonic, still never used to merge. The
epoch narrows the promise to what is actually true — **N means the same patch forever *within one
epoch***.

Cost: one stored value, one field on the snapshot and the stream handshake, one comparison on
connect. The alternative considered and rejected was a rollback procedure instructing the operator
to clear site data on every device — free, and dependent on remembering every device that has ever
opened the app, at the worst possible moment.

### A local write is acknowledged only once it is durably in the outbox

Amended by [Observability: how do you find out it's broken?](https://github.com/stainii/task/issues/27),
2026-08-05.

This ADR made the outbox the single ordered path for every write, and specified what happens to a
patch once it is in there — `4xx` drops to a visible failed-to-sync list, `5xx` and network errors
stall and preserve order. It never said what happens if the patch **never gets in there**.

If IndexedDB throws on put — quota exceeded, storage evicted mid-session, a private-window
restriction — the tick lands in the UI and nothing is queued. It looks exactly like a successful
write, and it is discovered when the task reappears, or never. That is the same class as the
`?since=<dateTime>` defect and the rewound `sequence`: a silent divergence with no symptom at the
moment it happens.

**The UI acknowledges a write only after the patch is durably in the outbox.** A failed local write
shows as failed. This is a rendering rule, not a change to the sync contract — `sequence`, the fold
and the outbox semantics are untouched.

See [ADR-0009](0009-the-app-is-its-own-monitor.md), which rejected front-end telemetry in favour of
this guarantee: an error report arrives after the tick has been forgotten, while a write that refuses
to lie is visible at the moment it matters.

### The write-path contract: a duplicate patch id is `200`, not an error

Amended by [Security posture of an internet-exposed personal app](https://github.com/stainii/task/issues/28),
2026-08-05.

This ADR made patch ids **client-minted** and made the outbox stall on `5xx` while preserving order.
Both are right, and together they deadlock.

Patch ids are the primary key. On an ordinary mobile connection the client POSTs a patch, the server
commits it, and the response is lost. The outbox retries — exactly as specified. The retry hits a
primary key violation, which surfaces as `500`. The `5xx` rule says stall and preserve order. It
retries. Another `500`. **Forever.** A successful write, retried once, permanently wedges the queue,
and [ADR-0009](0009-the-app-is-its-own-monitor.md)'s *online but not syncing* banner fires correctly
on a system where nothing is wrong except that the server refused to be told the truth twice.

**A patch id already stored is `200`, a no-op.** The client-minted id is the idempotency key — that is
what it was always for, and it is what this ADR's "`409` never reaches the client" was reaching for.
Replay stops being a hazard and becomes the mechanism that makes the retry path safe.

The full contract, since the rest of it was implied rather than written:

| Case | Response |
| --- | --- |
| Patch id already stored | **`200`**, no-op |
| Unknown field name in `changes` | `400` |
| Value does not parse as its field's type | `400` |
| Creating patch missing a required field | `400` |
| Patch for a task id that does not exist | `404` (the orphan rule above) |
| Body over the size cap | `413` |
| Anything else | `5xx` |

Unknown fields are rejected rather than ignored because the patch log is append-only and
[ADR-0005](0005-migration-by-replay-into-one-history.md) replays it forever: anything accepted is
accepted permanently. See [ADR-0010](0010-a-tunnel-an-allowlist-and-a-role.md), which also
re-examined this ADR's rejection of a client-clock guard and **left it closed**.

### `completedOn` is a domain clock, and it is not a third sync clock

Amended by [How does a template learn one of its occurrences was done?](https://github.com/stainii/task/issues/33),
2026-08-07. See [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md).

Reproducing portal's explicit *"I did it last Tuesday"* adds a **`completedOn`** date to `Task` — a
normal patchable field, set on every completion and defaulting to today.

It could have been expressed by backdating the completing patch's own `dateTime`, at no schema cost.
That is precisely what this ADR forbids: `dateTime` is the **write** clock, ordering the fold and
deciding last-writer-wins. Backdating it would make a Tuesday completion **lose** to any Wednesday
edit from another device — correcting a name on a laptop would silently un-complete the chore — and
on the complete-an-open-task path it would sort **before** that task's creation patch, which this ADR
defines as the first patch for an id. A task completed before it existed.

So `completedOn` is a third clock only in the sense that it is a **domain value on the aggregate**,
carried in `changes` and merged by the ordinary fold. Correcting a mis-entered date is just a later
patch winning, which is the right behaviour. The two sync clocks are untouched.

Note for the fixtures: patch payloads and the golden fold fixtures both grow a field.

### The fixture rule extends to template rendering

Amended by the same ticket.

This ADR pinned the fold with shared golden fixtures because it exists in Java and TypeScript, and
[#10](https://github.com/stainii/task/issues/10) turned that into a rule — *no fold rule without a
fixture*. ADR-0011 makes out-of-band completions **client-minted**, so that the "I already did this"
affordance works offline, which puts **template rendering** in both languages too.

Template rendering gets the same protection: a sibling fixture directory holding template + inputs →
expected tasks, enumerated by both suites with the same non-empty assertion. **No rendering rule
without a fixture.** The rendering code is small; the failure mode — a task named or dated
differently on one device than another, visible only in history — is not.

### The fold rules this ADR left implicit

Amended by [Rebuild the fold: `Task`, `TaskPatch` and the shared golden fixtures](https://github.com/stainii/task/issues/45),
2026-08-11.

Writing the fold twice — once in Java here, once in TypeScript in
[#55](https://github.com/stainii/task/issues/55) — meant every rule had to be exact, and four were
not. Each is now a fixture in `/fold-fixtures/`, which is what "no fold rule without a fixture"
turns out to protect: not the rules this ADR stated, but the ones it did not.

**Ties are broken by patch id, compared as a string.** This ADR ordered the fold by `dateTime` and
stopped there. Two devices can mint patches in the same millisecond, and then "last writer wins"
names no winner. It cannot be `sequence` — the client folds patches it has not sent yet and has no
sequence for them, so the fold would give a different answer on each side, which is exactly the
divergence the shared fixtures exist to prevent. The id is the only ordering both sides can compute
offline. **As a string**, deliberately: `UUID.compareTo` compares signed longs and orders
differently from every lexicographic comparison a TypeScript implementation would reach for.

**A void naming a patch that is not earlier in fold order does nothing.** Voids are resolved by
walking the history *backwards*, so a void that has itself been voided is already inactive by the
time it would have removed anything. That direction is also what makes *undo-of-undo is voiding the
void* terminate: two patches voiding each other is unrepresentable rather than a cycle the algorithm
has to survive.

**A change key the fold does not recognise is ignored.** The write-path contract above rejects
unknown field names with a `400`, and that stands — but it guards the *door*, and the fold also
replays years of migrated history that never came through it
([ADR-0005](0005-migration-by-replay-into-one-history.md)). A key that names no field changes
nothing rather than failing the fold.

**A change whose value is null clears the field.** Absent from a patch and present-but-null are
different things, so neither implementation may drop null values on the way in — a detail that
matters because the obvious immutable-map idiom in Java (`Map.copyOf`) rejects them outright.

Two model consequences, both narrowing this ADR rather than contradicting it:

- **`TaskPatch` loses `@Version`.** This ADR gave `Task` and `TaskPatch` optimistic locking so that
  `409` never reaches the client. A patch is immutable and append-only — there is no update to lose
  a race over — so the version column was ceremony. `Task`'s version is what serialises two
  concurrent folds, and that is untouched.
- **`Task.creationDateTime` becomes an `Instant`.** The creation patch carries it verbatim, so
  leaving it a `LocalDateTime` would have meant a zone conversion at the one point in the model
  where no zone is available — and `ZoneId.systemDefault()` is a compile error here (#44).

### The event id is `epoch:sequence`, because the browser's own reconnect carries nothing else

Amended by [One write verb, one stream: the sync API](https://github.com/stainii/task/issues/46),
2026-08-11.

This ADR made the SSE event id the **`sequence`**, so that `Last-Event-ID` is a cursor the browser
maintains for free. The epoch amendment above separately told clients to "persist it alongside their
cursor and present both on every reconnect".

The two rules do not compose. On the reconnect the browser performs **by itself** — after a dropped
connection, and after every one of the bounded-lifetime closes this ADR schedules several times an
hour — the only thing sent is `Last-Event-ID`. A bare sequence there is a cursor with no lineage, so
the epoch check would run on the deliberate reconnect and be **skipped on the automatic one**: the
common path, the unattended path, and precisely the path a client is on the morning after a restore.

**The event id therefore carries both, formatted `epoch:sequence`**, and `?since=` is only accepted
together with `?epoch=` — a `400` otherwise, rather than defaulting the epoch to the server's own,
which would produce a cursor that can never be found stale.

Three cases now answer **resync** rather than a stream: a cursor from another epoch, a sequence past
the end of history, and an event id this server did not write. The last one matters for the same
reason as the first two — a stream served on a cursor the server cannot account for looks healthy
while silently skipping whatever the cursor could not name.

### A creating patch is one that carries `creationDateTime`

Amended by the same ticket.

This ADR says the first patch for a task id creates it, that a non-first patch for an unknown task id
is a `404`, and that an incomplete create is a `400` — with **no `create` flag on the wire**. On the
wire, though, an incomplete create and an orphan are the same thing: a patch naming a task that does
not exist, carrying fewer fields than a task needs. Without a discriminator only one of those two
rows of the contract table is reachable.

The model already has one. The creation patch is a dump of **every** field (TODO-046) and nothing
else ever restates when the task came into being, so **a patch carrying `creationDateTime` is a
create** and anything else is an edit. A create that does not then fold into a complete task is a
`400`; an edit for a task nobody has heard of is a `404`. Both are dropped by the outbox, but only
the `400` is the client's own work going missing, and only that one needs a human to see it.

Two smaller rules, written down because they were being enforced nowhere:

- **A patch that changes nothing and voids nothing is a `400`.** It would otherwise burn a sequence
  number and echo to every client to say nothing happened.
- **A patch is capped at 64 KB of changes** (`413`, as the contract table already said). The log is
  append-only and never compacted, so there is no later opportunity to disagree with what was
  accepted.

### The write is not announced until it has committed

Amended by the same ticket.

The stream is how a client learns its write is durable — this ADR makes seeing its own patch echo
back the acknowledgement, and the outbox drops the patch on the strength of it. Emitting from inside
the transaction therefore promises durability the server has not got yet: a rollback afterwards
leaves the client having discarded a patch the database never kept.

Emission moves after the commit. In the same move, an optimistic-lock failure is **retried outside
the transaction** rather than inside it, which is what "`409` never reaches the client" requires:
the loser re-reads the winner's task and folds onto it, three attempts, then an honest error. A
unique-key violation is retried on the same footing, because the only way to raise one here is two
attempts to create the same task at once — and on the second pass the id is already in the history,
so it resolves to the no-op it always was.

**A constraint this hands to [ADR-0008](0008-every-backup-restores-itself-before-it-is-kept.md)'s
`restore.sh`**: `task_patch.sequence` is unique, so a restore that loads rows without restoring the
sequence generator's own position leaves the generator *behind its data*, and the next write is a
`500` — which the outbox reads as *the server is down* and retries forever. `pg_dumpall` emits
`setval`, so the chosen mechanism is already safe; it is written down here because nothing else says
so, and this is the fifth time on this map that a guarantee living in code turned out to depend on
something living outside it.

### The one-day horizon never discards a task with an unsent patch

Amended by [The local store: IndexedDB and the fold in TypeScript](https://github.com/stainii/task/issues/55),
2026-08-13.

This ADR says the client keeps closed tasks and their history for **1 day**, then discards them
locally. Written that way it is unconditional, and building it exposed the case it does not cover:
a task completed offline and never synced is a *closed* task, so on day two the sweep deletes it —
including the patches sitting in the outbox, whose bodies **are** the request that has not been
made yet.

The rule everything else in this ADR turns on is that an evicted store is not data loss, because
the hard-reset path refetches it — **only an undrained outbox is.** A prune that takes the outbox
with it is that same loss, self-inflicted on a timer, and it would be worst on exactly the device
that has been offline longest.

So the horizon carries one exception: **a task with any patch still in the outbox is never pruned,
however old.** It becomes prunable the moment its last queued patch is acknowledged, which is the
first moment the server can hand it back.

### A history that cannot fold yet produces no task, not an error

Amended by the same ticket.

The client stores patches and materialises tasks from them. A patch whose task has no creation
patch — the first arrival of a resync landing out of step, or a prune that raced a late edit —
cannot fold into a whole task, since the fold replays *from* the creation patch and every required
field comes from it.

The client keeps the patches and simply produces **no row** for that task. It is neither an error
nor an empty task: the history is incomplete, not wrong, and the row appears by itself when the
missing patch lands. Rendering a partial task would be worse than rendering nothing, because a task
with no name is indistinguishable from a task somebody named badly.

### A resync keeps the outbox, and refolds what it keeps

Amended by [Sync: the outbox, the stream client and the auth stall](https://github.com/stainii/task/issues/56),
2026-08-13.

This ADR defines **resync** as the server telling a client to "drop local state and re-snapshot —
the server pulling the hard-reset lever the user already has". Building it made the equivalence
untenable. The hard reset is a human deciding the local copy is the problem. A resync is issued
because the *server* cannot serve a cursor, and by far the likeliest reason is
[ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md)'s restored backup — which is precisely the
moment a device is most likely to be holding patches the server has never seen.

Dropping them there is **the one loss this ADR calls real**: an evicted store is not data loss
because the snapshot recovers it, and only an undrained outbox is. A resync that takes the outbox
with it inflicts that loss on the user *because* the server lost some of its own history.

So a resync clears the tasks and the patches that came from the server, keeps the outbox and every
patch it names, and clears the cursor so the next connection snapshots. A kept patch whose task did
not survive the restore is answered `404` and lands in the visible failed-to-sync list, which is the
mechanism this ADR already has for saying so out loud.

**And the kept patches are refolded immediately**, rather than left for the snapshot to restore. A
task created offline exists *only* in the outbox, so clearing the task rows and waiting would take
it off the screen until the server echoed it back — which on a device that is still offline is
never. It would look lost while being perfectly safe, which is the same lie as the opposite case.

### Authentication is initialised lazily, and `check-sso` is an iframe

Amended by the same ticket.

*Authenticate to sync, not to see* was written as a rule about tokens. Building it made it a rule
about **when the auth adapter runs at all**, because three ordinary ways of wiring Keycloak into an
Angular app each break the cold start offline:

- **`provideKeycloak` in an `APP_INITIALIZER`** gates the first paint on the auth server being
  reachable. Deleting `onLoad: 'login-required'` (which [#14](https://github.com/stainii/task/issues/14)
  did) is not enough on its own — the initialiser itself is the gate.
- **`onLoad: 'check-sso'` without a silent redirect URI** sends the whole page to the auth server
  and back. On a device with no signal that is a browser error page instead of an app.
- **`silentCheckSsoFallback`** (on by default) turns the iframe back into that navigation whenever
  the iframe cannot be used, reintroducing the failure on exactly the browsers most likely to block
  third-party frames.

So the adapter is constructed on **first use by something that needs a token**, `check-sso` runs
through a `silent-check-sso.html` iframe on the app's own origin, and the fallback is off. A failed
initialisation is remembered as *not now* rather than as a verdict: the usual cause is that the
network was not there yet.

`keycloak-angular` is dropped as a dependency in the same move. What it adds over `keycloak-js` is
the bootstrap provider and interceptor helpers, and the provider is the one thing this design cannot
use.

### One reconnect path, resuming from the persisted cursor

Amended by the same ticket.

`@microsoft/fetch-event-source` has a retry of its own, and it is switched **off** (its `onerror`
throws). It retries in memory with the `Last-Event-ID` it happens to hold; this client resumes from
the cursor it *persisted*, which is the same cursor a cold boot uses and the only one that survives
a browser kill. Two mechanisms implementing one rule is how the two drift, and this ADR already has
six amendments about rules that turned out to exist in two places.

Two consequences worth writing down, both of which the library's shape makes easy to get wrong:

- **A clean close is not the end.** The library resolves its promise when the response body ends,
  which for this server is the *normal* case, several times an hour by design. The reconnect after a
  bounded-lifetime close is therefore the client's own job, not the library's.
- **`defaultOnOpen` does not check the status.** It checks the content type only, so a `401` arrives
  as *expected content-type to be text/event-stream* rather than as an authentication failure. The
  stall and the login prompt need `response.status`, which means supplying an `onopen`.

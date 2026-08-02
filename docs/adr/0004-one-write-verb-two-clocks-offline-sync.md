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

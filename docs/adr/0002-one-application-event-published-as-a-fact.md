# 2. One application event, published as a fact

Date: 2026-08-02

## Status

Accepted. Resolves [#5](https://github.com/stainii/task/issues/5).

## Context

In portal, `portal-todo` carried a user-configurable **subscription** engine: other services
published events to RabbitMQ, and SpEL mappings stored per subscription turned matching events into
tasks. `portal-todo` also published six lifecycle events of its own (`TaskCreated`, `TaskCompleted`,
`TaskCancelled`, `TaskPatched`, `TaskRescheduled` and a create-request) outward onto the broker.

[#12](https://github.com/stainii/task/issues/12) already killed all of it. Every remaining event
producer is either out of scope (`weather`, `location`, `activity`, `image`, `email`) or now runs
in-process, so there is nothing left to subscribe to, and a user-authored SpEL rules engine would be
the most security-sensitive surface in the codebase. All six outbound lifecycle events and the
RabbitMQ wire dropped with it.

That left a narrower question, and it is the one this ADR answers: **which concrete in-process
application events does the modulith actually need, and what is the standing rule for minting one?**

The starting point in `task-back-end` was a single event, `TaskCreationRequestedEvent(List<Task>)`,
published by `recurring/scheduler/CreateDueTasks` and consumed by `task/eventlistener/TaskEventListeners`,
whose whole body is `taskService.create(event.tasks())`.

[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) moved the ground under it: `recurring`
merges into `template`, `Execution` and `activeTask` are deleted, and an occurrence is **derived**
rather than stored.

## Decision

**Cross-module communication is by application event.** The alternative — a direct call from the
firing side into `task`'s exposed API — was put and rejected: the decoupling is judged worth the
navigational cost over the long run. A retro on whether that paid off is deliberately post-migration.

**Events are facts, in the past tense, named for what happened in the publisher's domain — never for
what a listener should do about it.** `TaskCreationRequestedEvent` becomes **`TaskTemplateFired`**.
No `Event` suffix.

**An event payload is self-contained, and is never another module's aggregate.** `TaskTemplateFired`
carries the template id, the occurrence id, the firing date, and the **rendered** task definitions —
`${variable}` placeholders already substituted, day offsets already resolved to real dates — as a
value record. Constructing a `Task` from those descriptions stays entirely inside `task`.

**Rule 1: an event exists only when a cross-module listener exists.** Not "might exist" — exists, in
`main`, today. Within a module, call directly. Adding an event later is one local, compile-checked
commit.

**The modulith therefore needs exactly one event: `TaskTemplateFired`.** Every other candidate dies
to rule 1, with a reason:

| Candidate | Why there is no event |
| --- | --- |
| `TaskClosed` / `TaskCompleted` | ADR-0001 derives occurrences, so the template side **queries** rather than reacts |
| `TaskCancelled` | Same; and what closes a stuck occurrence is still open on [#33](https://github.com/stainii/task/issues/33) |
| `TaskCreated`, `TaskPatched`, `TaskRescheduled` | SSE is intra-module — `TaskPatchService` calls `TaskPatchSseEmitterService` directly, both inside `task` |
| anything touching `goal` | The module is empty and goals are out of scope ([#4](https://github.com/stainii/task/issues/4)) |

**The event type lives in `task`, the consumer, as a deliberate inbound port.** Events invert the
code dependency: a listener depends on the module owning the event type. Since `template` must read
`task` (min/max needs the last completion date, calendar needs "is an occurrence open"), putting the
type in `template` would make the graph cyclic and fail `ApplicationModules.verify()`. Keeping it in
`task` means every arrow points inward at the core aggregate and **`task` has no outbound module
dependencies**. The type is still *named* for the publisher's fact.

**Delivery is synchronous and in-transaction** — a plain `@EventListener`, not
`@ApplicationModuleListener`. No event publication registry, no outbox table, no broker.

## Consequences

- **Firing and task creation are atomic.** There is no state where a template counts as fired but no
  task exists — the failure mode that would look, from the outside, exactly like today's `activeTask`
  bug.
- **Crash recovery comes from the model, not from infrastructure.** ADR-0001 made firing derived from
  task state, so a crash mid-firing is not a lost message: the next cron tick recomputes and refires.
  Adding the persisted registry on top would give two recovery mechanisms that can disagree — a
  replayed event landing on a firing the cron already redid, producing duplicate tasks for one
  occurrence.
- **Nothing needs a broker.** Not the modulith (same process, same transaction); not the front-end
  (SSE is already the push channel, and it is intra-module). The one thing that needed durable async
  delivery was the subscription engine, and [#12](https://github.com/stainii/task/issues/12) killed it.
- **Tripwire: the first event that must flow `task → template` breaks the inbound-port arrangement**
  and forces the event types into a shared kernel module both sides depend on. The likely trigger is
  [#33](https://github.com/stainii/task/issues/33)'s cancelled-or-abandoned task, if its answer turns
  out to need the template side to *react* rather than *query*. This is a foreseen fork, not a
  surprise; rule 1 says the event gets minted there, with its listener.
- **`ApplicationModules.verify()` gains something real to check.** Modules are currently implicit by
  package — there are no `@ApplicationModule` declarations and no named interfaces. Making the
  inbound port explicit is [#6](https://github.com/stainii/task/issues/6)'s work.
- **The rendered-definition payload will resemble `Task`'s fields.** That duplication is intended: it
  is the module contract, and it is allowed to drift from `Task`'s internals.
- **Rendering moves to the publisher.** Substituting variables and resolving offsets happens in
  `template`, where `TaskDefinition` owns them, before the event is published — not in the listener.

## Alternatives considered

- **A direct call into `task`'s exposed API, deleting the event.** The publisher depends on the
  outcome, so the decoupling is arguably fictional, and the listener is a one-line delegation.
  Rejected by the author in favour of the long-run flexibility of a consistent event seam.
- **Commands rather than facts** (`TaskCreationRequestedEvent`, `RegisterExecutionRequested`).
  Rejected: the publisher keeps accumulating knowledge of what should happen downstream, which is
  the coupling events are paid to remove.
- **A thin payload** — ids only, listener calls back into `template` for the detail. Rejected: it
  creates a call cycle and makes the event unactionable on its own.
- **Publishing the domain's facts regardless of listeners.** Rejected: portal's six lifecycle events
  died exactly that way. Every persisted payload is a schema that must stay deserializable forever,
  and unlike portal, adding an event here is local and cheap.
- **A shared kernel module owning event types.** Rejected *for now* — a module for one record. It is
  the named remedy if the tripwire above fires.
- **`@ApplicationModuleListener` with the persisted event publication registry.** Rejected: costs a
  table and a Flyway migration, gives up atomicity, and introduces duplicate delivery to be
  idempotent against — buying durability the derived model already provides.

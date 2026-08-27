# 11. Completion is a task fact, which the template reads

Date: 2026-08-07

## Status

Accepted. Resolves [#33](https://github.com/stainii/task/issues/33).

Amends [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) and
[ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md); corrects
[ADR-0005](0005-migration-by-replay-into-one-history.md).

## Context

Portal closed the loop _recurring task → todo task → done → execution recorded_ over RabbitMQ, with a
`flowId` correlating the two services. [#12](https://github.com/stainii/task/issues/12) dropped
`flowId`, [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) deleted `Execution` and
`activeTask` and made an occurrence derived rather than stored, and
[ADR-0002](0002-one-application-event-published-as-a-fact.md) refused a `TaskClosed` event because
the template side is meant to **query** task state rather than react to it.

That left three things genuinely open, all of which this ADR answers:

1. **What happens to a scheduled template's clock when its task is cancelled rather than completed.**
   ADR-0001's amendment from [#14](https://github.com/stainii/task/issues/14) anchored the min/max
   clock to the last _completed_ patch, which is right about history and, as it turns out, wrong
   about scheduling.
2. **How you say "I already did this"** for a template that is showing nothing — portal's
   housagotchi form, kept as REC-005 by [#13](https://github.com/stainii/task/issues/13), whose
   meaning evaporated when `Execution` was deleted.
3. **Whether reproducing portal's explicit "I did it last Tuesday" date needs a field**, which
   ADR-0001 raised and deferred here.

`housagotchi-add-execution.component.html` settles that the third is a real feature and not a
theoretical one: a **required** `mat-datepicker` labelled _"When did you do it?"_, defaulting to
today, next to a dropdown asking _"What did you do?"_.

## Decision

### Two anchors, not one

ADR-0001 anchors the `MinMax` clock to the last completion, and [#14](https://github.com/stainii/task/issues/14)
sharpened that to the last patch setting `COMPLETED`. Run forward against the firing rule, that
composes into a loop:

> Template `Beddengoed wassen en bed stofzuigen`, min 10. Completed 15 February. Fires 1 March. On
> 1 March the task is cancelled, so nothing is open and nothing suppresses the template. The last
> completion is still 15 February, which is more than 10 days ago.
>
> **It fires again on 2 March. And 3 March. And every day after, until a task is completed.**

That is `activeTask`'s freeze bug inverted — instead of never firing again, firing every day — and it
is the same shape this map keeps finding: two individually sound decisions composing into something
neither evaluated. `Calendar` is immune (an absolute clock; the next date is the next date) and
`Manual` has no clock, so it is `MinMax` only.

**The fix is that two different questions were sharing one answer:**

- _**When did I last actually do this?**_ — history and reporting. **Completions only.**
  `TaskOccurrences.lastCompletionOf` keeps ADR-0001's amended meaning exactly.
- _**When should I next be asked?**_ — scheduling. **Any closure ends the round.** A cancelled task
  buys the template a full `min` interval of quiet.

Cancelling is not forgetting. **Forgetting is the task sitting open**, which already suppresses
refiring, so the deliberate drift ADR-0001 wants is fully preserved by the suppression rule and
needs no help from the anchor. Cancelling is an explicit _"not this round"_, and the honest response
to it is a full interval of silence with the history still recording plainly that it was not done.

The accepted cost: a template can be cancelled indefinitely with no signal beyond its own history.

### Completion is a fact about a task, never about an occurrence

**A template is not completable. Tasks are, separately.** An occurrence id is a group key naming
which firing a task came from — it is not a unit that gets completed and it has no completion date
of its own.

So the anchor reads tasks directly: **`lastCompletionOf(template)` is the latest `completedOn`
among that template's completed tasks.** There is no _"did the round count as done"_ rule, because
there is no round to judge.

This is not only simpler, it is more truthful, and it removes a decision rather than making one. A
template with two definitions where one task is completed and the other cancelled reports the
completion it actually had, on the date it had it — arithmetic rather than policy. Any rule at
occurrence level would have had to choose between calling a partly-done round done (optimistic) and
erasing work that genuinely happened (plainly wrong), and neither is right for every template.

Suppression is unchanged and was always task-level: a scheduled template does not fire while it has
an **open task**. That is ADR-0003's `hasOpenOccurrence` query as written.

### `completedOn` is a domain clock on the task

Reproducing _"I ticked it off today but I actually did it last Tuesday"_ gets **a `completedOn` date
on `Task`** — a normal patchable field, **set on every completion and defaulting to today**.

It is deliberately **not** the patch's `dateTime`. ADR-0004's thesis is that a patch carries two
clocks and neither may do the other's job, and `dateTime` is the **write** clock: it orders the fold
and decides last-writer-wins. Backdating it would tell two lies:

- A Tuesday completion **loses** to any Wednesday edit from another device, because the fold reads
  it as the older write. Correcting a task's name on a laptop would silently un-complete the chore.
- On the complete-the-open-task path it sorts **before** that task's creation patch, and ADR-0004
  defines the first patch for an id as the creating one. A task completed before it existed.

That is the `?since=` defect in a new costume — one clock quietly doing another's job — so
`completedOn` is a third clock only in the sense that it is a **domain value**, on the aggregate,
subject to the ordinary fold. It resolves by last-writer-wins like any other field, which is exactly
what correcting a mis-entered date should do.

Setting it on **every** completion rather than only when backdated means the anchor reads one field
unconditionally, with no _"the field if present, else the patch timestamp"_ fallback. A fallback that
only old or ordinary data exercises is a branch nobody tests.

### "I did it" is a task the client mints

The out-of-band completion is **built client-side and written through the patch outbox**, not by a
server endpoint.

The requirement decides it: **"I already did this" has to work offline.** It is a real user action —
housagotchi's entire interaction, performed while doing chores around the house — and a server call
cannot happen when there is no server. A server firing endpoint (extending
`POST /api/task-templates/{id}/tasks`) would have kept template rendering in one place, and is
rejected on exactly this.

The button has one meaning with two shapes, which the client resolves locally from tasks it already
holds, so it works offline in both:

- **A task for this template is open** → complete it, with the chosen date.
- **Nothing is open** → mint a task **created and completed in the same breath**, with the creating
  patch and the completing patch both carrying the chosen date.

The second is ADR-0005's migrated-execution shape exactly, so a migrated execution and a live
out-of-band completion are the same rows, and _"when did I last do this"_ stays one query over one
history.

The affordance **picks a task, not a template** — portal's _"What did you do?"_ dropdown listed
recurring tasks, one name each, and with several definitions the equivalent is choosing which
definition was done. Conjuring every definition as completed is rejected: it would complete tasks
the user never named, which is template-level completion in a different hat.

Queueing an _intent_ — "fire template X, completed on date D" — and letting the server render on
drain was considered as a way to keep one renderer while staying offline-tolerant. Rejected: ADR-0004
renders from local state before any network and ADR-0009 acknowledges a write only once it is durably
in the outbox, so the task must appear named and dated the instant it is tapped. The client has to
render it anyway, and this variant adds the question of whether the local render and the server's
later render agree.

### Shared golden fixtures pin template rendering

The consequence of the previous section is that **template rendering exists in Java and TypeScript**.
It is small — `fillInVariables` is a `String.replace` loop and `calculateDateWithDeviation` is
`plusDays` off a deviation base — but small is not the risk; silent divergence is.

This map already has a settled answer for a rule that exists twice. ADR-0004 pinned the fold with
**shared golden fixtures**, `/fold-fixtures/` enumerated by both suites and asserted non-empty, and
[#10](https://github.com/stainii/task/issues/10) made it a rule: _no fold rule without a fixture_.

Template rendering gets the same treatment: a sibling fixture directory holding template + inputs →
expected tasks, enumerated by both suites, with the same non-empty assertion — because a path that
silently matches nothing is how [#32](https://github.com/stainii/task/issues/32)'s pitest run
measured its own exclusion for four months. **The rule extends: no rendering rule without a
fixture.**

## Consequences

- **ADR-0002's tripwire does not fire.** Every answer here is derivable from tasks and their patch
  history, so the template side still **queries** and never reacts. The modulith keeps **exactly one
  application event**, the event types stay in `task` as an inbound port, and `task` keeps zero
  outbound module dependencies. No shared kernel module is forced.
- **REC-005 loses its endpoint but keeps its shape.** ADR-0005 reasoned that once `Execution` was
  deleted the register-an-execution endpoint _"can only mean create a task for this template and
  complete it in the same breath"_. The meaning is confirmed and the endpoint is not: there is no
  endpoint, the client mints the patches. ADR-0005's guarantee — migrated and live completions
  produce identical rows — is preserved by the shape, not by the route.
- **`Task` gains `completedOn`**, so ADR-0004's patch payloads and the golden fold fixtures both
  grow a field, and ADR-0005's importer sets it from the portal execution date rather than leaving
  it to default.
- **[#36](https://github.com/stainii/task/issues/36) inherits the affordance**: what "I did it"
  looks like on the template screen, and the definition picker when a template has more than one.
- **A multi-definition scheduled template is newly plausible.** Three of the archived housagotchi
  templates are two chores hand-joined with "en" — `Beddengoed wassen en bed stofzuigen`,
  `Onedrive en Google Drive backuppen`, `Gas- en energieleverancier vergelijken` — so the
  combination [#14](https://github.com/stainii/task/issues/14) left open on purpose has real
  candidates waiting for it. Nothing here needs revisiting when it arrives, which is the point of
  deciding at task level.
- **None of this can land before [#11](https://github.com/stainii/task/issues/11).** ADR-0003's
  package moves, ADR-0002's payload change and the deletion of `Execution`/`activeTask` all ride
  with the backlog, and this sits on top of them.

## Alternatives considered

- **Cancel moves the one clock** (revert ADR-0001's amendment). Rejected: _"when did I last do
  this"_ would then answer with a day you explicitly did not do it, which is the false-completion
  problem [#14](https://github.com/stainii/task/issues/14) introduced cancelling to end.
- **Cancel asks for a snooze duration.** Rejected as disproportionate: it puts a prompt inside the
  single uninterrupted swipe [#9](https://github.com/stainii/task/issues/9) designed, and adds state
  the model has nowhere to keep.
- **Refiring daily after a cancel is correct behaviour.** Rejected: it makes cancelling
  indistinguishable from ignoring, so the action [#14](https://github.com/stainii/task/issues/14)
  added would buy nothing.
- **An occurrence-level completion rule**, either partial credit or clean sweep. Rejected as a
  question that should not exist — see _Completion is a fact about a task_.
- **Backdating the completing patch's `dateTime`.** Rejected; see _`completedOn` is a domain clock_.
- **A server-side firing endpoint** for out-of-band completions. Rejected on the offline
  requirement; see _"I did it" is a task the client mints_.
- **Leaving the duplicated renderer untested** because it is forty lines. Rejected: the failure mode
  is a task rendered with a different name or due date on one device than another, visible only in
  history, which is the class of defect this map has now found seven times.

## Amendments

### Backdating does not live on the task edit screen, and `completedOn` is not editable later

Amended by [Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42),
2026-08-10.

This ADR placed correcting a completion date on the task create/edit screen "explicitly", and
[ADR-0014](0014-two-destinations-and-you-capture-by-typing.md) kept it there.
[ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) removes it: `completedOn`
is written by the completion confirm and is **never editable afterwards**.

The reason is reachability, not preference. ADR-0006's overview shows only open tasks, so the moment
a task is completed the screen that would own the field becomes unreachable. Reaching closed tasks
through the omnibox was offered and refused as functionality the author does not want, and the
nearest thing that would restore it — reporting / task history — is out of scope for this map.

**A stated limit follows.** This ADR sold `completedOn` partly on _"correcting it later is just
another patch"_. That promise is withdrawn: the correction path is undo-then-recomplete inside the
toast's ~8 seconds, and after that a wrong completion date is permanent.

### The overview panel gains an up-front "another day" surface, so fewer dates are wrong to begin with

Amended by [Complete a one-off task on another day](https://github.com/stainii/task/issues/83),
2026-08-27.

The limit above still holds — `completedOn` is not editable once the toast expires — but issue #83
adds an **earlier** path that keeps more dates from being wrong in the first place. The expanded
panel's `Complete` splits into `[ complete ][▾]`, and the `▾` picks the completion date **before**
the write (`Today · Yesterday · 2 days ago · In the past…`), the same deliberate _"when did you do
it?"_ a template and complete-by-name already carry. The task panel had been the one completion
surface without it.

The horizon correction is unchanged in intent and now named precisely: the toast's _change day_ is
**undo-then-recomplete**, because `local-store.ts` treats a patch id as an idempotency key and will
not rewrite a stored patch. It records `undoPatch` plus a fresh `completePatch` on the chosen day,
which the fold resolves by last-writer-wins exactly as this ADR says a corrected date should. See
[ADR-0014](0014-two-destinations-and-you-capture-by-typing.md)'s amendment for the swipe-versus-panel
split and the `DateConfirm` today-cap that rides with it.

### The sibling fixture directory is `/render-fixtures/`, and rendering moved onto the aggregate

Amended by [Template CRUD v2](https://github.com/stainii/task/issues/50), 2026-08-12.

This ADR predicted "a sibling fixture directory holding template + inputs → expected tasks". It
exists, as [`/render-fixtures/`](../../render-fixtures/README.md), with eleven fixtures and the same
enumerate-and-assert-non-empty contract as `/fold-fixtures/`.

Building it moved one thing. **Rendering now lives on `TaskTemplate`, not in `TaskTemplateService`**
— everything it reads is the template's own (the placeholders, the offsets, and the trigger that
supplies the fallback due date), and on the aggregate it is callable with no Spring context, which is
what keeps the fixture runner a plain unit test. The same move deleted a parameter: the fallback due
date used to be computed by each caller and passed in, and the manual-run path passed a hard `null`
that agreed with `Manual`'s answer only by luck. The trigger is now asked, always.

One fixture pins a rule this ADR did not state: **a variable nobody answered is left standing as
`${…}`** rather than blanked. The task is then named for the mistake, which is how it is found;
substituting it away would produce a plausible name nobody typed.

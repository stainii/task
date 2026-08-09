# 1. One task aggregate, with templates that fire on a trigger

Date: 2026-08-02

## Status

Accepted. Resolves [#2](https://github.com/stainii/task/issues/2).

## Context

`task-back-end` inherited three separate concepts from portal, because `portal-todo` and
`portal-recurring-tasks` were separate services that each reinvented "a thing to do":

- `Task` — the plain todo.
- `TaskTemplate` — a manually-run template producing several tasks, with variables and date offsets.
- `RecurringTaskTemplate` — min/max days between executions, an `executions` date log, and an
  `activeTask` boolean.

The goal of the migration is to integrate recurring tasks properly, so we needed to know whether
"kinds of task" are one model or several before anything else could be modelled or ported.

Two candidate kinds were ruled out before this decision. [#13](https://github.com/stainii/task/issues/13)
established that `deployment-name` becomes **context** — so housagotchi, setlist and health are
filters, not kinds — and that `portal-social` disappears entirely, so a person is a template in the
`social` context rather than a kind. [#13](https://github.com/stainii/task/issues/13) also widened
scope with a third recurrence behaviour: **calendar-based**, which portal never had.

## Decision

**One `Task` aggregate.** There are no task subtypes. Everything the user ticks off has the same
shape, so status, importance, patch history and offline sync have exactly one thing to deal with.

**One `TaskTemplate` generator**, absorbing `RecurringTaskTemplate`. It holds the shared core
(name, context, importance, description, variables, one or more `TaskDefinition`s) plus one
**`Trigger`**. Producing several tasks per firing is therefore available to every trigger, not only
to manual runs.

**`Trigger` is a sealed interface of three records** — `Manual`, `MinMax(min, max)`,
`Calendar(rule)` — each answering "when do I next fire?" itself, so a fourth trigger cannot be
half-implemented. Persisted as a discriminator column plus typed nullable columns, keeping min/max
values readable in SQL for the portal data migration ([#8](https://github.com/stainii/task/issues/8)).

`Calendar` rules come from a small fixed vocabulary — every N days from a start date, every N weeks
on given weekdays, every N months on a day of the month, yearly on a date — not RRULE and not cron.
Each rule can enumerate the dates it produces.

**The two scheduled triggers differ by design in whether they drift.** Min/max measures from the
*completion* of the previous occurrence, so forgetting pushes the rhythm out — that is the point.
Calendar fires on absolute dates and never drifts. Anything that must happen weekly regardless of
neglect uses calendar.

**An occurrence is derived, not stored.** A firing is not an entity: `Task` gains a template id and
an occurrence id shared by siblings from the same firing, and everything else follows from the task
and its append-only patch history — fire date is the creation date, close date is the patch that
closed it, and "does this template have an open occurrence?" is a query. This is only safe because
tasks are never deleted; there is no delete endpoint for tasks, only for templates.

`Execution` and the `activeTask` boolean are removed. A scheduled template does not fire while one
of its occurrences is open; for calendar triggers the skipped firing does **not** move the clock.

## Consequences

- **`activeTask` cannot silently freeze a template**, which is the failure mode of the current code.
  But a completion-anchored clock still needs an answer for tasks that are cancelled rather than
  completed — handed to [#33](https://github.com/stainii/task/issues/33), which already asks it.
- **Defect D1 disappears rather than being fixed.** `RecurringTaskTemplate.shouldTaskBeCreatedBecauseItIsDue`
  used `Period.between(...).getDays()` — the day *component*, so any template with `min > 30` could
  never fire. `MinMax` computes total elapsed days from scratch.
- **Missed calendar firings are derivable, not stored.** The rule enumerates the dates that should
  have fired; the generated tasks show which did. The gap is the miss. No third outcome and no extra
  state — but note the map currently rules reporting out of scope, so this is a possibility the
  model leaves open, not work that has been added.
- **The min/max clock now reads its anchor from the patch history**, which is offline-merged. Portal's
  `ExecutionDto` allowed an explicit "I did it last Tuesday" date; reproducing that needs a field on
  `Task`, since a patch's timestamp is when it was written, not when the thing was done. Handed to
  [#33](https://github.com/stainii/task/issues/33).
- **The `template` and `recurring` modules merge.** The boundary detail belongs to
  [#6](https://github.com/stainii/task/issues/6).
- **`TaskDefinition`'s start/due date offsets need an anchor** when no base date was typed by hand.
  For a generated occurrence, the natural anchors are the firing date and the occurrence's due date.
  Backlog detail for [#11](https://github.com/stainii/task/issues/11).
- **The `goal` module is untouched.** If a goal turns out to be a template with an end condition,
  this model has room for it; [#4](https://github.com/stainii/task/issues/4) is unaffected either way.

## Alternatives considered

- **One `Task` with an embedded recurrence rule**, rolling forward on completion. Rejected: it puts
  scheduling inside the aggregate that has to survive offline merging, and loses the history of what
  was actually done.
- **Three aggregates**, one per type. Rejected: the three differ by a two-field payload, and
  everything the user interacts with is identical.
- **Keeping task templates and recurring templates apart.** Rejected: they differ only in trigger and
  cardinality, and merging makes multi-task recurrence available for free.
- **A real `Occurrence` table.** Rejected: every field is derivable from data we already keep, and a
  second copy of "when did I do it" can drift from the task's own history.

## Amendments

### The min/max anchor reads *completed*, not *closed*

Amended by [Feature triage: portal front-end features](https://github.com/stainii/task/issues/14),
2026-08-03.

This ADR anchors the `MinMax` trigger's clock to "the last completion". That was written when
`CANCELLED` was a status nothing in the system could set: portal declared it in `TaskStatus` and
never wired a UI to it, so in practice every task left the list by being completed.

[#14](https://github.com/stainii/task/issues/14) makes cancelling a real action (swipe left on the
task panel). A cancelled occurrence is a task that was **not done**, so it must **not** move the
clock. The anchor is therefore the last patch that set status to `COMPLETED` — not the last patch
that closed the task.

`TaskOccurrences.lastCompletionOf` ([ADR-0003](0003-two-modules-with-package-visibility-as-the-boundary.md))
is already named for the correct semantics; this amendment fixes the reading, not the port.

Note the migration consequence, recorded and deliberately not acted on: portal's historical data
cannot distinguish a genuine completion from a task cleared off the list by pressing Complete,
because there was no other way to do it. Imported anchors inherit that ambiguity. The first
unambiguous anchors are the ones written after cutover.

### The anchor splits in two: history reads completions, scheduling reads closures

Amended by [How does a template learn one of its occurrences was done?](https://github.com/stainii/task/issues/33),
2026-08-07. See [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md).

The amendment above is right about history and silently wrong about scheduling. With the anchor
reading completions only, a cancelled `MinMax` task leaves nothing open to suppress the template
while the last completion stays in the past — so the template **fires again the next day, and every
day after, until a task is completed**. That is `activeTask`'s freeze bug inverted.

Two questions were sharing one answer. *When did I last actually do this?* reads **completions**, as
amended above — `TaskOccurrences.lastCompletionOf` is unchanged. *When should I next be asked?* reads
the last **closed** task, so cancelling buys a full `min` interval of quiet. The deliberate drift this
ADR wants comes from the suppression rule (an open task blocks refiring), not from the anchor, so it
survives the split intact.

### There is no occurrence-level completion

Amended by the same ticket. This ADR says an occurrence "closes when every one of them is closed",
which reads as an occurrence being a unit that gets completed. It is not: **a template is not
completable, tasks are, separately.** An occurrence id is a group key naming which firing a task came
from, with no completion date of its own.

The anchor therefore reads tasks directly — the latest `completedOn` among the template's completed
tasks — and a partly-completed, partly-cancelled firing needs no rule, because there is no round to
judge. Suppression is unaffected and was always task-level.

### The calendar vocabulary swaps *yearly* for *the Nth weekday of the month*

Amended by [Rethink the template UI](https://github.com/stainii/task/issues/36), 2026-08-08.
See [ADR-0013](0013-one-anchor-and-a-trigger-that-shapes-the-form.md).

This ADR names four calendar rules, the fourth being *yearly on a date*. That one is redundant:
"every 12 months on day 14" plus an anchor in March **is** "yearly on 14 March", so the model needs
no fourth case. It survives as a UI unit only.

The slot is spent on `NthWeekday(n, ordinal, weekday)` instead, because
[#36](https://github.com/stainii/task/issues/36)'s own motivating example — *"every first
Saturday"* — could not be expressed by the other three. `Months(1, day)` gives a fixed date, not a
weekday, and `Weeks(4, [Sat])` drifts off the month (1 Aug, 29 Aug, 26 Sep). The same hole
swallowed "last Friday of the month".

The count is unchanged at four rules.

### Templates are deactivated, not deleted

Amended by the same ticket.

This ADR states that "there is no delete endpoint for tasks, only for templates". The asymmetry was
reasoned from tasks being where history lives — but a template is the **group key** for that
history, and [#35](https://github.com/stainii/task/issues/35) measured what cutting it costs:
**tasks reference 115 distinct templates, 43 survive, and 3,954 of 8,086 recurring tasks (49%)
point at a template that no longer exists.**

Cheap in portal, where `flowId` was decoration. Not cheap now that `taskTemplateId` is load-bearing
for the min/max anchor and [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md) has the
template *read* its tasks.

Templates are therefore **deactivated**, which stops them firing and hides them from the list.
Real deletion survives only while a template has no tasks at all.

### `context` and `importance` move back to the definition (partly)

Amended by the same ticket.

This ADR gives the template "name, context, importance, description, variables, one or more
`TaskDefinition`s". Portal had context, importance and description on the **definition**, and the
move happened silently while merging the two aggregates. The real data splits the difference:

- **`context` stays on the template** — it never varies inside one (11 definitions, 3 templates,
  zero variation), and [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md) made it the
  overview's grouping axis.
- **`importance` and `description` go back to the definition**, because importance does vary
  meaningfully (`Opvolgen workshop` has one `NOT_SO_IMPORTANT` chase-up among three `IMPORTANT`
  tasks) and a description is task instructions, not a description of the template.
- **`variableNames` is deleted outright** — variables are inferred from the `${…}` in the text.
  Portal's list had already drifted: `Nagaan of workshop mogelijk is` declares four and uses three.

### Backdating gets its field

Amended by the same ticket. This ADR noted that reproducing portal's explicit "I did it last Tuesday"
date "needs a field on `Task`". It does, and it is **`completedOn`** — set on every completion,
defaulting to today. Confirmed as a real feature rather than a theoretical one:
`housagotchi-add-execution.component.html` carries a *required* datepicker labelled "When did you do
it?".

### A `Calendar` trigger needs explicit same-date suppression

Amended by [Check for due templates on startup, not only at 04:00](https://github.com/stainii/task/issues/40),
2026-08-09. See [ADR-0016](0016-the-due-check-ticks-hourly-and-starts-with-the-app.md).

This ADR says a scheduled template does not fire while one of its occurrences is open, and leaves it
there. That is sufficient for `MinMax`, whose clock also moves on closure — but **not for
`Calendar`**, whose clock is the calendar. Complete a calendar-fired task at 09:00 and the date is
still today with nothing open, so the template fires again.

A daily cron hid this by cadence. ADR-0016 makes the due check hourly and adds a startup check, so
it becomes live. The rule: **a `Calendar` trigger fires for date D only if no task from that template
already carries firing date D** — derived from the creation date this ADR already defines as the
firing date, with no schema change.

*Missed* dates, as opposed to repeated ones, remain out of scope here and belong to
[#41](https://github.com/stainii/task/issues/41).

### Missed calendar firings are in scope, and they cost a column

Amended by [Do missed calendar firings need catching up?](https://github.com/stainii/task/issues/41),
2026-08-09. See [ADR-0017](0017-a-calendar-template-fires-for-its-latest-unclosed-date.md).

This ADR ruled missed calendar firings out of scope, on the grounds that they are "derivable, not
stored" and cost no schema change. **Both halves need correcting.**

They are in scope: a missed date comes back as **one** task, for the most recent missed date,
anchored on that date. `MinMax` has had exactly this behaviour since this ADR — firing is a state
comparison, so an outage costs nothing — and letting `Calendar` silently skip would make the trigger
that "never drifts" the only one that loses work to downtime.

And the derivation needs a floor the model did not have. The rule enumerates dates back to an anchor
that can be years old, and ADR-0013 made templates deactivated rather than deleted, so an unbounded
enumeration back-fills a template's entire life. The floor is **`active_since`**, a new column: *the
date this template began firing under its current rule*. So the "no schema change" claim above is
withdrawn.

`active_since` also seeds `MinMax` for a template with no closed task yet, which is the explicit
start date [#13](https://github.com/stainii/task/issues/13) decided in REC-003 and that this ADR's
restructure around the anchor silently dropped.

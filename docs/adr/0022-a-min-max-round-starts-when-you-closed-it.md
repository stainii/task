# A min/max round starts when you closed it, not when it fired

**`MinMax` counts its interval from the day the last task was closed.** The closure date is
`completedOn` for a completion and a new `cancelledOn` for a cancellation, so ticking a chore off
always buys a full `min` days of quiet — including the chore you were three weeks late with.
`Calendar` is untouched.

Accepted. Resolves [#75](https://github.com/stainii/task/issues/75).

Amends [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md), which split one anchor into
two and left a third distinction unmade, and
[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md), whose *drifts on purpose* is what
this restores. **Leaves [ADR-0017](0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)
intact**, deliberately and after putting the alternative — see *Calendar keeps the firing date*.

## The defect

Practise a piano piece every five days. Complete it today, and it is back in your list today, due in
four.

`MinMax` starts its round at `lastClosure`, and
[`TaskOccurrences#lastClosureOf`](../../task-back-end/src/main/java/be/stijnhooft/task/backend/task/TaskOccurrences.java)
returns *"the firing date of the template's most recently **closed** task"* — the day the task was
created, not the day you closed it. So a task that fired on `F` and sat open until today `T` gives
`roundStarted = F` and a next firing of `F + min`, which is **already in the past** whenever
`T - F >= min`. The hourly due check ([ADR-0016](0016-the-due-check-ticks-hourly-and-starts-with-the-app.md))
then fires it within the hour.

The condition is not exotic. It is *"I was later than the interval"*, which for a five-day chore is
most weeks.

It also makes [`Trigger`](../../task-back-end/src/main/java/be/stijnhooft/task/backend/template/domain/Trigger.java)'s
own docstring false where it is most load-bearing:

> `MinMax` **drifts on purpose**: its clock restarts from the last closure, so a chore done late is
> next asked for late. `Calendar` **never drifts**.

There is no drift. Anchored on firing dates, `MinMax`'s cadence is fixed to a grid it inherited from
its own first firing — which is the calendar it exists in order not to be. The two triggers were
distinguishable in the docs and not in the arithmetic.

**The rule that looks like it should catch this cannot.** `TaskTemplate#firingDateOn`'s rule 3 —
`firingDate.isAfter(lastClosure)` — carries the comment *"otherwise completing a three-week-old bin
task instantly hands you another"*, which is exactly the symptom. But for `MinMax` the filter is
**vacuous**: `F + min` is always after `F`, because `min > 0`. Rule 3 protects `Calendar` and has
never protected anything else, and the comment describes a hazard it does not cover.

**Nor could the fixtures see it.** `firing-fixtures/03-min-max-counts-from-the-last-closure.json` is
named for precisely this rule, and pins `lastClosure` and `from` to the same day — the one
configuration in which the firing date and the closure date cannot disagree. A fixture that names
the rule and cannot fail on it is the shape [#32](https://github.com/stainii/task/issues/32) already
paid for once.

## The closure date is `completedOn`, and cancelling gets its own

ADR-0011 established that *when did I last do this?* and *when should I next be asked?* are two
questions and share one answer at their peril. The worked example there was a cancelled task
composing with the firing rule into a daily loop. This ADR adds the distinction that ADR-0011 did
not need and therefore did not draw: **which date of the closed task** — the one it fired for, or
the one it closed on. Scheduling wants the second.

For a **completion** that date is `completedOn`, the domain clock ADR-0011 put on `Task` so
*"I ticked it off today but I did it on Tuesday"* is sayable. Backdating it therefore moves the
schedule as well as the history, and that is the point rather than a side effect: someone who takes
the trouble to correct the date means *count from here*. A date set far enough back can put the next
firing in the past, so the template comes round immediately — truthfully, because that round really
has elapsed.

For a **cancellation** there is no such date, so `Task` gains **`cancelledOn`**: set on every
cancellation, always today, and not editable. *"I cancelled this on Tuesday"* means nothing, which
is exactly why it does not get the affordance `completedOn` has.

Existing cancelled tasks are **backfilled with their firing date**. That reproduces today's
behaviour exactly, so no template's rhythm moves on the day this ships, and afterwards there is one
rule with no exception.

## Calendar keeps the firing date, and that is a separate question

`Calendar` reads no closure at all — its dates come from the rule
([ADR-0013](0013-one-anchor-and-a-trigger-that-shapes-the-form.md)'s four shapes) and a closure moves
none of them. The only place a closure touches a calendar template is rule 3, and what rule 3
decides is narrow: **whether a date that passed while a task was open comes back once.**

Bins, every two weeks on Tuesday: 4, 18 August, 1 September. The task fires for 4 August, sits open,
and you close it on 20 August.

- **Bound = the firing date (4 August)**, as today: the latest occurrence on or before 20 August is
  18 August, which is after 4 August, so one task comes back for 18 August. Then nothing until
  1 September.
- **Bound = the closure date (20 August)**: 18 August is not after 20 August, so it is swallowed.
  Straight on to 1 September.

The cadence is identical either way. **The catch-up is the whole difference**, and ADR-0017 already
decided it under *One task, for the most recent missed date*: a missed calendar date returns, exactly
once however long the outage, arriving already overdue and honestly so.

The closure-date bound was put and rejected. Its argument is good — close a bin task *on* a bin day
and you almost certainly did today's bin, not the fortnight-old one, so suppressing is right. But it
loses real dates in the case that is not that one: close on 2 September and 1 September's bin, which
you did not put out, is gone until the 15th. A missed bin costs more than a task you tick away. And
it would have cost more than the bin: ADR-0017 subsumes ADR-0016's *a calendar trigger fires for a
date only once* **into** rule 3, so changing rule 3's bound means writing that suppression back out
as a rule of its own — two rules over one condition, which is the shape ADR-0017 says is how two
implementations come to disagree at a date boundary.

So the two triggers genuinely need different dates, and that is the model showing through rather
than an inconsistency: `MinMax` is measured from you, `Calendar` from the calendar.

## One record, two dates

`TaskOccurrences#lastClosureOf` answers with **one value carrying both** — the firing date of the
most recently closed task, and the day it closed. `TaskTemplate#firingDateOn` passes it whole to the
trigger.

Two methods on the port would have worked and were the first proposal. One record wins on what it
makes visible: three lines side by side saying that rule 3 reads the firing date, `MinMax` reads the
closure date, and `Calendar` reads neither. That is the difference `Trigger`'s docstring claims is
*visible right here*, and until now one name meant both things. It is also one query per template per
tick rather than two, over 43 templates, 24 times a day.

**Rule 3 stops being vacuous for `MinMax`.** With `closedOn` in play, a `completedOn` backdated past
the task's own firing date makes `closedOn + min` land on or before it, and rule 3 blocks the firing.
The filter that existed as a `Calendar` guard becomes load-bearing for both shapes, which is worth a
test of its own rather than a happy accident.

## Consequences

- **`Task` gains `cancelledOn`**, so ADR-0004's patch payloads grow a field and `/fold-fixtures/`
  grows with them, on [#10](https://github.com/stainii/task/issues/10)'s rule: no fold rule without a
  fixture.
- **The importer backfills it** on migrated cancellations, with the firing date, so
  [ADR-0005](0005-migration-by-replay-into-one-history.md)'s stored-vs-folded diff stays clean and no
  template's next firing moves on deploy day.
- **`/firing-fixtures/` grows the case it never had**: fired long ago, closed today, next date a full
  `min` away. Fixture 03 is corrected so its `lastClosure` and `from` differ — it has been asserting
  a tautology.
- **`firing.ts` and its `Lookahead` follow**, because ADR-0011 put template rendering in two
  languages on purpose and the fixtures are what keep them honest.
- **ADR-0011's two anchors become two anchors and a date.** `lastCompletionOf` is unchanged;
  `lastClosureOf` changes what it answers with, not which question it answers.
- **A template cancelled repeatedly still buys a full interval each time**, and now measured from the
  cancellation rather than from a firing date that may be months old. ADR-0011's accepted cost — a
  template can be cancelled indefinitely with no signal beyond its own history — is unchanged.
- **The rounds that already fired early stay.** One round early, then correct. The model has no clean
  way to remove them and should not grow one: `CANCELLED` means *not this round* and would write a
  code defect into the history `lastCompletionOf` answers from, and ADR-0004 does not let a task
  un-exist — voiding a creation patch completes it instead.

## Alternatives considered

- **Delay the new task's start date by one day**, leaving the anchor alone. The original proposal,
  and it was rejected on arithmetic: it buys one day of quiet where the interval promises five. The
  piano piece you played today would be back tomorrow, due in three.
- **A global floor — nothing closed today comes back today.** Rejected as unnecessary once the anchor
  moves, and harmful on its own terms: a template with an interval of one day means *every day*, and
  a floor would quietly contradict the thing the author typed. This map has been caught twice by two
  sound rules composing into a third nobody evaluated (`activeTask`'s freeze, ADR-0011's daily
  refire), and the cheapest defence is one rule fewer.
- **Push `min` to `min + 1` after a closure.** Rejected: it falsifies the interval the author set,
  and `MinMax` already drifts with your closures, so an extra day compounds silently.
- **One port question answering only the closure date**, which would drop the firing date entirely.
  Rejected with the closure-date bound above — it is the same decision seen from the other end.
- **Read the cancelling patch's timestamp instead of storing `cancelledOn`.** Rejected on ADR-0004's
  thesis that a patch's two clocks do not do each other's work, and on ADR-0011's warning about *the
  field if present, else the patch timestamp* — a branch only old data exercises is a branch nobody
  tests.
- **One `closedOn` on every closure, with `completedOn` demoted to history.** Tidier on paper, but it
  puts the field you may backdate beside the field the scheduler reads, and then *does backdating
  move the schedule?* has to be answered twice.
- **Leave `cancelledOn` null on migrated data and fall back to the firing date.** Rejected for the
  same reason as the line above it: the fallback would run only for the templates that already carry
  the defect.

## Not decided here

- **Whether a long-open task should say anything about itself.** `Gitaarles` sat open 19 days and the
  only signal was the overdue label. Suppression is silent by design (ADR-0001) and stays so.
- **Whether `completedOn` should be settable when closing out of band from the templates list.**
  ADR-0011 specifies the affordance; nothing here changes it, and the anchor reads whatever it wrote.

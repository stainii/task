# 17. A calendar template fires for its latest date you have not already closed

Date: 2026-08-09

## Status

Accepted. Resolves [#41](https://github.com/stainii/task/issues/41).

Amends [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) twice: missed calendar
firings come **into** scope, and its claim that they are *"derivable with no schema change"* is no
longer true.

Amends [ADR-0016](0016-the-due-check-ticks-hourly-and-starts-with-the-app.md): its same-date
suppression rule is **subsumed** by the firing predicate below, and stops being a rule of its own.

Amends [ADR-0013](0013-one-anchor-and-a-trigger-that-shapes-the-form.md): deactivating, reactivating
and editing a trigger all write `active_since`.

Nothing is enacted, on ADR-0016's precedent. See *Consequences*.

## Context

ADR-0001 introduced `Calendar` as a third trigger precisely because it **never drifts** — its clock
runs from the calendar rather than from the last completion. That is what makes a missed occurrence
a real thing: *"every first Saturday"* has a specific date, and if the app was off across it,
nothing in a derived model notices. ADR-0001 ruled that out of scope, noting the miss is derivable;
[#9](https://github.com/stainii/task/issues/9) reopened it when the author asked for missed
recurring tasks to be caught up on reboot, and ADR-0016 explicitly left it here.

Three facts reshaped the question before it was answered.

**Min/max already catches up, and nobody calls it that.** Firing is a state comparison against the
last closure, so a fortnight of downtime means the template fires **once** on return. So *"no
catch-up for calendar"* does not make calendar the simpler of the two triggers; it makes it the only
one that loses work to an outage. The question was never whether to add a mechanism — it was whether
calendar keeps the behaviour min/max has for free.

**ADR-0001's derivability claim is arithmetically true and operationally incomplete.** The rule
enumerates dates, the tasks show which fired, the gap is the miss — but the enumeration has no
*floor*. The anchor can be years back, and ADR-0013 made templates deactivated rather than deleted,
so a naive enumeration back-fills a template's entire life. A bound is a decision, not a derivation.

**A naive catch-up becomes a queue.** ADR-0001 suppresses firing while a task is open. Down ten days
on a daily template: fire the oldest missed date, task is open, everything else suppressed, complete
it, the next tick fires the next missed date. Ten days of downtime handed over one task at a time.

## Decision

### One task, for the most recent missed date

A missed calendar date comes back, and **exactly one task-set comes back however long the outage
was**. The template returns to your attention once, anchored on the date it should have fired, so it
arrives already overdue and honestly so. Older missed dates are gone.

The alternative — one task per missed date — was rejected on what the tasks would say. The value of
a missed chore is *"you still owe this"*, not a count; two weeks away should not produce fourteen
bin tasks for bins already collected. And [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md)
shows **every** overdue task always, a guarantee [#38](https://github.com/stainii/task/issues/38)
refused to weaken, so there is no cap to hide the pile behind.

Collapsing to one also makes both scheduled triggers behave identically after an outage — one task
on return — so they differ **only in phase**, which is ADR-0001's stated reason for having both.

### `active_since`: the date a template began firing under its current rule

A new column on the task template. It is written on **creation**, on **reactivation**, and whenever
the **trigger changes** — that one sentence is the whole definition, and creation, reactivation and
rule edits fall out of it rather than being three rules to remember.

It exists because the enumeration needs a floor, and every floor that avoids a new column is wrong
in the case that needs one:

- **A fixed lookback constant** (say 30 days) needs a number to pick and defend, and misfires twice.
  Create a `Weeks(1, [Tue])` template today anchored on last Tuesday for phase, and the first tick
  fires a task that was due before the template existed. Reactivate a template after three months
  and it fires for a Tuesday it slept through. Both are one-off and both are untraceable to their
  cause.
- **The template's earliest existing task** is free and undefined for a template that has never
  fired — precisely the case that needs a floor.

Resetting on a trigger change matters as much as the other two, and is the case that is easy to
miss. A bin template has fired every Tuesday since January; change the rule to `Weeks(1, [Thu])` and
every Thursday in the range has no task, because the tasks are all on Tuesdays. Without a reset the
range runs to January and the template immediately fires a three-day-overdue task. Resetting is also
the safe direction: it can only ever prevent a firing, never lose one.

### `active_since` is also the min/max seed, and it repairs an orphaned verdict

A brand-new min/max template has no closed task, so ADR-0001's *"when should I next be asked?"*
reads nothing. `active_since` answers it: the first firing is at `active_since + min`.

This is not scope creep, it is a field that was already missing.
[#13](https://github.com/stainii/task/issues/13) resolved **REC-003** as *"a template now takes an
explicit start date on creation, replacing both portal's never-fires-until-executed and `task`'s
silent creation-date fallback"* — and that date appears in **no ADR and in no `CONTEXT.md` entry**.
The verdict did not survive ADR-0001's restructure around the anchor. Minting a second date column
here, meaning the same thing, is the drift shape this map keeps finding; one field serves both.

Portal's own behaviour is what the ledger row was written against:
`getLastExecutionDateOrCreationDate()` falls back to the creation date, so a never-executed template
is immediately due — described in the ledger as *"a deliberate-looking improvement, unrecorded."*
Making a new template due the moment you save it also makes `min` mean nothing on the one firing you
are paying attention to.

### The firing predicate, and what it subsumes

A calendar template fires at most one task-set per tick, when all of these hold:

1. the template is **active**;
2. it has **no open task** — ADR-0001's suppression rule, unchanged;
3. `D`, the rule's most recent occurrence on or before today, satisfies **`D >= active_since`** and
   **`D >` the firing date of the template's most recent closed task**.

It fires for `D`, anchored on `D`.

**ADR-0016's same-date suppression is subsumed rather than kept.** If a task already carries today's
date it is either open, and rule 2 stops it, or closed with firing date `D`, and rule 3 stops it.
The rule ADR-0016 had to state explicitly stops being a rule and becomes a consequence of this one.

**There is no walk.** This was designed as *walk the rule's dates backwards until you find one with
a task*, and rule 3 collapses that into arithmetic: every rule in ADR-0013's vocabulary — `Days`,
`Weeks`, `Months`, `NthWeekday` — can compute its latest occurrence on or before a date directly.
The enumeration cost that motivated a lookback window in the first place does not exist. It is the
mirror image of ADR-0001's *"each rule answers 'when do I next fire?' itself"*; here each rule
answers *"when did you last come round?"*.

### An open task suppresses calendar firing, and closing it satisfies the gap

Rule 2 is ADR-0001's existing sentence and it is load-bearing in a way it was not before. Without
it, a bin template whose Monday task is left open fires again the next Monday, and a month of
neglect gives four open bin tasks — the accumulation failure `activeTask` was deleted to prevent,
arriving from the other direction.

The tempting objection is that suppression makes calendar drift. It does not: suppression pauses the
**rhythm**, and the **dates** never move. Close the task and the next firing is the next rule date,
not *n* days from the closure, which is exactly the min/max behaviour calendar is defined against.

Rule 3's *most recent **closed** task* — rather than *any* task — is the author's call on the case
that falls out of combining the two. A bin task fires 21 July and is completed on 12 August; nothing
is open, and the latest Tuesday, 11 August, has no task. Reading *any* task would fire one more:
**you complete a three-week-old bin task and instantly receive another.** It is bounded — exactly
one, then the rhythm resumes — and it is technically true, and it tells you the one thing the open
task had been telling you for three weeks, at the moment you just acted on it. That is the signal
shape [#34](https://github.com/stainii/task/issues/34) killed the notification mail over and
[#27](https://github.com/stainii/task/issues/27) refused a daily banner for: it fires when nothing
new has happened.

So **dates that passed while one of the template's tasks was open are satisfied by closing it.** You
were already being asked.

### Nothing reports the dates that are gone

Dates below `active_since`, and dates dropped by the collapse to one, are not surfaced anywhere. A
firing that never happened produces no task, and ADR-0006's overview shows tasks.

A candidate surface existed: [ADR-0014](0014-two-destinations-and-you-capture-by-typing.md) made the
templates list the *reminding* surface, every row carrying the last-done date, so a calendar row
could say *"3 missed since 12 Jul"*. Declined. The row's overdue count already says the template is
late, and a second lateness number on the same row is the `postponeCount` argument #38 refused
twice. A banner is wrong on [ADR-0009](0009-the-app-is-its-own-monitor.md)'s own terms — a banner is
for the app failing, and a missed date after a holiday is the app working correctly.

This is a **stated limit, not an oversight**.

## Consequences

- **ADR-0001's *"missed calendar firings are derivable, not stored"* is half-retracted.** Derivable,
  yes; with no schema change, no. `active_since` is the change, and the Consequences entry in
  ADR-0001 must say so rather than leave a claim that reads as still true.
- **ADR-0016's calendar rule is deleted, not amended.** #11 must implement the predicate above, and
  not the same-date rule alongside it — two rules where one suffices is how the same condition gets
  two implementations that disagree at an edge.
- **Catch-up is not a rarely-exercised path**, and needs none of the self-testing apparatus
  [ADR-0008](0008-every-backup-restores-itself-before-it-is-kept.md) built for backups. It is the
  ordinary firing path with `D` older than today — the same code, 24 times a day. This is ADR-0016's
  argument for `fixedDelay` repeating itself: the startup check is trustworthy because it is
  literally the common path.
- **Nothing is enacted**, the same deliberate exception ADR-0016 made. `CreateDueTasks` operates on
  `RecurringTaskTemplate`, `Execution` and `activeTask`, all three deleted by ADR-0001, and its due
  predicate is D1. `task` is not deployed anywhere; portal is still production.
- **#11 inherits four obligations**: the `active_since` column and the three events that write it;
  the three-part firing predicate as a single rule; the min/max seed reading the same field; and
  boundary tests per [#10](https://github.com/stainii/task/issues/10)'s convention, since every part
  of rule 3 is a date comparison — `D` exactly on `active_since`, `D` exactly on the last closed
  firing date, and a rule whose latest occurrence *is* today.
- **ADR-0005's importer must set `active_since` = import date for any min/max template converted to
  calendar.** ADR-0013 left that conversion to #11 as a per-template call and named
  `Warmteketels laten keuren` and `Visa afrekening` as candidates. A conversion is a trigger change,
  so it takes the reset — otherwise the new rule's dates match no imported task, the range runs back
  years, and the template fires a spurious overdue task on first boot. Unconverted templates take
  their earliest task's firing date, where the field is inert because they all have closed tasks.

## Found on the way

- **REC-003's verdict is orphaned, and #16 could not have caught it.** #13 gave templates an
  explicit start date; no ADR and no glossary entry carries it. The coverage gate checks that every
  ledger row *has a verdict* and that every survivor *reaches the backlog* — and this row has a
  verdict, so it ticks clean. What went missing is downstream of both checks: a verdict that no
  later design ticket picked up. That is a gap in the gate's design, not a row it missed, and it is
  the third time a triage verdict has been overturned or lost by a later design ticket rather than
  by new evidence (REC-005 in [#8](https://github.com/stainii/task/issues/8), REC-018 in ADR-0016).
- **The ticket's own framing was backwards.** #41 asks whether catch-up should be *added* to
  calendar. Min/max has had it since ADR-0001 as a free consequence of being a state comparison, so
  the honest question was whether calendar should be allowed to *lose* it — and put that way it
  answers itself, because "never drifts" and "silently skips" cannot both be true of the same
  trigger.
- **Restoring a backup repairs itself here, and the client is already handled.** A restored dump
  loses the tasks created since it was taken; the predicate then sees rule dates with no task and
  refires one per calendar template, which is repair rather than duplication. On the client,
  ADR-0007's epoch bump forces the hard reset, so nothing is duplicated under a second id. Neither
  half needed anything added.
- **Two of this ticket's answers deleted machinery instead of adding it.** The lookback window died
  when collapse-to-one bounded staleness by the rule's period rather than by the outage, and the
  walk died when rule 3 turned it into arithmetic. Both were in the recommendation that opened the
  session; both were wrong for the same reason, which is that they were sized against an outage
  nobody had measured instead of against what the model already guaranteed.

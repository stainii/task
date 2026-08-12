# 16. The due check ticks hourly, and starting the app is one of the ticks

Date: 2026-08-09

## Status

Accepted. Resolves [#40](https://github.com/stainii/task/issues/40).

Amends [ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md): the deploy window no longer has to
be **clear of 04:00**. That clause existed only to protect the single daily firing, and there is no
longer a single daily firing to protect.

Amends [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md): a `Calendar` trigger needs
an explicit **same-date suppression** rule, because the check now runs many times per date.

Overturns a triage verdict. **REC-018 was *keep as landed*; it is now *drop*.** The cron expression
does not carry over, so `docs/portal-inventory.md` and
[#13](https://github.com/stainii/task/issues/13)'s counts change by one row —
[#16](https://github.com/stainii/task/issues/16) must read the corrected verdict, not the original.

Nothing is enacted. This ADR is a constraint on
[#11](https://github.com/stainii/task/issues/11)'s rebuild; see *Consequences*.

**Enacted by [#49](https://github.com/stainii/task/issues/49)**, which built all five obligations:
`DueCheckSchedule` (the `fixedDelay`/`initialDelay` pair, its own thread, the checker/wrapper split),
`DueTemplateChecker` (silent when idle), `TaskTemplate#firingDateOn` (the single predicate, with the
same-date rule subsumed per the amendment below), scheduling off in `src/test/resources/application.properties`
with `DueCheckStartupIntegrationTest` turning it back on, and the cron property — already gone with
`RecurringTaskTemplate` in [#47](https://github.com/stainii/task/issues/47).

## Context

[#9](https://github.com/stainii/task/issues/9) found that **the server never needed to be up at
night**: under [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) an occurrence is
derived, so "is this template due?" is a state comparison against stored data, not a calendar event
that can be missed. A cron at 04:00 is portal's shape, and [#15](https://github.com/stainii/task/issues/15)
traced the expression itself to portal's `PUBLISH_OVERTIME_RECURRING_TASKS_CRON`.

ADR-0007 then added what looked like a second, independent argument, and it is the one this ticket
was written around: the deploy window is at night, so a restart landing on the 04:00 tick means that
day's templates silently never fire — *and* the box is now guaranteed to restart at night, so a
restart could simply **be** the due check.

**The second half of that is false, and it was checked rather than assumed.** ADR-0007's unit is
`pg_dump → git pull → compose pull → compose up -d`, and `compose up -d` recreates a container only
when the image or its configuration changed. On any night with no push to `main`, nothing restarts.
A startup-only check would mean that after a quiet fortnight, nothing has fired for a fortnight —
which is worse than the hazard the ticket set out to remove.

So the ticket's title is right and its argument is not. **Startup can be added to the periodic
check; it cannot replace it.**

One expected benefit also turned out to be already banked: the timezone. [#34](https://github.com/stainii/task/issues/34)
found the 04:00 cron actually fires at 06:00 local in summer, but
[ADR-0012](0012-one-push-at-0730-derived-not-stored.md) already pins `Europe/Brussels` app-wide, so
that is fixed there and is not a reason for anything here.

## Decision

### One annotation: `fixedDelay` of one hour, `initialDelay` of zero

The author's proposal, and it is the whole mechanism:

```java
@Scheduled(fixedDelay = 1, initialDelay = 0, timeUnit = TimeUnit.HOURS)
```

With `fixedDelay`, Spring's first execution happens as soon as the scheduler starts — right after
context refresh. So **"check on startup" and "check periodically" are the same annotation**, with no
`ApplicationReadyEvent` listener beside a cron and no second mechanism to keep in step with the
first. `initialDelay = 0` is technically redundant, and is written anyway: it is the line that says
the startup check is deliberate rather than incidental.

`fixedDelay` and not `fixedRate`, because the gap is measured after completion — a slow run can
never overlap the next one.

This is [ADR-0009](0009-the-app-is-its-own-monitor.md)'s shape applied to a scheduler: the path that
is hardest to trust is the one that almost never runs, and here the rarely-exercised startup check
becomes literally the same code path as the common one, run 24 times a day instead of on deploy
nights.

### Hourly, and the cadence is load-bearing for the 07:30 push

The author first proposed a **24-hour** `fixedDelay`, which is the same mechanism with a different
number. It was rejected on a coupling, not on freshness.

**A 24-hour delay has no fixed phase.** The tick lands at whatever wall-clock time the container
last started: a deploy at 02:00 sets it to 02:00, a manual `compose up` at 19:15 sets it to 19:15,
and it stays there for weeks until the next restart. If the tick settles at 08:00, then
ADR-0012's **07:30 push** reports on a task list up to 24 hours stale — it announces what is due
today before today's tasks have been created.

That only bites where creation day equals due day, which narrows it to calendar triggers and the
**window-0** min/max templates — but [#36](https://github.com/stainii/task/issues/36) counted **ten
of 44** in that shape. And the failure flips with the container's start time, so it is intermittent,
silent and unreproducible. It is this map's *guarantee broken by something outside the code* shape
with the reboot time as the outside thing.

**Hourly removes it by construction.** Anything that becomes due at the date boundary exists by
01:00 at the latest, always ahead of 07:30, with no ordering rule to state or remember. Keeping a
daily cadence would require pinning the phase with a wall-clock cron *plus* a startup listener —
two mechanisms and the parameter this ticket exists to delete.

The author's condition was that hourly must solve more than it creates. Audited:

- **Load: nothing.** 24 read queries a day over the 43 live templates
  [#35](https://github.com/stainii/task/issues/35) counted. A tick that finds nothing due writes
  nothing.
- **Tasks appear at arbitrary times of day**, and can displace another task from ADR-0015's visible
  five mid-session. This is the only genuine behavioural change — and it is also the feature: the
  window-0 templates exist so a chore can come back the same day, and that cannot be a benefit for
  those and a defect for the rest.
- **Log noise**, addressed below.
- **Same-date idempotency is not hourly's cost.** It was first put in this column and it does not
  belong there: the moment there is a startup check *at all*, a template can be checked twice on one
  date under any cadence, including 24 hours. It is the price of the ticket, not of the interval.

### An idle tick is silent

Today's `CreateDueTasks` logs *"Checking if recurring tasks are due…"* unconditionally at INFO. At
24×/day that fills [ADR-0009](0009-the-app-is-its-own-monitor.md)'s 30-day forensic window with
records of nothing having happened — the same wallpaper argument
[#34](https://github.com/stainii/task/issues/34) used to kill the notification mail.

**A tick that fires logs what it fired. A tick that finds nothing logs nothing.**

### A `Calendar` trigger fires for a date only once

Min/max is self-suppressing: [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md) has an
open occurrence block refiring, and a closure move the clock. **Calendar is not.** Complete the task
at 09:00 and the 10:00 tick still sees today's calendar date with nothing open — so it fires again,
and again, every hour until midnight.

A daily cron hid this by cadence accident, which is the least trustworthy kind of correctness.

**Rule: a `Calendar` trigger fires for date D only if no task from that template already carries
firing date D.** Firing date is the task's creation date under ADR-0001, so this is derived from
data that already exists — no schema change, no `activeTask` flag returning by another name.

This is **same-date** suppression only. Whether a firing date missed entirely — because the app was
down across it — is caught up later is
[#41](https://github.com/stainii/task/issues/41)'s, and this ADR does not pre-empt it.

### The schedule is a wrapper; the check is a service

Splitting them is a testability decision with a sharp edge behind it. Today the 04:00 cron never
fires during a test run. `initialDelay = 0` means it fires **in every integration test context**,
against the shared Postgres — straight into the isolation problem [#10](https://github.com/stainii/task/issues/10)
found, where a patch dated 2071 leaked between test classes.

So: a plain checker service holds the logic and is driven directly by tests, with **scheduling off
by default in the test profile**.

**Plus one integration test that turns scheduling on and asserts the startup fire actually
happened.** This is not optional. #10's `-Xplugin` line break and #23's Error Prone canary are the
same lesson twice: a mechanism that installs itself and silently does nothing produces a green
build. A startup check nobody proves is a comment.

### The interval is a constant, not a property

Portal's 04:00 was configuration (REC-018), and #15 traced it there. A knob nobody turns is a way
for production to differ from the suite silently — #10's dead `error_prone.version` sat in the build
for months, and #25 found Renovate reopening #20's image drift through exactly this seam.

There is no scenario where the box wants a different interval from CI. **`recurring-tasks.create-due-tasks.cron`
is deleted** — but only when #11 lands, because removing the property while the `@Value` still reads
it breaks the context.

## Consequences

- **Nothing is enacted, and that is a deliberate exception to this map's execution habit.**
  `CreateDueTasks` operates on `RecurringTaskTemplate`, `Execution` and `activeTask`, all three
  deleted by ADR-0001, and its due predicate *is* D1
  (`Period.between(...).getDays()`, parked by #10). `task` is not deployed anywhere; portal is still
  production. Editing condemned code buys no live behaviour.
- **#11 inherits five obligations** from this ADR: the `fixedDelay`/`initialDelay` pair, the
  checker/wrapper split, scheduling off in the test profile with one test that turns it on, the
  calendar same-date rule, and deleting the cron property in the same change that deletes the
  `@Value`.
- **ADR-0007's deploy window keeps its reason and loses its constraint.** Still at night, because a
  PWA should not swap out mid-use. No longer clear of 04:00: a restart landing on a tick loses
  nothing, because the next tick re-derives the same state.
- **REC-018 flips from keep to drop**, so #13's tally moves from 11 keep / 5 transform / 14 drop to
  **10 / 5 / 15**. The row still has a verdict, so coverage holds — but #16 checking the original
  comment would tick a *kept* row that nothing implements.
- **REC-010 is reinforced, not changed.** Its verdict kept `task`'s `>= min` over portal's
  exactly-on-the-day predicate, on the grounds that a missed cron run lost portal's reminder
  permanently. Hourly is that argument taken to its conclusion.
- **A persistently failing tick is still invisible**, and that is accepted rather than solved. See
  below.

## Found on the way

- **Spring does not have the `scheduleWithFixedDelay` cancellation trap, verified in the bytecode on
  the classpath.** A raw `ScheduledExecutorService.scheduleWithFixedDelay` task that throws has all
  its future executions cancelled — which would mean one bad hour silently ending due-task creation
  forever, the purest instance of this map's recurring shape. Spring wraps `@Scheduled` methods in
  `DelegatingErrorHandlingRunnable`, and `TaskUtils.getDefaultErrorHandler(isRepeatingTask=true)`
  returns `LOG_AND_SUPPRESS_ERROR_HANDLER` (confirmed in `spring-context-7.0.8`). A throwing tick is
  logged and the schedule survives.
- **The flip side is a failure mode #27 already examined and accepted.** A tick that fails *every*
  hour logs an ERROR and produces nothing, and "no tasks appeared" looks like a quiet day. That is
  the D1 nightmare exactly — the job ran on time and produced the wrong answer — and
  [#27](https://github.com/stainii/task/issues/27) refuted the heartbeat on precisely this case. Not
  reopened here; recorded so that hourly is not later blamed for it.
- **The ticket's own second argument was its weakest.** #40 was raised twice — once by #9 on the
  model, once by ADR-0007 on the deploy — and the second, later, more operational-sounding argument
  is the one that did not survive contact with `compose up -d`. The first argument alone was always
  enough.
- **`docs/portal-inventory.md` REC-018 says the cron "LANDED" and #13 kept it as immaterial.** Both
  statements were true of a daily sweep and are now both wrong, from a decision taken two tickets
  later. This is the second time on this map that a *keep as landed* verdict has been overturned by
  a later design ticket rather than by new evidence (REC-005 was the first, in
  [#8](https://github.com/stainii/task/issues/8)) — worth #16 knowing that the ledger's verdicts are
  a moving target and the ADRs outrank them.

## Amendments

### The same-date rule is subsumed by ADR-0017's firing predicate

Amended by [Do missed calendar firings need catching up?](https://github.com/stainii/task/issues/41),
2026-08-09. See [ADR-0017](0017-a-calendar-template-fires-for-its-latest-unclosed-date.md).

This ADR added *"a `Calendar` trigger fires for date D only if no task from that template already
carries firing date D"*, and deferred **missed** dates to #41. Answering that question replaced the
rule rather than extending it.

ADR-0017's predicate fires for `D`, the rule's latest occurrence on or before today, when the
template has no open task and `D` is both at or after `active_since` and strictly after the firing
date of the most recent **closed** task. A task already carrying today's date is then either open,
and the open-task rule stops it, or closed with firing date `D`, and the last clause stops it.

So the same-date rule is **deleted, not kept alongside**. #11 implements one predicate. Two rules
covering one condition is how two implementations come to disagree at an edge — and here the edge is
a date boundary, which is where [#10](https://github.com/stainii/task/issues/10)'s convention says
the bugs are.

### `@EnableScheduling` moves out of `DueCheckSchedule`

Amended by [Web Push: the `notification` module](https://github.com/stainii/task/issues/51),
2026-08-12. See [ADR-0012](0012-one-push-at-0730-derived-not-stored.md)'s amendments.

The tick, the interval and the knob are unchanged. What moved to `config/SchedulingConfig` is
`@EnableScheduling` and the pool, because ADR-0012's 07:30 push is the second scheduled job this
application has — and while `@EnableScheduling` sat behind `task.due-check.enabled`, switching the
due check off switched off *scheduling*, silently taking the other module's job with it.

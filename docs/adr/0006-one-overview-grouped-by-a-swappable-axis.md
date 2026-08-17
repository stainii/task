# 6. One overview, grouped by a swappable axis

Date: 2026-08-03

## Status

Accepted. Resolves [#9](https://github.com/stainii/task/issues/9).

Adds a requirement that appears in no ledger row: **the visible-work cap**. See *Visible work is
capped, and overdue counts as due*.

Makes [Rethink the template UI](https://github.com/stainii/task/issues/36) a **blocker for**
[Cut over: switch off the old portal](https://github.com/stainii/task/issues/17) — cutover now
requires a feature-complete front end.

## Context

[#13](https://github.com/stainii/task/issues/13) turned four apps into four labels: housagotchi,
setlist, health and social stopped being deployments and became a `context` on one screen.
[#14](https://github.com/stainii/task/issues/14) gave every `FE-*` row a verdict and handed this
ticket the overview screen with three requirements — desktop efficiency, contexts visible rather
than merely filterable, and goal-readiness — plus an explicit instruction that the layout is *not*
a port.

The tension is that those requirements pull against each other. Portal is mobile-shaped throughout
(400px centred panels, a handset-breakpoint sidenav, three vertically stacked bands), so on a wide
screen it is a narrow column with acres of dead space. But filtering *to* one context tells you
nothing about the shape of the whole, which is exactly what four separate apps gave for free.

[#4](https://github.com/stainii/task/issues/4) left one question deliberately open: whether a goal
is a second axis alongside context, or is what context becomes. The overview must not close that
question by accident.

Four layouts were built and driven with real data before deciding
(`task-front-end/prototypes/`), together with three variants of the task panel's action
affordance. Two of the decisions below were changed by what the prototypes showed.

## Decision

### One responsive continuum — density scales, functionality does not

Phone and desktop are **both primary**, and the difference between them is how much is shown, not
what can be done. A desktop shows more tasks and more context per screen; a phone collapses. **No
feature is device-exclusive in either direction.**

This replaces the harder split first proposed — phone as the capture-and-tick-off device, desktop
as the survey-and-plan device — which would have bought two layouts and two interaction models
forever.

### Actions live in the expanded panel; swipe is available everywhere

The panel stays an expandable row. Expanding reveals the description and three actions —
**Edit / Complete / Cancel**. Swipe right completes, swipe left cancels, with a **labelled fill**
so the gesture says what it is about to do.

> **Amended by [ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) and
> [ADR-0019](0019-verbs-are-glyphs-facts-are-words.md).** There are **four** actions — ADR-0015 adds
> *Postpone* — and they are **glyphs, not text buttons**. Portal drew `edit` and `done` as
> icon-only `<mat-icon>`s for years and the author confirms they were clear; the text buttons
> written here were a drift, not a decision. Each glyph carries the verb's full name as its
> accessible name and tooltip. The **labelled** swipe fill is unaffected — a gesture has no glyph.

**Swipe is not touch-only.** Portal's `(swiperight)`/`(pan)` ran on HammerJS, which binds mouse
drag as well — desktop has had the gesture for years. The rebuild uses **Pointer Events**, which
covers mouse, touch and pen in one code path. HammerJS is dead (unmaintained since 2016, Angular
has dropped `HammerModule`), so this is a rebuild, not a port.

Always-visible per-row action buttons were **rejected on evidence**: in a three-column desktop
grid the three buttons cost ~110px and truncate the task name — *Vacuum the livin…*, *Book dentist
appoi…* — forcing a two-column grid to stay legible. That trades away the desktop density the
layout exists to provide, so the cost lands precisely on the requirement it was meant to serve.
Hover-reveal was rejected as a discoverability trap for `CANCELLED`, which is a brand-new verb.

> **Amended by [ADR-0019](0019-verbs-are-glyphs-facts-are-words.md): this measurement has expired,
> but the rejection stands on a different reason.** The ~110px was **word-width** — three *text*
> buttons. ADR-0019 makes the verbs glyphs, which do not cost that, so the evidence quoted above no
> longer supports the conclusion. Always-visible actions are still rejected, now because swipe
> already covers complete and cancel: a permanent row would put four affordances on every card to
> serve the two verbs swipe does not reach. Recorded so nobody re-derives the button measurement and
> finds it does not reproduce.

### Contexts are cards above one global list

The overview is a row of **context cards**, then the task bands beneath them.

A card carries: the context name, its open count, an **overdue or due-today badge**, a
six-segment colour bar (importance buckets of its soonest tasks) as shape-at-a-glance, and one
line of **what is next after the visible work** — `house · next: Renew bike insurance · in 9 days`.
Clicking a card **enters** that context: the bands re-render scoped to it, with a
`house — 7 open` line and `← everything` to leave. Filtering *is* entering the app, which gives
back the four-apps feeling without four routes, four modules or four deployments.

Cards deliberately do **not** list their next few tasks. An earlier draft did, and it duplicated
almost the entire visible band in the first fold. Showing what comes *after* the visible work makes
the cards additive instead of redundant, and it composes with the cap below rather than fighting it.

Rejected alternatives, both for the same reason: **a column per context** and a **two-axis grid
(bands × contexts)** each destroy the single ranked list. With a column per context there is no
global "what's next" — only one answer per column. In the grid, the top band reads left-to-right by
context, so the 1st and 5th ranked tasks sit side by side as equals. The grid additionally has
nowhere to go under ~780px, collapsing into the column layout, which is the hard device split
already rejected above.

### Visible work is capped, and overdue counts as due

**A new requirement, from the author, matching no ledger row.** The first fold must not show more
work than can be acted on:

- **everything overdue or due today is always visible**, however many that is — the cap does not
  apply to it;
- **otherwise the band is topped up to 5** in rank order.

Overdue and due-today are **one set**, not two rules: an overdue task is due work and is never
folded away. When the cap is exceeded the band **retitles itself** — `Due today — all 8, the cap
does not apply` — so it is visible *why* more than five things are on screen, rather than the list
silently growing.

`Also…` and `Starting in the future…` **start folded**, showing only a count and a click target.
This restores portal's original click-to-expand behaviour, and without it the cap would be
decorative: work that is still on screen has not been limited.

This supersedes the plain "top 5" that [#14](https://github.com/stainii/task/issues/14) recorded as
confirmed. The top-5 survives as the *floor*, not the ceiling.

### The grouping axis is a property of the card row, not of the layout

Contexts are what the cards group by **today**. Nothing below the cards knows that. Swapping the
axis from context to goal changes the card row and leaves the rule, the bands, the panels and the
ordering untouched — demonstrated in the prototype with a `group by` toggle.

**Goals are not built, and the axis stays `context`.** This is the one decision that discharges
[#4](https://github.com/stainii/task/issues/4)'s instruction to *design for a second axis and not
build one*, and it is why the card-row layout was chosen over the two rejected alternatives, where
the grouping axis **is** the layout and changing it later is a rewrite.

A task row shows the axis it is currently grouped by. Whether a row should show both its context
and its goal once goals exist is left open, with goals.

### `goals` (the importance bucket) is renamed `long-game`

The four-colour stripe survives — red `focus`, green `long-game`, orange `fit-in`, grey
`back-burner` — but the green bucket loses the name `goals`, which `CONTEXT.md` reserves for a
standing theme. `long-game` keeps the four names reading as one family: two about importance
(`focus`, `long-game`), two about proximity (`fit-in`, `back-burner`).

This is **client-side only** — the buckets are computed in the front end from importance ×
remaining time, so there is no column, no endpoint and no migration.

### Cutover requires a feature-complete front end, so there is no minimum cutover set

The author will switch only when the app is finished: portal off, data migrated, task app on. There
is therefore **no minimum viable cutover slice** to specify — the set is everything, and
[#36](https://github.com/stainii/task/issues/36) becomes a blocker for
[#17](https://github.com/stainii/task/issues/17).

The migration event itself is short. [ADR-0005](0005-migration-by-replay-into-one-history.md)'s
one-shot importer is minutes-to-hours; what takes weeks is building the app, not moving the data.

### A dogfooding milestone exists, on throwaway data

The task loop — overview, create, complete, cancel, and the full offline stack — is finished
**first** and run against a **copy** of migrated data while the author still lives in portal. Not
to switch: to find out what is wrong while the cost of being wrong is still zero.

[ADR-0005](0005-migration-by-replay-into-one-history.md)'s diff report proves the *data* is
faithful. It cannot say whether the swipe feels wrong or the cap hides something needed. Cutover is
a one-way door with no rollback, and without this milestone the first meeting of the new app, the
real data and real habits would be the day portal dies.

**The dogfooded instance is write-throwaway.** Its data is re-imported and discarded, never
promoted. Running two offline-first apps over the same tasks as live systems would invent a
two-way sync problem that neither [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) nor
[ADR-0005](0005-migration-by-replay-into-one-history.md) solves.

## Consequences

- The overview has one data model — the cap, the bands, the ordering — and one presentation that
  scales by density. There is no phone layout and desktop layout to keep in step.
- Changing the grouping axis later costs a change to the card row. It does not touch the bands.
- `CANCELLED` becomes reachable in two ways from day one, which is what
  [#14](https://github.com/stainii/task/issues/14) found had been missing for years.
- The cap is a **front-end rule over data the client already holds**. It needs no endpoint, no
  query parameter and no back-end change, and it therefore works identically offline.
- The first fold can now exceed five rows. That is intended, and only when everything in it is
  overdue or due today.
- `#36` blocking `#17` means the template UI's design sits on the critical path to cutover.
- The prototypes in `task-front-end/prototypes/` are throwaway. They are the only visual record of
  this decision until the overview is built, and should be deleted once it is.

## Found on the way

Two back-end findings, both outside this ticket, raised separately:

- **The scheduler never needed the server up at night.**
  [`CreateDueTasks`](../../task-back-end/src/main/java/be/stijnhooft/task/backend/recurring/scheduler/CreateDueTasks.java)
  runs on cron `0 0 4 * * *`, but its due check is a **state comparison**
  (`daysSinceLastExecution >= min && !activeTask`), not a calendar event — so a missed 04:00 loses
  nothing, the next run re-derives it. This is the same property
  [ADR-0002](0002-one-application-event-published-as-a-fact.md) relies on for crash recovery. What
  is genuinely missing is that the check runs **only** at 04:00, so a template that came due
  overnight stays invisible until the next morning. The check is idempotent, so running it at
  startup and more often than daily needs no model change.
- **Calendar triggers are the exception.**
  [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md)'s `Calendar(rule)` *is*
  date-anchored, so unlike min/max it has genuinely missed occurrences. ADR-0001 ruled missed
  calendar firings out of scope, noting they are derivable with no schema change. The author's
  wish for catch-up on reboot re-opens exactly that.

## Amendments

### Ordering *within* a band is specified, and it is FE-004 redesigned

Amended by [Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42),
2026-08-10.

This ADR settled which **band** a task lands in and what the cap does, but never what order tasks
take **inside** a band. Instant capture made that urgent: a task created from the omnibox has no due
date, and must not sink out of sight.

[ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) adopts portal's
`task.comparator.ts` points model — urgency 0–50, importance 0–50, an importance-scaled overdue
bonus, ties broken by earliest creation date — with the `expectedDurationInHours` term deleted along
with the field. An undated but important task scores 20 urgency points, which is exactly "due in 30
days".

**Band membership trumps the score.** The always-visible guarantee is unchanged; the points order
what sits inside it.

### The scope line says the same total the card does

Amended by [The overview, part 2: context cards, the folds and the indicators](https://github.com/stainii/task/issues/58),
2026-08-16.

*Contexts are cards above one global list* specifies a `house — 7 open` line on the entered context
and never says which 7. Building it made the question sharp, because
[ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md)'s reversal had just split the
card into a badge that counts **started** work and a count that is a **true total**.

**It is the total**, matching the card exactly. The card and this line sit one click apart and use
the same word; two numbers that disagree there would each be true under a rule the screen never
states, which is the failure mode the badge reversal was itself about. `PROTOTYPE-context-card-sleeping.html`
counts the awake ones here — that line was scaffolding around the question under test, not an answer
to this one, and it is superseded.

*Decided by recommendation while building #58; the prototype had it the other way and was not driven
on it.*

### A sleeping task is named on the card, and nothing more

Amended by the same ticket.

[ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) states the cost of removing
the honesty valve precisely: `Onderhoud ketels`, 62 days overdue and asleep, *"is still inside the
card's count and can still be the name on the what comes next line, **but nothing says it is
late**"*. Built literally, the line composes `next: ${name} · ${dueLabel(...)}` — and `dueLabel`
answers `62 days overdue`. **The card would have been the one surface on the overview breaking the
rule the whole reversal turns on**, one line under a badge scoped to avoid exactly that.

**So the time half is dropped for a sleeping task.** `next: Onderhoud ketels`, with no date. It is
the same rule as the badge's and the future band's, applied to the half of the line that speaks
about urgency rather than to the whole line: a task that has not started is not taken into
consideration by anything that speaks about urgency.

The half that survives is the half ADR-0015 explicitly protects — the card still names it, so the
task is not hidden. And the alternative reading, dropping sleepers from the line altogether, was
rejected as the stronger contradiction: that ADR says in as many words that the line *can* name one.

*Decided by recommendation while building #58; found by review against ADR-0015's own sentence.*

### What a folded band says when nothing in it has a due date

Amended by the same ticket.

[ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md)'s fold-bar sentence has two
halves — *is anything urgent in there*, and *when does the next one arrive*. The second half has no
answer for a band holding only undated work, which `Also…` can easily be: an omnibox capture has no
due date by construction.

**The clause is dropped, not filled in.** `Also… (3) · nothing urgent` is half a sentence and says
only what is true; `soonest never` and `soonest no due date` are both answers to a question that was
not asked. This is the same rule as ADR-0015's count-only future band — say nothing where there is
nothing to say — reached one band along.

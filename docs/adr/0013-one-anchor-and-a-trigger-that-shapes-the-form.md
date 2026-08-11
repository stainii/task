# 13. One anchor, and a trigger that shapes the form

Date: 2026-08-08

## Status

Accepted. Resolves [#36](https://github.com/stainii/task/issues/36).

Amends [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) four times: the calendar
rule vocabulary, template deletion, where `context` and `importance` live, and the removal of
`variableNames`.

## Context

[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md) merged `TaskTemplate` and
`RecurringTaskTemplate` into one aggregate carrying a sealed `Trigger`.
[#14](https://github.com/stainii/task/issues/14) triaged portal's two unrelated template screens
(FE-010, FE-011, FE-015, FE-016) as **TRANSFORM — merged**, and deliberately left the resulting
design open: the merge hands over a combination neither old screen could express — a template with
several definitions *and* a repeating trigger — and #14 refused to rule on whether that is a
feature or a trap.

The named risk was that one form carrying trigger, offsets, anchors, variables and context becomes
a control panel: a lot of knobs for a screen you open when you set something up and then do not
touch for months.

The decision was made against the **real portal data**, extracted from the frozen archive at
`~/portal-archive/2026-08-04/` ([#35](https://github.com/stainii/task/issues/35)) rather than from
the code alone. That data is lopsided in a way that shaped almost every choice below:

- **44 recurring templates**, all of them exactly *name + min + max*. No description, no context,
  no variables, no definitions.
- **3 todo templates**, carrying 11 definitions between them, with variables, per-definition
  descriptions and per-definition importance.

So one side of the merge contributes three fields and the other contributes everything else.

## Decision

### The trigger is the first field, and it shapes the form beneath it

One screen. Name, context, then a **trigger picker** — *Run by hand* / *Every so often* /
*On the calendar* — and the section under it swaps to that trigger's own fields. Creating a plain
task is not on this screen; it stays where it is.

Three separate forms behind a chooser were considered and rejected. They match the three user
intents exactly, and they would have re-forbidden the one combination this ticket exists to unlock
(see below). Reverting to three *aggregates* was raised by the author and rejected in the same
breath: it splits on the trigger, which protects the variables axis and re-closes the definitions
axis in one cut.

### Any trigger may carry any number of definitions

This is what the merge buys, and it is not theoretical — **both halves of the missing combination
are already in production as workarounds**:

- `Vissen eten geven (5 dagen)` is a manual template with **five identical definitions** at offsets
  0, 1, 2, 3, 4, because a manual template could not repeat.
- `Beddengoed wassen **en** bed stofzuigen` and `Onedrive **en** Google Drive backuppen` are two
  chores hand-joined in a name string, because a recurring template could only ever be one task.

Both stop being workarounds here. The recurrence rule that lived in `(5 dagen)` becomes a trigger;
the second chore that lived in the word `en` becomes a second definition.

### Progressive disclosure: a definition collapses to name and importance

A definition's row shows **name + importance**. The `▾` reveals **offsets and description** — the
two things that are rare across the real data. A single-definition template therefore never opens
the drawer, which is what keeps the 44 recurring templates from paying for the 3 workshop-shaped
ones.

### One anchor, two offsets — the base selectors are deleted

`startDateDeviationBase` and `dueDateDeviationBase` (each `START_DATE` or `DUE_DATE`) are removed.
Every firing has **one anchor date**, and each definition says *starts N days from the anchor, due
M days from the anchor*.

The evidence is that the base selector never did any work. Across all 11 real definitions:

- `Opvolgen workshop scholencoordinatie` sets **all eight bases to `DUE_DATE`**. Its offsets fan
  out around a single date — the workshop — at `-14/-7`, `+1/+7`, `+1/+7`, `+7/+14`.
- `Nagaan of workshop mogelijk is` uses `START`→`START`, `DUE`→`DUE`, which is the default pairing.
- `Vissen` is **inconsistent**: four definitions anchor their start to `DUE_DATE` and one to
  `START_DATE`, with identical offsets either way. A mis-click that never surfaced, because that
  template is run with the same date in both fields.

Ten of eleven do nothing; the eleventh is silently wrong. Under one anchor the mis-click is
unrepresentable.

Each trigger supplies its own anchor:

| trigger | anchor | editable |
|---|---|---|
| Manual | typed when you run it, and **named by the template author** | yes |
| Min/max | the firing date | no |
| Calendar | the date the rule produced | no |

**The template author names the manual anchor** — *"When is the workshop?"*, *"When do you
leave?"* — instead of it being a generic date field. One string on the template. It is what makes
the run-it dialog ask a question rather than present a date picker, and it is the label the
authoring preview uses.

The cost, accepted: manual instantiation used to take **two** dates, and the gap between them set
every definition's duration at run time. It now takes one, and each definition owns its duration.
The duration of *"send the preparation mail"* is a property of the template, not something to
re-decide at each workshop.

### Offsets are numbers, with a timeline when there is more than one task

Three presentations were built and driven (`task-front-end/prototypes/PROTOTYPE-template-offsets.html`):
a draggable timeline, a fixed phrasing vocabulary (*"2 weeks before"*), and two number fields with
the resolved dates echoed.

**Numbers are the default. The timeline appears additionally when a template has more than one
definition, with the numbers still visible.** On mobile the timeline appears only when there is
room.

Phrasing was rejected on a failure found by driving it: nudging an offset off the vocabulary
degrades it into a dropdown *plus* a number field — strictly worse than the number field alone —
and every offset that is not a round week lands there.

The timeline earns its place only when there is something to compare. It makes `Opvolgen workshop`
readable without reading a single number — preparation left of the anchor line, follow-ups right,
the two identical `+1/+7` definitions visibly identical. For a one-definition template it is a
ruler, a legend and a drag affordance wrapped around two numbers.

### `context` on the template; `importance` and `description` on the definition

Portal put all three on the definition; ADR-0001 moved all three to the template. Neither is right:

- **`context` never varies inside a template** — 11 definitions, 3 templates, zero variation. And
  [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md) made contexts the card row on the
  overview, so a context is something a whole template belongs to.
- **`importance` does vary, and meaningfully.** `Opvolgen workshop` has three `IMPORTANT`
  definitions and one `NOT_SO_IMPORTANT` — *"has feedback come in yet?"*, a chase-up that genuinely
  matters less.
- **`description` is per-definition and usually absent** (2 of 11). It is task instructions, not a
  description of the template.

A template-level *default* importance with a per-definition override was proposed and **overruled by
the author** as less straightforward than one home for the field. That is recovered by putting
importance in the **collapsed** definition row rather than behind the `▾`, so the
single-definition case still costs nothing and there are no override semantics to explain.

There is **no template-level description**.

Consequence, accepted: a template that would span two contexts must be split in two. That is
correct — a template firing into two contexts is two templates that happen to share a date.

### Min/max is an interval plus a window

The form asks *"comes round every **N** days, and I have **M** days to do it"*, with **M defaulting
to 0**. Stored unchanged as `min` and `min + M`; ADR-0001's `MinMax(min, max)` and
[#13](https://github.com/stainii/task/issues/13)'s *created at `min`, due at `max`* are untouched.

This is a direct consequence of what `min == max` turned out to mean. **Ten of the 44 recurring
templates have `min == max`** — 720/720, 365/365, 361/361, 30/30, 8/8, 6/6, 5/5 ×3, 3/3 — and the
author's reason is that those tasks should be **due immediately**, with no soft period at all. Under
two absolute fields that idiom requires typing the same number twice and wondering whether it was a
mistake. Under interval-plus-window it is one field.

### Four calendar rules, and *yearly* is not one of them

ADR-0001 named four: every N days, every N weeks on given weekdays, every N months on a day of the
month, **yearly on a date**. The fourth is redundant and a fifth shape is missing. The stored
vocabulary becomes:

- `Days(n)`
- `Weeks(n, weekdays[])` — **several weekdays in one rule**, so *"Tuesday and Thursday"* is one
  template rather than two. Portal could not say this.
- `Months(n, day)` — the day **clamps** to the end of short months (31 → 30 → 28).
- `NthWeekday(n, ordinal, weekday)` — *first / second / third / fourth / **last*** weekday of the
  month.

**`yearly on a date` is dropped as a stored rule and kept as a UI unit.** The picker offers
*days / weeks / months / **years***, and `years` writes `Months(n × 12)`; the month comes from the
anchor, exactly as the phase of every other rule does. Nobody thinks *"every 12 months on day 14"*,
so the affordance stays even though the rule does not.

**`NthWeekday` is what the fourth slot is spent on instead, because the ticket's own motivating
example was not expressible.** #36 asks for *"every first Saturday, generate these three tasks"*.
`Months(1, day)` gives a fixed date, not a weekday; `Weeks(4, [Sat])` drifts off the month —
1 Aug, 29 Aug, 26 Sep, 24 Oct — and three of those are not first Saturdays. There is no combination
of the other three rules that produces it, and the same hole swallows *"last Friday of the month"*.

Net: still four `Trigger` rules, the count ADR-0001 budgeted, with one swapped for a better one.

### Variables are inferred from the text, and `variableNames` is deleted

Anything matching `${…}` in a definition's name or description **is** a variable. There is no list.

Portal kept both a declared list and the placeholders, and they drifted:
**`Nagaan of workshop mogelijk is` declares four variables and uses three.** `${lector}` is in
`variableNames` and in neither definition, so every run of that template has asked for a lector and
thrown the answer away, for as long as the template has existed, with nothing able to report it.
With inference that state cannot be represented.

Typos surface earlier rather than later. With a declared list, `${scool}` does not substitute and
is discovered as a task named `${scool}` — at run time, in the task list. With inference a fifth
chip appears in the authoring form as it is typed.

The `${…}` syntax is unchanged; it is in the stored data and
[ADR-0005](0005-migration-by-replay-into-one-history.md)'s importer reads it.

**Variables are manual-only, enforced at save.** A scheduled template containing `${…}` is rejected,
because nothing will be present to fill it when it fires at 04:00. This is deliberately a
**validation and not an unrepresentable state**. Putting `variableNames` on the `Manual` trigger
record was designed and discarded once variables became inferred: there is no field left to move,
and it would have made the *list* unrepresentable while `${foo}` sat happily inside a scheduled
template's task name substituting to nothing. `Manual` is a marker record.

### Running a template creates its tasks immediately

The run-it flow asks for **the named anchor date and the variables**, shows what it is about to
create, and creates it. No edit-before-create step: the tasks are ordinary tasks the moment they
exist, and ADR-0006 already gives every tool needed to edit, complete or cancel them one screen
later. A confirm-and-adjust step would be a second editing surface for something already editable.

**Its presentation follows whatever creating a regular task looks like** — dialog or screen — which
is [#37](https://github.com/stainii/task/issues/37)'s to settle. This ADR records the constraint,
not the answer.

### Templates are deactivated, not deleted

ADR-0001 says *"there is no delete endpoint for tasks, only for templates"*. That sentence was
written before the cost was known.

**[#35](https://github.com/stainii/task/issues/35) measured it: tasks reference 115 distinct
templates and 43 survive — 3,954 of 8,086 recurring tasks, 49%, point at a template that no longer
exists.** Setlist is the extreme: 4 surviving templates against 2,054 tasks.

That was nearly free in portal, where `flowId` was decoration nothing read. It is not free now.
ADR-0001 makes `taskTemplateId` load-bearing — the min/max anchor is the latest `completedOn` among
the template's completed tasks — and
[ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md) makes the template **read** its
tasks rather than be told. Deleting a template severs a link the model actively uses.

There is a symmetry argument too. ADR-0001 forbids deleting **tasks** because history is derived
from them. A template is the group key for that history; protecting the leaves while leaving the
branch cuttable is half a rule.

So:

- **Deactivating** stops the template firing and drops it from the list by default. Tasks keep
  pointing at something real; history stays queryable.
- **Deleting is allowed only while the template has no tasks** — the genuine "I just made this and
  got it wrong" case. Once it has fired, the button becomes *deactivate*. The condition is a count,
  not a judgement.
- **Open tasks at deactivation are left alone.** They are real work; complete or cancel them
  normally.

This matches the shape [#4](https://github.com/stainii/task/issues/4) already reached for goals —
*a standing theme that never completes and is deactivated when it stops mattering*.

### The preview shows dates when dates exist, and shape when they do not

One preview component, one rule:

- **Authoring a manual template shows the shape** — *"2 weeks before → 1 week before the
  workshop"*, using the anchor's own name. There is no anchor date while authoring, and inventing
  one would display concrete dates that are wrong for every actual run.
- **Running a manual template shows real dates**, because the anchor has been typed. This is the
  check that replaces an edit-before-create step.
- **Scheduled templates show dates in both**, because a rule can enumerate its own firings with
  nobody typing anything. This is the strongest case for the preview existing at all:
  *"every 14 weeks on Saturday"* is unreadable until the next firings are listed under it.

## Consequences

- **Two production workarounds become expressible**, and a third is retired: the recurrence rule in
  `(5 dagen)`, the second chore in `en`, and the fixed interval faked as `min == max`.
- **`variableNames` leaves the model.** ADR-0005's importer drops the column and derives nothing
  from it; the placeholders in the text are the only source.
- **Both `*DeviationBase` columns leave the model.** The importer folds two bases into one anchor —
  verified against all 11 real definitions, so this is a rewrite of eleven rows, not a data problem.
- **`Manual` becomes a marker record**, and variables-on-a-scheduled-template is a save-time
  validation rather than a type error. Named here as the weaker guarantee it is.
- **The template list will accumulate**, because nothing is deleted: ~115 entries where there are
  43 today, most inactive. That is a browsing problem for [#37](https://github.com/stainii/task/issues/37),
  and the price of the 49% never recurring. The escape hatch is a *deactivated* filter, not a delete.
- **`NthWeekday` is a fourth `Trigger.Calendar` rule to implement and enumerate**, and *yearly* is a
  UI concern with no back-end representation.
- **Day-of-month clamping is a decision, not a default.** `Months(1, day 31)` produces
  31 Aug, 30 Sep, 31 Oct, 30 Nov, 31 Dec, 28 Feb. The alternative — skipping February — was rejected.
- **Which of the ten `min == max` templates convert to calendar is a per-template import call**, not
  a decision of this screen. `Warmteketels laten keuren` and `Visa afrekening` are date-driven;
  `Benen`/`Buik`/`Armen` at 5/5 genuinely want drift. Handed to
  [#11](https://github.com/stainii/task/issues/11).
- **`De Goede Gasten (alle oneven weken)` is expressible** as `Weeks(14, [Sat])`: any **even**
  number of weeks preserves ISO week parity, so starting on an odd week stays on odd weeks.
  Stated limit: a 53-week ISO year flips the parity across the boundary, so this holds for years
  and then slips once. The author had already offered to drop the requirement entirely, so the
  slip is accepted rather than designed around.
- **Four throwaway prototypes** in `task-front-end/prototypes/` are the only visual record until
  the screens exist: `PROTOTYPE-template-authoring.html`, `PROTOTYPE-template-offsets.html`,
  `PROTOTYPE-calendar-rules.html`, `PROTOTYPE-template-variables.html`. Delete with ADR-0006's.

## Alternatives considered

- **Three aggregates** (`Task`, `TaskTemplate`, `RecurringTaskTemplate`), raised by the author.
  Rejected: it splits on the trigger, which protects the variables axis and re-closes the
  definitions axis in the same cut — buying type-safety for the hole while keeping the `en`
  workaround. The same safety was available by moving one field, and then became unnecessary
  when variables turned out to be inferrable.
- **Three separate forms** behind a chooser. Rejected: a min/max form shaped for the 44 has nowhere
  to put a second definition, so `Beddengoed wassen en bed stofzuigen` stays a name string forever.
  Progressive disclosure gives the 44 the same three fields without closing the door.
- **An editable sentence** (*"X, in house, creates 1 task every 10–21 days"*) with everything behind
  drawers. Built as a deliberate foil to the control-panel risk. Rejected: it reads beautifully for
  a one-definition template and has nowhere to put four definitions with eight offsets.
- **A fixed phrasing vocabulary for offsets.** Rejected on the degradation described above.
- **A template-level default importance with per-definition override.** Overruled by the author;
  recovered by moving importance into the collapsed row.
- **Keeping `yearly on a date` as a stored rule.** Rejected as redundant with `Months(n × 12)` plus
  an anchor, and the slot spent on `NthWeekday` instead.
- **A declared `variableNames` list.** Rejected on `${lector}`.
- **Edit-before-create when running a template.** Rejected: a second editing surface for tasks that
  are editable one screen later. The honest cost — a typo in `${school}` means fixing four task
  names by hand — was put to the author and accepted.

## Amendments

### Deactivating, reactivating and editing a trigger all write `active_since`

Amended by [Do missed calendar firings need catching up?](https://github.com/stainii/task/issues/41),
2026-08-09. See [ADR-0017](0017-a-calendar-template-fires-for-its-latest-unclosed-date.md).

This ADR made templates **deactivated rather than deleted**, and left reactivation as the plain
inverse. It is not, once a calendar template can catch up on dates it missed: a template switched
back on after three months would fire for a date it slept through.

ADR-0017 adds **`active_since`** — *the date this template began firing under its current rule* —
written on creation, on reactivation, and whenever the **trigger** changes. The third case is this
ADR's, and it is the one that is easy to miss: a bin template that has fired every Tuesday since
January, re-ruled to `Weeks(1, [Thu])`, finds no task on any Thursday and would immediately fire a
backdated one. Editing a task definition's name or description writes nothing.

### The task form inverts this one's split, and `TaskDefinition.importance` is non-nullable

Amended by [Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42),
2026-08-10.

Two things, both from measuring six years of real edits.

**The split.** This ADR's progressive drawer hides what is rarely **set** — offsets and description.
[ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) found that applying the
same rule to the *task* form would hide description and *ask me from*, the third and fourth
most-edited fields in the app. The rule that fits an edit screen hides what is rarely **changed**,
which is the opposite list — and the task form ends up flat anyway, being short enough that a drawer
buys no space. A template form is where you set things up; a task form is where you change one
thing.

**Importance.** `TaskDefinition.importance` becomes non-nullable, defaulting to `important`, so that
a definition cannot produce a task that must have one from a value that does not. Portal corroborates
that no null case is needed: its `recurring_task` table had no importance column at all, and each
subscription pinned a constant.

### The manual anchor's name lives on `Manual`, which is therefore not a marker record

Amended by [`TaskTemplate` absorbs `RecurringTaskTemplate`](https://github.com/stainii/task/issues/47),
2026-08-11.

This ADR says two things that cannot both be built. *One anchor, two offsets* requires the template
author to **name** the manual anchor — *"When is the workshop?"* — and calls it **"one string on the
template"**; the variables section then concludes that **"`Manual` is a marker record"**. The second
sentence was written to refuse `variableNames` a home once variables became inferred, and it
overshot: the paragraph is about variables, and the anchor label is the one field that does belong
to that record.

**The field goes on `Manual`.** It is the reading that keeps both halves true. On disk it is still
one string on the template — the whole trigger persists as columns of `task_template`, so
`trigger_anchor_label` is a nullable column there either way. In the model it makes the invalid
state unrepresentable: only a manual trigger can carry an anchor label, where a template-level field
would have let a calendar template name an anchor it never asks anyone for.

The alternative, a nullable `manualAnchorLabel` beside the trigger, was rejected on this map's own
recurring shape — a field that is meaningful for one discriminator value and inert for the others is
a second discriminator waiting to disagree with the first.

`Manual` therefore has exactly one component and no behaviour: it is the trigger that answers *when
do you next come round?* with **never**.

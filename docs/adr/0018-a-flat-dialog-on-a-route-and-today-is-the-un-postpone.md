# 18. A flat dialog on a route, and *today* is the un-postpone

Date: 2026-08-10

## Status

Accepted. Resolves [#42](https://github.com/stainii/task/issues/42).

Amends [ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) twice: its claim that
portal never postponed is **false**, and its proposed postpone presets are reshaped against the
measured distribution.

Amends [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md): it specified which **band** a
task lands in and never the ordering **within** one. FE-004's scoring model, which #14 ruled must be
redesigned rather than ported, is that ordering.

Amends [ADR-0011](0011-completion-is-a-task-fact-the-template-reads.md): backdating a completion
does **not** live on this screen, and `completedOn` is not editable after the fact.

Amends [ADR-0013](0013-one-anchor-and-a-trigger-that-shapes-the-form.md): this form is deliberately
**flat** where the template form is progressive, and `TaskDefinition.importance` becomes
non-nullable.

Amends [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md): **`Task.importance` becomes
non-nullable**, defaulting to `important`.

Amends [ADR-0005](0005-migration-by-replay-into-one-history.md): its importer would **abort on a
correct `flowId` prefix**. See *Found on the way*.

Graduates one ticket: a coherent visual language for the whole app
([#43](https://github.com/stainii/task/issues/43)).

## Context

[#38](https://github.com/stainii/task/issues/38) found that no ticket owned the screen where a task
is written or changed: FE-007 is `TRANSFORM` with nothing designing the survivor, and portal's
version is a **seven-step `mat-vertical-stepper` in a dialog**.

Both this ticket's inherited premises were **measured and found false** before anything was
designed. The frozen archive at `~/portal-archive/2026-08-04/` was parsed directly — 11,833 tasks
and 38,165 patches, 2020–2026 — rather than reasoned about through the ledger:

1. **"Portal tasks were rarely edited" is false.** 1,207 of 3,021 hand-made tasks — **40%** — carry
   a real edit, 3,289 edit patches in all, and **1,371 of them land more than 30 days after
   creation**. The seven steps were answered, too: importance set on **99%** of tasks, a due date on
   **92%**, context on 100%. The stepper was opened constantly. #38's argument for instant capture
   was built against a screen nobody avoided.

2. **Portal already had postpone, and it was the busiest thing in the app.** ADR-0015 states portal
   "had no way to say *not today* except editing the due date… so in practice nothing was ever
   postponed". Step 5 of that dialog is the **only** UI in portal that writes `startDateTime`, and
   it produced **3,726 start-date-only patches on 1,190 hand-made tasks** — **64% of all edit
   traffic**, every year from 2020 to 2026, one task pushed **98 times**. Postpone was not missing.
   It was this screen's dominant real use, performed the long way round.

That reframes the ticket. This is not the form nobody used; it is the second-busiest surface in the
app, whose single busiest job **ADR-0015 has already taken away**. What is left for it is what
postpone cannot do.

Built and driven before deciding: `task-front-end/prototypes/PROTOTYPE-task-edit.html`, with rig
axes for host, create mode, form shape and density. Three of the decisions below were changed by
what it showed.

## Decision

### A dialog — but a routed one, at `/task/:id`

The author chose a dialog over a full route and over editing in the expanded panel. It is **routed
anyway**: `/task/:id` renders as a dialog over the overview.

The look and the addressability are separate questions, and
[ADR-0014](0014-two-destinations-and-you-capture-by-typing.md)'s reasons for making everything a
route all still apply — an installed PWA's hardware back must leave the *thing* and not the app, and
[ADR-0012](0012-one-push-at-0730-derived-not-stored.md)'s 07:30 push has to be able to land
somewhere. An unrouted Material dialog swallows both. Routing costs nothing visually and makes the
create toast's *Add details* an ordinary navigation.

**Editing in the panel was rejected on a cost found by building it**, not the one predicted. The
form is perfectly legible in a ~410px grid cell — the prediction that it would rebuild portal's
narrow mobile column was wrong. What it actually does is stretch the CSS grid row: **every card
beside it grows to match**, turning two unrelated tasks into tall empty boxes. The full-width
variant is worse, evicting the whole first fold. Both are in the prototype.

### The form is flat — six fields, no steps, no drawer

Name, context, importance, due, *ask me from*, description. Nothing is hidden and nothing is
staged. It fits a 375px phone with **nothing scrolled**, once the two dates stack below ~430px.

This is a **deliberate inversion of ADR-0013**, which gave the template form a progressive drawer.
The mechanism is the same; the list is the opposite, and the reason is measured. ADR-0013 hid what
is rarely **set**. Applying that rule here would hide **description and *ask me from*** — the third
and fourth most-edited fields in six years. The split that fits an *edit* screen hides what is
rarely **changed**: importance (194 edits) and context (102). The author chose flat over either,
which the prototype supports: the form is short enough that a drawer buys no space at all.

The stepper's sin was never its fields. It was making six of them into six screens.

### `Ask me from`, never "start date" — and *today* is the un-postpone

The field is worded in postpone's vocabulary, so that the two surfaces cannot disagree about what a
start date means. Presets: **`Today` · `Tomorrow` · `In 3 days` · `Next week`**.

That set is measured, and it **replaces ADR-0015's proposed `Tomorrow / +1 week / pick a date`**:

| push, from the day of the patch | count |
|---|---|
| today | 1,210 |
| +1 day | 552 |
| +2…6 days | **1,283** |
| exactly +7 days | **55** |
| +8…31 days | 378 |
| longer | 129 |

`+1 week` would have been the least-used control on the screen. `In 3 days` is the median of the
bucket that dominates.

**`Today` is the point of this screen.** 1,210 of the 3,726 pushes set the start date to the day
they were made — pulling a sleeping task *back* into the day's work. ADR-0015's postpone moves
forward only, so **this is the only un-postpone in the app**. The panel's postpone menu uses the
same set **minus `Today`**, where it would be a no-op.

### There is no start-before-due validation, on purpose

**4,678 of 11,579 real tasks (40%)** have a start date *later* than their due date. That is not
corruption; it is the exact fingerprint of ADR-0015's "push start, never due" — a postponed overdue
task is overdue *and* asleep. The reflex validation on a form like this would reject 40% of the
author's history.

The form states it instead of forbidding it: *Asking from 17 Aug, still due 30 Jun — 48 days
overdue.*

### Fields only; the panel keeps the verbs

No Complete or Cancel in the dialog. The panel owns verbs, the dialog owns values. Repeating them
would give `CANCELLED` — a brand-new verb ADR-0006 deliberately gave exactly two well-understood
entry points — a third one, inside a form.

A **deliberate `Cancel`** discards and says what went (`Discarded 2 unsaved change(s)`). An
**accidental dismissal** — scrim, Escape, hardware back — **asks first**. Splitting by gesture is
what lets both of the author's answers hold without putting a confirm in front of every close.

One Save writes **one patch carrying every changed field**, not a patch per field: the fold is
last-writer-wins *per field*, so they merge identically, and one patch is one outbox entry, one
sequence number and one thing to undo.

### A generated task is edited freely, and told where it came from

`Made by Beddengoed wassen en bed stofzuigen — edit the template instead →`, with *changes here
apply to this task only* beneath it.

Locking the fields would contradict ADR-0001 — a generated task **is** a task — and would break the
ordinary case of annotating one particular round. But renaming it fixes this fortnight only, and the
link is the one thing on the screen that offers to fix every fortnight after it.

### Importance becomes non-nullable, defaulting to `important`

`Task.importance` is `VARCHAR(20) NULL` today (`V1__create_task_and_task_patch.sql:9`), and
`TaskDefinition.importance` is nullable too (`V2__create_template.sql:32`). **Both become
non-nullable**, defaulting to `important`. Migrated tasks with no importance become
`NOT_SO_IMPORTANT`.

This began as a bug report and ended as a deletion. Portal **contradicts itself** about a missing
importance: `task.comparator.ts` scores `null` at **20**, *above* `NOT_SO_IMPORTANT` (15), while
`task.model.ts`'s bucket logic treats a missing importance as low and drops it into
`fit-in`/`back-burner`. The same task therefore sorts as middling and colours as unimportant.

The author refused the framing that null means *unimportant* — it means *undefined*, which is
weaker — and then removed the case instead of ruling on it: with `important` as the default, nothing
new can arrive without one, so the contradiction has no values left to apply to. **The type system
deletes the question rather than answering it.**

Portal is corroborating evidence that a template needs no null case either: its `recurring_task`
table has **no importance column at all**, and each subscription pinned a constant —
Housagotchi/Health/social `IMPORTANT`, Setlist `NOT_SO_IMPORTANT`.

### Ordering within a band: FE-004 redesigned, not ported

ADR-0006 said which band a task is in. It never said what order tasks take inside one, and instant
capture makes that urgent: a task created by Enter has no due date, and must not sink.

`todo/task.comparator.ts` already balances exactly what the author described, and it survives with
one term removed:

- **Urgency, 0–50.** Overdue → flat `50`. Dated → `50 − daysUntilDue`, clamped. **Undated →
  `20` if important or very important, else `0`** — and 20 points *is* "due in 30 days", so the
  comment is literally true: *"important tasks are assumed to be urgent enough to be done within
  the month."*
- **Importance, 0–50.** `0 / 15 / 30 / 50`. The `null` row is gone with the nullable column.
- **Overdue bonus, on top**, scaled by importance (`5 / 10 / 25 / 30`) — kept, because once eight
  overdue tasks are on screen, ordering *among* them is the only job left.
- **Ties by earliest creation date.**
- **`+ expectedDurationInHours / 4` dies** with the field (TODO-001). It was set on 5% of tasks.

**Band membership trumps the score.** ADR-0006 guarantees every due-or-overdue *started* task is
visible however many there are; the points order what is inside that guarantee, they never override
it. Per RES-011, the whole model is portal's documented **Eisenhower four-quadrant** design, which
is also where the importance buckets come from.

### Capture is unchanged, and this screen is not part of it

There is **no create mode**. Enter in the omnibox mints the task, as ADR-0015 settled; this screen
only ever edits one that exists. Routing a capture into a form gives back the keystroke the omnibox
exists for — and it writes its first patch only on Save, so anything typed and abandoned is lost,
where the toast path has a real task under offline sync from the first keystroke.

Three things make the defaults good enough that the form is rarely needed:

- **Context defaults to the context you are standing in** (`/in/housagotchi`), falling back to the
  last one used. ADR-0006's card click *enters* a context, so a task captured from inside one
  belongs to it by construction rather than by history.
- **Context chips under the omnibox** change that default before Enter, in one tap. **Not a token
  syntax** — #38 rejected a keyboard layer, and `#house` is one wearing a costume: a vocabulary to
  remember whose failure mode is silently corrupting a task name. Natural-language dates were
  rejected for the same reason, needing a parser that is right in Dutch and English and that eats a
  word when it is wrong.
- **The create toast carries due-date chips** — `Added "…" in housagotchi · due today · tomorrow ·
  in 3 days · Add details`. Due date is the most-edited field in the author's history (1,397
  edits); one tap here means this screen never opens.

**The write-only-inbox risk is accepted, named, and left to dogfooding.** A captured task has no due
date, so under ADR-0006 it is never *due* and never in the top five. A default due date was rejected
for ADR-0015's reason in reverse — a made-up due date lies about when the thing was needed, and
would make ADR-0012's 07:30 push announce tasks the author never dated. The mitigations are the
toast chips, the context badges, and the fact that **`important` is now the default**, which puts a
captured task in `long-game` rather than `back-burner`. If [#39](https://github.com/stainii/task/issues/39)
shows a real inbox forming, the pre-agreed cheapest fix is the fold speaking louder — `Also… (23) ·
14 with no due date` — not a column.

### A closed task's URL redirects

Routing makes `/task/:id` deep-linkable, including to a task that has since been completed — which
ADR-0012's push makes likely rather than exotic. It **redirects to the overview with a toast**:
*Beddengoed wassen is already completed.*

Opening it editable would let a change be saved into a task ADR-0006's overview will never show
again; a read-only rendering is a second copy of the whole form for a case with nothing to do in it.
The author accepted this as *"a consequence of having everything routable"* rather than a feature
wanted for itself.

### Backdating a completion does not live here

**ADR-0011 is amended.** It placed correcting a completion date on this screen "explicitly", and
ADR-0014 kept it here. `completedOn` is written by the completion confirm ADR-0014 already routes
both capture paths through, and is **never editable afterwards**.

The correction path is undo-then-recomplete inside the toast's ~8 seconds; after that a wrong date
is permanent. This is a **stated limit**, not an oversight: ADR-0011 sold `completedOn` partly on
*"correcting it later is just another patch"*, and that promise is now withdrawn. The alternative —
reaching closed tasks through the omnibox — was offered and refused as functionality the author does
not want, and the nearest thing that would restore it is **reporting / task history**, which the map
has ruled out of scope.

### The visual language is a separate ticket

The author's note that the prototypes carry too much textual overload is **bumped to
[#43](https://github.com/stainii/task/issues/43)**, a prototype ticket for a coherent visual
language across the whole app. This screen ships **verbose**.

An icon variant is built and kept in the prototype as input, along with the finding that killed
deciding it here: **the two dates become indistinguishable.** A calendar icon and a clock icon both
read as *a date* and neither says *which*, native date inputs cannot carry a placeholder, and these
two are precisely the pair ADR-0015 exists to keep apart — confusing them writes the exact lie that
ADR forbade. Deciding the app's icon vocabulary from its only dense form would be deciding it from
its least representative screen, and ADR-0015's *words beat stripes* is evidence #43 has to reckon
with rather than an obstacle.

## Consequences

- **`Task.importance` and `TaskDefinition.importance` need a Flyway migration** (V4) plus model
  changes, and ADR-0005's importer gains a rule: `null → NOT_SO_IMPORTANT`. This is the first time a
  design ticket on this map has changed the **schema** rather than the front end, and it is work for
  [#11](https://github.com/stainii/task/issues/11).
- **FE-004 moves from MISSING to specified.** #14 ruled it a redesign; this is the redesign, and it
  is a front-end rule over data the client already holds — no endpoint, no query parameter, works
  offline.
- **ADR-0015's postpone presets change before they are built.** Any implementation using
  `Tomorrow / +1 week / pick a date` is wrong.
- The dialog needs a history entry so hardware back closes it rather than leaving the context
  beneath it.
- The two date inputs must stack below ~430px, or the second one clips. Found at 375px.
- `#43` blocks [#11](https://github.com/stainii/task/issues/11): the backlog should be built against
  a settled visual language rather than retrofitted to one.
- The prototype is throwaway and should be deleted once this screen is built, together with the rest
  of `task-front-end/prototypes/`.

## Found on the way

- **ADR-0005's importer would abort on a correct prefix.** Running its own step 1 for the first
  time — enumerate the `flowId` prefixes actually present — yields `Health` (2,729), `Housagotchi`
  (2,528), `Setlist` (2,049), `social` (762) and **`Todo` (744)**. Step 2 says *"report any prefix
  with no database, or database with no prefix… Either is an abort signal, not a warning"* — and
  `Todo` has no recurring-tasks database because it is not a deployment at all, but portal-todo's
  own task-template `flowId`. The **shape is the discriminator**: deployments are
  `<Name>-<numeric>`, templates are `Todo-<uuid>`. The casing question ADR-0005 anticipated is now
  measured too: three prefixes capitalised, one not.
- **The map's own claims were the least reliable input.** Both premises this ticket inherited were
  false, and both were repeated across several tickets before anyone measured them — the *rarely
  edited* stepper and the postpone that "never happened". Each had been reasoned from the code's
  shape rather than from its data. The archive was pulled by
  [#35](https://github.com/stainii/task/issues/35) in early August and has been available to every
  design ticket since.
- **Two prototype predictions were wrong in opposite directions.** In-panel editing was predicted
  unusable and is legible; the flat form was predicted to need a drawer and needs none. Both
  corrections came from rendering the thing, and the second one is why the author's *"probably
  progressive"* could be revisited on evidence rather than deferred to taste.
- **Portal's own two components disagreed for years** about what a missing importance means, and
  nothing could report it — the same shape as ADR-0013's `variableNames` finding, where a template
  declared four variables and used three. Both were only visible by reading the data instead of the
  model.
- **The `expectedDurationInHours` term outlived its field in the ledger.** FE-004 was marked MISSING
  *because* it consumes a dropped field, which framed the scoring model as blocked on a decision. It
  was not: the term contributes at most a few points on 5% of tasks, and deleting it leaves the
  model whole.

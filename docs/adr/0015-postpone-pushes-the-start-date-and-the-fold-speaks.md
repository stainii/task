# 15. Postpone pushes the start date, and the fold speaks

Date: 2026-08-09

## Status

Accepted. Resolves [#38](https://github.com/stainii/task/issues/38).

Amends [ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md) in three places: the bands are
**start-date** bands, the always-visible rule scopes to tasks that have **started**, and the grid is
capped at three columns.

Amends [ADR-0014](0014-two-destinations-and-you-capture-by-typing.md) in two: the omnibox's third
option creates immediately, and the appbar gains an offline/queued indicator.

Graduates one screen that no ticket owned:
[Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42).

## Context

[#14](https://github.com/stainii/task/issues/14) gave all 34 `FE-*` rows a verdict, but *broken* and
*structurally obsolete* are not the same category as *irritating*. This ticket was the author's ask
for "a brainstorm session about what we can improve in the front-end, with lots of prototypes and
back-and-forth", bounded by a completion condition: it ends with an accepted list, each item
graduated.

Portal's front-end was read directly rather than through the ledger. Two prototypes were built and
driven — `task-front-end/prototypes/PROTOTYPE-postpone.html` and
`PROTOTYPE-wide-screen-fold.html`. **Both changed an answer**, and the first one changed it twice.

## Decision

### Postpone pushes the **start date**, and never the due date

The one genuinely new capability. Portal had no way to say *not today* except editing the due date
inside a seven-step dialog, so in practice nothing was ever postponed — it just sat there. Under
ADR-0006's cap that is worse than it sounds: a task you cannot face holds one of five slots
indefinitely.

The first draft had postpone writing the **due date**. The author replaced it with the **start
date**, which is better on three counts:

- **It needs nothing built.** `start_date DATE NOT NULL` already exists
  (`V1__create_task_and_task_patch.sql:6`) and `CONTEXT.md` already reads *"a start date, an
  optional due date"*. Postpone is an ordinary patch on an ordinary column, so
  [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md)'s offline contract carries it for free
  — no field, no migration, no new verb on the wire.
- **It cannot lie.** Pushing a due date *rewrites when the thing was needed*. Pushing a start date
  says "not yet, ask me again later" and leaves the due date alone, so a boiler service that is 62
  days overdue is still 62 days overdue when it comes back. The app never stops knowing; it stops
  asking you today.
- **It already has a home.** ADR-0006's third band is where a postponed task goes, so nothing
  invents a state.

The offset is measured **from today**, not from the old start date — postponing is a statement about
now, not an increment on a date you have forgotten. `Tomorrow / +1 week / pick a date` is the
minimum set; whether `this weekend` earns a place is left to implementation, because it means
something different on a Saturday than on a Monday.

Postpone lives in the **expanded panel**, alongside Edit / Complete / Cancel. **Not a third swipe**:
right completes and left cancels, and a third gesture is a coin flip — ADR-0006's own argument
against hover-reveal applied to gestures.

The template clock is untouched by construction:
[ADR-0001](0001-one-task-aggregate-with-triggered-templates.md)'s min/max anchor is the last
*completed* patch, so postponing cannot move it however often it is done. The prototype recomputes
and displays the anchor after every action as live proof.

### The bands are **start-date** bands, and always-visible scopes to started tasks

*A correction to ADR-0006, found by building the postpone prototype.*

ADR-0006's third band, "Starting in the future…", was implemented in the #9 prototype as
`dueIn > 35` — a far-off **due** date. That is wrong: portal's band was driven by the **start** date,
and the name says so. The banding rule is:

> A task is live work when its start date has arrived. Otherwise it is **not started yet**, whatever
> its due date says.

This resolves the conflict postpone would otherwise create. ADR-0006 says *"everything overdue or
due today is always visible, however many that is"* — but a postponed overdue task is overdue **and**
not started, and both rules cannot win. The author settled it by refusing the exception and fixing
the rule instead: **tasks are in *not started yet* when their start date is not due**, full stop. The
always-visible guarantee applies to tasks that have started.

The alternative was driven and rejected on the spot: with the always-visible rule absolute, postpone
does nothing at all for precisely the tasks you most want to postpone, which is every overdue one.

**The always-visible rule protects you from the app hiding work. It was never meant to protect you
from yourself**, one deliberate act at a time.

### The visible-work rule, restated

Unchanged in substance, restated in the author's own words because the previous phrasing invited the
misreading that the cap is a maximum:

> Every due-or-overdue task is shown, however many there are. Normal tasks then top the band up to
> five — `max(0, 5 − dueCount)` of them.

**Five is a day's feasible work, not a function of screen estate.** It does not scale with the
viewport, and it does not get laxer because you bought a monitor. The same argument keeps the fold
at every width.

### The grid is capped at three columns

Width goes into the columns, not into more of them. **Found by building it**: with
`repeat(auto-fill, minmax(270px, 1fr))` a 1500px viewport yields **five** columns and truncates
almost every task name — *"Vacuum the l…"*, *"Replace the kitch…"* — which is the exact failure
ADR-0006 rejected always-visible action buttons in order to avoid. One column phone, two tablet,
three desktop.

> **Note added by [ADR-0019](0019-verbs-are-glyphs-facts-are-words.md).** The cap itself is
> untouched — it was measured on **task names** at five columns, and has nothing to do with buttons.
> Only the cross-reference moved: ADR-0006's button rejection now rests on swipe coverage rather
> than on the ~110px of text-button width, because ADR-0019 makes the verbs glyphs. Also: the fourth
> verb this ADR adds, **Postpone**, is a glyph like the other three.

At three columns the cap of five leaves one empty cell in the second row. **It stays empty.** Both
variants were rendered side by side: outlining the gap turns an absence into a UI element, and the
dashed outline collides with the fold bars, which use the same border to mean *you can click this*.
The gap is the cap being honest.

### The collapsed band says what is behind the door

`Also… (9)   nothing urgent · soonest in 5 days`.

Four variants were built at the author's request rather than one recommended: count only, count +
colour stripes, count + words, and both. **Words won.** The stripes describe a *distribution* nobody
can act on — "two of the nine are orange" — while the words answer the only two questions that would
make you open the band: is anything in there urgent, and when does the next one arrive. The stripes
additionally collide with the six-segment colour bar ADR-0006 puts on the context cards, so the same
visual language would mean two different things one band apart.

The fold itself survives at every width, for the same reason the cap does. A collapsed band is not a
space saving; it is the thing that stops the screen showing more work than you can do.

### The other accepted improvements

- **The omnibox creates immediately.** ADR-0014's third option — *create a task with what you typed*
  — mints the task on Enter from the name alone, with the default context and no due date. Routing
  it into a form gives back the keystroke that is the omnibox's entire argument. Refinement is the
  panel's *Edit*, for the minority of tasks that ever need it.
- **Undo is a toast.** ADR-0004 made undo a *void patch* and put `id` on `TaskPatchDto` so it works
  cold, but nothing said how you invoke it. Complete, cancel and postpone all make a row leave the
  screen, and ADR-0006's overview does not show closed tasks — so a mis-swipe vanishes without a
  trace. An ~8 second toast with *Undo* follows any action that removes a row. This is also the only
  path that exercises the void patch in normal use.
- **Queued work is visible.** ADR-0014 placed *rejected* changes on the overview; *pending* ones had
  no home, so forty offline changes looked exactly like zero. One quiet appbar affordance shows
  offline-and-`n`-waiting as a single state. It stays out of the overview body because, unlike a
  rejection, nothing is wrong and there is nothing to act on.
- **Dark mode from the first component**, driven by `prefers-color-scheme`, with no toggle.
  Retrofitting theming after every screen is styled is a full restyle; doing it now costs custom
  properties nobody would otherwise regret.
- **Descriptions linkify URLs**, and nothing else. Markdown invites a formatting toolbar, which
  invites an editor.
- **A postponed task does not announce its return.** When the start date arrives it simply reappears
  in the day's work, still carrying its true overdue count. ADR-0012's 07:30 push already answers
  *here is your day*, and a `69d overdue` label is not a subtle signal.
- **Context card badges count sleeping tasks.** Postponing empties the list but not the badge, so
  `house — 3 overdue` stays true while the rows sleep. This is the honesty valve that makes postpone
  a deferral rather than a hiding place.

  > **Reversed by [#58](https://github.com/stainii/task/issues/58).** The badge counts **started
  > tasks only**, and postpone has no mitigation at all. See *The honesty valve is removed, and
  > postpone is deliberately unmitigated* below.

### Rejected: a keyboard layer

Proposed as the answer to ADR-0006's desktop-efficiency requirement and **rejected by the author**:
pointer-only is fine, and desktop efficiency means **using the screen space better than portal did**,
which is what the three-column grid delivers. A keyboard model is a second interaction model to
maintain forever, for a single-user app that is primarily a phone in a hand.

## Consequences

- Postpone is the first action in the app that writes a **date** rather than a status, and it is the
  reason `startDate` stops being a field only the importer sets.
- **ADR-0006's band implementation changes before it is built.** Any prototype or code banding on
  `dueIn > 35` is wrong; the third band is `startDate > today`.
- The overview gains one more conditional element (the toast) and the appbar one (the queued
  indicator). Neither needs an endpoint — both read state the client already holds.
- **Postpone can be abused, and the map knows it.** The mitigation is the badge, not a counter. A
  `postponeCount` field was considered twice and refused twice; if dogfooding
  ([#39](https://github.com/stainii/task/issues/39)) shows a real postpone-forever loop, the cheapest
  fix is a string change in the 07:30 push, not a column.

  > **Amended by [#58](https://github.com/stainii/task/issues/58).** There is now **no** mitigation —
  > the badge no longer counts sleepers and nothing replaced it. `postponeCount` stays refused, but
  > the reason it was safe to refuse has gone, so [#39](https://github.com/stainii/task/issues/39) is
  > the only thing standing between a postpone-forever loop and nobody noticing.
- The task create/edit screen becomes a tracked ticket rather than an assumption, and it is on the
  critical path to cutover for the same reason
  [#36](https://github.com/stainii/task/issues/36) is.

## Found on the way

- **The `disabled` gate is gone and needs no decision.** Portal's folded bands rendered six tasks
  greyed out behind a `disabled` class, so the commonest act after clearing the top five was a click
  that revealed what was already on screen. ADR-0006's "starts folded, showing a count" already
  removes it — folded means *not rendered*, not *rendered dead*. Unfolding is remembered for the
  session, so clearing the top five does not re-gate the same list.
- **A prototype bug worth naming, because it will recur in Angular.**
  `classList.toggle(name, undefined)` **toggles** rather than forcing, so a rig button matching none
  of its clauses flipped its own state on every render. Any expression built from `&&` chains over
  optional dataset keys can yield `undefined`; both prototypes now coerce with `!!`.
- **`CONTEXT.md` was still missing `omnibox`**, which
  [ADR-0014](0014-two-destinations-and-you-capture-by-typing.md) flagged and deliberately did not
  add because a concurrent session held the file. Added here, with `postpone`.

## Amendments

### Portal *did* postpone — constantly — and the presets were shaped against the wrong distribution

Amended by [Task create/edit: the surface where you write a task](https://github.com/stainii/task/issues/42),
2026-08-10.

This ADR states that portal "had no way to say *not today* except editing the due date inside a
seven-step dialog, so in practice nothing was ever postponed". Measured against the archive, that is
**false in its second half**. Step 5 of that dialog is the only UI in portal that writes
`startDateTime`, and it produced **3,726 start-date-only patches on 1,190 hand-made tasks** — 64% of
all edit traffic, every year from 2020 to 2026, one task pushed 98 times. Postpone was portal's most
common act on an existing task; it just cost four clicks and a dialog.

The decision this ADR reached is **unaffected and strengthened** — postpone deserves its own
affordance more, not less. What changes is the preset set. `Tomorrow / +1 week / pick a date` is
shaped against a distribution that is not in the data: exactly +7 days was used **55** times, while
+2…6 days was used **1,283**. [ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md)
replaces it with **`Tomorrow / In 3 days / Next week`** in the panel, and the same set plus
**`Today`** on the task edit screen — because 1,210 of the 3,726 pushes set the start date to the
current day, which is a task being pulled *back* into the day's work and which postpone, moving
forward only, cannot express.

### The honesty valve is removed, and postpone is deliberately unmitigated

Amended by [The overview, part 2: context cards, the folds and the indicators](https://github.com/stainii/task/issues/58),
2026-08-16.

**A context card's badge counts started tasks only.** A sleeping task — one whose *ask me from* has
not arrived — is not counted as overdue anywhere on the card, however late its due date is. This
reverses *Context card badges count sleeping tasks* above, and it is **the author's ruling**, taken
against the recommendation.

**The badge is the only part of the card that is scoped that way.** The count stays a true total of
everything open in the context, the six-segment bar keeps drawing all of them, and the *what comes
next* line names the genuinely soonest task whether or not it is asleep. So `house · 4` over three
reachable rows is the intended reading: a count that is not a total is not a count, and the card
describes the context, not the subset of it you can act on today. Only the **badge** makes a claim
about urgency, and only that claim has to survive being clicked into.

The reason the badge was kept was that a card is a pressure surface. The reason it goes is that it
is also a **description**: `house — 1 overdue` above a context you can click into and find nothing
overdue in is a card that does not survive being checked. The ADR defended the badge as the thing
that keeps postpone honest and never considered that the badge itself has to stay believable —
pressure you learn to distrust is not pressure, so the valve was at risk of defeating itself. Both
halves are true; the author weighed them and chose the believable card.

**Nothing replaces it, and the card gains nothing to compensate.** Seven candidates were put up and
all seven declined, which is worth listing because each will otherwise be re-proposed by whoever
next notices that a postponed task can vanish: the fold bar carrying the fact instead; a softened
fourth badge state (`1 waiting`); a split count (`2 open · 3 asleep`); a subset inside the badge
(`2 overdue · 1 asleep`); the *what comes next* line naming the soonest sleeper; a second colour bar
for sleeping tasks; and dimming the sleeping segments of the existing bar. **Postpone is unmitigated
on purpose** — recorded as a decision so nobody later reads it as a gap and quietly plugs it.

The last three were **built and driven** rather than argued
(`task-front-end/prototypes/PROTOTYPE-context-card-sleeping.html`), and two findings came out of
drawing them:

- **A fifth colour in the six-segment bar destroys the bar**, confirming this ADR's own objection at
  *The collapsed band says what is behind the door*. With two of five segments purple, the bar has
  stopped saying anything about importance — a purple segment cannot tell you whether the sleeping
  task is a `focus` or a `back-burner`, and in the driven case it was one of each. The objection was
  recorded there about stripes on a **fold bar**; it turns out to hold just as hard *inside* the card
  bar, which is a stronger claim than the original made.
- **A second bar has to share the first one's scale**, or it decides the question by accident. The
  first cut let the sleep bar flex to fill the card, so one postponed task rendered as a full-width
  underline — a single sleeper looking like a crisis. Any future *add a second indicator* proposal
  inherits this trap.

What this costs, stated plainly so the trade is not rediscovered:

- **A sufficiently postponed task is invisible as *urgent* from the overview.** `Onderhoud ketels`,
  62 days overdue and asleep, raises no badge and appears in no band; it is still inside the card's
  count and can still be the name on the *what comes next* line, but nothing says it is late. The
  only surface that says so is `Starting in the future…`, behind a fold, inside a context you have to
  enter. *The app stops asking, it does not stop knowing* still holds — but the knowing is now two
  clicks deep rather than one glance.
- **`postponeCount` loses the argument that killed it.** It was refused twice on the grounds that
  the badge already did the job. The field stays refused, but that reason is now void; if it is
  raised a third time it must be argued on its own merits.
- **[#39](https://github.com/stainii/task/issues/39) becomes the only detector.** Dogfooding on
  throwaway data is where a postpone-forever loop would surface, and there is no longer anything on
  the screen that would surface it first. The cheapest fix remains a string change in ADR-0012's
  07:30 push, not a column.

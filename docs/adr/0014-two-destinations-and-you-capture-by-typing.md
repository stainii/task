# 14. Two destinations, and you capture by typing

Date: 2026-08-08

## Status

Accepted. Resolves [#37](https://github.com/stainii/task/issues/37).

Amends [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md): the failed-to-sync list is
placed on the overview as a band, not on a settings screen.

Discharges the three ledger rows [#14](https://github.com/stainii/task/issues/14) left open —
FE-025 (app shell), FE-026 (module menu, dropped) and FE-031 (routing; lazy loading dropped) — and
answers the presentation question [ADR-0013](0013-one-anchor-and-a-trigger-that-shapes-the-form.md)
handed over.

## Context

[#13](https://github.com/stainii/task/issues/13) turned four apps into four labels and
[#14](https://github.com/stainii/task/issues/14) dropped the module menu outright, which left the
shell unstaffed: `app.html` is a bare `<router-outlet/>` after
[#30](https://github.com/stainii/task/issues/30), and the overview prototype's entire chrome was
`<header><h1>Tasks</h1></header>`.

[ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md) had already answered more of the
ticket than its body assumed — clicking a context card _enters_ that context, so context is neither
a filter nor a screen switch. What it did not say is whether entering is a route or in-page state.

The author named the destinations directly: the tasks; the task templates **plus "what did I do
without it being a task already"**; and the technical surfaces, with an open question about whether
those split.

Two rounds of prototypes were built and driven with **real archive data** — the 44 recurring
templates with their real names, min/max and last-execution dates from
`~/portal-archive/2026-08-04/` ([#35](https://github.com/stainii/task/issues/35)), with "today"
pinned so dueness was real. Four navigation shells
(`task-front-end/prototypes/PROTOTYPE-navigation-shell.html`), then four placements for rejected
changes (`PROTOTYPE-failed-to-sync.html`). **Both rounds changed the answer, and the second refuted
the premise it was built on.**

## Decision

### Two destinations, and a third you are sent to

- **Tasks** — ADR-0006's overview, including entered-context state.
- **Templates** — the list, and authoring behind it (ADR-0013).
- **Status & settings** — behind `⋯`, and deliberately not a peer of the other two.

The third is not a destination you visit on purpose. [ADR-0009](0009-the-app-is-its-own-monitor.md)
already ruled that health must **come to you** as banners on the overview, on the argument that a
passive readout _"reports but can never alarm."_ A status screen you must remember to open is the
failure mode that ADR was written against — so what is left behind `⋯` is what remains **after** a
banner has already spoken: two build dates, the 07:30 push toggle for this device, and log out.

**No profile surface.** FE-030 shrinks to a single _log out_: [ADR-0010](0010-a-tunnel-an-allowlist-and-a-role.md)
makes this single-user with nothing to edit, and [#14](https://github.com/stainii/task/issues/14)'s
_authenticate to sync, not to see_ makes login a prompt the outbox raises, not a page.

### You capture by typing

The appbar carries an **omnibox**, not a menu: _Add, find, or say what you did…_. Typing offers,
in order — complete a matching open task, **I already did this** for a matching template, and create
a task with what you typed.

This was chosen over a FAB-and-sheet (3 taps) and over a chores destination (2 taps) because it
puts capture **one keystroke from wherever you are**, and because it is what portal actually did:
`housagotchi-add-execution` was never a list you browsed and ticked, it was
`<mat-select>` + a datepicker + _Done!_. The browse-and-edit list was a separate route behind its
own menu bar. Portal had already split daily capture from administration; the memory of "two
screens" was the creature plus a dropdown.

**The omnibox is not a route.** It is a control on the appbar, so typing never changes the URL and
Escape returns you to where you were.

### The templates list is the reminding surface

Typing assumes you know what you did. The author does not always: _"I like that you can go to the
templates, see when it's last done and hit a button."_ So the same list serves both moods —
every row carries **when it was last done, as an elapsed count and a date**, and a **✓**.

The date is not decoration. On real data the top row reads _Onderhoud ketels — 62 days overdue ·
last 792 days ago · 7 Jun '24_. "792 days ago" is arithmetic; "7 Jun '24" is a memory.

### "I already did this" is only ever a chore that is **not yet due**

Building the shells forced a distinction that shrinks the feature. If a template is due, ADR-0013
has already fired a task and it is on the overview — you complete it there. ADR-0011's second shape
(_mint a task created and completed in one breath_) applies **only** when no task exists yet.

Portal blurred this because its dropdown listed all 44 templates undifferentiated. On the author's
real data that is **16 due** against **28 not due**, so more than half of portal's dropdown was
offering things reachable more directly one screen away. The omnibox and the templates list both
rank not-yet-due templates first for the ✓, and prefer the open task when there is one.

### Both capture paths ask when, defaulting to today

_Amended by the author after the first draft, which let the ✓ fire silently._

Neither the templates list's ✓ nor the omnibox's _I already did this_ completes on the spot. Both
open the same one-field confirm — **a date, defaulting to today** — and both write ADR-0011's
`completedOn`.

The first draft treated the ✓ as a single tap and counted that as its advantage. That was the wrong
saving to chase: **the whole reason this action exists is that you did something away from the app**,
and the gap between doing it and recording it is exactly what makes it out-of-band. A capture path
that can only mean _today_ would force the same lie ADR-0011 was written to prevent — and would
throw away the min/max anchor's accuracy in the process, since `lastCompletionOf` reads that date.

The evidence was already on the table and this ADR walked past it: ADR-0011 records that portal's
form carried a **required** `mat-datepicker` labelled _"When did you do it?"_ — required, not
optional, in the one screen that had lived with this for years.

**This includes completing an existing task from the omnibox dropdown** — and that corrects the
boundary this ADR first drew. The line is not _out-of-band versus on the overview_. It is:

- **You chose it by name** — typed it into the omnibox, or found it in the templates list. You are
  recording something that already happened, so the app asks **when**.
- **You acted on it in place** — swipe right, or _Complete_ in the expanded panel, on a task row in
  front of you. The gesture is the point, and it means _now_.

Splitting on where the row was clicked would have broken ADR-0011's _one button, two shapes_ in
half: typing a chore's name means the same thing whether or not a task happens to exist for it, and
the two shapes (complete the open task, or mint one created-and-completed) are an implementation
detail chosen by the data, not by the user.

**So the dropdown's two groups collapse into one list.** Once every row opens the same confirm, the
`Complete an open task` / `I already did this` split is invisible and misleading — it was also
listing a due template **twice**, once in each group, against this ADR's own not-yet-due rule. One
list of things you can mark done, each row's sub-line saying which state it is in (_7 days overdue_
versus _last done 10 days ago_), plus _create a task_ underneath.

Backdating a task completed in place stays where ADR-0011 put it, in the task's own edit surface.
Extending the prompt to the swipe is a later call if that default proves wrong; nothing here
forecloses it.

### Rejected changes are a band on the overview, above _Due today_

[ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) said `4xx` patches drop into "a visible
failed-to-sync list" and never said where. It goes **on the overview**, above _Due today_, with
_Fix and retry_ / _Discard_ per row, and vanishes entirely when there is nothing rejected.

Above the work, not below it, because a rejected change is not diagnostics — **it is something you
believe you did that did not happen**, and it outranks today's work.

Putting it behind `⋯` was rejected for ADR-0009's reason: you would go on believing those tasks were
done, indefinitely, and the only thing that could tell you is a screen you have no reason to open.

**Marking the rejection on the task row was rejected on a finding that inverted the prototype's own
premise.** The file was built expecting the hard case to be a task the server never heard of — which
turns out not to be hard at all, because ADR-0004's client renders from local storage, so a
locally-created task always has a row. The hard case is the opposite, and it is the **common** one:

> A rejected **completion** has no row to attach to, because the completion succeeded _locally_ —
> the fold closed the task and ADR-0006's overview does not show closed tasks. The thing the
> rejection belongs to has already left the screen.

Completions are the most common patch in the system, so "mark it on the task" fails precisely where
it matters. The variant needed an inline mark _and_ a band, degenerating into this decision plus
extra machinery.

> **Read this sentence carefully — [#58](https://github.com/stainii/task/issues/58)'s body did not.**
> _"needed an inline mark and a band"_ is the **reason the variant lost**, not a specification. The
> band alone is the decision; no inline mark is built. See _A rejected-change row names the act and
> the reason_ below, which also fixes the band's contents.

### Everything is a route, and the route does not name the axis

```
/                    overview, everything
/in/:value           entered context — ADR-0006's scoped bands
/templates           the list
/templates/:id       authoring (ADR-0013)
/status              the boring screen
```

Routes rather than screen state, because three decided things depend on it: ADR-0012's 07:30 push
must land somewhere when tapped; FE-014's last-used context restore becomes a stored URL rather than
bespoke state; and this is an installed PWA on Android, so hardware back must leave the _context_,
not the app.

`/in/:value` rather than `/c/:context` **keeps [#4](https://github.com/stainii/task/issues/4)'s
promise in the one place it would otherwise leak.** ADR-0006 made the grouping axis a property of
the card row alone; a route naming `context` would hard-code it into every stored URL and
notification deep link, so swapping to goals later would invalidate them all.

_Recorded as decided-by-recommendation:_ the author accepted "everything is a route" without ruling
on the axis-neutral form. Reverting to `/c/:context` is a one-line change while nothing is built.

**Lazy module loading stays dropped** (FE-031) — it existed because there were seven apps behind a
menu, and there are now two destinations.

## Consequences

- The shell is an appbar with an omnibox and a `⋯`, plus two tabs. There is no sidenav, no bottom
  bar and no rail — the phone and desktop shells differ only in density, which is ADR-0006's
  one-continuum rule holding at the navigation layer too.
- **Templates stop being setup furniture.** ADR-0013's named risk was "a screen you open when you
  set something up and then do not touch for months"; the ✓ makes it a screen touched weekly. Its
  authoring form is unaffected, but its _list_ now has a daily job.
- The overview gains one conditional band. It needs no endpoint — rejected patches are already in
  the client's outbox.
- **The list size ADR-0013 handed over is survivable, because of its own deactivation rule.**
  [#35](https://github.com/stainii/task/issues/35) found 115 referenced templates against 43 alive,
  so the migrated list is ~115 entries — but ADR-0013 drops deactivated templates from the list by
  default, leaving the 44 the author actually keeps. The omnibox is the escape hatch if that is ever
  wrong: typing beats scrolling at any length.
- A push notification, a shared link and a restored session are the same mechanism: a URL.
- **Two capture paths exist for one action** (type it, or ✓ a row). That is deliberate — they serve
  different states of knowing — but it is two implementations of ADR-0011's one button, and they
  must mint identical patches. The shared date confirm is what makes that cheap: the two paths
  differ only in how the template is chosen, and converge before anything is written.

## Found on the way

- **Housagotchi's "due tasks" half needs nothing built.** `HousagotchiReportService` scored
  templates past `min` as _late_ and past `max` as _very late_, and that is what drove the
  creature's mood. Under ADR-0013 _late_ means a task exists and _very late_ means it is overdue —
  both already on ADR-0006's overview. The report is fully derivable from the tasks screen, which is
  why [#13](https://github.com/stainii/task/issues/13) could drop the gamification layer without
  losing information. Only the capture half needed a home.
- **`CONTEXT.md` still needs `omnibox` adding**, and was deliberately not edited here: a concurrent
  session resolving [#36](https://github.com/stainii/task/issues/36) has uncommitted changes to that
  file, and editing it from this session risked silently clobbering them.

## Amendments

### The one list has one cap, and the order inside each half

Amended by [Templates: the reminding list and the one authoring screen](https://github.com/stainii/task/issues/61),
2026-08-15.

This ADR collapsed the dropdown's two groups into one list and stated the order of what typing offers
— _"complete a matching open task, **I already did this** for a matching template, and create a
task"_. Building the merge needed two things that sentence does not say, and got one of them wrong on
the first attempt.

**The stated order holds, and it was briefly inverted.** The first implementation put template rows
above task rows and recorded that as decided-by-recommendation on the grounds that the ADR was silent
about the merged list. It is not silent; the sentence above is the order, and the reason it is right
is the same reason the groups were collapsed — an open task is the thing the app has already decided
you should be doing, and ranking a chore above it re-creates the split as an ordering.

**The five rows are shared, not five each.** Both halves capping at `bands.CAP` independently and the
merge capping again is a cap of ten pretending to be a cap of five, and the half listed second loses
every row: five matching chores pushed every open task off the list. `templateOffers` therefore takes
the room the tasks left rather than a cap of its own.

**A template row is one task definition, not one template.** ADR-0011 makes the affordance pick a
task, and portal's _"What did you do?"_ dropdown listed one name each; with several definitions the
equivalent is naming which one. Expanding in the list rather than asking after the row is picked is
what keeps the box one keystroke deep — you type _stofzuigen_ and the thing you meant is there,
instead of a row called _Beddengoed_ that then asks a question.

### The templates list's own order, inside each half

Amended by the same ticket.

This ADR says the list ranks **not-yet-due templates first** and says nothing about the order within
either half. _Decided by recommendation:_ the quiet half leads with the one longest since it was
done, and a template never done leads them all — which is the reminding question in order. The firing
half keeps the overview's own `byRank`, because a second answer to _what matters most today_ one tab
away from the first is how portal's comparator and its buckets came to disagree for years.

### A rejected-change row names the act and the reason, in words, with no status code

Amended by [The overview, part 2: context cards, the folds and the indicators](https://github.com/stainii/task/issues/58),
2026-08-16.

_Rejected changes are a band on the overview_ fixed the band's **position** and left its **contents**
unspecified; `PROTOTYPE-failed-to-sync.html` had four placements to compare and never asked what a
row says. A row is:

> **Boeken tandarts voor Elise — marked complete**
> Tuesday, offline. You completed it on this device, so it has already left your list.
> `Fix and retry` `Discard`

**No HTTP status code.** Drawn rather than argued
(`task-front-end/prototypes/PROTOTYPE-rejected-band-contents.html`), and the drawing produced a
better reason than the one this ADR would have given. _Rejected (400)_ is not merely diagnostics in
a band defined as not-diagnostics — it is **constant**: a validation refusal is a 400 essentially
always, so the code is a fixed phrase repeated on every row of a band whose entire job is to say
what went wrong _this time_. It occupies the position the eye reaches first and carries no per-row
information. The technical detail remains recoverable on `/status`
([#63](https://github.com/stainii/task/issues/63)).

**Accepted cost:** nothing on the overview now distinguishes _the server refused this_ from _the
server never received it_. That distinction lives on `/status` and in the queued indicator. Put to
the author as the reason to keep the code, and declined.

**The two verbs are words, not glyphs**, which is [ADR-0019](0019-verbs-are-glyphs-facts-are-words.md)
applied rather than excepted: a glyph is spent on a verb, and anything a glyph would have to
_explain_ is a fact that gets a word. Drawing the glyph variant found a collision the rule predicts
but nobody had named — **the bin for _discard this change_ sits one band above the glyph for
_cancel this task_, two destructive icons of similar weight meaning entirely different things one
band apart.** `Discard` also throws away something you believe you did, which is the maximum
consequence in the app carried by the minimum affordance. This spends no new glyphs, leaving
ADR-0019's roughly-a-dozen tripwire untouched at four.

**The inline mark is not built, and [#58](https://github.com/stainii/task/issues/58)'s body was
wrong about it.** That ticket reads _"It needs an inline mark **and** the band"_, which inverts this
ADR: the sentence it summarises — _"The variant needed an inline mark and a band, degenerating into
this decision plus extra machinery"_ — is the **reason marking-on-the-task lost**, not a
requirement. The prototype is the evidence: its variant 3 renders orphaned rejections in a band
anyway, so it is this decision with a partial duplicate bolted on for the minority of rejections
that still have a row. The band alone covers every case. #58's body is corrected.

**`PROTOTYPE-failed-to-sync.html` has not expired**, unlike the fold prototype that
[ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) invalidated. Its bands predate
ADR-0015's start-date banding and its markup needs rebuilding, but the finding it carries is
untouched by that: a rejected completion has no row because the task is **closed**, and ADR-0015
gave sleeping tasks a home while correctly giving closed ones none.

### What the two verbs on a rejected row actually do

Amended by [The overview, part 2: context cards, the folds and the indicators](https://github.com/stainii/task/issues/58),
2026-08-16.

_A rejected-change row names the act and the reason_ settled the words and left the mechanics open.
Building them found that the obvious reading of _Fix and retry_ — open the task, correct it, save —
**cannot work for the case the band exists for**: the commonest rejection is a completion, whose task
is closed, and [ADR-0018](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) redirects
`/task/:id` away from a closed task. The one verb the band can offer every row is therefore:

- **Fix and retry** puts the patch **back in the outbox, at the back of the queue**, and takes the
  notice down. At the back because the queue drains strictly in order and everything made since is
  newer intent — re-inserting at its old position would replay a stale value over the edits that
  followed it.
- **Discard** forgets the notice. The patch stays in the task's history either way: the failed-to-sync
  list is about what the user can see, not about unremembering what happened.

**The notice comes down because it was acted on, not because the send succeeded.** If the server
refuses the patch a second time it returns through the ordinary drop path, which is the only shape in
which this cannot quietly lose the fact. _Fix_ is then what you did elsewhere — in the dialog, on the
server, in the next deploy — and this is the button that tries again.

_Decided by recommendation while building #58; no prototype drove the verbs' mechanics, only their
words._

### The expanded panel's _Complete_ gains an "another day" surface; the swipe does not

Amended by [Complete a one-off task on another day](https://github.com/stainii/task/issues/83),
2026-08-27.

_Both capture paths ask when_ put swipe-right and the expanded panel's _Complete_ in one bucket —
_acted on it in place … it means now_ — and closed with _"Extending the prompt to the swipe is a
later call … nothing here forecloses it."_ Issue #83 is that call, and it **splits the bucket
rather than moving the line**: the task panel was the only completion surface with no _"when did you
do it?"_ step, where a template and complete-by-name both have one.

- **The swipe stays silent-and-today.** A gesture on a row in front of you is the fast path on
  purpose ([#9](https://github.com/stainii/task/issues/9)'s single uninterrupted swipe), and a
  prompt inside it is the thing this ADR and ADR-0011 both rejected.
- **The panel's `Complete` becomes a split control `[ complete ][▾]`.** A plain tap still means
  today, silently. The `▾` is a disclosure — a caret, like the row's own — opening a _Done when?_
  menu (`Today · Yesterday · 2 days ago · In the past…`). Opening the card and picking a date is a
  deliberate act, not the in-place gesture, so it asks without crossing the line. _In the past…_ is
  this ADR's one confirm (`DateConfirm`), reached the same way from the toast below.
- **A silent completion is correctable in its toast.** The undo toast carries a _change day_ row
  for the horizon — variant A of the prototype — which is where ADR-0011's amendment already put
  the only correction a wrong `completedOn` gets.

Two consequences are taken knowingly. `DateConfirm` now caps at **today** on every path, because it
only ever collects a completion date and you cannot have done a thing tomorrow. And the toast
correction is **undo-then-recomplete** (ADR-0011's amendment names it): a patch id is an idempotency
key, so _change day_ records a void plus a fresh `completePatch` rather than rewriting the original,
and re-arms the horizon around the new completion each time it is used.

_Driven by `PROTOTYPE-complete-on-another-day.html`; the A+B verdict and its three open
sub-decisions are recorded on #83._

# Verbs are glyphs, facts are words

An **action** is drawn as a glyph. A **fact** is written as a word. That is the whole rule, it
applies to every screen, and it is not new — it is what `portal-front-end` did for years, recovered
after this map had spent five design tickets quietly walking away from it.

## The rule

- **Verbs are glyphs.** Edit, postpone, complete, cancel, *I already did this*. An icon-only
  control always carries an accessible name and a tooltip carrying the verb's **full** name.
- **Facts are words.** Dates, counts, band titles, field labels, context names, meta lines, state
  indicators, everything in a card. No glyph stands in for information.
- **No word is deleted to save space.** Pruning was built, driven and rejected — see below.

## The premise was backwards, and that is what settled it

[#43](https://github.com/stainii/task/issues/43) was raised as *"the prototypes carry too much
textual overload"*, framing icons as the untested alternative to a settled word-based design. The
opposite was true.

`portal-front-end` shipped an icon vocabulary of **seventeen glyphs**, self-hosted from the
`material-design-icons` npm package (`styles.scss:13` — never a CDN, so offline was never the
blocker). And it was **icon-only in exactly the places this map has since specified words**:

```html
todo-task-panel.component.html
  <mat-icon aria-label="Edit task">edit</mat-icon>
  <mat-icon aria-label="Complete task">done</mat-icon>
todo-menu-bar-for-overview.component.html
  inbox = Tasks    settings = Templates    mailbox = Subscriptions
```

The author's answer to whether that cost anything, across years of daily use: **the icons were
clear.** So the four text buttons in ADR-0006 and ADR-0015 are not an improvement on something that
failed — they are a **drift nobody on this map ever decided to make**, arrived at one ticket at a
time because each ticket wrote its own labels and none of them looked at portal's markup.

The corroboration is that the map already agrees with itself where it wasn't paying attention:
[ADR-0014](0014-two-destinations-and-you-capture-by-typing.md) gives every templates-list row a
**✓** — the one verb affordance this map invented from scratch, drawn as a glyph with no discussion
at all. This ADR does not change that row. It generalises it.

## What was rejected, and what it cost to find out

Four policies were built into one prototype and applied to four surfaces at once —
overview, templates list, task dialog, omnibox — because a visual language cannot be judged one
screen at a time.

**Icons for *kinds* was rejected because the floors carve two holes in the middle of the one form
it was invented for.** This was the dense variant in
[#42](https://github.com/stainii/task/issues/42)'s prototype, where a glyph labels *what kind of
field this is* — a tag for context, a flag for importance — while values stay words. Generalised, it
breaks: `Due` and `Ask me from` must stay worded (below), so the dialog ends up with three glyph
labels and two uppercase word labels and **no rule a reader could infer**. #42's version looked
coherent only because it iconised the dates too, which is the exact lie #42 was written to catch. It
also buys nothing — the chip groups wrap, so the glyph floats beside a two-line block and the form
is **no shorter than the worded one**.

**Pruning was rejected by the author on the strongest possible grounds: it loses too much
interesting information.** This one took two rounds and is the reason the prototype earned its cost.
Pruning first *looked* like the winner: it delivered the only real saving the icons-for-kinds policy
had — the context card header fitting one line instead of wrapping — for **one distinct glyph
instead of seven**. The author then asked to combine it with the icon verbs, so that combination was
built as its own policy and driven. Seeing it applied, the verdict reversed. The leading indicator
was on the templates list, where pruning turns

```
Onderhoud ketels — 62 days overdue · last 792 days ago · 7 Jun '24
                                     ^^^^^^^^^^^^^^^^^ deleted by the prune rule
```

into a single date — deleting a distinction ADR-0014 records as the **author's own improvement**,
with its reason on the record: *792 days ago* is arithmetic and *7 Jun '24* is a memory, and they do
different jobs. A rule that cannot see the difference between a redundant word and a second fact is
not a rule worth having.

Pruning also had to **shorten the verbs themselves** — Postpone→*Later*, Cancel→*Drop* — which
changes what they mean. Choosing glyphs deletes that problem instead of solving it: **a glyph has no
length to shrink**, so the verb keeps its full name in its tooltip and accessible name at zero
horizontal cost. Accessibility is not a tax paid here; it is where the word goes.

## Three floors: words, always

Confirmed by the author, not up for retesting, because in each case the evidence already exists and
already went one way:

1. **The two dates.** [#42](https://github.com/stainii/task/issues/42) found that a calendar glyph
   and a clock glyph both read as *a date* and neither says **which** — and those two are the pair
   [ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) exists to keep apart.
2. **The collapsed band.** ADR-0015 drove four variants and chose words over stripes.
3. **The rejected-changes band.** ADR-0014 put it above the work precisely so it would *speak*.

**A floor turned out to be narrower than the ticket assumed, and the narrowing is a finding.** On an
overview row only **one** of the two dates can ever be live — the task is visible, so *ask me from*
is by definition already past. The confusable pair is a property of the **dialog**, where both are
editable and side by side. Floor 1 is therefore a rule about the dialog, not about every surface
that mentions a date.

## Consequences

- **ADR-0006 is amended twice.** Its three panel verbs become glyphs; and its rejection of
  always-visible action buttons **loses its measurement** — that rejection rested on three *text*
  buttons costing ~110px and truncating task names in a three-column grid, and the premise was
  word-width. Four glyphs do not cost that. *The rejection stands anyway, decided by
  recommendation:* swipe already covers complete and cancel, so an always-visible row would put four
  permanent affordances on every card to serve the two verbs swipe does not reach. Expand-to-reveal
  now stands on that reason instead of an expired one.
- **ADR-0015 is amended.** The fourth verb it added, *Postpone*, is a glyph like the other three.
  Its three-column cap is untouched — that measurement is about **task names** at five columns, not
  about buttons — but its cross-reference to ADR-0006's button rejection now points at a rejection
  whose stated reason has changed.
- **ADR-0014 is unchanged**, and is the precedent this ADR generalises.
- **Cancel is separated from the constructive verbs** — edit, postpone, complete grouped, cancel
  pushed to the far edge. *Decided by recommendation.* Colour already distinguishes them, but colour
  alone fails the way #42's two dates failed: it says *category*, never *which*. Portal never faced
  this, because portal had two well-separated verbs (pencil, tick) and no `CANCELLED` at all.
- **No icon font. Inline SVG on `currentColor`.** *Decided by recommendation.* The vocabulary this
  rule needs is **four glyphs**, against portal's seventeen — a font dependency for four glyphs
  fails the standing *prefer fewer moving parts* preference, and `currentColor` gives ADR-0015's
  dark mode both themes from **one asset**, with no FOUT and nothing to cache offline. Portal's own
  package, `material-design-icons`, has been unpublished-stale since 2016. **Tripwire:** if the
  vocabulary passes roughly a dozen glyphs, that trade flips and this should be revisited.
- **The clock is reserved for *postpone*.** Choosing verbs-as-glyphs and rejecting kinds-as-glyphs
  resolves a collision the two policies had when both were on screen: the clock meant *postpone*
  under one and *ask me from* under the other, so adopting both would have made one glyph mean two
  things one screen apart — the objection ADR-0015 raised against reusing the stripe language.
- **A budget, not a style guide.** A glyph is spent on a verb. Anything a glyph would have to
  *explain* is a fact, and gets a word.

## Found on the way

- **`addD` is wrong in every prototype in `task-front-end/prototypes/`.** All of them compute
  `new Date(dt.getTime() + n * 86400000)`, and adding fixed milliseconds across the CET→CEST
  boundary lands the result at 01:00 — so `due > TODAY` is **true for a task due today** and it
  sorts into the wrong band. It put `Gitaar schoonmaken` in `Also…` instead of `Due today`. Cosmetic
  in a prototype; not cosmetic in [#11](https://github.com/stainii/task/issues/11), where the same
  arithmetic decides what a template fires and which band a task lands in. `LocalDate` on the Java
  side is immune; the TypeScript fold and the client's band logic are not.
- **The map wrote this ticket's premise without checking the codebase it was describing** — the
  second time in two tickets, after [#42](https://github.com/stainii/task/issues/42) found both of
  *its* inherited premises false in the archive. There the unread evidence was data; here it was
  `portal-front-end`'s own markup, which is checked into a repo on this laptop and takes one `grep`.
- **The prototype's cheapest policy was refuted by building the author's combination of it.** The
  pruning policy survived the first round and would have been adopted on the strength of a screenshot
  of the context cards. It fell only once it was combined with the icon verbs and applied to the
  templates list, where what it deletes became visible. The rig was extended rather than argued with,
  which is the second time on this map that building the author's variant beat defending the
  recommendation.

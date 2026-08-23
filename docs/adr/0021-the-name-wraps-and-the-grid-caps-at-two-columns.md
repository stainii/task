# The name wraps, and the grid caps at two columns

**A task's name is never truncated.** The overview row is two lines — the name alone on the first,
everything that used to compete with it on the second — and the desktop grid caps at **two**
columns rather than three.

Accepted. Resolves [#73](https://github.com/stainii/task/issues/73).

Amends [ADR-0015](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) in one place: the grid
is capped at **two** columns, not three. Amends
[ADR-0006](0006-one-overview-grouped-by-a-swappable-axis.md) in one: *desktop density* is no longer
measured in columns.

## The defect the cap was hiding

`.name` was `flex: 1` with `overflow: hidden; text-overflow: ellipsis; white-space: nowrap`, and the
`.meta` beside it — context chip, due label, caret — was `flex: none`. Flex resolves the fixed
sibling first, so **the name absorbed every shortfall there was**. On a 390px phone that cut a name
past roughly ten characters, and `62 days overdue` cost about 100px of it on its own.

Opening the panel did not recover the name either: the body renders the description, and the row it
came from is still clipped above it. So the app held a task's name and had no width at which it
would show it to you.

## Why the three-column cap has to go with it

ADR-0015 capped the grid at three columns on a measurement: `repeat(auto-fill, minmax(270px, 1fr))`
gives five columns at 1500px and truncates almost every task name — *"Vacuum the l…"*, *"Replace the
kitch…"*. ADR-0019 later reaffirmed the cap, explicitly noting the measurement was about task names
rather than about buttons.

**That measurement was always about a row that truncates.** The row above does not, so the cap has
nothing left to be measured against — it is a number inherited from a failure mode that no longer
exists. It cannot simply be deleted, though: the argument that produced it was *width goes into the
columns, not into more of them*, and that argument is still right. It just now points somewhere
else.

Three columns at 1120px leave a panel around 330px, where a wrapping name runs to two lines often
enough that the grid gets visibly ragged. Two leave about 520px, where most names fit on one line
and the second line of the row is the only one that ever appears. **Two columns is the cap that
makes the wrap rare** — the wrap is the guarantee, not the intended everyday appearance.

### What this costs

Desktop density, and knowingly. ADR-0006 asked the layout to *use the screen space better than
portal did*, and ADR-0015 rejected a keyboard layer on the grounds that the three-column grid was
what delivered that. **The reason survives the number.** Portal was a single narrow list; two
columns of untruncated names is still the better use of a wide screen, and it beats three columns of
names you have to open a panel to read. A layout that fits more things on screen by not saying what
they are has not used the space well.

The empty-cell reasoning is unaffected: a cap of five over two columns leaves one empty cell, and it
stays empty, for exactly the reason ADR-0015 gives.

## The row's second line

The name is alone on line one. Line two carries, in order: the context chip, the due label, a
`notes` marker, and the caret pushed to the far edge.

**`notes` is a word, not a glyph**, and that is ADR-0019 applied rather than an exception to it —
*there is something written about this task* is a fact. It exists because the collapsed row could
not say it: `description()` falls back to *No description.*, so every panel looked equally worth
opening and the only way to find the ones with anything in them was to open all of them.

**The context chip is dropped at `/in/:value`.** Inside a context every row on screen carries the
same chip, so it is about 60px of constant that the name can have instead. The panel is *told*
rather than reading the route, which keeps it the axis-agnostic component ADR-0006 requires: it does
not learn that the grouping axis is context, only that its caller has already said this one.

**One row design at every width**, deliberately. It does not revert to a single line on desktop —
one shape to maintain rather than two, and at two columns the vertical cost is a few pixels on the
rows that wrap.

## Consequences

- **ADR-0015's cap is now two.** The rest of that ADR — postpone pushing the start date, the fold
  speaking, the cap of five, the empty cell — is untouched.
- **ADR-0006's density requirement is no longer measured in columns.** Nothing else in it changes;
  its rejection of always-visible action buttons already rests on swipe coverage rather than on the
  expired ~110px measurement (ADR-0019), and this ADR does not disturb that.
- **The context card's `next:` line wraps to two lines too**, and drops its ellipsis. The name of the
  next task is the whole point of that line, and it did not survive 210px. The cards are a grid, so
  one wrapping card lifts its neighbours rather than misaligning them.
- **`Due today` is gone from the band heading except when the cap is exceeded.** The band tops
  itself up with work that is not due, so the heading was false over those rows — the objection the
  existing docstring already raised for the zero-due case, which turns out not to depend on the
  count being zero. `Today's work` replaces it; `Due today — all {n}` stays, because when the cap
  breaks every row in the band really is due.
- **The rows and the fold bar gain a hover, behind `@media (hover: hover)`.** Without the guard a
  touch device holds `:hover` after a tap and one row looks permanently picked. No transitions —
  nothing else on this screen animates. The tint is one token, `--app-hover`, because the two
  surfaces that carry it live in different stylesheets and two copies of one colour is how they
  would quietly stop matching.
- **The fold bar's existing hover moves behind that guard too**, which it was not before. It is the
  same argument applied to a rule that predates it rather than a new decision, and the alternative
  is one hover on the screen that sticks after a tap while the two beside it do not.
- **A caveat for anyone adding a hover to the panel: never the `border-color` shorthand.** The
  panel's left border *is* the importance stripe (FE-005), so the shorthand repaints every task's
  bucket grey for as long as the pointer is over it. Three sides, named individually.

## Not decided here

- **The task edit page and the templates list.** The row vocabulary lands there too, as follow-ups.
- **Shortening the due label to `62d`.** Considered and dropped: `wording.ts` argues against pruning
  these words, ADR-0019 forbids deleting a word to save space, and the wrapping row makes the trade
  unnecessary.
- **A create button on the overview.** Considered and dropped; typing in the omnibox and pressing
  Enter is the affordance (ADR-0014).

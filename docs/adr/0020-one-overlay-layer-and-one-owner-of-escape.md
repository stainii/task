# One overlay layer, and one owner of Escape

**Anything painted over the page is painted by the shell, and the app has exactly one
`document:keydown.escape`.** Two rules, one cause: an overlay owned by the screen that raised it
is an overlay that cannot be ordered against the other screens' overlays, in paint or in keys.

Decided while fixing [#67](https://github.com/stainii/task/issues/67), which was raised by the
Standards review of [#60](https://github.com/stainii/task/issues/60).

## The rules

- **Every overlay is a child of `app.html`.** The one bottom corner (`.corner`) and the one date
  confirm live there. A screen keeps the *verbs* — undo is still the overview's business — and gives
  up the *coordinate*.
- **`styles.css` holds the whole depth of the app**, six tokens, read top to bottom. Every z-index
  in `src/app` is one of them, with a single stated exception.
- **One key, one owner.** `Overlays.open(dismiss)` says an overlay is up and hands back a closer;
  `App` binds the only `document:` key listener there is and gives the press to the topmost.
- **The corner is a column, not a coordinate.** Two things in it stand one above the other. Nothing
  in the app arbitrates *which* toast wins by z-index, because there is one toast slot.

## Why a comment could not hold this

`app.css` said the bottom corner was shared and that **"only one is ever up"**. Four components
positioned themselves into it independently — the shell's notice, the overview's undo toast, the
omnibox's create toast, and the shared undo toast — and nothing made the sentence true. Complete a
task on the overview, capture within eight seconds, and two of them stood in the same place with the
newer one underneath.

This is the shape `docs/quality-bar.md` opens with: **a guarantee that lives in code and is broken
by something outside it.** The other instance in the same ticket is sharper still. `DateConfirm`
declares `aria-modal="true"` and traps focus — a promise to a screen reader that nothing outside it
is reachable — while being painted inside `.appbar`, which is `position: sticky; z-index: 5` and
therefore a **stacking context**. Every z-index it declared was clamped to 5:

| element | declared | effective |
|---|---|---|
| `omnibox.css` `.panel` | 12 | 5 |
| `omnibox.css` `.created` | 13 | 5 |
| `date-confirm.css` `.scrim` | 20 | 5 |
| `date-confirm.css` `.confirm-dialog` | 21 | 5 |

Against the shell's `.notice` at 20, in the root context. **No CSS fixes that** — a
`position: fixed` descendant still obeys its stacking context, and `position: sticky` creates one
whatever its z-index says. The only fixes are moving the DOM or adopting the CDK Overlay, and the
DOM move is the smaller of the two: `.notice` was already in the shell, so the layer it needed
already existed.

## Escape had two unconditional owners, and one of them navigates

`TaskPage` and `DateConfirm` both bound `(document:keydown.escape)`, and `TaskPage`'s **navigates
away**. With the confirm open over `/task/:id`, one press cancelled the confirm *and* left the
dialog underneath it. It was unreachable in practice only because the task-page scrim happens to
cover the appbar the confirm was painted in — which is the accident above, not a rule.

**The stack is the generalisation of what #60 already did.** The omnibox deliberately did not add a
third owner: it scoped Escape to its own host so it still caught a chip or a suggestion. That works
until two overlays overlap, which is precisely when the key matters. So the pattern is kept and the
listener is dropped: an overlay *says it is open* and the shell decides who answers. `Templates`'
*Which one did you do?* gained an Escape it never had by registering, which is one line.

Removal is **by identity, not by popping**: nothing guarantees the stack unwinds top-first, because
a route can drop a screen while something opened over it is still up.

## The one exception, stated rather than left to be found

The omnibox's dropdown (`omnibox.css .panel`) stays inside the appbar's stacking context, because it
hangs off the input and leaving would mean re-anchoring it to a moving target. Its z-index is
therefore **deliberately local** — it orders the panel against the appbar's own contents and nothing
else — which is what it was already doing when it read `12` and looked like it meant something on
the app's scale. Nothing else in the appbar paints over the page.

## What this does not do

It does not adopt `@angular/cdk`'s `Overlay`. That was the other candidate and it is a real one —
the CDK appends to the body and its keyboard dispatcher already routes Escape to the topmost
overlay, which is this ADR's second rule for free. It was declined because it also brings portals,
position strategies and scroll strategies for three boxes that already know where they go, and
because the anchored dropdown — the one thing the CDK's positioning would actually earn — is the one
thing staying put. **If a fourth overlay ever needs anchoring, reopen this.**

It does not put the notice through the toast slot. The notice is a fact you have already lived
through and a toast is an offer with a deadline; they stand side by side in the corner rather than
competing for it.

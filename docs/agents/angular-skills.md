# Angular skills, and what they found

`angular-developer` and `angular-new-app` are installed and pinned in `skills-lock.json`
([`ece70a5`](https://github.com/stainii/task/commit/ece70a5)). Read the skill before writing Angular
code — this note is only about the one question the skill raises and does not answer: **is there
existing code to migrate?**

**No. `task-front-end/` was audited against the skill's guidance on 2026-08-14 and already complies.
Do not run `ng update` or modernization schematics expecting to find work; there is none, and a
schematic sweep would only churn formatting.**

**Kept current with the code, on purpose.** A compliance snapshot that has quietly gone stale is
this project's recurring shape — a gate that still reports success after it stopped describing
anything — so a ticket that adds a component or an effect updates the counts below with it. Last
moved by [#63](https://github.com/stainii/task/issues/63), which gave `/status` a real screen — a
template file and a stylesheet where there had been an inline placeholder — and put ADR-0009's two
banners in the shell.

## What was checked

Angular 22, zoneless (no `zone.js` dependency at all), 12 components. (The count read **13** until
#63 recounted it and found 12 — this note's own warning, arriving on schedule.)

| Guidance                                      | State                                                                    |
| --------------------------------------------- | ------------------------------------------------------------------------ |
| Standalone components                         | Zero `NgModule`; zero vestigial `standalone: true`                       |
| Native control flow                           | 70 uses of `@if`/`@for`/`@switch`, including `@else if` on `/status`'s two push failures; zero `*ngIf`/`*ngFor`/`ngClass`/`ngStyle` |
| `input()` / `output()`                        | Used throughout; zero `@Input`/`@Output`/`EventEmitter`                  |
| `inject()`                                    | All DI; no constructor-parameter injection                               |
| `ChangeDetectionStrategy.OnPush`              | All 12 components                                                        |
| Signals for state                             | `computed`/`signal` throughout, plus `linkedSignal`, `resource`, `untracked`, `PendingTasks` |
| Effects for side effects only                 | Exactly 5, all genuinely imperative (navigation; four store re-reads)    |
| `provideHttpClient` + functional interceptors | `src/app/app.config.ts`                                                  |
| Host bindings via `host` metadata             | e.g. the Escape binding on `TaskPage`                                    |

The compliance is not accidental, and two places already argue the skill's own reasoning from first
principles: `task-page.ts:109` explains why the dialog's load is a `resource` rather than an
`effect` that assigns, and `client-config.ts` explains why one call deliberately uses plain `fetch`
instead of `HttpClient` (the bearer interceptor would deadlock on the config it is fetching).

## Signal Forms: offered at the trigger it named, and declined

**This section predicted its own test and the test has now happened.** It said *"the natural trigger
is the next new form, not this one"*, and named template authoring
(`pages/template-authoring/`) as the clean first use. [#61](https://github.com/stainii/task/issues/61)
built that screen, put the choice to the author, and **the author chose hand-rolled**, on two
grounds:

- **the app keeps one form idiom** rather than two that have to be learned separately, and
- **what the authoring form mostly is** — a discriminated union whose selected branch swaps the
  fields under it, plus a list of sub-forms with a drawer — **is not the flat field set Signal Forms
  is aimed at.** Its trigger section has three shapes and its calendar section four; the part a
  signal form would simplify is the six plain inputs around them.

So the candidate is **closed by decision, not still open**. Reopening it needs a new argument, not a
new form.

One factual correction to what follows: **`@angular/forms` *is* a dependency today** — it arrives
with `@angular/material` — so adopting Signal Forms would cost an import rather than an install. The
reasoning below never rested on that, and stands.

### The original argument, kept

The task dialog is the app's only other form and it is hand-rolled. In `pages/task/task-page.ts` and
`task-page.html`:

- the draft is a `linkedSignal<Task | null, TaskDraft>` (`task-page.ts:134`) that resets when the
  routed task changes
- each of the six fields binds `[value]` and an `(input)`/`(change)` handler by hand
- `value(event: Event)` casts the event target once rather than in six bindings
- three mutators — `edit`, `clearable`, `editStart` — differ only in how they treat empty string

Signal Forms (v21+, and this project is on v22) targets exactly that shape and would remove all of
it in favour of `form(model)` and `[formField]`.

**It was considered and deliberately not done.** Four reasons, so they don't have to be
rediscovered:

1. **`null` is load-bearing here, and the Signal Forms model forbids it.** The skill is explicit:
   never `null`/`undefined` in the model, use `''`. But here `null` is not "empty", it is a distinct
   wire value — `task-draft.ts:46` records that absent means _no opinion_ and present-and-null means
   _clear it_. `TaskDraft.dueDate` and `TaskDraft.description` are `T | null` for that reason, and
   `changesOf` diffs on identity so a cleared field patches as `null`. A signal form would need `''`
   in the model plus explicit `'' → null` mapping at the boundary — reintroducing, somewhere new,
   exactly what `clearable` does today. A lateral move that puts a domain distinction behind a
   framework rule.

2. **This form deliberately does not validate,** which is most of what Signal Forms buys.
   [ADR-0018](../adr/0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) and
   `task-draft.ts:68` record that the start-after-due state is _stated, never forbidden_: 4,678 of
   11,579 real tasks are in it, and validating it would reject 40% of the author's own history. The
   `conflict` computed renders a sentence instead. So `required`/`validate`/`applyWhen` and
   `submit`-gating would go largely unused.

3. **`dirty()` is not the same question as `changeCount()`.** The dialog counts _fields whose value
   differs from the stored task_ (`changesOf`), which drives both the discard confirmation and
   whether Save writes a patch at all. Signal Forms' `dirty()` is per-field interaction state: type
   a character, undo it, and the field stays dirty. Switching to it would make Save emit the no-op
   patch that `savePatch` (`task-draft.ts:82`) exists to prevent — "a row in the history saying that
   on this date somebody changed nothing".

4. **The code is new and working.** It landed as #59 and was reviewed; rewriting it buys a modest
   reduction in boilerplate against the three costs above.

### If it is revisited anyway

- `form()` accepts any `WritableSignal` as its model, and `linkedSignal` is one. The existing
  reset-when-the-routed-task-changes behaviour can be passed straight to `form()` rather than
  rebuilt as an effect.
- The tests address controls by `[data-field='…']` (`task-page.spec.ts:62`) and assert there are six
  (`:171`). Those attributes are independent of `[formField]`, so the selectors survive; what
  changes is the interaction helper at `:89`, which dispatches `input`/`change` by hand.
- **The trigger this section was waiting for has been and gone.** Template authoring
  (`pages/template-authoring/`) was the named candidate; #61 built it hand-rolled with the author's
  agreement, so the app now has two hand-rolled forms rather than one, and a Signal Forms adoption
  would be a migration rather than a first use. That raises the bar, deliberately.

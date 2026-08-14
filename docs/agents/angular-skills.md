# Angular skills, and what they found

`angular-developer` and `angular-new-app` are installed and pinned in `skills-lock.json`
([`ece70a5`](https://github.com/stainii/task/commit/ece70a5)). Read the skill before writing Angular
code — this note is only about the one question the skill raises and does not answer: **is there
existing code to migrate?**

**No. `task-front-end/` was audited against the skill's guidance on 2026-08-14 and already complies.
Do not run `ng update` or modernization schematics expecting to find work; there is none, and a
schematic sweep would only churn formatting.**

## What was checked

Angular 22, zoneless (no `zone.js` dependency at all), 9 components.

| Guidance | State |
| --- | --- |
| Standalone components | Zero `NgModule`; zero vestigial `standalone: true` |
| Native control flow | 18 uses of `@if`/`@for`; zero `*ngIf`/`*ngFor`/`ngClass`/`ngStyle` |
| `input()` / `output()` | Used throughout; zero `@Input`/`@Output`/`EventEmitter` |
| `inject()` | All DI; no constructor-parameter injection |
| `ChangeDetectionStrategy.OnPush` | All 9 components |
| Signals for state | 15 `computed`, 11 `signal`, plus `linkedSignal`, `resource`, `untracked` |
| Effects for side effects only | Exactly 2, both genuinely imperative (navigation; store re-read) |
| `provideHttpClient` + functional interceptors | `src/app/app.config.ts` |
| Host bindings via `host` metadata | e.g. the Escape binding on `TaskPage` |

The compliance is not accidental, and two places already argue the skill's own reasoning from first
principles: `task-page.ts:109` explains why the dialog's load is a `resource` rather than an
`effect` that assigns, and `client-config.ts` explains why one call deliberately uses plain `fetch`
instead of `HttpClient` (the bearer interceptor would deadlock on the config it is fetching).

## The one open candidate: Signal Forms

The task dialog is the app's only form and it is hand-rolled — `@angular/forms` is not a dependency
of this project at all. In `pages/task/task-page.ts` and `task-page.html`:

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
   wire value — `task-draft.ts:46` records that absent means *no opinion* and present-and-null means
   *clear it*. `TaskDraft.dueDate` and `TaskDraft.description` are `T | null` for that reason, and
   `changesOf` diffs on identity so a cleared field patches as `null`. A signal form would need `''`
   in the model plus explicit `'' → null` mapping at the boundary — reintroducing, somewhere new,
   exactly what `clearable` does today. A lateral move that puts a domain distinction behind a
   framework rule.

2. **This form deliberately does not validate,** which is most of what Signal Forms buys.
   [ADR-0018](../adr/0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) and
   `task-draft.ts:68` record that the start-after-due state is *stated, never forbidden*: 4,678 of
   11,579 real tasks are in it, and validating it would reject 40% of the author's own history. The
   `conflict` computed renders a sentence instead. So `required`/`validate`/`applyWhen` and
   `submit`-gating would go largely unused.

3. **`dirty()` is not the same question as `changeCount()`.** The dialog counts *fields whose value
   differs from the stored task* (`changesOf`), which drives both the discard confirmation and
   whether Save writes a patch at all. Signal Forms' `dirty()` is per-field interaction state: type
   a character, undo it, and the field stays dirty. Switching to it would make Save emit the no-op
   patch that `savePatch` (`task-draft.ts:82`) exists to prevent — "a row in the history saying that
   on this date somebody changed nothing".

4. **The code is new and working.** It landed as #59 and was reviewed; rewriting it buys a modest
   reduction in boilerplate against the three costs above.

### If it is revisited

- `form()` accepts any `WritableSignal` as its model, and `linkedSignal` is one. The existing
  reset-when-the-routed-task-changes behaviour can be passed straight to `form()` rather than
  rebuilt as an effect.
- The tests address controls by `[data-field='…']` (`task-page.spec.ts:62`) and assert there are six
  (`:171`). Those attributes are independent of `[formField]`, so the selectors survive; what
  changes is the interaction helper at `:89`, which dispatches `input`/`change` by hand.
- **The natural trigger is the next new form, not this one.** Template authoring
  (`pages/template-authoring/`) is an 18-line stub today and would be a clean first use — matching
  the skill's own rule that new forms on v21+ prefer Signal Forms while existing forms keep the
  app's current strategy.

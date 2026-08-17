# Quality bar and conventions

The standard every ticket in the migration backlog is built to, for both `task-back-end/` and
`task-front-end/`. Decided in [#10](https://github.com/stainii/task/issues/10).

Read this before writing code. It is deliberately short on style and long on the two things that
have actually gone wrong on this project: **guarantees that live in code but are broken by
something outside it**, and **defects a green test suite cannot see**.

---

## 1. What "green" means

| | Runs | Gates |
|---|---|---|
| `./mvnw verify` (back-end) | locally + CI | tests, `ApplicationModules.verify()`, Error Prone/NullAway |
| `npm run lint`, `npm run format:check`, `npm test` (front-end) | locally + CI | ESLint, Prettier, unit tests |
| Playwright end-to-end | CI, and by hand when you touch a journey | critical journeys, offline behaviour |

**Green means all three.** [ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md) deploys every
green push to `main` with no staging, so this list is the only thing between a commit and the real
database.

**Locally, run the first two.** The end-to-end suite needs Docker, a built front-end and browsers;
CI runs it on every push, which is where a slow check belongs. Run it by hand when you change a
journey.

**CI runs this list on every pull request and every push to `main`** — see [`ci.md`](ci.md) for the
jobs, for how to reproduce a failure locally, and for the canary that proves the Error Prone gate is
actually running rather than merely configured.

---

## 2. Back-end gates

### Tests and the modulith check

`./mvnw verify` — currently 215 tests in about 0:50. `ApplicationModules.verify()` runs as a test
and enforces [ADR-0003](adr/0003-two-modules-with-package-visibility-as-the-boundary.md)'s module
boundaries.

### Error Prone and NullAway

Configured in `task-back-end/pom.xml`. Three things about it are load-bearing and easy to break:

**The version pair is pinned together.** Error Prone `2.47.0` + NullAway `0.12.7` on JDK 26.
`2.50.0` breaks NullAway (`NoClassDefFoundError: predicates/type/DescendantOf`) and `2.41.0` does
not run on JDK 26 at all. When Renovate offers a bump, take both and run a clean compile.

**The `-Xplugin` argument must stay on one line.** Split across lines, javac cannot resolve the
plugin, and **Error Prone silently does not run while the build stays green**. This was hit while
writing this document.

**The `add-exports` flags in `task-back-end/.mvn/jvm.config` are required.** Without them the
compiler dies with an `IllegalAccessError` into `jdk.compiler`.

#### Severities

Stock severities, plus an explicit promotion of the date/time and comparison family to ERROR. That
promotion is the whole point: `JavaPeriodGetDays` ships as a *warning*, and it is **defect D1** —
the bug that stopped every recurring task with a min interval over 30 days from ever firing. A
warning in a build that already prints MapStruct warnings would not have caught it.

`JavaTimeDefaultTimeZone` at ERROR is how TODO-043's `Clock` bean stops being a documented wish and
becomes a compiler rule: `LocalDate.now()` will not compile.

> **With one hole, found by canary in [#44](https://github.com/stainii/task/issues/44): a `now()`
> behind Lombok's `@Builder.Default` is invisible to Error Prone.** `@Builder.Default private
> LocalDate d = LocalDate.now();` compiles green; delete the annotation and the identical
> initializer is an ERROR — Lombok moves the initializer into a generated `$default$` method, and
> the check never sees it. That is not a theoretical gap: **three of the six `now()` calls in
> `src/main` were hidden this way**, which is why #10 counted exactly three findings. So the rule
> the compiler enforces is narrower than it looks, and while any entity still carried a Lombok
> builder it needed a human: **no `@Builder.Default` initializer may call `now()`.** One more entry
> in #19's Lombok ledger — records do not have this failure mode either.
>
> **As of [#47](https://github.com/stainii/task/issues/47) the hole is closed by construction.**
> [#45](https://github.com/stainii/task/issues/45) made `Task` and `TaskPatch` records and #47 did
> the same for `TaskTemplate` and `TaskDefinition`, so
> `grep -rn "Builder.Default" task-back-end/src/main` finds nothing and Error Prone now sees every
> initializer there is. The rule stays written down because it governs *reintroducing* a Lombok
> builder on an entity, not the code that happens to exist today.

`StringConcatToTextBlock` was **off** — it crashed the compiler on the old `VariableUtils`, an
upstream bug. #47 rewrote that class; the exclusion is left in place rather than removed on a guess,
since nothing here has re-tested the crash.

#### Scope

Error Prone gates `src/main` only. On test sources NullAway is almost all noise — a test that
asserts a value is present and then uses it is correct, and NullAway cannot see the assertion.
The gate protects what runs in production; whether test code is right is answered by whether the
tests pass. MapStruct's generated `*MapperImpl` sources are excluded for the same reason
[#32](https://github.com/stainii/task/issues/32) found them worthless to measure: nobody wrote
them and nobody can fix them.

#### Suppressions

**Every suppression carries a reason.** Never a bare `@SuppressWarnings("NullAway")`.

The existing violations are parked, not fixed, because most of that code is deleted by
[ADR-0001](adr/0001-one-task-aggregate-with-triggered-templates.md) and
[ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md). Each suppression names #10 and says
what resolves it, so `grep -rn "Parked by #10" task-back-end/src` is the live list of debt and it
shrinks to nothing as the backlog lands. **Delete the suppression as part of the rewrite, not after.**

One of them used to suppress D1 itself, loudly, in `RecurringTaskTemplate`. That class is gone
([#47](https://github.com/stainii/task/issues/47)) and D1 with it, so **one parked suppression
remains**, in `JsonToMapConverter`.

> A caution learned the hard way: the file that carried `@SuppressWarnings("SuspiciousDateFormat")`
> was suppressing a check that does not fire there. For the life of that file it suppressed nothing,
> and nobody noticed because nothing was running Error Prone to notice with. **A suppression naming
> the wrong check is indistinguishable from a working one** until something runs.

---

## 3. Front-end gates

- `npm run lint` — ESLint via `angular-eslint`, the counterpart to Error Prone.
- `npm run format:check` — Prettier. Formatting is a tool's job, never a review comment.
- `npm test` — vitest. **`npm test`, never `ng test` directly**; see below.

Node is pinned to 26 in `.nvmrc`; run `nvm use` first. On an older Node the CLI refuses to run,
which is the front-end's version of the `~/.mavenrc` trap in `task-back-end/README.md`.

**The suite runs in `Europe/Brussels`, and the zone is part of the test.** `npm test` is
`TZ=Europe/Brussels ng test` — the same zone as the back-end's `task.time-zone`. Added by
[#57](https://github.com/stainii/task/issues/57), whose band arithmetic exists to survive the two
days a year that are 23 and 25 hours long: **under `TZ=UTC` every one of those tests passes against
the very arithmetic they were written to catch**, because a zone with no daylight saving cannot
express the bug. `ng test` on its own is therefore a green suite that has checked nothing, which is
the same shape as the `-Xplugin` argument above — a gate that still reports success after it has
stopped running.

**`now()` goes through the `NOW` token** (`src/app/clock.ts`), the counterpart to the back-end's
`Clock` bean and there for the same reason: a date-boundary decision taken by calling `new Date()`
inline can only be tested by waiting for the calendar. Nothing enforces it — there is no Error Prone
here — so it is a convention, and the band arithmetic
[#57](https://github.com/stainii/task/issues/57) is about to write is exactly what it is for.

**`Math.random()` goes through the `RANDOM` token** (`src/app/random.ts`), added by
[#65](https://github.com/stainii/task/issues/65) and the same convention for the same reason. The
evidence is portal's own: `random-adjective.service.spec.ts` asserted that two draws differ, which
against 994 words is a coin flip that lands wrong once in a few hundred runs — a test that fails for
no reason is a test that gets deleted. Behind the token, a spec says which word it wants.

---

## 4. End-to-end tests (Playwright)

Against the **real stack** — the app, Postgres and Keycloak from compose — not a mocked API.

`browserContext.setOffline(true)` is the only mechanism in the whole stack that can test ADR-0004's
offline contract end to end. The shared fold fixtures prove the *rule*; only these prove the browser
actually queues and replays. **That, and only that, is what the suite carries.**

This section originally also promised critical user journeys — *create a task, complete it, swipe to
cancel, create a template and see it fire*. [#64](https://github.com/stainii/task/issues/64)
deliberately did not build them, and the list is deleted rather than left standing: a journey a
vitest can assert is a vitest, and a documented promise nobody kept is the fiction #10 found in
Error Prone. Creating and completing a task are exercised here anyway, because the offline scenarios
need them; swiping and template firing are not covered end to end at all.

**Installed by #64.** Four scenarios, in `task-front-end/e2e/`, and no more:

| file | what only it can see |
|---|---|
| `outbox-drains-in-order.spec.ts` | an out-of-order drain makes the rename a `404`, which the outbox **drops**, silently |
| `cold-start-offline.spec.ts` | the document comes from the service worker and the tasks from IndexedDB, with no token |
| `expired-token-stalls.spec.ts` | a refused patch stalls and prompts, and survives the redirect to Keycloak and back |
| `stream-resume.spec.ts` | a stranded device catches up on exactly what it missed — no gap, no duplicate |

```bash
cd task-front-end && npm run e2e
```

That builds the front end and runs `e2e/stack.mjs`, which brings up Postgres and Keycloak from
`task-back-end/compose.yaml`, starts the back-end jar, and serves `dist/` behind one origin with
`/api` and `/realms` proxied — nginx's shape (ADR-0010), because the suite needs the *production*
bundle: `ng serve` emits no service worker, so half of these scenarios cannot exist there.

Three rules the suite is written to, each of which cost a debugging session:

- **Assert only on data you created, by name.** The stack is shared and nothing is cleaned between
  runs; the database holds years of real tasks. Nothing here counts rows or reads the overview's
  panels, because whether a row is on screen depends on the band cap and which folds are open.
- **Prove the fault you claim to have injected.** `setOffline(true)` does not close an *established*
  connection, so the stream test refuses its reconnect instead — the first version cut nothing and
  passed anyway.
- **Wait for a state, never for a duration.** `navigator.onLine`, the not-syncing banner and
  `/ngsw/state` are the three the suite waits on, and each is the app's or the browser's own answer.

**What it deliberately does not cover, and cannot.** #64: the nginx limits ADR-0007 names —
`proxy_read_timeout` under the stream's lifetime, and `proxy_buffering` on — "must not be faked".
`e2e/serve.mjs` is a stand-in for nginx's *shape*, one origin with `/api` and `/realms` behind it,
and it is unbuffered by construction with no read timeout: it **cannot express either fault**, so a
green suite says nothing about them. They stay untested here, and belong to
[#24](https://github.com/stainii/task/issues/24) with the real nginx config. Nor does the suite
prove a migration against real rows ([#29](https://github.com/stainii/task/issues/29)).

**And one deviation, stated rather than quietly adopted.** #64 says *assert only on data you
created, **by id***. The suite asserts by unique **name**: an id never appears on any surface a
person can drive, and reading one out of IndexedDB would be the store-poking the whole suite
avoids. `uniqueName()` makes the name as unrepeatable as an id.

It has already earned itself twice over, on two defects nothing else in the build could see:

- the service worker answered the navigation to Keycloak's login page with the app shell, which made
  logging in impossible (`src/app/pwa/ngsw-config.spec.ts` now holds the cheap version of that test);
- the create toast had been missing its whole positioning block since #61 split `undo-toast.css` out
  — rendering inline inside the appbar with `Add details` off the right of the screen. It surfaced on
  CI and not on a Mac, because it is geometry and fonts differ.

---

## 5. Testing conventions

### Integration tests over unit tests

A deliberate choice where business logic is thin: an integration test carries more value there than
a mock-heavy unit test. **Nothing in this bar should be read as pushing toward unit tests that exist
to move a number** — and note that there is no number. See §7.

### Assert on your own data, by id

Integration tests share one reused Postgres container and nothing is cleaned up between them —
**between *tests*. The database is emptied once at the start of each *run***, in
`TestcontainersConfiguration#emptyOnce`, so a local run and a CI run start from the same place. That
is a guarantee this section depends on rather than a convenience; see *Why the run starts empty*
below. So:

- **A test asserts only on data it created, identified by its own ids.** Never "the stream contains
  one event", never "the repository has three rows".
- **Test data is not dated in the future** unless the test is about future dates.
- **Test data never poses as something the server issued.** A value the application mints — a
  `sequence`, and anything like it — must be unmistakably not one when a test mints it instead.

None of this is theoretical. Instancio mints dates decades ahead, and a stream test written against a
time window received a patch from another test class dated 2071. Test *order* would have changed
the result.

#### Why the run starts empty

**A reused container grows for ever, and one endpoint turns that into a failure.** `GET /api/tasks`
returns *every* open task with its full patch history, and the tests that read a snapshot do so
through `WebTestClient`'s default **256 KB** buffer. Nothing cleans up, so every run adds to what
the last one left.

Measured, not predicted. Each full run left exactly **+34 open tasks**, dead linear across eight
runs — 63, 97, 131, 165, 199, 233, 267, 301, 334 — and on **run 8** the suite failed with four

```
DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144
```

in `TaskModuleIntegrationTest.thePatchThatIsSentTwiceIsAcceptedTwiceAndStoredOnce` and three tests
of `TaskPatchStreamResumeIntegrationTest`. 301 open tasks passed and 334 did not, so the wall is
around 320. With the wipe in place the same counter sits flat at **29, 29, 29**.

Three things about this are worth keeping:

- **CI could never have caught it.** CI is always run #1 on a fresh container, so the bug is
  invisible to precisely the thing that exists to see bugs — this document's own recurring shape, in
  a new place. What the wipe buys is that *a local run and a CI run mean the same thing*.
- **It looked like an importer problem and was not.** The failure was first met with 12,850 tasks in
  the shared container, left by a stale `PortalArchiveImportIntegrationTest` run from before that
  class had its own container. Its private container works exactly as
  [#52](https://github.com/stainii/task/issues/52) intended — a full run leaves the shared database
  on **89** rows, not 12,850 — so the importer reached the same wall in one step instead of eight,
  and in doing so hid the ordinary accumulation underneath it. **The obvious culprit was the
  loudest, not the cause.**
- **The 256 KB buffer is deliberately left at its default.** Raising it postpones rather than fixes
  — unbounded growth beats any constant — and it would also hide the wipe failing. At the default, a
  return of the growth fails loudly and early instead of years later.

This does not weaken anything above it: the wipe runs **once per JVM, before the first test touches
Postgres**, so no test ever sees another test's data vanish. The one case it does not cover is two
`./mvnw verify` runs at once on one machine, where the second empties the first's database mid-run
— the shared container was already unsafe that way before this existed.

**A test that cannot obey these rules gets its own container, and says why.** There is exactly one:
`PortalArchiveImportIntegrationTest`, which drives [ADR-0005](adr/0005-migration-by-replay-into-one-history.md)'s
importer — its first act is `TRUNCATE task_patch, task CASCADE` and its last is 12,483 tasks and
39,450 patches, so it both deletes what other classes created and leaves behind a hundred times more
than any of them expects to sweep. Against the shared container it made
`DueTemplateCheckerIntegrationTest` hang for **31 minutes** and die on a closed connection. **Reach
for a private container only when the test's whole point is incompatible with sharing** — not
because isolation would be tidier, since a container per class is what made the old suite slow.

The third rule cost [#46](https://github.com/stainii/task/issues/46) an afternoon. `TaskMother`
stamped its patches with sequences drawn from far *above* the real one, to avoid the unique
constraint — so mother data held the highest `sequence` in the shared database, the server's own
watermark reported a cursor a thousand times past the end of history, and **every stream test
resumed from a point no real patch will reach for years**. The numbers are negative now: the server
only ever issues positive ones, so a negative sequence cannot be mistaken for a place in the history.

### Date and comparison logic is tested at its boundaries

From [#32](https://github.com/stainii/task/issues/32), and the labour is now split:

- **Error Prone owns API misuse** — `Period.getDays()`, default time zones, day-of-year patterns.
  A compiler check, not a test.
- **This convention owns semantics, which no checker can judge.** Does a `MinMax` trigger fire on
  the right day? How does the fold order two patches with identical timestamps? Does the importer's
  date arithmetic land on the boundary or one day off? All of that is correct Java and wrong
  behaviour.

Aimed at what the backlog is about to build: the sorted fold, the three `Trigger` implementations,
and [ADR-0005](adr/0005-migration-by-replay-into-one-history.md)'s importer.

### Shared golden fixtures for anything implemented twice

Three rules in this app exist in both Java and TypeScript, and drift between them would be silent:

- **the fold** — [ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md): *no fold rule
  without a fixture*, in [`/fold-fixtures/`](../fold-fixtures/README.md);
- **template rendering** — [ADR-0011](adr/0011-completion-is-a-task-fact-the-template-reads.md):
  *no rendering rule without a fixture*, in [`/render-fixtures/`](../render-fixtures/README.md),
  added by [#50](https://github.com/stainii/task/issues/50);
- **when a template comes round** — [ADR-0013](adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md):
  *no firing rule without a fixture*, in [`/firing-fixtures/`](../firing-fixtures/README.md), added
  by [#68](https://github.com/stainii/task/issues/68). The rule under this bar's own *third
  implementation* clause: the server asks a trigger when it **last** came round, the authoring
  screen asks when it comes round **next**, and the second question got a second answer.

All three directories work the same way, and the mechanism is the point:

- Fixtures live at the repo root, as plain JSON: the inputs plus the expected output, with **every
  field named including the nulls**.
- **Both suites enumerate the directory dynamically** — a JUnit `@ParameterizedTest` over the files,
  vitest over the same glob. Adding a file adds a test on both sides, with nothing to register.
- **Both suites assert they ran a non-zero count**, so a broken path fails loudly instead of quietly
  testing nothing. That is exactly how #32's pitest run came to measure its own exclusion for four
  months.
- **The rule under test is callable without a Spring context.** `Task.foldOf`, `TaskTemplate#render`
  and `Trigger#nextFiringDates` are all on the domain object, which is what keeps the fixture runners
  plain unit tests rather than something that needs a database to say what a date should be.

**A third implementation of anything gets the same treatment.** The test is not "is this logic
complicated" — rendering is a `String.replace` loop and some `plusDays` — it is "does this rule exist
in more than one place".

**Where a fixture can also be pointed at production code, point it there.** `FiringFixtureTest`
asserts that every date the preview lists is a date `latestFiringDateOn` — the hourly due check's own
question — would fire on. Without that, the firing fixtures would pin two previews against each
other and say nothing about the scheduler; with it, a forward rule that drifts a day off its backward
mirror fails in the fixture suite rather than in the app.

### Testing SSE

It is testable. The recipe, proven in
`task-back-end/src/test/java/.../task/TaskPatchStreamResumeIntegrationTest.java`:

- `@ApplicationModuleTest(extraIncludes = "config", webEnvironment = RANDOM_PORT)` with
  `WebTestClient.bindToServer()` — a real server. **MockMvc cannot express a mid-stream drop.**
- Read the stream as `Flux<String>` via `.returnResult(String.class).getResponseBody()`.
- **The drop is `StepVerifier.thenCancel().verify()`** — cancelling the subscription closes the
  socket. There is no need to fight `SseEmitter(0L)`'s infinite timeout.
- Reconnect is simply a second request. The heartbeat fires immediately, so "the stream is alive"
  is cheap to assert.
- Two traps: URL-encode the offset (a literal `+02:00` in a query string reads as a space and
  returns `400`), and filter for your own ids per the isolation rule above.

The SSE resume path must keep an automated test that survives a real disconnect. Portal's
`// TODO: find a way to write an integration test for SSE` survived a port and a rewrite; two of
this project's defects lived in that path.

Once ADR-0004 lands, the connection lifetime bound is a config property **set low in tests**, so
the resume path is exercised on every run rather than only after 15 minutes of uptime.

---

## 6. Code conventions

Settled in earlier tickets, recorded here so they apply to every module rather than the one they
were decided in.

- **Controllers declare explicit verb mappings, and no trailing slashes.** A bare
  `@RequestMapping("/{id}")` maps *every* verb, so a `PATCH` silently returns the entity instead of
  405. (#13)
- **A service layer sits between controller and repository** — the rule, not a per-module judgement.
  (#13, REC-013)
- **Ids are minted client-side** for anything an offline client can create. It needs its id before
  the server is reachable. (#12, ADR-0004)
- **All `now()` goes through the `Clock` bean**, which is `config/TimeConfig` and reads its zone
  from `task.time-zone` (`Europe/Brussels`). Enforced by `JavaTimeDefaultTimeZone` at ERROR:
  `LocalDate.now()` does not compile. **An entity receives the time, it never reads it** — it has
  no bean to inject, so `Task.builderForInitialTask(clock)` and `Task.undoPatch(patch, clock)` take
  one from their caller (#44). In tests, use `TestClock` and *move* it; a mocked `Clock` returns
  null for whichever method you forget to stub, and fails somewhere else.
- **jspecify is enforced, not decorative.** Packages are `@NullMarked`; NullAway checks them.
  Before this ticket, all 28 `package-info.java` files were annotated and nothing in the build read
  a single one.
- **Lombok is judgment, not rule** (#19) — use it where it is genuinely shorter. One data point
  from installing NullAway: most of the nullness violations are Lombok `@Builder`/`@Data`
  complaints that the initializer does not prove non-null fields are set. **Records do not have
  that failure mode.** Evidence for the entities' rebuild, not a ban.

---

## 7. What deliberately has no gate

Stating these so they are not quietly reintroduced.

- **No mutation score, and no mutation threshold.** pitest was removed by
  [#32](https://github.com/stainii/task/issues/32). Read that issue before reinstating it — in
  particular, do not configure it with a `targetClasses` whitelist, which will silently stop
  measuring a module when packages move.
- **No currency gate.** A build that fails because a dependency shipped yesterday fails for a reason
  the commit does not control. (#19)
- **No CVE gate.** `main` auto-deploys, so a new advisory could block the deployment of its own fix.
  (#28)

---

## 8. Before closing a ticket

- [ ] `./mvnw verify` passes.
- [ ] `npm run lint`, `npm run format:check` and `npm test` pass.
- [ ] No new suppression without a one-line reason saying what resolves it.
- [ ] `CONTEXT.md` updated if the ticket settled a domain term.
- [ ] `docs/adr/` updated if it settled or amended a decision.

End-to-end tests are not required locally; CI gates them.

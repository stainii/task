# Repo health check

**Date**: 2026-07-31
**Purpose**: establish the factual state of the hand-done `portal` → `task` migration, as the root of the migration map ([#18](https://github.com/stainii/task/issues/18)).
**Scope**: evidence only. Nothing here was fixed or redesigned; problems become backlog issues via [#11](https://github.com/stainii/task/issues/11).

**Environment used**: macOS (darwin 25.5.0), Java 25 (Oracle GraalVM 25+37.1), Maven 3.9.12, Node v22.14.0, npm 11.6.2, Docker 29.6.1 (Docker Desktop).

> **Superseded — [#20](https://github.com/stainii/task/issues/20) (2026-08-04): this is no longer the toolchain.** The environment line above records the machine this evidence was gathered on and is kept for that reason. The repo now targets **Java 26** (Temurin 26.0.2, pinned in `.sdkmanrc`), Maven 3.9.16, **Node 26** (pinned in `task-front-end/.nvmrc`) and npm 12.0.2, on Spring Boot 4.1.0 / Spring Modulith 2.1.0 / Angular 22.1 / TypeScript 6.0. All 82 back-end tests and the front-end build/test remain green on that toolchain.

---

## Headline

The migration is in **better shape than "unknown" implies**: the back-end builds, all 82 tests pass, Spring Modulith's own structural checks pass, the app boots against a real Postgres and Keycloak, and every REST endpoint responds. The front-end builds.

The soft spots are the **front-end** (both of its two tests fail, and after login it renders patches instead of tasks because it never sends its auth token), **pitest** (does not complete as configured, so there is no mutation baseline today), and a handful of **genuine defects** in back-end code that the green test suite does not catch — including one that stops long-interval recurring tasks from ever firing.

> **Update — [#30](https://github.com/stainii/task/issues/30) (2026-08-03): every front-end finding below is now history, not work.**
> The scaffold was discarded rather than repaired: `src/app/` is reduced to a building, booting, empty shell, and `@ngrx/signals` is dropped. **F1, F2 and F3 died with the code they lived in**, and both failing tests are gone — `ng build` and `ng test` are green.
> The findings are kept verbatim because they *are* the evidence that discard was the right call: each one is a bug in code whose contract [ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md) had already replaced. Read this section as a record of the spike, not as a defect list. The back-end findings are unaffected and still live.

The single most useful structural fact: **test quality is split in two, by intent.** Where unit tests reach the code they are strong (92% test strength); every controller, mapper, the scheduler and the whole `template` service are covered *only* by integration tests — a deliberate choice, since those layers hold little business logic. That split is simultaneously why mutation coverage reads 35% and why pitest cannot finish, which is what [#32](https://github.com/stainii/task/issues/32) exists to resolve. **Do not read 35% as a quality gap.**

---

## Does it build and pass?

### Back-end — `mvn verify`: PASSES

```
Tests run: 82, Failures: 0, Errors: 0, Skipped: 0
```

All 17 test classes green, including the Testcontainers-backed integration tests. Docker was available and was **not** a blocker. `TaskBackEndApplicationTests.verifiesModularStructure` runs `ApplicationModules.verify()` — **it passes**, and the `Documenter` wrote module docs to `target/spring-modulith-docs/`.

Spring Modulith recognises **six** modules: `config`, `goal`, `recurring`, `task`, `template`, `utils`. That `config` and `utils` are modelled as application modules rather than shared/cross-cutting is a structural oddity worth a decision, not a failure.

Compiler warnings (all MapStruct, all benign-looking but unreviewed):

```
TaskPatchMapper.java:[12,15] Unmapped target properties: "id, version".
RecurringTaskTemplateMapper.java:[20,27] Unmapped target properties: "activeTask, version".
RecurringTaskTemplateMapper.java:[22,27] Unmapped target properties: "executions, creationDate, activeTask, version".
TaskTemplateMapper.java:[13,18] Unmapped target property: "version".
TaskTemplateMapper.java:[15,18] Unmapped target property: "version".
```

One non-fatal but real symptom — **the test JVM does not shut down**:

```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

This costs 30s on every build. Likely causes visible in the code: `AbstractIntegrationTestCases` starts a `KeycloakContainer` in a **static initialiser and never stops it**, and the SSE emitter machinery holds non-daemon threads.

### Back-end — pitest: NOT USABLE AS CONFIGURED

**This is the most consequential finding for [#10](https://github.com/stainii/task/issues/10): there is no mutation-score baseline today, because pitest as configured does not finish.**

`pitest-maven` is bound to `post-integration-test`, so it runs on every `mvn verify` — against **all** tests, including the Testcontainers-backed integration tests. It got as far as:

```
Created 46 mutation test units in pre scan
Sending 83 test classes to minion
Calculated coverage in 43 seconds.
Created 29 mutation test units
```

and then produced nothing but repeated minion deaths for the next ~16 minutes before the run was abandoned:

```
21:13:06 PIT >> WARNING : Minion exited abnormally due to TIMED_OUT
21:14:50 PIT >> WARNING : Minion exited abnormally due to TIMED_OUT
… 9 occurrences, no mutation results emitted …
```

The author confirms this is not a one-off: on previous complete runs pitest takes **extremely long and ends in an out-of-memory error**. The cause is structural — every mutation re-runs test classes that boot a full Spring context plus Postgres and Keycloak containers, so cost scales as (mutations × container startup) and heap accumulates across minions.

> **Partly overturned on the new toolchain — [#20](https://github.com/stainii/task/issues/20) (2026-08-04): pitest *does* finish, and there is now a baseline.**
> On `pitest-maven` **1.25.8** / Java 26 / Boot 4.1, a full `./mvnw clean verify` **completed with BUILD SUCCESS** and wrote a complete report to `target/pit-reports/`. **This is the first whole-project mutation baseline this repo has ever had**, and it supersedes both the "no baseline exists" claim above and the four-month-old stale report warned about below:
>
> | | |
> |---|---|
> | Classes | 28 |
> | Line coverage | **85%** (521/613) |
> | Mutation coverage | **73%** (171/235) |
> | Test strength | **86%** (171/200) |
> | Mutations with no coverage | 35 |
>
> **What did not change is the cost**: the run took **~1 hour** of wall clock for `verify` as a whole, and earlier the same day, on a loaded machine, the identical configuration produced repeated `Minion exited abnormally due to TIMED_OUT` and had to be abandoned. So the structural diagnosis above still stands — cost scales as (mutations × Spring context + container startup) and the run is very sensitive to machine load. What is wrong is only the absolute claim that it *cannot* finish; given an unloaded machine and an hour, it does.
>
> Two consequences. **[#32](https://github.com/stainii/task/issues/32) now has real numbers to reason about** rather than an absence — and note the shape of them: 73% mutation against 86% test strength, with whole packages at 0% (`config`, `task.config`) that are configuration classes no test asserts on. That is the "split by intent" above showing up as a number, not a quality gap. **[#23](https://github.com/stainii/task/issues/23) must not put this on the default CI build** at an hour a push, and it should also correct [#21](https://github.com/stainii/task/issues/21)'s estimate of ~13 billable minutes per push with pitest, which this run contradicts by an order of magnitude.
>
> Practical fact for whoever writes that CI job: the skip property is **`-DskipPitest=true`**. Both `-Dpitest.skip` and `-Dpit.skip` are silently ignored — they look like they work and the mutation run happens anyway.

**Beware the stale report.** `target/pit-reports/` contains an index dated **18 March 2026** reporting 90% line / 81% mutation / 90% test strength across 25 classes. It is four months old, predates this state of the code, and is easy to mistake for a current result. It should not be used as the #10 baseline.

**A scoped run does complete, and it is informative.** Excluding the Spring-booting test classes, pitest finished in ~100 seconds with 1 timeout instead of 9:

```
./mvnw -B org.pitest:pitest-maven:mutationCoverage \
  -DexcludedTestClasses='be.stijnhooft.task.backend.TaskBackEndApplicationTests,*ModuleIntegrationTest,*ApplicationIntegrationTest,*RepositoryTest,*ControllerTest,*TaskEventListenersTest'

>> Generated 235 mutations Killed 82 (35%)
>> Ran 162 tests (0.69 tests per mutation)
BUILD SUCCESS
```

| Package | Line coverage | Mutation coverage | Test strength |
|---|---|---|---|
| **Project total** | 33% (199/612) | **35%** (82/235) | **92%** (82/89) |
| `task` | 87% (100/115) | 78% (39/50) | 93% |
| `task.service` | 83% (29/35) | 81% (17/21) | 100% |
| `utils` | 52% (23/44) | 57% (12/21) | 92% |
| `config.jdbcconverters` | 85% (11/13) | 71% (5/7) | 83% |
| `recurring` | 58% (25/43) | 47% (7/15) | 78% |
| `recurring.mapper` | 23% (11/47) | 12% (2/17) | 100% |
| `config`, `task.config`, `task.controller`, `task.mapper`, `task.service.helper`, `recurring.controller`, `recurring.scheduler`, `template.controller`, `template.mapper`, `template.service` | **0%** | **0%** | — |

**Read this carefully — and do not read 35% as a quality verdict.** *Test strength of 92%* means that where unit tests reach the code at all, they are genuinely good: they assert enough to kill almost every mutant. *Mutation coverage of 35%* is low because **ten packages are reached only by integration tests, and are therefore invisible to mutation testing** — every controller, every mapper, the recurring scheduler and the whole `template` service.

That is **deliberate, not neglect.** The author writes integration tests in preference to unit tests where there is little business logic: for a controller, a mapper or a CRUD service, a test that exercises the real HTTP layer, the real mapper and a real database carries more value than a mocked unit test asserting that a delegation happened. On that reading, most of the 0% measures *"covered a different way"*, not *"uncovered"*.

So the honest statement of test quality is a **split by intent**: a well-tested domain core (`Task`, `TaskPatch`, the services) with real unit tests, and an outer shell covered end to end. The number to be suspicious of is not 35% — it is that **pitest cannot see the second half at all**, which is simultaneously why it cannot finish. Whether mutation score is a meaningful measure of this codebase, and what pitest is for at all, is [#32](https://github.com/stainii/task/issues/32); it blocks the quality bar in #10.

Two things keep this from being a purely philosophical point: D1 below is exactly the class of bug mutation testing catches and the integration suite did not, and chasing the number with mock-heavy unit tests would be a real cost for no value.

Note also `PIT >> Project uses Spring, but the Arcmutate Spring plugin is not present.` — pitest itself points at the missing piece for mutating a Spring app affordably.

**One timeout survived even the scoped run**, in the package containing `Task`. Worth checking whether it is the recursion in `Task.patch()` (defect D2 below): a mutant that flips the `isAfter` comparison would make the reapplication loop non-terminating. Unconfirmed, but it would explain both the timeout and the OOM on full runs.

### Front-end — `npm ci` + `ng build`: PASSES

```
Application bundle generation complete. [11.880 seconds]
Initial total | 410.16 kB | 101.41 kB transfer
Output location: task-front-end/dist/task-front-end
```

`npm ci` installed 478 packages cleanly, but reports **34 advisories (1 critical, 19 high, 11 moderate, 3 low)**. Most are ordinary patch-level currency drift rather than anything exotic — e.g. `@angular/common` and `@angular/core` are pinned below 21.2.17, and the critical is `node-tar` (decompression DoS). This is the "no Renovate today" gap made concrete ([#21](https://github.com/stainii/task/issues/21)–[#25](https://github.com/stainii/task/issues/25)).

### Front-end — `ng test`: BOTH TESTS FAIL *(resolved by deletion in #30)*

There are exactly two tests and **neither passes**.

```
FAIL src/app/app.spec.ts > App > should compile
  Unknown Error: Zone is needed for the waitForAsync() test helper but could not be found.
  Please make sure that your environment includes zone.js

FAIL src/app/task-list/task-list.spec.ts > TaskList > should create
  ɵNotFound: NG0201: No provider found for `Keycloak`. Source: DynamicTestModule.
  Path: SignalStore -> Keycloak
   ❯ Object.initStreaming src/app/task.store.ts:58:24
   ❯ onInit src/app/task.store.ts:107:13
```

Both are **test-setup defects, not necessarily product defects**:

- `app.spec.ts` uses `declarations: [App]` for a standalone component and `waitForAsync`, but the project runs zoneless on Vitest. The spec is CLI-generated boilerplate that was never adapted.
- `task-list.spec.ts` fails because `TaskStore.onInit` unconditionally calls `initStreaming()`, which injects `Keycloak` — not provided in the TestBed. In the real app `provideKeycloak()` *is* configured, so this specific error should not occur at runtime. Do not read it as proof the store is broken in production.

---

## Does it run?

### Back-end: YES

`docker compose up -d` brought up both services (note: the containers were **already present from 4 months ago**, so this was a restart, not a first run).

```
task-back-end-keycloak-1   quay.io/keycloak/keycloak:26.1   0.0.0.0:8081->8080/tcp
task-back-end-postgres-1   postgres:latest                  0.0.0.0:61655->5432/tcp
```

The app started against the existing database:

```
Flyway: Database: jdbc:postgresql://localhost:61655/mydatabase (PostgreSQL 18.1)
Successfully validated 3 migrations
Current version of schema "public": 3 — Schema "public" is up to date.
Started TaskBackEndApplication in 23.502 seconds
```

**Configuration mismatch found**: `application.yml` points the datasource at `localhost:5432`, but `compose.yaml` publishes Postgres on **61655**. The run above only worked because the URL was overridden on the command line. `spring-boot-docker-compose` is on the classpath (which papers over this during `mvn spring-boot:run`), but the committed config does not stand on its own.

### Endpoints exercised (real Keycloak token, `portal-realm` / `portal-client`)

| Request | Result |
|---|---|
| `GET /api/tasks` (no token) | **401** — security is on |
| `GET /api/tasks` | **200**, returned 7 pre-existing tasks from March 2026 |
| `POST /api/tasks` | **201**, task created and persisted |
| `POST /api/task-patches` (with `changes` map) | **200**, patch applied — task renamed, history grew to 2 |
| `GET /api/task-patches` (SSE) | **200**, stream opens and emits `event:heartbeat / data:keepalive` |
| `DELETE /api/task-patches/{id}` | **404** — see defect D3 below |
| `GET /api/task-templates` | **200** `[]` |
| `GET /api/recurring-task-templates/` | **200** `[]` |
| `GET /api/recurring-task-templates` (no trailing slash) | **404** — see defect D4 |

The `http-requests/tasks.http` file contains a single `POST /api/tasks` request; its equivalent **passes**. The gitignored `http-client.env.json` (dev-only credentials) is present locally.

### Front-end: RUNS, AUTHENTICATES, BUT DOES NOT WORK *(resolved by deletion in #30)*

`ng serve` came up on :4200. The app redirects to Keycloak (`portal-realm`), and after a successful interactive login it returns and renders. So the Keycloak wiring is genuinely functional. What it renders, however, is wrong:

The page shows `task-list works!` and a `Tasks:` array containing **two copies of a single TaskPatch** — not a single one of the 8 tasks that `GET /api/tasks` returns to curl. Three separate defects combine to produce this:

**F1 — the bearer-token interceptor never fires, so `GET /api/tasks` is 401.**

```
GET http://localhost:4200/api/tasks → 401 Unauthorized
[error] Failed to fetch tasks HttpErrorResponse
```

`app.config.ts` matches on `urlPattern: /^(http:\/\/localhost:8080)(\/.*)?$/i`, but `task.store.ts` calls the **relative** `/api/tasks`, which resolves against the dev server as `http://localhost:4200/api/tasks` and is then forwarded by `proxy.conf.json`. The pattern never matches, no `Authorization` header is attached, and the back-end correctly rejects it. The SSE call is unaffected because `fetchEventSource` sets the header by hand. This is the single reason no tasks appear.

**F2 — patches are appended to the task array as if they were tasks.**

```ts
patchState(store, {lastUpdated: new Date(), tasks: [...store.tasks(), JSON.parse(event.data) as Task]});
```

The SSE payload is a `TaskPatch`; the `as Task` cast silences TypeScript, and the patch lands in `tasks: Task[]`. The rendered JSON is patch-shaped (`{id, taskId, dateTime, changes}`), which is exactly what the screenshot shows. There is no code anywhere that *applies* a patch to an existing task — the offline-first replay that `TaskPatch` exists to serve is simply not implemented on the client.

**F3 — two SSE connections are opened per page load, and the same patch is stored twice.**

```
GET /api/task-patches?since=2026-07-31T19:18:04.056Z → 200 OK [FAILED: net::ERR_ABORTED]
GET /api/task-patches?since=2026-07-31T19:18:04.056Z → 200 OK
```

Two streams, identical `since`, one later aborted — but both delivered the replayed patch before that happened, so it was appended twice. Hence the duplicate in the screenshot. (Note the `since` value is the moment `onInit` ran, not the last-seen patch time, because `updateLastUpdated(new Date())` is called synchronously right after the *asynchronous* `fetchTasks()` is dispatched.)

So the honest summary is: **the front-end authenticates and connects, and does nothing else correctly.** It is a scaffold, and the parts that exist are miswired.

[#30](https://github.com/stainii/task/issues/30) took that summary at its word and deleted the scaffold. The decisive argument was not that F1–F3 were hard to fix — F1 is a one-line regex — but that the code they live in is *already superseded*: `?since=<dateTime>` is the silent-divergence defect ADR-0004 was written to kill, `localStorage` is replaced by IndexedDB, and the missing patch-application logic is now specified as a fold pinned by shared golden fixtures. Repairing F1–F3 would have bought a correct implementation of a wrong contract.

---

## What's half-finished

### The `goal` module is empty

`src/main/java/be/stijnhooft/task/backend/goal/` contains exactly one file — `package-info.java` with `@NullMarked`. No entity, no repository, no controller, no test, no schema. It is a placeholder that Spring Modulith nonetheless recognises and documents as a module. Feeds [#4](https://github.com/stainii/task/issues/4).

### Schema versus entities: NO DRIFT FOUND

Flyway V1–V3 were checked field by field against `Task`, `TaskPatch`, `TaskTemplate`, `TaskDefinition`, `TaskTemplateVariableName`, `RecurringTaskTemplate` and `Execution`. Every mapped property has a matching column, and Flyway validated all 3 migrations against the live database. The one asymmetry is cosmetic: `task.version` is `INT` while `task_template.version` / `recurring_task_template.version` are `BIGINT`, though all the Java fields are `long`.

### Genuine defects found

**D1 — `RecurringTaskTemplate.shouldTaskBeCreatedBecauseItIsDue` uses the wrong day count.**

```java
var numberOfDaysSinceLastExecution = Period.between(getLastExecutionDateOrCreationDate(), now).getDays();
```

`Period.getDays()` returns only the *day component* of a Y/M/D period, not the total elapsed days. Demonstrated:

```
Period.between(2026-01-01, 2026-07-31).getDays() = 30
actual days apart                                = 211
```

So a recurring task last executed 7 months ago reports "30 days since last execution". Any template with `minNumberOfDaysBetweenExecutions > 30` can **never** become due. `ChronoUnit.DAYS.between(...)` is the correct call. The 14 `RecurringTaskTemplateTest` cases do not catch it.

**D2 — `Task.patch()` re-applies newer patches recursively and re-appends them.**

`patch()` adds the patch to `history`, then finds the first patch newer than it and calls `patch()` on that one — which appends that newer patch to `history` **again**. Re-patching a task with out-of-order patches grows the history with duplicates and re-runs the reapplication loop over a mutating list. This is the offline-first replay path, so it matters. Flagged in the ticket as "worth a second look"; it looks like a real bug, not just a smell.

**D3 — patch ids are reachable only over SSE, so undo breaks on a cold start.**

`DELETE /api/task-patches/{id}` needs a patch id. The two read paths disagree about whether the client may have one:

- `GET /api/tasks` maps history through `TaskPatchDto`, a record of `(taskId, dateTime, changes)` with **no `id`** — so ids are stripped: `{"taskId":…,"dateTime":…,"changes":{…}}`.
- The SSE stream emits the **domain `TaskPatch`** directly, bypassing the DTO layer entirely, and that payload **does** carry `id` (confirmed in the browser: `{"id":"bfc7d11e-…","taskId":"13ebc8b7-…",…}`).

So a client that has held the stream open since the patch was made can undo it; a client that starts fresh and loads tasks over REST can never undo anything. `TaskPatchDto` also has no `id` on the write side, so a client cannot mint one either — which collides with offline-first, where a disconnected client needs client-generated ids to make reconnect writes idempotent. The inconsistency is the finding: one endpoint leaks the domain object, the other over-strips the DTO.

Ids do exist in the database throughout:

```
id                                   | task_id                              | order_index
ce05cd10-b6c9-477a-a6d8-2418c40157e2 | 13ebc8b7-008d-473a-b5d6-bd725621dc98 | 0
bfc7d11e-11dc-4639-aeaa-4677544dc597 | 13ebc8b7-008d-473a-b5d6-bd725621dc98 | 1
```

This also collides with offline-first: a disconnected client cannot mint patch ids, so it cannot make writes idempotent on reconnect.

**D4 — `RecurringTaskTemplateController` is inconsistent with the other three controllers.**

- Routes are `"/"` and `"/{id}"`, so `GET /api/recurring-task-templates` (no trailing slash) **404s** while the sibling controllers work without one.
- Read endpoints use bare `@RequestMapping` rather than `@GetMapping`, so they answer **every** HTTP method.
- It injects `RecurringTaskTemplateRepository` **directly into the controller**, bypassing the service layer that `task` and `template` use.
- No `@Valid` on its request bodies (`TaskController` and `TaskPatchController` do validate).

**D5 — a null `changes` map yields a 500, not a 400.**

`POST /api/task-patches` with a body lacking `changes` produces:

```
HTTP 500
java.lang.NullPointerException: changes is marked non-null but is null
  at be.stijnhooft.task.backend.task.TaskPatch.<init>(TaskPatch.java:20)
  at be.stijnhooft.task.backend.task.mapper.TaskPatchMapperImpl.toDomain
```

`TaskPatchDto` is a bare record with no Bean Validation constraints, so `@Valid` has nothing to enforce and the Lombok `@NonNull` check fires deep in the mapper. There is no `@ControllerAdvice` anywhere in the codebase, so the custom exceptions (`TaskNotFoundException` and friends) also have no declared HTTP mapping.

**D6 — the `addExecution` Spring Data JDBC workaround.**

```java
public void addExecution(Execution execution) {
    var executions = new ArrayList<>(this.executions);
    executions.add(execution);
    this.executions = executions; // why... Spring Boot Data JDBC needs this to trigger the update query.
}
```

Real and load-bearing (Spring Data JDBC change detection is identity-based), but it is a workaround carrying a comment that reads as unresolved. Worth an explicit decision rather than leaving it as folklore.

### Test-quality observations

- 82 back-end tests, ~2,410 lines of test code, 17 test classes. No `@Disabled`, no `@Ignore`, no assertion-free tests found. Density is reasonable (e.g. `TaskTest`: 8 tests / 46 assertions; `RecurringTaskTemplateModuleIntegrationTest`: 10 / 55).
- Weakest by that measure: `TaskPatchControllerTest` (4 tests / 5 assertions) and `RecurringTaskTemplateTest` (14 / 22 — and it still misses D1).
- **Container images are unpinned**: tests use `postgres:latest` and the default `KeycloakContainer` image, which resolved to **26.0**, while `compose.yaml` pins **26.1**. Tests and local runtime are on different Keycloak versions, and `postgres:latest` resolved to **PostgreSQL 18.1** here.
  **RESOLVED by [#20](https://github.com/stainii/task/issues/20)** — kept verbatim above as the evidence that the drift was real. Both sides are now pinned to the same explicit versions: `postgres:18.4` in `TestcontainersConfiguration` *and* `compose.yaml`, and `quay.io/keycloak/keycloak:26.7.0` in `AbstractIntegrationTestCases` *and* `compose.yaml`. The Keycloak image is now named explicitly rather than inherited from `testcontainers-keycloak`'s default, so a library bump cannot move it silently.
- `TestcontainersConfiguration` carries three unused imports (`KeycloakContainer`, `DynamicPropertyRegistry`, `DynamicPropertySource`) — leftovers from a refactor.
- Both containers use `.withReuse(true)`, which silently does nothing unless testcontainers reuse is enabled in the user's `~/.testcontainers.properties`.
- Test realm is `test-realm` / `test-client` / `testuser`; runtime realm is `portal-realm` / `portal-client`. Divergent fixtures.

### Front-end versus back-end coverage

The back-end exposes four controllers; the front-end consumes **two endpoints** of one of them:

| Back-end | Front-end |
|---|---|
| `GET/POST /api/tasks` | `GET` attempted, but 401s (see F1); no create UI |
| `GET /api/task-patches` (SSE) | consumed — the only call that actually works |
| `POST/DELETE /api/task-patches` | **not used** — no way to edit, complete or undo |
| `/api/task-templates` (5 endpoints) | **no UI at all** |
| `/api/recurring-task-templates` (6 endpoints) | **no UI at all** |

So the entire `template` and `recurring` surface — including everything housagotchi/setlist/health would ride on — has no front-end whatsoever. There is nothing in the front-end with no back-end.

### Stubs, TODOs and scaffolding

- `task-list.html` is two lines: `<p>task-list works!</p>` and the task array through the `json` pipe. It is CLI scaffold output.
- `app.html` still has the generated sidenav with `Link 1` / `Link 2` / `Link 3` pointing at `#`.
- `app.config.ts` carries three `TODO`s about hardcoded config: the bearer-token URL pattern and the Keycloak URL are pinned to `localhost`, and `onLoad: 'login-required'` is annotated `TODO zal tegenwerken bij offline use` — a known conflict with the offline-first goal.
- `task.store.ts` has three TODOs: load local state into the store first, sync back to localStorage automatically, and move the localStorage helpers into a service. The offline layer is half-wired — it writes to localStorage but never reads it back into state on init.
- `task.store.ts` has a latent bug independent of the above: `setLastUpdatedInLocalStorage` writes `date.toString()` while the `effect` writes `date.toISOString()` to the same key, and the reader parses with `new Date(...)`. Two formats, one key.
- `TaskPatchService.tail` carries `// todo: avoid disconnect after 30s (nginx still left)` — a leftover assumption from the portal deployment.
- Back-end has no other commented-out code.

---

## Repo hygiene

**History is a single squashed commit.** `3556ff9 Initial commit: monorepo with back-end, front-end and agent config` is the entire history. Consequences: no `git bisect`, no record of what was ported when or from which portal service, no way to distinguish hand-written code from CLI scaffolding, and no attribution for the migration decisions already made. Everything this document records had to be established by running things, not by reading history.

**`target/` is not committed.** The ticket suspected it was; it is not. `git ls-files` matches zero paths under `target/` or `node_modules/`, and the working tree is clean. Both are covered by the nested `.gitignore` files (`task-back-end/.gitignore` ignores `target/`, `task-front-end/.gitignore` ignores `/node_modules` and `/dist`) — the root `.gitignore` only covers OS files and the local HTTP credentials. `target/` and `node_modules/` do exist on disk, and `target/pit-reports/` still holds a **stale pitest report from 18 March 2026** that is easy to mistake for a current one.

**There is no CI.** No `.github/` directory, no workflow files, no Jenkinsfile, nothing carried over from portal. The only YAML in the repo is `task-back-end/compose.yaml`.

**Documentation referenced by `AGENTS.md` does not exist.** `docs/agents/domain.md` prescribes a root `CONTEXT.md` and `docs/adr/`; **neither exists**. There are no ADRs, so none of the migration decisions already taken (Spring Data JDBC over JPA, modulith over microservices, the TaskPatch model, dropping the gateway) are recorded anywhere.

**Other notes**: `lombok.config` is present, and Lombok is used heavily (`@Data`, `@Builder`, `@SneakyThrows`) — in tension with the map's stated preference for records and less Lombok. `error_prone.version` is declared as a property in `pom.xml` but **no Error Prone dependency or plugin uses it**; it is dead config. `task-workspace/` is empty apart from the IntelliJ `.idea` config.

---

## Side effects of this health check

- One task was created in the local dev database and then renamed via a patch: `13ebc8b7-008d-473a-b5d6-bd725621dc98` ("Renamed by health check"). It sits alongside the 7 pre-existing March test tasks. Local dev data only — no production system was touched.
- `npm ci` reinstalled `task-front-end/node_modules` from the lockfile.
- `docker compose up -d` restarted the two long-dormant local containers.

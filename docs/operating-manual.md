# Operating manual

For future-you, six months from now, with no memory of any of this.

This project exists because a hand-done migration was left alone for a while and its state became
unknown even to its author ([#18](https://github.com/stainii/task/issues/18) had to go and find out).
This page is the thing that stops that happening again: **start here, and you should be able to run
the app, understand its shape, fix the usual breakages and restore the data without reading anything
else first.**

It is an index as much as a manual. Where a detail already lives somewhere that is kept current, this
page links there instead of copying it — a second copy is a copy that goes stale.

| I want to… | Go to |
|---|---|
| run it on this laptop | [From a clean clone to a running app](#from-a-clean-clone-to-a-running-app) |
| understand the shape of it | [The architecture in one page](#the-architecture-in-one-page) |
| know *why* something is the way it is | [the decision log](adr/README.md) |
| fix something that is broken | [When the common things break](#when-the-common-things-break) |
| get the data back | [Backups, restore, and rebuilding the box](#backups-restore-and-rebuilding-the-box) |
| deploy, or undo a deploy | [Deploy and rollback](#deploy-and-rollback) |
| write code here | [`quality-bar.md`](quality-bar.md) first, then [`AGENTS.md`](../AGENTS.md) |

---

## From a clean clone to a running app

### 1. The toolchain

Versions are pinned in the repo rather than left to whatever is on the machine.

```bash
sdk env          # repo root — JDK 26, from .sdkmanrc
nvm use          # task-front-end/ — Node 26, from .nvmrc
```

**`~/.mavenrc` beats `sdk env`.** If yours sets `JAVA_HOME`, `./mvnw` uses that JDK regardless of the
shell and the build dies with `release version 26 not supported`. Either `sdk default java 26.0.2-tem`
or run with `MAVEN_SKIP_RC=1`. The full pin table is in
[`task-back-end/README.md`](../task-back-end/README.md#toolchain).

### 2. The back-end, which brings its own infrastructure

```bash
cd task-back-end && ./mvnw spring-boot:run
```

That is the whole step. `spring-boot-docker-compose` is on the classpath, so starting the application
**starts `compose.yaml` for you** — Postgres and Keycloak — and derives the datasource from it. There
is deliberately no `spring.datasource` block to keep in sync; the four lines that used to sit there
named the wrong port for a year and nothing read them.

Flyway builds the schema from `V1` on first run. **There is no seed data**, by design: an empty task
list is the honest starting state, and real data arrives only through
[ADR-0005](adr/0005-migration-by-replay-into-one-history.md)'s importer.

### 3. The front-end

```bash
cd task-front-end && npm ci && npm start
```

`src/proxy.conf.json` puts `/api` and `/realms` on the dev server's own origin, so development is
same-origin exactly as production is behind nginx
([ADR-0010](adr/0010-a-tunnel-an-allowlist-and-a-role.md)).

**`ng serve` emits no service worker** — `serviceWorker` is set on the production configuration
alone. Anything about install, offline caching or push has to be checked against a production build
(`npm run e2e` builds one and runs the real stack; see
[`task-front-end/README.md`](../task-front-end/README.md#running-end-to-end-tests)).

### 4. Where everything is

| | URL / port | Notes |
|---|---|---|
| Front-end (dev server) | http://localhost:4200 | proxies `/api` and `/realms` |
| Back-end | http://localhost:8080 | Spring Boot default; nothing overrides it |
| Keycloak | http://localhost:8081 | realm `stijnhooft-realm` |
| Postgres | `localhost:61655` | db `mydatabase`, user `myuser`, password `secret` |
| Health | http://localhost:8080/actuator/health | the **only** exposed management endpoint ([ADR-0009](adr/0009-the-app-is-its-own-monitor.md)) |
| Runtime config | http://localhost:8080/api/config | unauthenticated on purpose; hands the browser its Keycloak url, realm and client id, plus the server's `buildTime` |

Log in as **`stijnhooft@hotmail.com` / `test`**; Keycloak admin is **`admin` / `admin`**. These are
deliberately worthless dev values in a public repo — see
[`compose/keycloak/README.md`](../task-back-end/compose/keycloak/README.md), which is also the file
to read before touching the realm fixture.

Every `/api` request requires the realm role **`task-user`** — except `/api/config`, which is
unauthenticated because the browser needs it *before* it can log in. `/actuator/health` is the other
`permitAll`, for compose's healthcheck. A user who exists, authenticates and still gets `403` on
everything is almost always missing the role.

---

## The architecture in one page

**One deployable, not thirteen.** That is the point of the whole migration: `task-back-end` is a
single Spring Boot application, internally divided by Spring Modulith, plus `task-front-end`, an
Angular PWA. The old `portal` stack it replaces is reference material only.

**Six modules.**
[ADR-0003](adr/0003-two-modules-with-package-visibility-as-the-boundary.md) named four; `notification`
and `migration` arrived later with the work that needed them. The authoritative list is always
[`docs/modules/all-docs.adoc`](modules/all-docs.adoc), which is generated:

| Module | Owns | Why it is its own module |
|---|---|---|
| `task` | `Task`, `TaskPatch`, status, importance, the sync endpoint | It is an aggregate. |
| `template` | `TaskTemplate`, `TaskDefinition`, triggers, the due check | A second aggregate, which *creates* tasks but does not own them. |
| `notification` | Web Push subscriptions and the 07:30 push | Talks to the outside world; nothing else may. |
| `migration` | the portal importer | Exists to be deleted after cutover. |
| `config` | `Clock`, security, `/api/config` | The one genuinely app-wide thing. |
| `goal` | nothing yet | A deliberate empty bookmark ([#4](https://github.com/stainii/task/issues/4)). |

**The boundary is package visibility, and it is enforced** — `ApplicationModules.verify()` runs as a
test, so a cross-module reference fails the build rather than a review. **Modules tell each other
things by application event**, in the past tense, named for what happened in the publisher's domain
([ADR-0002](adr/0002-one-application-event-published-as-a-fact.md)).

One thing that confuses the graph on first reading: `components.puml` draws *"Template uses Task"*
even though template only publishes an event. The event **type** lives in the `task` package —
`TaskTemplateFired` is `task`'s vocabulary, published by `template` and listened to by `task` — so
the dependency arrow points at the package that owns the word, not at a method call.

**The module graph is generated, not drawn.** `TaskBackEndApplicationTests` writes
[`docs/modules/`](modules/) on every run and commits it, so a new arrow between modules shows up as a
diff. Do not hand-maintain a picture of this — read `docs/modules/components.puml` and
`module-*.adoc`.

**The two mechanisms worth understanding before changing anything:**

- **Offline sync** ([ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md)) —
  `POST /api/task-patches` is the only way a client writes **a task** — there is no task `PUT` and no
  task `DELETE`. Templates and push subscriptions are ordinary REST and deliberately outside this
  contract: patching works for a task because a task is inert, whereas a template is a rule that keeps
  running in your absence, so its writes need the server and the authoring screen says so rather than
  queueing (`sync/template-api.ts`). Ids are minted client-side. The client's clock
  orders patches; the server's `sequence` drives resync; an outbox holds what has not landed yet.
  Nearly every subtle bug on this project has been in here.
- **Templates firing** ([ADR-0001](adr/0001-one-task-aggregate-with-triggered-templates.md),
  [ADR-0016](adr/0016-the-due-check-ticks-hourly-and-starts-with-the-app.md),
  [ADR-0017](adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)) — one hourly
  `@Scheduled` check in `template/schedule/DueCheckSchedule`, which also runs at startup.

The rest of the *why* is in [the decision log](adr/README.md), and the domain vocabulary — every term
this code uses in a specific sense — is in [`CONTEXT.md`](../CONTEXT.md).

---

## When the common things break

**First, look at the app itself.** [ADR-0009](adr/0009-the-app-is-its-own-monitor.md) decided there is
no monitoring stack at all: the app reports on itself on the `/status` screen, and two banners that
need no threshold announce the conditions worth acting on. If something is wrong, that screen usually
already says so.

The screen states three lines — **Last synced**, **App built**, **Server built**. ADR-0009's two
*facts* are the first and the last; the middle one is the front-end's own build date, and it earns its
place by making the persistent-mismatch banner legible rather than mysterious. Only the server can
state what the server is running, which is why the third line is fetched rather than compiled in.

**Logs**: `task-back-end/logs/task-back-end.log`, rolled daily and kept 30 days.

> **Hole — owned by [#24](https://github.com/stainii/task/issues/24).** ADR-0009 requires those logs
> to sit on a volume that outlives container recreation, "or a nightly deploy erases the week".
> Nothing in this repo provides that volume: `compose.yaml` has no back-end service, and the deploy
> unit is #24's unbuilt work. Locally the path above is simply a directory.

| Symptom | Most likely cause | What to do |
|---|---|---|
| Nothing has fired for a while; no new tasks from templates | The scheduler is not running, or the app has not restarted | `DueCheckSchedule` runs hourly *and at startup*, so a restart is a valid first move — and if it fires a backlog, the schedule was the problem. There is deliberately **no heartbeat** for this; ADR-0009 explains why. |
| Changes made on the phone never appear elsewhere | The outbox is stalled | The outbox stops on `5xx` and network errors **by design** and the PWA keeps rendering from IndexedDB, so this looks like nothing is wrong. `/status`'s *online but not syncing* banner is the tell. Check the back-end is answering `/api/config` at all. |
| The app looks a version behind | A half-completed deploy, or a cached bundle | `/status`'s persistent build-date mismatch banner covers exactly this. A single day's skew after a nightly deploy is routine; a *persistent* one is not. |
| The app will not start after a schema change | Flyway migration failed | The failure is in the log with the version that broke. Migrations are `task-back-end/src/main/resources/db/migration`; **never edit a migration that has run** — add `V9__…`. Locally the cheapest fix is dropping the schema and letting Flyway rebuild from `V1`. |
| Locked out of Keycloak, or the realm is gone | Keycloak's own state is lost | The realm is **live state that this repo does not mirror** — `realm-export.json` is a dev fixture and must never be imported over production. Restoring it is part of [rebuilding the box](#backups-restore-and-rebuilding-the-box). Losing the realm locks you out of your own data, which is why ADR-0008 backs it up as a first-class artifact. |
| `release version 26 not supported` | `~/.mavenrc` overrides `sdk env` | `MAVEN_SKIP_RC=1`, or make 26 the sdkman default. |
| A test "passes" that cannot possibly pass | `./mvnw surefire:test -Dtest=Foo` compiles nothing | Use `./mvnw test -Dtest=Foo`. Found while canarying a boundary test that had been deliberately broken. |
| The suite fails only after several local runs | Reused Testcontainers accumulating data | `TestcontainersConfiguration#emptyOnce` handles this; if it is bypassed, `docker rm -f` the reused containers. Detail in [`task-back-end/README.md`](../task-back-end/README.md#faster-local-test-runs). |
| CI is red, locally green | Usually the end-to-end suite, which does not run locally by default | [`ci.md` §2](ci.md) is written for exactly this — how to reproduce a CI failure on the laptop. |

---

## Backups, restore, and rebuilding the box

The decision is [ADR-0008](adr/0008-every-backup-restores-itself-before-it-is-kept.md), and it is the
one to read on a bad evening: **three copies, and a backup only counts once it has restored itself.**
[ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md) adds the pre-deploy dump, which is part of
the deploy unit rather than merely a nightly schedule.

Two things that are easy to get wrong under pressure:

- **Restoring rewinds `sequence`**, which the sync contract as written cannot survive. That is what
  the **epoch** is for — ADR-0007 amended ADR-0004 to add it. Clients hard-reset rather than silently
  losing the patches in the gap. If you restore, expect every device to refetch, and let it.
- **Config is part of the restore, not just data.** The VAPID keypair in particular: losing it
  invalidates every existing push subscription. The re-subscribe-on-open rule is what makes that
  survivable rather than a manual repair.

> **Hole — owned by [#24](https://github.com/stainii/task/issues/24).** ADR-0008 puts the backup and
> restore **scripts** in #24's list of artifacts, and they do not exist yet. Until that ticket lands,
> the procedure above is a decision, not a runbook: there is nothing here to copy and paste. #24 also
> owes this page its *rebuilding the box* section — bare machine to running app, including how the
> production Keycloak realm was built, since nothing in this repo reflects it and
> [`compose/keycloak/README.md`](../task-back-end/compose/keycloak/README.md) says every change to it
> is applied by hand.

---

## Deploy and rollback

The shape is decided — [ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md): the local server
**pulls**, nightly, behind a dump; there is no inbound port and no staging environment; every green
push to `main` is a deployment, which is why the [quality bar](quality-bar.md) is the only thing
between a commit and the real database.

> **Hole — owned by [#24](https://github.com/stainii/task/issues/24).** The pipeline itself, runtime
> secrets, Flyway-on-deploy and **a rollback that has actually been rolled back** are that ticket's
> work, and it is deferred until the server is powered on again. The real commands belong here when
> they exist. Do not write them from the ADR in the meantime — a runbook that has never been run is
> the thing this manual exists to prevent.

---

## Keeping this current

A manual that rots is worse than none, because it is believed. Three mechanisms, in order of how much
they are worth:

1. **Generated beats written.** The module graph comes out of `ApplicationModules` on every test run
   and is committed, so it cannot disagree with the code. Anything else that *can* be generated
   should be.
2. **Enforced beats remembered.** The decision log's index is checked by `DecisionLogIndexTest`: add
   an ADR without indexing it and the build fails. `ToolchainPinsTest` does the same for the JDK
   pinned in two places. When you notice a fact written down twice, make one of the copies fail the
   build rather than trusting yourself to update both.
3. **Same-commit beats later.** [`quality-bar.md` §8](quality-bar.md) is the checklist before closing
   a ticket, and it already carries `CONTEXT.md` and `docs/adr/`. **If a ticket changes how the app is
   run, deployed, restored or debugged, this page changes in the same commit.**

The holes above are marked as holes on purpose. An honest gap is findable; a plausible paragraph
about a script that does not exist is not.

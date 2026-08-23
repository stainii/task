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
| change something in Keycloak | [Keycloak in production](#keycloak-in-production-which-no-file-in-this-repo-describes) |
| merge a schema change safely | [the pre-flight dry run](#before-merging-anything-that-changes-the-schema) |
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

## Keycloak in production, which no file in this repo describes

Everything above is the **dev fixture**. The production realm is live state: created once, configured
out-of-band, never imported from this repo
([`compose/keycloak/README.md`](../task-back-end/compose/keycloak/README.md),
[#31](https://github.com/stainii/task/issues/31)). That makes this page the only record of it, so:

**The admin console is on the LAN, on purpose.** `/admin/**` is never routed through the tunnel;
Keycloak publishes a LAN-only host port instead. **Administering Keycloak means being on the local
network** — a conscious inconvenience, written down here because it is exactly what gets rediscovered
at the worst possible moment.

**Every setting below is applied to the live realm by hand.** Changing the committed fixture is not
changing the realm. [ADR-0010](adr/0010-a-tunnel-an-allowlist-and-a-role.md) rewrote the fixture so the
resemblance is worth something, and the list is the resemblance:

- realm **`stijnhooft-realm`**, named after its owner — Keycloak is shared infrastructure, and `task`
  is one client in it;
- the `task` client is **public**, with **PKCE S256**, **real redirect URIs** (never a wildcard — on a
  public client that is account takeover, and PKCE does not prevent it), **empty `webOrigins`**
  (everything is same-origin) and **no direct access grants**;
- **brute-force protection on**, **30-day SSO sessions**, 5-minute access tokens;
- the realm role **`task-user`**.

**The one with teeth: a new Keycloak user gets nothing until granted `task-user`.** A working login
followed by `403` on every screen is this, essentially every time.

**The admin console is at http://192.168.0.116:8082**, and Keycloak is told that address explicitly
(`KEYCLOAK_HOSTNAME_ADMIN`) so the console's own links do not point at the public host.

**"Timeout when waiting for 3rd party check iframe message" is the console's normal complaint here,
and it is survivable.** The console is *served* from the LAN address while being *told* its auth
server is `https://task.stijnhooft.be` (`authServerUrl`), so its cookie-check iframe is genuinely
third-party — which browsers now block by default. A **hard reload** (⇧⌘R) gets you in; the stale
value is cached in the page's own JavaScript.

> **Do not "fix" this by setting the master realm's `frontendUrl` to the LAN address.** It was tried
> on 2026-08-23, on the reasoning that it would make the console single-origin and keep it working
> while the tunnel is down. It made the console *worse* — usable before, unusable after — and was
> reverted the same hour. The attribute is now unset, and that is the working state. Recorded here
> because the reasoning is appealing and would otherwise be re-derived by the next person, who would
> be me.

If the iframe error persists through a hard reload, the thing actually worth checking is whether
`https://task.stijnhooft.be` still reaches this stack — `curl -sI https://task.stijnhooft.be` should
name our nginx, not portal's. That is how a tunnel or DNS problem shows up first: in the admin
console, by accident.

**One `kcadm` rendering trap** that makes a setting look absent when it is not: `--fields attributes`
prints nested maps as `{ }`. Use the unfiltered `get` to check what a realm or client really carries.

**Users are not created here.** A user, and its password, is made in the console. Whoever makes one
must also grant it `task-user`, or it logs in perfectly and gets `403` on every screen.

> **Do not narrow `/realms/**` in nginx.** It is routed whole, and must stay that way. Keycloak's
> **account console** lives under it, and it is the only way to sign a lost or stolen device out
> remotely — which 30-day sessions make the standing remedy. Tightening it to
> `/realms/*/protocol/**` looks like hardening, removes that silently, and breaks nothing until the
> day it matters.

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

**One thing here has a stated expiry.** Keycloak sits in `task`'s single compose file rather than a
stack of its own, and [ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md) allowed that only
because **nothing else authenticates against that realm today**. The moment a second app does, the
stacks split: from then on `task`'s nightly deploy cadence is somebody else's outage. That is a
revisit condition, not a *later* — if you are reading this because you are about to point a second app
at `stijnhooft-realm`, this is the paragraph you needed.

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

Locally that path is simply a directory. **In production the logs live on a Docker volume**, mounted
at `/logs` in the back-end container, precisely so that the nightly deploy recreating the container
does not erase the week:

```bash
ssh stijn@192.168.0.116 'docker exec task-back-end-1 tail -n 100 /logs/task-back-end.log'
```

| Symptom | Most likely cause | What to do |
|---|---|---|
| Nothing has fired for a while; no new tasks from templates | The scheduler is not running, or the app has not restarted | `DueCheckSchedule` runs hourly *and at startup*, so a restart is a valid first move — and if it fires a backlog, the schedule was the problem. There is deliberately **no heartbeat** for this; ADR-0009 explains why. |
| Changes made on the phone never appear elsewhere | The outbox is stalled | The outbox stops on `5xx` and network errors **by design** and the PWA keeps rendering from IndexedDB, so this looks like nothing is wrong. `/status`'s *online but not syncing* banner is the tell. Check the back-end is answering `/api/config` at all. |
| The app looks a version behind | A half-completed deploy, or a cached bundle | `/status`'s persistent build-date mismatch banner covers exactly this. A single day's skew after a nightly deploy is routine; a *persistent* one is not. |
| The app will not start after a schema change | Flyway migration failed | The failure is in the log with the version that broke. Migrations are `task-back-end/src/main/resources/db/migration`; **never edit a migration that has run** — add `V9__…`. On a dev database the cheapest fix is dropping the schema and letting Flyway rebuild from `V1`; **on production that is data loss**, and the way not to arrive here at all is the [pre-flight dry run](#before-merging-anything-that-changes-the-schema). |
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
  Restoring is not the only operation that rewinds `sequence`: **the portal importer truncates and
  reloads**, which is the same thing. It bumps the epoch itself, in the truncate's own transaction
  ([#72](https://github.com/stainii/task/issues/72)), so there is no invisible step to remember on
  that path — but expect the same refetch on every device after an import.
- **Config is part of the restore, not just data.** The VAPID keypair in particular: losing it
  invalidates every existing push subscription. The re-subscribe-on-open rule is what makes that
  survivable rather than a manual repair.
- **There is no TLS certificate on the box to reissue** — Cloudflare terminates. What actually puts the
  app back on the internet after a rebuild is the **tunnel credential**, which ADR-0007's amendment
  already put in the archive. Losing it is what makes a rebuilt box unreachable, not a certificate.

### The commands

All of them live in [`deploy/`](../deploy) and run on the box, from `/home/stijn/task`.

```bash
deploy/backup.sh                                   # dump, prove it by restoring it, archive, upload, prune
deploy/restore.sh scratch <archive|dump.sql.gz>    # load into a throwaway Postgres and leave it up
deploy/restore.sh live    <archive>                # replace the running stack's data with the archive
```

**Where the copies are** — ADR-0008's three, none of which needed a new account or a new credential:

| Copy | Where | Kept |
|---|---|---|
| the box | `/home/stijn/task-backups/task-backup-<stamp>.zip` | 7 days |
| the cloud | `google-drive:task-backups`, via the rclone config the box already had | 30 days |
| the laptop | inside `~/server_backup_<date>.zip`, because `backup-server.sh` zips `/home/stijn` | unpruned |

**Read this when you want to know whether backups are healthy** — it is what ADR-0008's recurring
*check the backup* task is for, and it can say no:

```bash
ssh stijn@192.168.0.116 'tail -n 20 /home/stijn/task-backups/backup.log'
```

**A live restore replaces the cluster; it does not load over it.** `pg_dumpall` drops the role the
application connects as, and Postgres refuses to drop the role you are connected as — so `restore.sh`
discards the data volume and loads into a brand-new cluster under a throwaway superuser. Which is
also why the rebuild recipe below is the same mechanism rather than a second one.

**Drilled 2026-08-23, on the box.** A marker realm was created, the stack restored from that morning's
archive, and afterwards: the marker was gone, `stijnhooft-realm` with its `task` client and
`task-user` role were back, and the epoch had gone from 1 to 2. The empty-database half of that drill
is weak on purpose — there is no real data on the box until [#17](https://github.com/stainii/task/issues/17).
The data half was drilled on the laptop the same day, against a task written through the API: it came
back, and the epoch moved.

### Putting a fresh copy of portal's data on the box

This is the **dogfood refresh** ([#39](https://github.com/stainii/task/issues/39)), and it is also
the **first half of cutover** ([#17](https://github.com/stainii/task/issues/17)) — the same commands,
run for real. Doing it several times before it matters once is the point: the procedure cutover
depends on is a procedure that has already run.

**The importer never runs on the box.** `MigrationRunner` is gated on the `migration` profile, which
truncates tasks, patches and templates, and the one thing that must be impossible is it running there
because a property defaulted somewhere. So the import happens on the laptop, against a **scratch
cluster restored from the box's own archive** — seeded rather than empty, so Keycloak's realm, the
users and the `task-user` role survive the round trip — and only the finished dump goes back.

Everything below is on the laptop, from a clone of this repo, except the last step.

**1. A fresh portal dump set.** The corpus at `~/portal-archive/2026-08-04/` is frozen evidence and
predates the box's shutdown; portal has kept running, so take a new set beside it. Per database, and
note the Mongo password is read *inside* the container rather than typed:

```bash
ssh stijn@192.168.0.116 'docker exec $(docker ps -q -f name=portal_portal-setlist-db | head -1) pg_dumpall -U portal-setlist > /tmp/portal-setlist-db.sql'
ssh stijn@192.168.0.116 'docker exec $(docker ps -q -f name=portal_portal-todo-db | head -1) sh -c "mongodump -u \$MONGO_INITDB_ROOT_USERNAME -p \$MONGO_INITDB_ROOT_PASSWORD --authenticationDatabase admin --db todo --archive=/tmp/todo.gz --gzip"'
```

Four Postgres databases (`portal-housagotchi`, `portal-setlist`, `portal-health`,
`portal-social-recurring-tasks`, each user-named after its database) and the `todo` Mongo. `scp` them
down into `~/portal-archive/<date>/`, `gzip` the SQL, `chmod 600`, and delete the copies left in
`/tmp` on the box and in the Mongo container. **The dump set stays outside this repository** (#31):
it is the data, and the data is the secret.

**2. Throwaway portal containers**, at the ports `application-migration.yml` already defaults to:

```bash
docker run -d --name portal-pg -e POSTGRES_PASSWORD=portal -p 55432:5432 postgres:12
docker run -d --name portal-mongo -p 57017:27017 mongo:4.2.1
```

Load each `*.sql.gz` with `psql -U postgres`, and the Mongo archive with
`mongorestore --archive --gzip --drop`. One `ERROR: role "postgres" already exists` per SQL dump is
expected and harmless.

**3. A scratch task cluster from the box's newest archive.** `restore.sh scratch` reads
`POSTGRES_DB` out of an env file, and the archive carries the right one:

```bash
scp stijn@192.168.0.116:/home/stijn/task-backups/task-backup-<stamp>.zip .
unzip -j task-backup-<stamp>.zip production.env -d /tmp/dogfood && chmod 600 /tmp/dogfood/production.env
TASK_ENV_FILE=/tmp/dogfood/production.env deploy/restore.sh scratch task-backup-<stamp>.zip
```

It prints the port it published and the throwaway superuser password. Keep both.

**4. The import, pointed at that scratch cluster.** `spring-boot-docker-compose` would otherwise
start the dev stack and import into *that*, which is the mistake this step is written to prevent:

```bash
SPRING_DOCKER_COMPOSE_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:<scratch-port>/<POSTGRES_DB from production.env> \
SPRING_DATASOURCE_USERNAME=<POSTGRES_USER> SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD> \
PORTAL_MONGO_URI=mongodb://localhost:57017 PORTAL_REPORT_DIR=$HOME/portal-archive/<date>/reports \
task-back-end/mvnw -f task-back-end spring-boot:run -Dspring-boot.run.profiles=migration
```

`PORTAL_REPORT_DIR` matters more than it looks: it defaults to the **2026-08-04** directory, so
without it every refresh drops its report into the frozen corpus that must outlive cutover. The
run also does not end on its own — the importer is an `ApplicationRunner`, so Tomcat keeps serving
after it finishes. `Report written to …` is the last line; Ctrl-C there.

**Ignore Maven's verdict here.** With devtools on the classpath, `spring-boot:run` prints
`BUILD SUCCESS` even when the application context failed to start and the importer never ran — a
mistyped port gets you a stack trace followed by a green build. The proof that this step happened is
the report file and the row counts, never the exit code.

Read the report it writes to `~/portal-archive/<date>/reports/` before going any further — a
stored-versus-folded diff is the only thing that says the copy is faithful. The run also bumps
`sync_epoch` in the truncate's own transaction ([#72](https://github.com/stainii/task/issues/72)).

**5. Rebuild the archive around the imported cluster**, keeping the three entries `restore.sh`
expects and the date stamp in the name it makes you type back:

```bash
docker exec task-restore-scratch pg_dumpall -U postgres | gzip -c > dump.sql.gz
zip -j task-dogfood-<stamp>.zip dump.sql.gz deploy/compose.yaml /tmp/dogfood/production.env
```

**6. Load it on the box.** This is the destructive one, and the only step that runs there:

```bash
scp task-dogfood-<stamp>.zip stijn@192.168.0.116:/home/stijn/task-backups/
ssh -t stijn@192.168.0.116 'cd /home/stijn/task && deploy/restore.sh live /home/stijn/task-backups/task-dogfood-<stamp>.zip'
```

**It pulls before it stops anything**, and refuses if the images for the box's checked-out commit are
not published yet — a restore run in the window between a push and its CI build would otherwise end
with the data back and the application gone. If you hit that, wait for CI or name a commit that has
images: `TASK_VERSION=<sha> deploy/restore.sh live <archive>`.

**`-t`, or the confirmation is invisible.** `restore.sh live` asks you to type the archive's date
stamp back; without a TTY, `read -p` prints no prompt, the run looks like it hung, and the Enter you
eventually press is read as an empty answer — `not confirmed; nothing was touched`. Harmless, and it
is the script refusing correctly, but on cutover night it reads as a broken restore.

Then throw away the scratch container (`docker rm --force task-restore-scratch`), the portal
throwaways, and `/tmp/dogfood`.

**Expect every device to refetch**, twice over: the import moved the epoch and so did the restore.
That is the mechanism working, and rehearsing it is half the reason this runs before cutover rather
than during it.

**A dogfooded copy is write-throwaway.** Anything typed into the app between two refreshes is lost by
the next one, and that is deliberate — promoting a dogfood database to the real system would invent a
two-way sync problem neither ADR-0004 nor ADR-0005 solves.

### Rebuilding the box from nothing

```bash
deploy/rebuild.sh <archive.zip>
```

That is the whole of it, once a machine has Docker and this repo. It checks what is present, restores
`production.env` out of the archive, restores the data, the Keycloak realm and the epoch, links the
timer, waits for the application, and then **tells you what it could not do**.

**What is automated, and what needs hands:**

| | Automated? |
|---|---|
| Docker, `git`, `zip`, `unzip` on a bare machine | no — root, network, and a distribution's opinions. `rebuild.sh` refuses and names what is missing |
| getting an archive | no, by nature: you have no box, so its rclone credential is gone with it. Download it from Drive in a browser, or take it from `~/server_backup_<date>.zip` on the laptop |
| `git clone` of this repo | yes — public repo, no credential |
| `production.env` — DB password, VAPID pair, **tunnel token** | **yes**, out of the archive, at mode 600. Nothing to reissue: there is no certificate, and the tunnel token is what puts the app back on the internet |
| data, Keycloak realm, users, epoch bump | **yes** — one `pg_dumpall`, one restore |
| the deploy timer | yes if `sudo` is passwordless, otherwise it prints the two commands |
| **the rclone credential for future backups** | **no, deliberately** — see below |

**The rclone configuration is not in the archive, on purpose.** It is an OAuth credential for the
whole of Google Drive, and putting it inside the Drive it unlocks would mean anyone holding one
backup holds the account. It comes back from the laptop's weekly zip
(`home/stijn/rclone/config/rclone.conf`). Both `rebuild.sh` and the nightly deploy say so out loud
when it is absent — because a rebuilt box otherwise runs perfectly and simply stops having backups,
which is this system's favourite shape of failure.

**Drilled 2026-08-23, on the laptop**: from an archive, with no `production.env` on the machine and no
cluster to discard, to a healthy back-end answering `/api/config` — and the drill found the bug that
only a bare machine can find, `restore.sh` treating a missing volume as an error.

### What the deploy checks every night

Preconditions are asserted on every run, so a gap is found the next morning rather than during a
disaster. Two are fatal before anything is touched — a stack about to be recreated with a blank
variable, or an archive that cannot leave the box, is worse than a night with no deploy:

| Check | If it fails |
|---|---|
| every variable in `production.example.env` has a value | **refuses to deploy** |
| an rclone binary or config exists | **refuses to deploy** |
| `deploy/compose.yaml` parses with this `production.env` | **refuses to deploy** |
| the deploy timer is enabled | warns, with the fix |
| the archive contains all three of its members | **fails the backup**, which stops the deploy |

---

## Deploy and rollback

The shape is decided — [ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md): the local server
**pulls**, nightly, behind a dump; there is no inbound port and no staging environment; every green
push to `main` is a deployment, which is why the [quality bar](quality-bar.md) is the only thing
between a commit and the real database.

**Rolling back is four steps, in this order:** restore the dump → **bump the epoch** → pin the previous
image digest → `up -d`. The epoch step is the one that gets left out of a manual written from memory,
and skipping it silently strands every client that was ahead of the restore — see
[ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md)'s amendment, and *Backups* above.

### Before merging anything that changes the schema

**Restore last night's dump locally and run the candidate migration against it.**

This is a procedure standing in for an environment. ADR-0007 decided there is **no staging**, and named
the gap honestly: CI proves a migration applies to an *empty* database, never to yours. A `NOT NULL`
added to a column that has nulls, or a unique index over data that is not unique, passes CI cleanly and
fails at 02:00, unattended, on the only copy of years of real data. The dry run costs nothing standing,
and because it exercises the restore path it doubles as a rehearsal for
[ADR-0008](adr/0008-every-backup-restores-itself-before-it-is-kept.md)'s drill.

```bash
deploy/restore.sh scratch ~/task-backups/task-backup-<stamp>.zip
# it prints a JDBC URL; point the candidate migration at that, not at production
```

### Installing the timer, once

The unit files live in [`deploy/systemd/`](../deploy/systemd) and are **symlinked, not copied**, so
there is exactly one copy of them and it is the one in git — `git pull` updates the schedule in
place, and the nightly archive needs no extra item. Root is needed once, ever:

```bash
sudo systemctl link /home/stijn/task/deploy/systemd/task-deploy.service
sudo systemctl enable --now /home/stijn/task/deploy/systemd/task-deploy.timer
```

After editing either unit, `sudo systemctl daemon-reload`.

**`Persistent=true` is the reason this is systemd rather than a container.** The box is switched off
for weeks at a time — it was, for the whole of this ticket's deferral — and a schedule with no
catch-up means those nights have no backup and no deploy, silently. systemd runs the missed job on
the next boot instead.

### The commands

```bash
systemctl status task-deploy.timer          # when the next deploy is due
systemctl start  task-deploy.service        # deploy now, the same way the timer does
journalctl -u task-deploy.service -n 50     # or tail ~/task-backups/deploy.log
```

`deploy.sh` runs `backup.sh` first and **stops if it fails** — better to skip a night than to migrate
unbacked, which is also why a broken backup silently stalls deploys until someone reads the log.

**Which version is running** is not written anywhere on the box; it is derived. `deploy.sh` takes
`git rev-parse HEAD` after the pull, so the box runs the images built from the commit it just checked
out, and both images necessarily match. Ask the app what it is running:

```bash
curl -s https://task.stijnhooft.be/api/config    # buildTime is the back-end's; the app shows it too
```

If the images for that commit are not published yet — CI still running, or `main` red — the pull
fails and **the running stack is left exactly as it was**.

### Rolling back, for real

Four steps, in this order. The epoch one is invisible, and `restore.sh` does it for you:

```bash
deploy/restore.sh live ~/task-backups/task-backup-<stamp>.zip   # restore + bump the epoch
echo 'TASK_VERSION=<previous-commit-sha>' >> deploy/production.env   # pin
deploy/deploy.sh                                                # up, on the pinned images
```

**Remove that `TASK_VERSION` line when you are done**, or the nightly deploy quietly stops advancing
for ever. The log says `PINNED` on every run precisely so this cannot go unnoticed.

**Drilled 2026-08-23, on the box.** Pinned to the previous commit: the served `buildTime` went from
09:07:49 back to 08:54:51 and both images moved together. Unpinned and redeployed: it came forward
again. That is the whole rollback path, run rather than described.

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

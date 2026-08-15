# 9. The app is its own monitor

Date: 2026-08-05

## Status

Accepted. Resolves [#27](https://github.com/stainii/task/issues/27).

Amends [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) with the **durable-ack rule**: a
local write is acknowledged in the UI only once it is durably in the outbox. See that ADR's
*Amendments* section.

Constrains [#24](https://github.com/stainii/task/issues/24) (`GET /api/config` gains the build date;
the log volume and the health check join its artifacts) and
[#38](https://github.com/stainii/task/issues/38) (the two banners are a cutover guarantee, not a
front-end nicety).

Discharges the observability half of the gate on [#17](https://github.com/stainii/task/issues/17).

## Context

The ticket opened on a premise that did not survive being checked.

**Portal never had monitoring.** The ticket says *"The old portal had Prometheus; nothing carried
over."* `prometheus/prometheus.yml` exists, with 17 scrape jobs, and every in-scope service exposes
`health,info,prometheus`. But Prometheus, Grafana, cAdvisor and node-exporter appear **nowhere in
`docker-compose-for-swarm.yml`** — the only file deployed to the box — and the config scrapes
`docker.for.mac.host.internal`. It was a laptop experiment. Nothing carried over because nothing was
ever running, exactly as [ADR-0008](0008-every-backup-restores-itself-before-it-is-kept.md) found for
backups. Two of this map's largest operational assumptions turned out to be the same shape: a
mechanism that existed in the repository and not in production.

**`task` already carries the machinery for an answer this ADR rejects**:
`spring-boot-starter-actuator`, `micrometer-registry-prometheus` and
`spring-modulith-starter-insight` are all on the classpath, with no `management:` block anywhere and
`anyRequest().authenticated()` in front of them.

**Most of this ticket had already been answered by its neighbours** while it sat blocked on
[#22](https://github.com/stainii/task/issues/22). Auditing the ticket's own must-not-fail-silently
list against the closed map:

| Silent failure | Owner |
| --- | --- |
| Backup fails | ADR-0008 — a recurring *check the backup* task |
| Patch sync rejects a device | ADR-0004 — visible failed-to-sync list on `4xx` |
| Flyway fails at startup | the app does not start → *the app is dead* |
| Keycloak unreachable | ADR-0004 *authenticate to sync, not to see* → sync stops → *the app is dead* |
| Disk fills | the backup fails first → ADR-0008's recurring task |
| Scheduler stops firing | [#40](https://github.com/stainii/task/issues/40) — startup + short interval, idempotent |
| **The app is dead** | nothing |
| **The deploy stopped happening** | nothing — ADR-0007 handed it here explicitly |
| **Front-end errors** | nothing |

Three residual failures, and the ticket's proposed machinery — Prometheus, Grafana, an uptime
service, a heartbeat — addresses almost none of them.

## Decision

### No observability infrastructure

No Prometheus, no Grafana, no Grafana Cloud, no OpenTelemetry collector, no uptime pinger, no
dead-man's switch, no error-reporting service. Nothing is added to the stack, and two runtime
dependencies are removed from it.

Self-hosted Prometheus needs Alertmanager to *tell* you anything — four containers
(prometheus, grafana, alertmanager, node-exporter) — and all four run **on the box they watch**, so
the failure they are least able to report is the box being off. That is ADR-0008's `backup-server.sh`
in a nicer costume.

And a dashboard is not an alert channel; it is a channel *you* must remember to open. For a single
user it is the archetype of the thing the ticket warned about: read carefully in week one, never
again, manufacturing confidence throughout.

Grafana Cloud was the serious contender, because it is the only option that puts telemetry off the
box, supports alert-on-absence, and answers *"what happened last Tuesday"*. It is rejected on the
same ground ADR-0007 rejected staging: a standing moving part, on a migration whose thesis is fewer
of them, that would spend its life unattended — plus an external dependency that can stop ingesting
silently, which is monitoring with this map's signature defect built in.

### Two facts, on the screen already opened every day

The app reports on itself, because it is the one surface with guaranteed attention:

- **Last synced** — the client knows this already; it holds ADR-0004's cursor and outbox.
- **The back-end's build date** — the answer to *did the deploy stop happening?*

**The build date is read from the server, never from the front-end bundle.** RES-013 ships a service
worker, and ngsw serves a cached bundle, so a date compiled into the front-end reports when *the
bundle in this device's cache* was built — which after a failed deploy is indistinguishable from a
successful one. Only the back-end can state what the back-end is running. It rides on ADR-0007's
already-unauthenticated `GET /api/config`, sourced from `build-info`, so `/actuator/info` needs no
exposure and there is one public endpoint rather than two.

`lastSchedulerRun` and *last backup* were both considered for this payload and both refused: the
scheduler for the reason in *No heartbeat*, the backup because ADR-0008 detects that with a recurring
task and two mechanisms for one failure is how a stale one survives unnoticed.

### The banners announce themselves, and need no threshold tuned

A passive line of grey text stating "synced 3 Aug · server built 12 Jul" occupies the same pixels in
the same colour as "synced 08:12 · server built 4 Aug". It reports, and it can never alarm — the
`echo "Backup completed"` shape for the third time on this map. So staleness surfaces itself.

Thresholds are where this design would go bad: *warn if not synced for 3 days* cries wolf on a
holiday and stays silent through a week of bad signal. Both conditions are therefore chosen to need
**no number**:

- **Online but not syncing.** The client distinguishes *no network* from *network fine, server will
  not answer*. The second is wrong immediately, with no false positive on a train — and it is
  precisely the case ADR-0004 is built to conceal, since the outbox stalls on `5xx` and network
  errors by design and the PWA renders from IndexedDB regardless. A back-end dead for four days and
  four days of poor signal are otherwise the same experience.
- **A persistent build-date mismatch** between front-end and back-end. ADR-0007 tags both images with
  one commit SHA *specifically because* ADR-0004 put the fold in two languages and skew would be
  silent — but nothing verified that at runtime. This does, and it also catches a half-completed
  deploy where one container was recreated and the other was not.

**Persistent is load-bearing in the second.** A plain mismatch fires every morning, because a nightly
deploy leaves the cached bundle a day behind the server until ngsw swaps it — routine, daily, and
therefore wallpaper within a week. The banner waits until the service worker has had its chance and
the dates *still* disagree. Then it is rare, and it means something.

Both dates remain visible passively for the question that genuinely needs judgment rather than a
rule: *have I actually pushed anything this month?*

### No heartbeat

The ticket's flagship proposal — alert on the absence of an expected scheduler run — is refused,
because the map contains the one real instance of this failure and the mechanism would have missed
it.

[#18](https://github.com/stainii/task/issues/18) found `Period.getDays()` in
`shouldTaskBeCreatedBecauseItIsDue`: long-interval recurring templates never fire at all, live for
years, unnoticed. That is the ticket's nightmare exactly — tasks silently not appearing — and **a
heartbeat would have been green throughout.** The job ran, on time, without throwing. It computed the
wrong answer. A liveness signal would have actively asserted that the scheduler was healthy while the
thing it monitors was broken.

The remaining case, the job genuinely stopping, is mostly dismantled elsewhere: #40 runs the check at
startup and on a short interval, the check is a state comparison rather than a calendar event so a
missed run self-heals, and ADR-0007 restarts the app nightly — so the check runs at least once a
night by construction. For the scheduler to be permanently dead, the app has to be dead, which is the
sync banner.

**Stated rather than hidden: a scheduler that runs and computes wrongly is invisible to everything
decided here.** The defence is tests, not observability, which puts it on
[#10](https://github.com/stainii/task/issues/10) and [#32](https://github.com/stainii/task/issues/32)
— where the `Period.getDays()` fix already lives with #40.

### Logs survive the deploy, for 30 days, on a volume

ADR-0007's deploy destroys the logs nightly. `docker compose up -d` recreates a container when its
image changes and removes the old one, taking its `json-file` log with it — and Docker's default
keeps logs *inside* the container. So *"what happened last Tuesday"* had no answer at all: a deploy
landed every night in between, and the usual fix for a bad deploy is another deploy. Seventh
instance on this map of a guarantee broken by something outside the code — here a logging default and
a `compose` verb.

The back-end writes a rolling file to a bind-mounted volume, and nginx does the same, ADR-0007 having
already noted that access logs are the cheap answer to *is anyone getting 5xx*. No container, no
logging driver, no service.

**Retention is 30 days, and the number is not free-standing** — it is ADR-0008's backup retention,
deliberately. That ADR stated plainly that *anything broken and not noticed within a month is
unrecoverable*. A forensic window longer than the recovery window is decoration: learning on day 45
what went wrong when the data to repair it aged out on day 30 is a story you cannot act on. One
number, one reason.

**Logs stay out of the archive.** ADR-0008 said the archive is three things with no fourth item, and
that holds: logs diagnose the live box, they do not restore it.

### No front-end telemetry — a guarantee instead

No Sentry, no `POST /api/client-errors`, no offline error buffer. Error reporting exists because
*users do not tell you*; there is one user, holding the device when it breaks, with devtools. The
offline case that sounds like it needs buffering is precisely the case where that user is present.

Asking what would actually be *in* such a report reframed the question. Fold bugs are pinned by
ADR-0004's shared golden fixtures on every push; ngsw update failures are now the persistent-mismatch
banner; Safari's 7-day eviction was accepted by RES-013 and recovered by ADR-0004's hard reset. One
candidate was left, and it is not a telemetry problem:

**A failed local write can look exactly like a successful one.** If IndexedDB throws on put — quota
exceeded, storage evicted mid-session, a private-window restriction — the tick lands in the UI and
**nothing enters the outbox**. You would find out when the task reappeared, or never. Telemetry does
not help: an error uploaded after the fact reports a tick already forgotten. What helps is that the
write is never acknowledged until it is durably queued.

So, two guarantees and no infrastructure:

1. A global Angular `ErrorHandler` **surfaces** uncaught errors rather than swallowing them into the
   console.
2. **A local write is acknowledged only once it is durably in the outbox**; a failed write shows as
   failed, never as a completed tick. Recorded as an amendment to ADR-0004, whose contract it belongs
   to.

Note the shape of that defect: an operation reporting success it did not have. The same shape as
portal's `echo "Backup completed"` and as the passive grey footer rejected above — three times in one
ticket.

### The classpath matches the decision

`micrometer-registry-prometheus` and `spring-modulith-starter-insight` are **removed**. Nothing will
scrape the first; nothing consumes the second's traces, and ADR-0003 already chose the committed
`docs/modules/` output as the way module structure stays visible, so a runtime endpoint duplicates a
diff. *Prefer fewer moving parts* applies to unused dependencies too, and on an internet-exposed
personal app ([#28](https://github.com/stainii/task/issues/28)) an unused management surface is a
liability with nothing on the other side of the trade. Both reversals are one dependency each, so
this closes no doors.

`spring-boot-starter-actuator` stays, exposing **`health` only, with `show-details: never`** — and it
is reachable **without a token**, which requires a `permitAll` that [#24](https://github.com/stainii/task/issues/24)
must add alongside `/api/config`. Not for an external prober, since there is none, but so compose can
use `healthcheck:` and `depends_on: condition: service_healthy` — which is how the app waits for
Postgres instead of crash-looping into Flyway. A JWT-gated health endpoint cannot be used by the one
thing that needs it.

## Consequences

- **[#17](https://github.com/stainii/task/issues/17)'s observability gate is discharged by two
  banners and a footer**, not by a monitoring stack.
- **[#24](https://github.com/stainii/task/issues/24) inherits four artifacts**: the build date on
  `GET /api/config`, a `permitAll` for `/actuator/health`, a bind-mounted log volume for the back-end
  and nginx, and a compose health check.
- **[#38](https://github.com/stainii/task/issues/38) may not treat the banners as optional polish.**
  They are the whole of this ADR's detection, so they are a cutover guarantee.
- **Nothing detects a running-but-wrong scheduler**, by explicit decision. That risk sits with #10
  and #32.
- **Nothing detects a total outage while you are away from the app.** The first thing that reports it
  is opening the app, which for a daily driver is under a day — the same latency ADR-0008 accepted for
  backups, and accepted here for the same reason.
- **No log history exists off the box.** If the disk dies, the logs die; they are not in the archive.
- **The observability answer is now load-bearing on the front-end**, which is where this map put
  almost none of its detection until today. A front-end that ships without the banners ships without
  monitoring.

## Amendments

### `/actuator/health` was never unauthenticated

Corrected by [Security posture of an internet-exposed personal app](https://github.com/stainii/task/issues/28),
2026-08-05.

This ADR recorded that `management:` exposes `health` only, "unauthenticated, so compose's
`healthcheck:` can use it". The exposure half was enacted; the unauthenticated half was not, and was
not true — `SpringSecurityConfig` was `anyRequest().authenticated()`, so the health endpoint required
a JWT and the compose health check would have failed on the first deploy that used it.

[ADR-0010](0010-a-tunnel-an-allowlist-and-a-role.md) makes it real, and improves on what this ADR
asked for: `/actuator/health` is `permitAll` in Spring Security **and** absent from nginx's
allowlist. The health check reaches it container-to-container inside the Docker network, and it is
not on the internet at all. Its real protection is the allowlist, not the Spring rule.

### *Persistent* means the service worker has been asked and has nothing

Enacted by [#63](https://github.com/stainii/task/issues/63), 2026-08-15.

This ADR made *persistent* load-bearing in the second banner — *"the banner waits until the service
worker has had its chance and the dates still disagree"* — without saying what *had its chance*
would be measured by. A duration would have reintroduced the very thing both banners were designed
without: a number nobody could derive, crying wolf on a slow morning and staying silent through a
half-landed deploy.

It is a **state**, and the browser already answers it: `SwUpdate.checkForUpdate()`. A mismatch plus
*an update is available* is the routine morning after a nightly deploy, and says nothing; a mismatch
plus *there is no newer version* is a deploy that half-landed. Where there is no service worker at
all, nothing is ever going to swap the bundle, so the mismatch is persistent immediately — waiting
for a fix that cannot come is silence indistinguishable from health. Where the check itself throws,
the banner stays down: an alarm raised because the update machinery could not be asked is an alarm
about the wrong thing.

Two consequences of the shape, both stated rather than discovered later:

- **The comparison is of calendar days, in the reader's own zone.** ADR-0007 builds the two images
  minutes apart in one CI run, so comparing instants would fire on every deploy that ever worked;
  and 23:30 UTC is already tomorrow in Brussels, so comparing UTC days would report skew across a
  line the reader cannot see.
- **The front end's own build date is stamped into the bundle at build time**, by `--define` in
  `npm run build`. That stamp is the one thing here that can silently stop happening, so
  `pwa/build-stamp.spec.ts` asserts the build command still carries it — a gate that keeps reporting
  success after it has stopped running is this document's own recurring subject.

The check runs at app open and nowhere else, which for a daily driver is the under-a-day latency
this ADR already accepted for a total outage.

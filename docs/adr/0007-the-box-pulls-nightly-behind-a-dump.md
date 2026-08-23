# 7. The box pulls, nightly, behind a dump

Date: 2026-08-04

## Status

Accepted. Resolves [#22](https://github.com/stainii/task/issues/22).

Amends [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) with the **epoch** — see that
ADR's *Amendments* section. Restoring a backup rewinds `sequence`, which the sync contract as
written cannot survive.

Constrains [#25](https://github.com/stainii/task/issues/25) (no automerge while `main` auto-deploys)
and [#26](https://github.com/stainii/task/issues/26) (the pre-deploy dump is part of the deploy
unit, not merely a nightly schedule).

## Context

The map has said "Docker Compose on the existing server, possibly AWS later" from the start, but
that was a preference carried forward, never a decision.
[#21](https://github.com/stainii/task/issues/21) costed the options against primary sources
(`docs/research/ci-cd-options.md`) and deliberately picked none.

Two earlier tickets had already removed options before this one started.
[#31](https://github.com/stainii/task/issues/31) settled that the repo is public by choice, which
rules out self-hosted runners on GitHub's own guidance and makes hosted CI free — so **this is not a
cost decision**. The costed shapes barely separate on money. [#15](https://github.com/stainii/task/issues/15)
ruled that Keycloak is shared infrastructure rather than `task`'s, which constrains what may live in
this stack.

Three facts were established rather than assumed:

- **`portal.stijnhooft.be` resolves to Cloudflare** (`172.67.205.66` / `104.21.44.238`, CLOUDFLARENET,
  checked 2026-08-04). A Cloudflare zone and `cloudflared` are already running in front of the live
  portal. §5c of the research costed Cloudflare Tunnel at 2–4 hours of new setup; that bill is
  already paid.
- **The target is a local server behind that tunnel**, not the VPS portal's setup notes suggest
  (`portal/_for_myself_server_setup_instructions.txt` reads like a DigitalOcean droplet). There is
  **no inbound port and no port-forwarding**.
- **`compose.yaml` and the test suite no longer drift.** [#20](https://github.com/stainii/task/issues/20)
  pinned both to `postgres:18.4` and `quay.io/keycloak/keycloak:26.7.0`, with an explicit comment in
  `AbstractIntegrationTestCases` saying the pin exists for that reason. The 26.0-vs-26.1 gap #21
  found is closed.

Together those collapse §5's four deployment patterns to two: the box pulls, or a hosted runner
reaches in through Access.

## Decision

### Local server, and AWS stays open on one condition

`task` deploys to the existing local server, in Docker Compose, behind the Cloudflare Tunnel that is
already there. AWS is not ruled out, only deferred.

The migration's motivation is **less operational burden**, and the burden was never the box — it was
thirteen services, RabbitMQ, a gateway, Swarm, and a self-hosted Jenkins. The modulith deletes almost
all of that on its own. AWS's cheapest honest shape is ≈$20–33/month (EC2 `t4g.small`/`medium`,
everything in containers, no ALB) and at that shape **you still operate everything**; the shapes that
genuinely reduce operating work cost 3–5× and add IAM, ECR, task definitions and a load balancer to
learn. Moving hosts during a cutover that has no rollback ([ADR-0005](0005-migration-by-replay-into-one-history.md))
adds a variable for no return.

The only thing that must not block a later move is **configuration reaching the app from the
environment rather than from the image**. Concretely that is one variable: `KEYCLOAK_ISSUER_URI`.
Cloudflare Tunnel helps here — the app never needs to know its own public address.

### The box pulls; nothing pushes in

A systemd timer on the server runs, in one unit and in this order:

```
pg_dump  →  git pull  →  docker compose pull  →  docker compose up -d
```

Nothing reaches into the box. GitHub holds no credential to the house, and because the repo is
public the GHCR package can be public too, so **the box holds no registry credential either** —
zero secrets on the deploy path in both directions.

That asymmetry is worth more here than on a normal project. [#31](https://github.com/stainii/task/issues/31)
knowingly accepted a public repo whose issue tracker is a prose catalogue of this system's defects.
A leaked Actions secret granting shell on the machine that holds years of real personal data is the
one failure that would make that trade look bad in hindsight, and this shape makes it impossible
rather than unlikely.

**Rejected: Watchtower.** The canonical `containrrr/watchtower` is archived; the live fork is
`nicholas-fedor/watchtower`. Adopting a fork of an abandoned tool cuts against "prefer fewer moving
parts" — but the disqualifying fact is behavioural: Watchtower restarts a container **with the
options it already had** and never re-reads `compose.yaml`. A new environment variable, a new
service or a changed port would ship only if also applied by hand. `git pull` plus `compose up -d`
ships the image and the topology together, and is what would be typed manually anyway.

**Rejected: a hosted runner reaching in through Cloudflare Access.** Genuinely cheaper than the
research implies now that `cloudflared` is already running, and it buys real deploy feedback and a
scriptable rollback. It costs a credential in Actions secrets that grants execution on the
production box. Not worth it for a single user.

### Every green push to `main` deploys, at night

There is no release ritual. A green `main` publishes the watched tag; the box picks it up on the
next poll.

The evidence for this is on this map. [#19](https://github.com/stainii/task/issues/19) found that
the May–June 2025 Keycloak/oAuth2 work was **committed but never shipped** — production still runs
legacy JWT auth. A year of drift between `main` and reality, caused by exactly one thing: a manual
gap between merging and deploying. A ceremony a solo developer must remember is a ceremony that
decays, and it has already decayed here once.

**The deploy window is at night, and clear of 04:00.** At night because a PWA that swaps out
mid-use is worse than one that swaps overnight — the app should not change while it is being used.
Clear of 04:00 because the due-task scheduler still fires on a clock
([#15](https://github.com/stainii/task/issues/15) traced the cron to portal's config): a container
restart landing on the tick means **that day's due templates silently never fire**, a one-day slip
with no error anywhere. [#40](https://github.com/stainii/task/issues/40) removes the hazard
altogether — once due-checking happens on startup, a nightly restart *is* a due check.

What "green" means is **not decided here**. [#32](https://github.com/stainii/task/issues/32) decides
what mutation testing measures, [#10](https://github.com/stainii/task/issues/10) sets the bar, and
[#23](https://github.com/stainii/task/issues/23) builds the gate.

### Flyway runs at startup, behind a dump that can abort the deploy

Migrations run automatically on application start. The dump immediately preceding them is what makes
that safe, and it is **the same nightly backup [#26](https://github.com/stainii/task/issues/26)
needs anyway** — one mechanism doing two jobs, rather than a conditional "dump only when the digest
changed".

Two properties are load-bearing:

- **One unit, not two timers.** A backup timer at 02:00 and a deploy timer at 02:15 is a race
  dressed as a sequence: the day `pg_dump` runs long, the deploy starts mid-dump and migrates
  against a backup that does not exist yet. It is seconds today; "it is fast today" is how that class
  of bug is born.
- **The deploy aborts if the dump fails.** Better to skip a night than to migrate unbacked. A broken
  backup therefore blocks deploys, which is the correct priority and a case for
  [#27](https://github.com/stainii/task/issues/27) to watch.

The usual reason to gate migrations does not exist here. Gating protects against **two versions
running at once** during a rolling update, where the schema must satisfy both. Compose stops the old
container and starts the new one: there is exactly one version at a time, ever. N-1 compatibility is
not a constraint on this system.

A gate would also be unworkable given everything above — the deploy is unattended, at night, with no
feedback path — so it would mean opening an SSH session to run migrations by hand, reintroducing the
#19 failure mode at precisely the point this design removes it.

**[ADR-0005](0005-migration-by-replay-into-one-history.md)'s importer must never run on this path.**
It is a one-shot at cutover that writes through the app's own model; an automatic run would be
catastrophic and silent.

### Rollback: restore, bump the epoch, pin the previous digest

Code rolls back by pinning the previous image digest — one command, no rebuild. Schema rolls back by
restoring the dump taken minutes earlier.

That second half breaks the sync contract, and the epoch is the fix. See
[ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md)'s *Amendments*; in short, a restore
rewinds `sequence`, a client whose cursor is ahead of the restored server concludes it is up to date
**permanently**, and the server then reissues the same numbers to different patches. The server
carries an epoch that the restore procedure increments; a client presenting a stale epoch gets the
`resync` ADR-0004 already defines.

The alternative — a rollback procedure that says "clear site data on every device" — is free and
depends on remembering, at 09:00 on a bad morning, every device that has ever opened the app. Miss
one and it diverges silently and forever.

### No staging

`main` goes straight to the machine used every day. Testcontainers plus a local environment that is
genuinely close to production is enough for a personal project — and that closeness is now
mechanical rather than aspirational, since #20's pins keep dev-compose and the test suite on the same
images.

A permanent second stack would mean a second Postgres, a second Keycloak client, a second tunnel
hostname, a second `.env` and a second thing to keep current — roughly doubling the operational
surface on a migration whose point is reducing it. It would spend most of its life stale, and a
stale staging environment is worse than none, because it produces confident green results about a
system that no longer exists.

The two needs it would serve are already covered:

- **Rehearsing before cutover** is [#39](https://github.com/stainii/task/issues/39) — a staging
  environment with a purpose and an end date, torn down at cutover.
- **Everyday pre-flight** is the development machine, running the same `compose.yaml`.

**The honest gap:** CI proves a migration applies to an *empty* database, never to yours. The
failure modes that only appear against real rows — a `NOT NULL` added to a column with existing
nulls, a unique index over data that is not unique — pass CI cleanly and fail at 02:00. The answer is
a **procedure, not an environment**: restore last night's dump locally and run the candidate against
it before merging anything schema-changing. It costs nothing standing, and it exercises the restore
path, so it doubles as a rehearsal for #26's drill. Documented in
[#29](https://github.com/stainii/task/issues/29).

### One compose file for now, with a named tripwire

Keycloak, Postgres and the app share one compose file. Both Keycloak and Postgres are self-managed
containers — there is no managed alternative on a local box, and #26 owns backups.

This sits in tension with #15's ruling that Keycloak is shared infrastructure whose lifecycle `task`
must not own, and the tension is accepted deliberately, because **nothing else couples to that realm
today**: #19 found production still runs legacy JWT auth, and #31 found the realm has never carried
real traffic. One compose file is honest about the system that actually exists.

Two things bound the risk:

- **The automerge constraint below already defuses the main hazard.** The worry was a Renovate bump
  to the Keycloak image restarting a shared auth server at 02:00, unattended. With automerge off,
  that bump is a merge made by a human.
- **The tripwire is the second app.** The moment anything else authenticates against that realm, the
  stack splits — from then on `task`'s deploy cadence is someone else's outage. Recorded here rather
  than left as "later", and carried into #29.

### Two images behind nginx, tagged together

The front-end image is nginx serving the built Angular app and proxying `/api/**` to the back-end
container; `cloudflared` points at nginx. Two images, one origin.

Same-origin is the property worth keeping: no CORS, and ADR-0004's SSE with an `Authorization`
header and `Last-Event-ID` resume stays plain (#30 established `@microsoft/fetch-event-source` is
load-bearing for exactly those headers). The service worker and the API also share an origin, so
#15's ruling that `ngsw-config.json` ships with `dataGroups` deliberately empty needs no exception.

Two images do reintroduce a risk that a single image would have made impossible. **ADR-0004 put the
fold in two places** — Java on the server, TypeScript on the client — and pinned them together with
shared golden fixtures *because drift between them would be silent*. Separate tags allow production
to run fold version N in the browser and N-1 on the server. **Both images are therefore tagged with
the same commit SHA**, referenced from compose as a single `${TASK_VERSION}`, so skew requires
actively writing two different values rather than happening whenever one push builds one image and
not the other.

**nginx's defaults would break SSE silently.** `proxy_read_timeout` defaults to **60 seconds**, so
every stream would die after a minute, and `proxy_buffering` is **on**, so events would be buffered
rather than delivered as they occur. The reconnect logic would paper over both: a client that
appears to work while reconnecting every 60 seconds forever, with the resume path exercised
constantly instead of rarely. The `/api` location requires `proxy_buffering off`, HTTP/1.1, and a
`proxy_read_timeout` comfortably past ADR-0004's 15–30 minute SSE lifetime. **Nothing in the test
suite can catch this** — Testcontainers never puts nginx in the path.

### Runtime configuration is a short, closed list

Following #31's rule — committed files hold deliberately worthless dev values, production values
arrive as a gitignored `.env` on the box — `compose.yaml` stays the **dev fixture** (`start-dev`,
realm import, throwaway credentials) and production uses a **committed compose file parameterised
entirely by `${VARS}`**. It must be committed, because the box gets it via `git pull`; it holds no
secrets, only variable names.

`.env` carries:

| Variable | Why |
| --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | database credentials |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | bootstrap admin only |
| `KEYCLOAK_ISSUER_URI` | the one value that must never be baked into an image |
| `TASK_VERSION` | the shared image tag for both services |

**No client secret exists anywhere.** The back-end is a resource server: it validates JWTs against
`issuer-uri` and holds no credential. #31 found every Keycloak client is public, which is why that
ticket could conclude "no incident" about years of public repositories.

**The front-end fetches its configuration at runtime** from an unauthenticated `GET /api/config`,
rather than having its Keycloak URL and realm baked in at build time — which would make the image
environment-specific and undo the portability the first decision preserved.

Two properties follow, and both must be honoured or they become bugs:

- **The endpoint is unauthenticated**, because it is what tells the client where the auth server is.
- **Its failure is not fatal to boot.** ADR-0004's *authenticate to sync, not to see* means a cold
  boot offline renders from IndexedDB with no token and no config; the app simply cannot sync until
  it reaches the network. No `dataGroups` exception is needed for it.

### Handed to Renovate: no automerge while `main` auto-deploys

[#31](https://github.com/stainii/task/issues/31) fired #19's automerge trigger, so
[#25](https://github.com/stainii/task/issues/25) arrives with the ambition unencumbered. This ADR
re-encumbers it, for a different reason.

On a deliberately bleeding-edge stack — Spring Boot 4.0.x, Java 25, Angular 22, where #21 noted
minor bumps carry real behaviour change — automerge plus auto-deploy means a dependency bump reaches
the daily driver with **no human in the loop at any point**. With automerge off, every merge to
`main` is a human decision and continuous deployment simply stops adding a second one.

If #25 wants automerge, it must introduce a release step first. That is a trade to make
deliberately, not to discover.

## Consequences

- **The default failure mode is now meeting a broken app at breakfast.** A bad deploy lands while
  asleep, with no signal that anything happened. This makes
  [#27](https://github.com/stainii/task/issues/27) materially more load-bearing and adds two items to
  its must-not-fail-silently list: *the backup succeeded* and *the deploy actually happened*.
- **A broken backup blocks deploys.** Correct priority, but it is a silent stall unless #27 watches
  it.
- **The Keycloak stack's configuration lives only on the box**, unversioned, since this repo commits
  a dev fixture rather than production Keycloak config. #31 already required #26 to restore *config*,
  not just data; this is one more item on that list.
- **#26's scope grows**: the pre-deploy dump is part of the deploy unit, and its restore drill must
  exercise that path — fitting, since #17 already cannot run until the drill has passed.
- **#24 inherits concrete artifacts**, none of which exist yet: two Dockerfiles, an nginx
  configuration with the SSE settings above, the systemd timer and its script, the GHCR publish step,
  and a rollback actually performed once.
- **A CSS change redeploys the back-end container too**, because `compose up -d` recreates whatever
  the shared `TASK_VERSION` changed. Accepted; at this scale it is seconds.
- **This is the third defect of the "an ADR reintroduced by configuration" shape** on this map, after
  #15's `ngsw-config.json` and this ADR's restore-rewinds-`sequence`. The pattern is worth naming:
  the sync contract's guarantees live in code, but the things that break them live in YAML, nginx
  and operational procedure, where no test looks.

## Amendments

### Keycloak persists in the same Postgres instance, in its own database

Amended by [Backups and disaster recovery](https://github.com/stainii/task/issues/26), 2026-08-04.

This ADR put Keycloak in the production compose file and **never said where its data lives**. Left
alone, that inherits the dev fixture's arrangement — `start-dev --import-realm`, no database
configured, a file store inside a container whose realm exists only because a JSON file is re-imported
at every boot. Production Keycloak on that footing turns #26's worst case, *losing the realm locks you
out of your own data*, from a risk into a scheduled event.

Keycloak gets its own **database inside the Postgres instance this stack already runs**. One container
to run, patch and pin, and the backup becomes one artifact: a single `pg_dumpall` covers tasks, patch
history and the realm together.

This deepens the tension with [#15](https://github.com/stainii/task/issues/15) that this ADR already
accepted, by one level: Postgres is now the shared auth server's dependency too. The *second app*
tripwire is unchanged but grows a step — splitting the stack now also means separating two databases,
which is a dump and a restore rather than data surgery. See
[ADR-0008](0008-every-backup-restores-itself-before-it-is-kept.md).

### `.env` carries the tunnel credential

Amended by [Backups and disaster recovery](https://github.com/stainii/task/issues/26), 2026-08-04.

The *Runtime configuration is a short, closed list* table omits `cloudflared`. The tunnel runs as a
container in the same compose file and is configured from `.env` like everything else, so its
credential is a fifth entry in that table — and, usefully, it means a restored archive contains
everything needed to put the app back on the internet. Without it a rebuilt box would hold the data
and have no route to it until the tunnel was re-provisioned by hand.

### The Keycloak stack's configuration is no longer unversioned

Amended by [Backups and disaster recovery](https://github.com/stainii/task/issues/26), 2026-08-04.

This ADR's consequence *the Keycloak stack's configuration lives only on the box, unversioned* is
discharged rather than carried. With the realm in Postgres it rides in the nightly dump, and the
container's configuration is `.env` plus the committed production compose file. Nothing about Keycloak
now exists solely as unbacked state on the machine.

### Keycloak's own public path, its two hostnames, and a wrong variable name

Amended by [Security posture of an internet-exposed personal app](https://github.com/stainii/task/issues/28),
2026-08-05.

This ADR said `cloudflared` points at nginx and nginx proxies `/api/**`. That cannot be the whole
story: OIDC login is a **browser redirect**, so the phone's browser must reach Keycloak itself and
`KEYCLOAK_ISSUER_URI` must resolve from the public internet. Keycloak *is* exposed; this ADR simply
never wrote down how. [ADR-0010](0010-a-tunnel-an-allowlist-and-a-role.md) settles it: nginx also
routes `/realms/**`, `/resources/**` and `/js/**`, on the app's own origin at
**`task.stijnhooft.be`**, and returns `404` for `/admin/**`, which is published on a LAN-only host
port instead.

Three configuration facts come with it, and each half-works rather than fails if missed:

- **`hostname` must be pinned** to `https://task.stijnhooft.be`. Keycloak otherwise derives its URLs
  from the request, so a login through the LAN address mints tokens whose `iss` the back-end rejects.
- **`hostname-admin`** must be the LAN URL, or the admin console's own links point at the public host.
- **`proxy-headers=xforwarded`** is required. TLS terminates at Cloudflare, so Keycloak sees plain
  HTTP; with `sslRequired: external` it will either refuse the login or emit `http://` redirects.
  Production also runs `start --optimized`, not `start-dev`, which disables those very checks.

And one row of the `.env` table is wrong: **`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` are
deprecated in Keycloak 26** in favour of `KC_BOOTSTRAP_ADMIN_USERNAME` /
`KC_BOOTSTRAP_ADMIN_PASSWORD`. Setting the old names does nothing and yields a Keycloak with no admin
account — discovered on the first deploy, on the box, with no admin console to fix it from.

### The deploy window is no longer "clear of 04:00", and the restart was never guaranteed

Amended by [Check for due templates on startup, not only at 04:00](https://github.com/stainii/task/issues/40),
2026-08-09. See [ADR-0016](0016-the-due-check-ticks-hourly-and-starts-with-the-app.md).

Two things in *Every green push to `main` deploys, at night* change.

**The constraint goes.** "Clear of 04:00" existed only to stop a container restart eating the single
daily firing. ADR-0016 replaces that firing with an hourly state comparison, so a restart landing on
a tick loses nothing — the next tick re-derives the same state. The window stays **at night**, for
the reason that was always load-bearing: a PWA should not swap out mid-use.

**And the argument this ADR handed to #40 was wrong.** This ADR reasoned that the box is now
guaranteed to *restart* at night, so a restart could simply *be* the due check. `compose up -d`
recreates a container only when the image or its configuration changed, so on any night with no push
to `main`, nothing restarts. Startup-only due-checking would have gone silent for as long as `main`
sat still. Recorded because the claim reads plausibly and is repeated in #40's body.

### `.env` gains the VAPID key pair

Amended by [Web Push: the `notification` module](https://github.com/stainii/task/issues/51),
2026-08-12.

Two more variables for the table above, both required — the application refuses to start without
them, deliberately, because a push client that quietly fails every morning is ADR-0009's
`echo "Backup completed"`:

| Variable | Why |
| --- | --- |
| `PUSH_VAPID_PUBLIC_KEY` / `PUSH_VAPID_PRIVATE_KEY` | ADR-0012's application server identity. **Losing the pair invalidates every existing subscription** — the concrete artifact behind [#26](https://github.com/stainii/task/issues/26)'s *restore config, not just data*. |
| `PUSH_VAPID_SUBJECT` | the `mailto:` a push service complains to; several reject a token without one |

The committed values in `application.yml` are a throwaway keypair that has never signed anything, per
[#31](https://github.com/stainii/task/issues/31). There is still **no client secret anywhere**: the
VAPID private key is ours, not a credential issued to us, and it authenticates nothing to Keycloak.

### The image tag is derived from the commit the box pulled, not written into `.env`

Amended by [Set up continuous deployment](https://github.com/stainii/task/issues/24), 2026-08-23.

The `.env` table lists `TASK_VERSION` as "the shared image tag for both services", which read as a
value a human writes. Nothing said who writes it, and on a pull-based deploy with no acknowledgement
the honest answer is: nobody should. `deploy.sh` derives it from `git rev-parse HEAD` immediately
after the pull, so the box runs the images built from the commit it has just checked out, and the
two images match because they were tagged by the same workflow run from the same SHA.

`TASK_VERSION` in `production.env` therefore becomes the **pin**, and that is exactly what a rollback
is: write the previous commit's SHA there and the nightly deploy stops advancing until it is removed.
Drilled on 2026-08-23 — the running build went back one version and forward again, both images
together.

One property falls out that the ADR wanted but had no mechanism for: if the commit's images are not
published yet — CI still running, or `main` red — the pull fails and **the running stack is left
exactly as it was**. A night that deploys nothing is a night that deploys nothing, rather than half a
deploy.

### The back-end fetches Keycloak's signing keys over the Docker network

Amended by [Set up continuous deployment](https://github.com/stainii/task/issues/24), 2026-08-23.

This ADR named `KEYCLOAK_ISSUER_URI` as the one value that must not be baked into an image, and left
it at that. Left at that, Spring performs OIDC discovery against it — the **public** address — so a
container three hops away is reached by leaving the box, crossing Cloudflare and coming back in
through the tunnel, and the back-end's ability to validate any token at all depends on `cloudflared`
having connected. At 02:30, with the whole stack starting at once, that is a race whose symptom is
every authenticated request answering `401` while the application reports itself healthy. Found by
running this file rather than by reading it.

The production stack therefore also sets `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` to
Keycloak's address on the Docker network. The `iss` claim is still validated against the public
issuer, so nothing about the trust boundary changes; the two values disagreeing fails loudly — every
request `401` — rather than silently, which is the only reason this ADR's "two properties that are
supposed to agree are two properties that can disagree" rule is bent here.

# 10. A tunnel, an allowlist, and a role

Date: 2026-08-05

## Status

Accepted. Resolves [#28](https://github.com/stainii/task/issues/28).

Amends [ADR-0004](0004-one-write-verb-two-clocks-offline-sync.md) with the **write-path contract** —
in particular that a duplicate patch id is `200`, not an error. See that ADR's *Amendments* section.

Corrects [ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md) four times: Keycloak's own public
path was never specified, its hostname must be pinned twice, it needs `proxy-headers`, and the
`.env` variable names for the bootstrap admin are the deprecated ones.

Corrects [ADR-0009](0009-the-app-is-its-own-monitor.md)'s claim that `/actuator/health` was already
unauthenticated. It was not; `SpringSecurityConfig` required a token on every request.

Constrains [#24](https://github.com/stainii/task/issues/24) (the nginx allowlist, the published
ports, the Keycloak flags), [#25](https://github.com/stainii/task/issues/25) (Renovate must watch
Docker image tags), [#29](https://github.com/stainii/task/issues/29) (LAN-only admin, the live-realm
procedure) and [#11](https://github.com/stainii/task/issues/11) (the write-path contract is an
implementation requirement).

## Context

`task` will be reachable from the internet so it works on a phone, it holds years of personal data,
and one person maintains it part-time. That deserves one deliberate pass — proportionate, not
paranoid, with the accepted risks *named* rather than merely unexamined.

Nothing had been examined. `SpringSecurityConfig` dates from the hand-done migration, the realm
export is portal's, inherited whole, and no decision on this map had yet asked what is actually
reachable from outside.

### The threat model, chosen first

Three candidates were put up: opportunistic internet background noise; a credential attack on this
person specifically; a targeted adversary. **The author chose the first.** Scanners sweeping for
known CVEs, exposed admin consoles, default credentials and open databases are the only threat
*guaranteed* to arrive — they are already arriving at `portal.stijnhooft.be` today. The other two are
consciously accepted.

That choice is the whole calibration. It is why there is no MFA, no password policy, no WAF rules,
no intrusion detection and no CVE gate in this ADR, and why the things that *are* here are the
things a scanner finds.

### The one structural advantage, inherited by accident

[ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md) chose a local server behind the Cloudflare
Tunnel that was already running, and noted almost in passing that there is **no inbound port and no
port-forwarding**. Against this threat model that single fact does most of the work: a scanner
cannot find the box by sweeping IP ranges. It can only reach what `cloudflared` chooses to publish.

**So the exposure surface is not a firewall. It is an nginx config file.** Everything not routed is
unreachable, permanently, for free. That reframing is what makes the rest of this ADR cheap.

## Decision

### nginx is the only internet-facing service, and it is default-deny

`cloudflared` publishes exactly one thing: nginx. nginx returns `404` for anything not on an
allowlist.

| Path | Routed to | Auth |
| --- | --- | --- |
| `/` and static assets | the built Angular app | none |
| `/api/config` | back-end | **`permitAll`** — this is what tells a cold client where the auth server is |
| `/api/**` (everything else) | back-end | role required |
| `/realms/**`, `/resources/**`, `/js/**` | Keycloak | none — this *is* the login |
| everything else, including `/actuator/**` and `/admin/**` | — | **404** |

`/api/` is one allowlist entry rather than an enumeration of endpoints. Per-endpoint enumeration was
rejected because local development runs through `proxy.conf.json`, not nginx: a route forgotten in
the allowlist would work perfectly in dev and 404 only in production. That is the same dev/prod skew
family as ADR-0007's nginx-SSE trap, and buying it back here would be self-inflicted.

**`/actuator/**` is not routed at all.** ADR-0009 needs `/actuator/health` for compose's healthcheck,
but that call is container-to-container inside the Docker network and never traverses nginx. So the
endpoint is `permitAll` in Spring Security *and* invisible from the internet — better than ADR-0009
imagined, at no cost. Its real protection is the allowlist, not the Spring rule.

**`/realms/**` is routed wholesale, and that is load-bearing.** Keycloak's account console lives at
`/realms/<realm>/account/`, which makes *sign out of all devices* and *change my password* reachable
from any browser while `/admin/**` stays unreachable. Tightening this later to, say,
`/realms/*/protocol/**` would silently remove the only remote revocation path this system has, and
nothing would break until the day it mattered.

### Keycloak's admin console is LAN-only

`/admin/**` is never routed through the tunnel. Keycloak publishes a host port on the box instead,
which — because there is no port-forwarding — is reachable from the local network and nowhere else.

Against scanners, *not published* beats *published but protected*: it removes the CVE surface as
well as the login, so a future advisory in Keycloak's admin REST API is unreachable before it is
even read. The cost is deliberate inconvenience — adding a client or rotating a password means being
at home — and the tripwire is ADR-0007's existing one: the second app coupling to this realm makes
admin access frequent enough to revisit.

Cloudflare Access in front of `/admin` was rejected: it spends a second auth system to protect the
first, against the standing preference for fewer moving parts.

### Everything on one hostname, `task.stijnhooft.be`

A separate `auth.` hostname was rejected. Serving Keycloak under the app's own origin keeps ADR-0007's
same-origin property — and buys a second thing that was not anticipated: **Keycloak's session cookie
is first-party.** Silent SSO on reload simply works. On a separate auth hostname that cookie is
third-party, which browsers are actively removing, so the login flow would have degraded over time
through no change of ours.

A *new* hostname rather than portal's also means both stacks can run at once, which
[#39](https://github.com/stainii/task/issues/39) needs, and makes cutover a tunnel change rather than
a swap under one name.

### The realm is `stijnhooft-realm`; `task` is one client in it

[#15](https://github.com/stainii/task/issues/15) required a neutral realm name because Keycloak is
shared infrastructure. It is named after its owner, which stays true however many apps couple later.

Its `portal-client` is not carried over; a new `task` client replaces it. That matters, because the
inherited one was wide open and hardening it is free when the thing is being written from scratch:

| Setting | Portal's | `task`'s | Why |
| --- | --- | --- | --- |
| `redirectUris` | `["*"]` | real hosts only | a wildcard redirect on a public client is account takeover, and **PKCE does not save it** — the attacker starts the flow with their own verifier, the already-logged-in browser auto-approves, the code lands on their site |
| `webOrigins` | `["*"]` | `[]` | everything is same-origin; there is no cross-origin call to permit |
| `directAccessGrantsEnabled` | `true` | `false` | it turns the token endpoint into a scriptable password-guessing API, and the browser redirect is the only login path the client uses |
| realm `bruteForceProtected` | `false` | `true` | the one genuinely scanner-reachable weakness, closed by one toggle |

The wildcard redirect is honestly *outside* the chosen threat model — exploiting it needs a crafted
link and a click, which is the credential attack the author accepted. It is fixed anyway because it
costs nothing, and because that is the difference between a risk accepted and a default left lying
around. `directAccessGrantsEnabled` is the one squarely inside it.

No password policy and **no MFA**. See *Accepted risks*.

The test realm keeps its password grant: the integration tests mint tokens that way, in a container
that never leaves the machine.

### A realm role is the boundary, not membership of the realm

`SpringSecurityConfig` required `anyRequest().authenticated()`. Combined with #15's decision that the
realm is *shared*, the property that gives is:

> Any user in the shared realm can read and write every task in here.

Not a bug — the code working as written. The back-end validates the issuer and nothing else, so the
day a Keycloak user is added for some unrelated app, that user is silently granted this entire task
history and the ability to patch it. Nothing errors and nothing logs.

This is the map's *a guarantee that lives in code, broken by something that lives in configuration*
shape for the sixth time, and the first where both halves are decisions made on this map, two
tickets apart.

**Every `/api` request now requires the realm role `task-user`.** One line in the filter chain, one
role in the realm. Spring's default converter reads `scope`/`scp`, so a small converter reads
Keycloak's `realm_access.roles` — without it `hasRole` can never match and everything is a 403.

A client/audience check was rejected as weaker: client ids are public, so "any user through the
`task` client" is a boundary anyone can step over.

**There is no owner column, and that is deliberate.** Adding one later is a single Flyway migration
plus a backfill, and the backfill is *trivially* correct precisely because there is one user today —
it stays trivial right up until a second person uses the app, which cannot happen before the column
exists. Same reasoning as [#4](https://github.com/stainii/task/issues/4)'s refusal to leave
structural room for goals. So: **single-user by data model, role-gated by access control.** The role
is the boundary that must exist now because it is free now; the column is the one that can wait
because it is free later.

### Sessions last 30 days; the access token stays 5 minutes

Portal's values were a 30-minute idle timeout and a 10-hour maximum. For an app opened ten times a
day for twenty seconds, that is a login prompt at almost every use — and on a phone with patchy
signal, at exactly the moments a login cannot complete.

The argument that settles it: **a short SSO session defends against exactly one thing — physical
access to an unlocked device, later.** Not scanners; they have no browser. Not a stolen bearer token;
that is the 5-minute one. And [#15](https://github.com/stainii/task/issues/15) already examined the
borrowed-device case, rejected the guest/trusted split as disproportionate, and accepted the
exposure. Keeping 30 minutes buys nothing this map has not already declined to buy, while costing
friction — and an app that demands a password ten times a day is an app that trains its user to type
that password into whatever asks, which is the real phishing risk.

So `ssoSessionIdleTimeout` and `ssoSessionMaxLifespan` both become **30 days**; `accessTokenLifespan`
stays **5 minutes**; `revokeRefreshToken` stays **false**, because rotation-with-revocation produces
spurious logouts in exactly this shape — one user, several devices, an offline-capable client.

`offline_access` tokens would achieve the same with an extra credential class and its own revocation
semantics. Rejected for fewer moving parts.

### The write path: validate at the door, because there is no later

Under the chosen threat model this is **not an attacker surface at all** — a scanner cannot get a
`task-user` token, so every patch that ever arrives is one our own client sent. The question is what
stops a buggy retry, a stale service worker or a half-written field from entering a log that is
replayed forever.

Two properties make the write path unforgiving: the patch log is append-only with **no delete
endpoint** (ADR-0001, ADR-0004), and ADR-0005 recomputes every task by folding it — so the log is not
a record of the truth, it *is* the truth. Anything accepted is accepted permanently and folded at
every future migration.

| Case | Response | Outbox behaviour |
| --- | --- | --- |
| Patch id already stored | **`200`**, no-op | continues — replay is safe |
| Unknown field name in `changes` | `400` | drops into the visible failed-to-sync list |
| Value does not parse as its field's type | `400` | same |
| Creating patch missing a required field | `400` | same |
| Patch for a task id that does not exist | `404` | same (ADR-0004's orphan rule) |
| Body over the size cap | `413` | same |
| Anything else | `5xx` | stall, preserve order |

**The first row is a defect fix, not a preference.** Patch ids are client-minted and are the primary
key. Trace an ordinary bad night on mobile: the client POSTs, the server commits, the response is
lost. The outbox retries — correctly, by design. The retry hits a primary key violation, which
surfaces as `500`. ADR-0004's rule says `5xx` means *stall and preserve order*. It retries. Another
`500`. **Forever.** A successful write, retried once, permanently wedges the queue — and ADR-0009's
*online but not syncing* banner fires, correctly, on a system where nothing is wrong except that the
server said no to being told the truth twice. It is the rarely-exercised path that both ADR-0004 and
ADR-0007 insist must be self-testing, deadlocking on the most ordinary event in mobile networking.
Making a duplicate id a `200` turns replay from a hazard into the mechanism: the client-minted id
becomes the idempotency key it was always meant to be, which is also what ADR-0004's "`409` never
reaches the client" was reaching for.

Unknown fields are **rejected** rather than ignored. Accepting them puts permanent garbage in an
append-only log; rejecting them risks a newer client losing a write against an older server — but
ADR-0007 pins both images to the same commit SHA so real skew requires deliberate misconfiguration,
and where skew can still happen (a stale service worker) ADR-0009's persistent build-date-mismatch
banner already catches it. The failure is visible twice; silent permanent garbage is visible never.

A body size cap at nginx and length limits on the fields come with it: `description` is `TEXT` and
`changes` is `JSONB`, so a looping client can otherwise write until the disk fills, and ADR-0008's
backup would faithfully preserve the result.

### Nothing gates on CVEs

No dependency-check plugin, no CVSS threshold, nothing in the build. **A CVE-failing build fails on a
day nothing changed**, because someone published an advisory — and ADR-0007 wires `main` straight to
production, so a red build blocks deploys. A newly-published CVE in a transitive dependency could
therefore block the deployment of its own fix. That is the standard failure mode of build-time CVE
gates, and the only escape hatch for one person part-time is disabling the gate, which is how gates
die.

GitHub's own alerting is the detector — free on a public repo
([#31](https://github.com/stainii/task/issues/31)), out-of-band, and it does not touch the build.

**A requirement falls out for [#25](https://github.com/stainii/task/issues/25), and it inverts the
obvious priority.** [#20](https://github.com/stainii/task/issues/20) pinned the images —
`postgres:18.4`, `keycloak:26.7.0` — which is what makes builds reproducible and is *also* what stops
security patches arriving. The two internet-facing components are nginx and Keycloak, and they are
the only things a scanner can reach: a library CVE deep in the tree usually needs a reachable code
path, while a Keycloak CVE is reachable by definition, because Keycloak is the login page. So
**Renovate must watch Docker image tags in the compose files**, not just Maven and npm. A Renovate
setup covering only language dependencies would miss the two things that matter most.

Automerge stays off per ADR-0007, with the consequence stated plainly: a security update reaches
production only when a human merges it.

### TLS, and what is not on the box

Cloudflare terminates TLS at the edge; `cloudflared` dials out over TLS; nginx-to-app is plaintext
inside the Docker network. **No certificate exists on the box, so there is nothing to renew and
nothing to remember** — which answers #28's renewal requirement by removing it rather than
automating it.

## Consequences

### Enacted in this ADR's session

- The dev realm fixture is now `stijnhooft-realm`: `portal-client` and `dummy-client` deleted, a
  `task` client added with real redirect URIs, empty `webOrigins`, no direct grants and PKCE S256;
  `bruteForceProtected` on; 30-day sessions; a `task-user` realm role granted to the fixture user.
- `application.yml`'s `issuer-uri` follows the rename.
- `SpringSecurityConfig` requires `task-user`, reads `realm_access.roles`, and exempts `/api/config`
  and `/actuator/health`.
- The test realm gains the role. **82 tests still green**, and four test classes drive secured
  endpoints with real Keycloak tokens, so the gate is exercised rather than merely compiled.

### Handed to other tickets

- **[#24](https://github.com/stainii/task/issues/24)** — the production compose and nginx: the
  default-deny allowlist above; `client_max_body_size`; **no published host ports except Keycloak's
  LAN one** (Postgres gets none — `docker exec` covers debugging and ADR-0008's laptop pull goes over
  SSH); `.env` at mode `600`; and four Keycloak flags without which this shape half-works, which is
  worse than failing:
  - `hostname` pinned to `https://task.stijnhooft.be` — otherwise a login through the LAN address
    mints tokens whose `iss` the back-end rejects;
  - `hostname-admin` set to the LAN URL, so the admin console's own links work;
  - `proxy-headers=xforwarded` — TLS terminates at Cloudflare, so Keycloak sees plain HTTP and with
    `sslRequired: external` will either refuse the login or emit `http://` redirects;
  - `start --optimized`, not `start-dev`, which disables the very checks that make the above safe.
- **[#25](https://github.com/stainii/task/issues/25)** — Renovate watches Docker image tags.
- **[#29](https://github.com/stainii/task/issues/29)** — how to reach the LAN-only admin console; how
  the live realm was configured and how to change it; and the warning that `/realms/**` must stay
  routed whole, because the account console is the only remote revocation path.
- **[#11](https://github.com/stainii/task/issues/11)** — the write-path contract as implementation
  requirements on the rebuilt patch endpoint.

### ADR-0007's `.env` table is wrong in one row

It names `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`. **Keycloak 26 deprecated those in favour of
`KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`.** Setting the old names does nothing
and produces a Keycloak with no admin account at all — discovered on the first deploy, on the box,
with no admin console to fix it from.

### Secrets: the check was made, and it came back clean

[#31](https://github.com/stainii/task/issues/31) checked this repo's whole 16-commit history. This
ticket checked the one place #31's scope did not reach: **portal's own public repo**, which commits
a `.env-example` listing database passwords and a `JWT_SECRET` — and [#19](https://github.com/stainii/task/issues/19)
established that production *still runs the legacy JWT auth*, so a real secret there would have been
a live forgeable-token hole on the system holding all 11,855 tasks. Confirmed with the author: **all
placeholder values.** Recorded because a check made and not written down gets made again.

### Accepted risks

Named so they are accepted rather than overlooked:

- **A targeted adversary.** No proportionate answer exists for a one-person part-time system;
  pretending otherwise produces theatre.
- **A credential attack on this person** — phishing, credential reuse. Blunted by brute-force
  protection, not closed. MFA was reconsidered here because 30-day sessions cut its cost by roughly
  a hundredfold — monthly, not daily — and was still declined, together with the lockout failure mode
  it would have introduced on a single-user system with nobody to reset it.
- **A borrowed or stolen unlocked device**, whose window 30-day sessions extend. Consistent with
  #15's conscious acceptance of the same exposure; the account console is the remote remedy.
- **The backup chain.** ADR-0008 sends `pg_dumpall` to a cloud provider unencrypted, and ADR-0008
  also put Keycloak's data in that database — so the archive contains a password hash, crackable
  offline, usable against a public login page. #26's reasoning ("the provider already holds the
  photos") is about data confidentiality and does not stretch to a credential. Three separately
  sound decisions composing into something none of them evaluated; accepted on a strong unique
  password, with re-encrypting the cloud copy as the option not taken.
- **A device with a wrong clock.** ADR-0004 rejected a future-dating guard, and this ADR leaves that
  closed after re-examining it. A clock running ahead freezes a field at a stale value until real
  time catches up; a clock running behind is worse — the write syncs cleanly and has no effect, this
  map's *success it did not have* shape. A server-side guard was rejected because its own failure
  mode is worse than the fault: a wrong *server* clock (a restored backup on a box with bad NTP,
  which ADR-0008 makes routine) would `400` legitimate patches into ADR-0004's drop-and-continue
  path and lose them permanently. A past-guard is impossible outright — week-old patches are the
  entire point of the offline model. A clock-skew banner reading the response `Date` header was
  designed as the middle path and declined; **void patches remain the remedy.**
- **Security updates reach production only when a human merges.** The direct cost of
  every-green-push-deploys.
- **Cloudflare sees the traffic in plaintext.** The same shape of provider trust ADR-0008 accepted.
- **Anything on the local network can reach the Keycloak admin console.** Out of scope by the chosen
  threat model, which is the internet.

### What this ADR does not decide

Nothing about rate limiting, WAF rules or intrusion detection — Cloudflare sits in front and absorbs
some scanner noise, but nothing here relies on it. No new tickets, and no fog graduated.

## Amendments

### Keycloak runs `start`, not `start --optimized`

Amended by [Set up continuous deployment](https://github.com/stainii/task/issues/24), 2026-08-23.

`--optimized` refuses to apply build-time options at run time, and `db` is a build-time option. An
optimized start therefore needs an image built with `kc.sh build --db=postgres` — a **third image**,
on a tag of its own, which is no longer the same pinned Keycloak that `compose.yaml` and the test
suite run. [ADR-0007](0007-the-box-pulls-nightly-behind-a-dump.md)'s refusal of a staging environment
rests on that pin ("#20's pins keep dev-compose and the test suite on the same images"), and it is
worth more than the ~15 seconds `start` spends re-running the build on boot.

What this ADR actually ruled out was `start-dev`, which disables the very checks the hostname and
proxy flags rely on. That ruling stands; production runs `start`.

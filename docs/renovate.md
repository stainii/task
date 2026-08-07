# Dependency currency

Renovate keeps this repo's dependencies current. Resolves [#25](https://github.com/stainii/task/issues/25).

The configuration is [`renovate.json5`](../renovate.json5) and carries its own reasoning inline, the
way [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) does. This file holds only what the
config cannot say: how it is switched on, how it is used, and where it is blind.

## Switching it on

The config file is inert until the **Renovate GitHub App** is installed on `stainii/task`. That is a
one-time manual step, at <https://github.com/apps/renovate>, granting access to this repository.

Self-hosting was not considered: [#31](https://github.com/stainii/task/issues/31) established the
repo is public, so the hosted app is free, and running a scheduler would add the kind of moving part
[ADR-0007](adr/0007-the-box-pulls-nightly-behind-a-dump.md) spent a whole decision avoiding.

On the first run Renovate opens an onboarding PR. Because `renovate.json5` already exists, that PR
should contain **no configuration changes** — if it proposes a config, something here was not read,
which is worth understanding before merging.

## How it behaves

| | |
| --- | --- |
| **When** | Weekday mornings, before 09:00 Europe/Brussels |
| **Cooling-off** | A release must be 3 days old before a PR is opened |
| **Automerge** | Yes — on `green`, via GitHub's own automerge |
| **Exception** | Security advisories: no cooling-off, any time |
| **Throttle** | 2 commits/hour, 3 concurrent PRs |

The **Dependency Dashboard** is a GitHub issue Renovate maintains. It is the inventory of what is
stale, including things it chose not to open a PR for, and it is where a deliberately-held-back
dependency is un-held.

### Why automerge is on, and what makes it safe

[#19](https://github.com/stainii/task/issues/19) deferred automerge with a named trigger;
[#31](https://github.com/stainii/task/issues/31) fired it. ADR-0007 then argued against it, because
every green push to `main` deploys. #25 settled it in favour of automerge, on the author's point that
the deploy is **pull-based**: the box takes whatever is on `main` at around 02:00 rather than being
triggered by the merge.

That does not isolate production — it delays it. What it buys is a **revert window**, and the morning
schedule is what makes that window ~17 hours instead of ten minutes.

The evidence behind accepting the risk is [#20](https://github.com/stainii/task/issues/20)'s upgrade:
three things broke, all of them on *minor* bumps, and **all three were compile failures**. That is the
class CI catches outright. The residual risk automerge carries is the narrower one — a change that
compiles, passes the suite, and behaves differently at 02:00.

**If a release step is ever introduced, revisit this.** ADR-0007's objection was never wrong, only
outweighed.

## Where it is blind

**The JDK has no manager.** Renovate has `nvm`, `docker-compose`, `maven` and `github-actions`; there
is no `sdkman` manager, so `.sdkmanrc` matches nothing by default. #20 chose Java 26 over 25 LTS while
knowingly accepting a **6-month forced-upgrade cadence**, which made the one dependency guaranteed to
expire on a known date the only one Renovate could not mention. Two `customManagers` fix it — the only
place [#19](https://github.com/stainii/task/issues/19)'s refusal of custom regexes is reversed, and
only because no better mechanism exists there.

**A regex that stops matching is silent.** A dependency Renovate cannot see never appears on the
dashboard, so nothing reports its absence. Two things guard that:

- `ToolchainPinsTest` fails the build if `.sdkmanrc` and `pom.xml` disagree about the JDK major, or if
  either pattern stops matching. A half-applied bump otherwise compiles cleanly and silently targets
  the older release — verified: setting `<java.version>` to 25 on a JDK 26 machine builds fine.
- The `renovate-config` CI job runs `renovate-config-validator --strict`, so a config Renovate would
  reject fails the build instead of quietly doing nothing.

Neither guard proves the regexes still *find* anything against the live datasource. **The dashboard
listing `java` is the only evidence of that** — if it disappears from there, the customManagers
stopped matching.

## Duplicated pins, and why there is now one of each

Renovate's `docker-compose` manager reads [`task-back-end/compose.yaml`](../task-back-end/compose.yaml)
and cannot read a Java string literal. Before #25 the Postgres and Keycloak tags were written in both,
so Renovate would have bumped compose, left the suite testing the old image, gone green, and
automerged — reopening precisely the drift #20 had closed, and quietly removing the evidence
ADR-0007's *no staging* decision rests on.

The literals are gone. `ComposeFile` reads the tags from `compose.yaml`, which is their only copy.

The same rule is owed to [#24](https://github.com/stainii/task/issues/24): **the production image must
take the JDK as a build argument derived from `.sdkmanrc`**, not hardcode `eclipse-temurin:26-…`. A
hardcoded base image is a separate dependency to Renovate and can drift from the toolchain the same
way. It costs one line while the Dockerfile does not yet exist, and a migration afterwards.

## Known limits

- `rangeStrategy: bump` is set because `package.json` uses carets: with the default, an in-range minor
  raises no PR at all and the lockfile silently freezes while `package.json` looks current.
- `lockFileMaintenance` runs weekly. Transitive updates move no other way — #20 found three moderate
  advisories inside `@angular/cli`'s own dependency tree, unreachable from a direct bump.
- Security PRs skip the cooling-off period and the morning window, deliberately spending the revert
  window. [ADR-0010](adr/0010-a-tunnel-an-allowlist-and-a-role.md)'s *nothing gates on CVEs* is
  untouched: that rule is about failing builds, not about shipping fixes.
- `timezone` is `Europe/Brussels`. The schedule means nothing without it.

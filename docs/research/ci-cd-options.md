# CI/CD options for `stainii/task`

**Research date: 2026-07-31.** Every price, quota and limit below was checked against a primary source on that date; the source URL is given inline. Anything that could not be verified from a primary source is marked `[unverified]`.

## What this covers / what it deliberately does not decide

This document lays out **options with numbers**. It does not pick one. A separate ticket makes the call.

Covered: GitHub Actions cost on this repo, Testcontainers in CI, monorepo build shapes, self-hosted runners, deploying to a home server behind NAT, deploying to AWS, container registries, secrets handling, and Renovate.

Not covered / not decided: which CI provider to use, whether to deploy to AWS at all, whether pitest belongs in the CI gate, branch-protection policy, or any migration plan. No recommendation is expressed or implied by ordering.

---

## Findings about *this* repo that the numbers depend on

Measured on the working tree at commit `3556ff9`, on 2026-07-31, on the author's Apple-Silicon Mac with a warm `~/.m2`, warm Docker images and Testcontainers reuse enabled.

| Fact | Value | How it was obtained |
| --- | --- | --- |
| Back-end main sources | 81 `.java` files, 1 Maven module (single `pom.xml`, no `<modules>`) | `find task-back-end/src/main -name '*.java' \| wc -l` |
| Back-end test sources | 27 `.java` files; **82 tests** executed by surefire | `./mvnw verify` output |
| Test classes that start containers | **8** classes extend `AbstractIntegrationTestCases` (1 `@SpringBootTest`, 1 more `@SpringBootTest`, 5 `@ApplicationModuleTest`, plus the base class) | `grep -rl "extends AbstractIntegrationTestCases"` |
| Containers used | `postgres:latest` (via `TestcontainersConfiguration`, `.withReuse(true)`) and `quay.io/keycloak/keycloak` (via `dasniko:testcontainers-keycloak:3.5.1`, `.withReuse(true)`, static initialiser) | `task-back-end/src/test/.../TestcontainersConfiguration.java`, `AbstractIntegrationTestCases.java` |
| Keycloak image actually pulled by the tests | `quay.io/keycloak/keycloak:26.0` (the default of `testcontainers-keycloak` 3.5.1) — note `compose.yaml` pins `26.1` for local dev, so **CI and dev-compose run different Keycloak versions** | build log line `tc.quay.io/keycloak/keycloak:26.0 -- Creating container for image` |
| `./mvnw verify` wall time (warm, reuse on) | **2 min 33 s**, and it **fails** at `pitest:mutationCoverage`: *"4 tests did not pass without mutation when calculating line coverage. Mutation testing requires a green suite."* | `./mvnw verify -B` |
| Surefire phase alone | ~48 s wall (21:10:05 → 21:10:53), plus a reported 30 s surefire fork-kill stall | build log timestamps |
| Container start times (warm image, reuse on) | Keycloak `PT1.60s` first start; Postgres `PT0.85s` first start, then `PT0.07–0.15s` on reuse | build log |
| Cold Maven dependency resolution | `dependency:go-offline` into an empty local repo: **1 min 13 s, 212 MB** | `./mvnw -Dmaven.repo.local=<empty> dependency:go-offline` |
| Front-end `npm ci` | **16.6 s**, 478 packages, `node_modules` ≈ 285 MB, `package-lock.json` 298 KB | clean copy in a scratch dir |
| Front-end `ng build` (production) | **13.3 s**, initial bundle 410 KB raw / 101 KB transfer | same |
| Front-end `ng test` | **9.5 s**, and it **fails**: 2 test files / 2 tests failing with *"Zone is needed for the waitForAsync() test helper but could not be found"* | same |
| Front-end spec files | 2 (`app.spec.ts`, `task-list.spec.ts`) out of 9 `.ts` files | `find task-front-end/src -name '*.spec.ts'` |
| Docker images pulled by the test suite | `postgres:latest` 162.4 MB compressed (amd64), `quay.io/keycloak/keycloak:26.1` 242.8 MB compressed (amd64) | Docker Hub / quay.io registry manifests, summed layer sizes |
| Dockerfiles in repo | **none** — there is no container build for either app today | `find . -iname 'Dockerfile*'` |

> **Repository visibility discrepancy.** The task brief states this is a **private** repo. `gh repo view --json isPrivate,visibility` on 2026-07-31 returns `{"isPrivate": false, "visibility": "PUBLIC"}` for `stainii/task`. This matters enormously: **standard GitHub-hosted runners are free on public repositories** ([GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), checked 2026-07-31), and GitHub explicitly recommends *against* self-hosted runners on public repos (§4). Section 1 gives the private-repo arithmetic as briefed, but flags the public-repo case where it changes the answer. **Confirm the intended visibility before using any of these numbers.**
>
> Two other facts worth knowing before designing a gate: **the back-end build is currently red at `verify`** (pitest) and **the front-end test suite is currently red**. Any "make CI green" plan starts from red, not from green.

---

## 1. GitHub Actions on a private repo

### Included allowances by plan

| Plan | Included minutes/month (private repos) | Artifact storage | Cache storage (per repo) |
| --- | --- | --- | --- |
| Free (user) | 2,000 | 500 MB | 10 GB |
| Pro | 3,000 | 1 GB | 10 GB |
| Free (organization) | 2,000 | 500 MB | 10 GB |
| Team | 3,000 | 2 GB | 10 GB |
| Enterprise Cloud | 50,000 | 50 GB | 10 GB |

Source: [`data/reusables/billing/actions-included-quotas.md`](https://raw.githubusercontent.com/github/docs/main/data/reusables/billing/actions-included-quotas.md) and [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), both checked 2026-07-31.

What differs between Free / Pro / Team, for this repo's purposes: **only the minute and artifact-storage allowance.** Cache storage is 10 GB per repository on every plan. Team additionally unlocks organization-level runner groups (§4) and 75 GB of custom-image storage. `stainii/task` is a **personal-account** repo, so Team/Enterprise features (runner groups, org secrets policies) are not reachable without moving it to an organization. The actual plan on the `stainii` account could not be verified from a primary source — `[unverified]`; the arithmetic below assumes **Free (2,000 min)** and notes where Pro (3,000) changes the picture.

### Per-minute rates (standard GitHub-hosted runners)

| Operating system | Billing SKU | Per-minute rate (USD) |
| --- | --- | --- |
| Linux 1-core (x64) | `actions_linux_slim` | $0.002 |
| Linux 2-core (x64) | `actions_linux` | $0.006 |
| Linux 2-core (arm64) | `actions_linux_arm` | $0.005 |
| Windows 2-core (x64) | `actions_windows` | $0.010 |
| Windows 2-core (arm64) | `actions_windows_arm` | $0.010 |
| macOS 3-core or 4-core (M1 or Intel) | `actions_macos` | $0.062 |

Source: [Actions runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing) and [`data/reusables/billing/actions-standard-runner-prices.md`](https://raw.githubusercontent.com/github/docs/main/data/reusables/billing/actions-standard-runner-prices.md), checked 2026-07-31.

**Minute multipliers.** GitHub's docs still state that *"Jobs that run on Windows and macOS runners hosted by GitHub consume minutes at 2 and 10 times the rate that jobs on Linux runners consume"* ([Billing and usage](https://docs.github.com/en/actions/concepts/billing-and-usage), checked 2026-07-31), i.e. Linux 1×, Windows 2×, macOS 10× against the *included* minute pool. The `/billing/reference/actions-minute-multipliers` URL now redirects to the per-minute price table above, and the implied ratios there are 1 : 1.67 : 10.3 rather than 1 : 2 : 10. The two pages disagree; treat the multiplier figures as `[unverified]` and use the per-minute rates for overage cost. This project has no reason to leave Linux, so the discrepancy is academic here.

**2026 pricing changes** ([Pricing changes for GitHub Actions](https://github.com/resources/insights/2026-pricing-changes-for-github-actions), checked 2026-07-31):
- 1 January 2026: hosted-runner prices cut by up to 39% — *"a ~40% price reduction across all runner sizes, paired with the addition of a new $0.002 per-minute GitHub Actions cloud platform charge"*. The rates in the table above already include it.
- 1 March 2026: **a $0.002/min cloud platform charge now applies to self-hosted runners in private repositories**, and self-hosted usage *"will consume available usage based on list price the same way that Linux, Windows, and MacOS standard runners work today."* Exempt: public repositories and GitHub Enterprise Server. This directly contradicts the still-live statement on [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions) that *"GitHub Actions usage is free for self-hosted runners"*. The announcement page is the more specific and more recent source; the discrepancy is flagged again in §4 and in Open Questions.

**Storage overage.** [About billing for GitHub Actions](https://docs.github.com/en/billing/managing-billing-for-github-actions/about-billing-for-github-actions) (checked 2026-07-31) gives shared storage (artifacts + GitHub Packages) at **$0.25 per GB/month**, accruing hourly. A separate GitHub docs statement quotes **$0.008 per GB per day** — the same figure to within rounding (0.008 × 30 = $0.24). Cache storage (10 GB/repo) is a hard limit rather than a billed line item.

### Runner hardware

`ubuntu-latest` (= Ubuntu 24.04) is **2 vCPU / 8 GB RAM / 14 GB SSD on private repos** and **4 vCPU / 16 GB RAM / 14 GB SSD on public repos** ([GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), checked 2026-07-31). The private-repo runner is therefore **half the machine** — this is the single biggest driver of the build-time estimates below. `ubuntu-slim` (1 vCPU / 5 GB, $0.002/min) is unusable here: it *"runs in unprivileged mode"* and *"operations requiring elevated privileges — such as mounting file systems, using Docker-in-Docker, or accessing low-level kernel features — are not supported"*, plus a 15-minute job cap.

### What a run of *this* repo costs

Measured locally, then scaled. **The scaling factor is the estimate, not the measurement.** The local machine is an Apple-Silicon laptop; a 2-vCPU x64 hosted runner is conservatively **2.5–3× slower** for JVM-heavy work — `[unverified]`, this is a rule of thumb, not a sourced number. All per-job figures are rounded up to whole minutes because billing is per job.

**Back-end job (`ubuntu-latest`, private-repo sizing, warm `~/.m2` cache):**

| Step | Local measured | Runner estimate |
| --- | --- | --- |
| `actions/checkout` | — | ~5 s |
| `actions/setup-java` (JDK 25 download + install) | — | ~20–30 s |
| Restore `~/.m2` cache (~212 MB+) | — | ~20–40 s |
| `docker pull` postgres (162 MB) + keycloak (243 MB) | — | ~15–25 s |
| Surefire, 82 tests, 6+ Spring contexts, containers | 48 s | ~2.5–4 min |
| `jar` + `spring-boot:repackage` | ~10 s | ~20–30 s |
| **Subtotal, `mvn verify -Dpit.skip`** | ~1.3 min | **~5 min billable** |
| `pitest:mutationCoverage` (if kept in the gate) | ~90 s before failing | **+~4–5 min → ~10 min billable** |

**Front-end job (`ubuntu-latest`, warm npm cache):**

| Step | Local measured | Runner estimate |
| --- | --- | --- |
| checkout + `actions/setup-node` + cache restore | — | ~30 s |
| `npm ci` (478 packages) | 16.6 s | ~45 s |
| `ng build --configuration production` | 13.3 s | ~40 s |
| `ng test` | 9.5 s | ~30 s |
| **Subtotal** | ~40 s | **~3 min billable** |

**Cold-cache penalty** (first run, or after a 7-day cache eviction — see §2): add the 212 MB / 1 min 13 s Maven download, which on a hosted runner is roughly **+1.5–2.5 min**, and a full `npm ci` without the npm cache, roughly **+30 s**.

### Monthly totals

Per-push cost = back-end + front-end run in parallel jobs but **billed as the sum of job minutes**.

- Without pitest: **5 + 3 = 8 billable minutes per push**
- With pitest: **10 + 3 = 13 billable minutes per push**

| Pushes / month | Minutes (no pitest, 8/push) | Minutes (with pitest, 13/push) | Cost at $0.006/min if *all* minutes were billed |
| --- | --- | --- | --- |
| 20 | 160 | 260 | $0.96 / $1.56 |
| 60 | 480 | 780 | $2.88 / $4.68 |
| 100 | 800 | 1,300 | $4.80 / $7.80 |
| 200 | 1,600 | 2,600 | $9.60 / $15.60 |
| 400 | 3,200 | 5,200 | $19.20 / $31.20 |

Against a **Free plan's 2,000 included minutes**, the actual bill is $0 until you cross the allowance:

| Scenario | Minutes/month | Over 2,000 by | Overage cost |
| --- | --- | --- | --- |
| 100 pushes, no pitest | 800 | — | **$0** |
| 200 pushes, no pitest | 1,600 | — | **$0** |
| 250 pushes, no pitest | 2,000 | 0 | **$0** (exactly at the line) |
| 200 pushes, with pitest | 2,600 | 600 | **$3.60** |
| 400 pushes, no pitest | 3,200 | 1,200 | **$7.20** |
| 400 pushes, with pitest | 5,200 | 3,200 | **$19.20** |

**Break-even points on a Free plan (2,000 min):** ~250 full CI runs/month without pitest, ~153 with pitest. On Pro (3,000 min): ~375 and ~230.

Caveats that move these numbers:
- **PR + push double-billing.** A workflow triggered on both `push` and `pull_request` runs twice for a PR branch. On a solo project with a push-to-main habit this doesn't bite; with a PR-per-change habit it roughly **doubles** the totals. Renovate PRs (§9) count here too — at `prConcurrentLimit: 10` a bad week can add 10 PRs × several rebases × 8 min.
- **If the repo is public** (which it currently is), *all* of the above is **$0** and the runners are twice as big, which would cut the per-run minutes as well.

---

## 2. Testcontainers in CI

### Docker on GitHub-hosted Ubuntu runners

Yes, out of the box. The `ubuntu-24.04` runner image ships **Docker Client and Server 28.0.4, Docker Compose 2.38.2, Buildx 0.35.0** and the ECR credential helper ([actions/runner-images Ubuntu2404-Readme.md](https://raw.githubusercontent.com/actions/runner-images/main/images/ubuntu/Ubuntu2404-Readme.md), checked 2026-07-31). Testcontainers' own docs state the runner image has Docker Engine pre-installed and *"no extra setup is needed"* ([testcontainers-java CI docs](https://github.com/testcontainers/testcontainers-java/blob/main/docs/supported_docker_environment/continuous_integration/dind_patterns.md), checked 2026-07-31). Nothing needs installing; there is no Docker-in-Docker dance on standard runners. (`ubuntu-slim` is the exception — unprivileged, no DinD.)

Disk is the constraint to watch: **14 GB SSD** on the runner, against ~405 MB of compressed images plus ~212 MB of Maven artifacts plus `node_modules`. Comfortable, but not unlimited if image count grows.

### Container startup cost for this repo

Measured locally with warm images (2026-07-31 build log):

| Container | First start | Subsequent starts (reuse on) |
| --- | --- | --- |
| `quay.io/keycloak/keycloak:26.0` (with `--import-realm` of `keycloak/realm-export.json`) | **1.60 s** | reused, ~0 s |
| `postgres:latest` | **0.85 s** | **0.07–0.15 s** |

Plus the **pull**: 162.4 MB (postgres) + 242.8 MB (keycloak) compressed, per registry manifests. On a cold runner that is a one-off ~15–25 s.

The important observation: **containers are not the bottleneck in this suite.** Spring context startup dominates — the surefire phase takes ~48 s for 82 tests across 6+ distinct application contexts, while all container starts together account for under 3 s. Optimising container caching would buy little; consolidating Spring contexts (fewer distinct `@ApplicationModuleTest` slices, or shared `@ContextConfiguration`) would buy more. That is an observation, not a recommendation.

Note also that Postgres restarts **per Spring context** in CI (5 separate `Creating container for image: postgres:latest` lines appear even with reuse enabled locally, because each context gets its own container bean), while Keycloak is a `static` singleton started once per JVM.

### Caching options and their limits

| Mechanism | What it caches | Limits |
| --- | --- | --- |
| `actions/cache` (and everything built on it) | arbitrary paths | **10 GB total per repository** by default; user-owned repos can be raised to 10 TB. Entries **not accessed in over 7 days are removed**. When the repo exceeds its limit, entries are evicted **oldest-last-accessed first**. 200 cache uploads/min and 1,500 downloads/min per repo. ([Dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching), [Actions limits](https://docs.github.com/en/actions/reference/limits), checked 2026-07-31) |
| Cache **scoping** | — | A run restores from its own branch, the default branch, and (for PRs) the base branch. **Sibling branches cannot share caches; child branches cannot read a parent branch's cache.** PR-triggered caches are scoped to the merge ref. Practical effect: a feature branch's first run is always a cold-ish cache unless `main` populated it. |
| `actions/setup-java` `cache: maven` | `~/.m2/repository` (+ `~/.m2/wrapper/dists` separately), keyed `setup-java-{platform}-{pm}-{hash}` over `**/pom.xml`, `**/.mvn/wrapper/maven-wrapper.properties`, `**/.mvn/extensions.xml` | Documented caveat: Maven resolves **plugins lazily**, so a cache written by a thin goal can omit plugin jars that the next run re-downloads. `cache-read-only: true` is available for PR branches. ([actions/setup-java](https://github.com/actions/setup-java), checked 2026-07-31) |
| `actions/setup-node` `cache: npm` | the **global npm cache**, *not* `node_modules`; keyed on the lockfile hash | For a monorepo you must set `cache-dependency-path: task-front-end/package-lock.json` — the action looks in the repo root by default. Caching is auto-enabled when `package.json` has a top-level `packageManager` field, which **this repo has** (`"packageManager": "npm@11.6.2"`), unless disabled with `package-manager-cache: false`. ([actions/setup-node](https://github.com/actions/setup-node), checked 2026-07-31) |
| Docker layer caching (`docker/build-push-action` with `cache-to/from type=gha`) | **layers you build**, into the same Actions cache pool | *"as long as your use case falls within the size and usage limits set by GitHub"*, and the cache API is *"subject to rate limiting"*. Multiple builds overwrite each other unless you set distinct `scope`s. **It does not help with third-party images you merely pull** — it caches build layers, not registry pulls. ([Docker docs: GitHub Actions cache backend](https://docs.docker.com/build/cache/backends/gha/), checked 2026-07-31) |
| Caching *pulled* images | `docker save`/`docker load` via `actions/cache`, or a registry mirror | Restoring ~405 MB from the Actions cache is not obviously faster than pulling 405 MB from Docker Hub/quay, and it consumes the same 10 GB repo budget. No primary source quantifies the trade-off — `[unverified]`. |

There is currently **nothing to Docker-layer-cache in this repo**: there are no Dockerfiles. That only becomes relevant if a container build is added (§5, §7).

### Reuse and Ryuk in CI

- **Reuse is explicitly not for CI.** Testcontainers' docs: *"Reusable containers are not suited for CI usage and as an experimental feature not all Testcontainers features are fully working (e.g., resource cleanup or networking)"*, and *"Those containers won't stop after all tests are finished"* ([Reusable Containers](https://java.testcontainers.org/features/reuse/), checked 2026-07-31). Reuse also requires opting in per-machine via `TESTCONTAINERS_REUSE_ENABLE=true` or `~/.testcontainers.properties` — **it cannot be enabled from a classpath properties file**. So the `.withReuse(true)` calls already in `TestcontainersConfiguration` and `AbstractIntegrationTestCases` are **inert on a fresh CI runner** unless that env var is set, and setting it on an ephemeral runner buys nothing anyway (the machine is destroyed after the job). This means the CI test phase will be *slower* than the 48 s measured locally: every Postgres container is a genuine cold start (~0.85 s each), not a 0.07 s reuse hit. That is ~5 extra seconds — negligible.
- **Ryuk** is the resource-reaper sidecar: *"responsible for container removal and automatic cleanup of dead containers at JVM shutdown"*, and it *"must be started as a privileged container"* ([Testcontainers configuration](https://java.testcontainers.org/features/configuration/), checked 2026-07-31). GitHub-hosted Ubuntu runners allow privileged containers, so Ryuk works unmodified. `TESTCONTAINERS_RYUK_DISABLED=true` exists for environments that forbid privileged containers; on an ephemeral runner disabling it is harmless (the VM is discarded), but it saves only the Ryuk container's start time. `TESTCONTAINERS_CHECKS_DISABLE=true` skips startup checks that *"take a couple of seconds"* — a small, real saving on every run.

---

## 3. Monorepo builds

The repo is one git repo with two independent build systems (`task-back-end/` Maven, `task-front-end/` npm) plus `task-workspace/` (IntelliJ config, no build).

### Native path filters

`on.push.paths` / `on.push.paths-ignore` accept globs with `*`, `**`, `?`, `+` and `!` negation; **you cannot use both `paths` and `paths-ignore` for the same event** — use `paths` with `!` negation instead, and **order matters** (a later negative excludes, a later positive re-includes). If both `branches` and `paths` are given, both must match ([Workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), checked 2026-07-31).

**The required-checks pitfall**, quoted from the same page: *"If a workflow is skipped due to path filtering… then checks associated with that workflow will remain in a 'Pending' state. A pull request that requires those checks to be successful will be blocked from merging."*

Concretely: if `back-end.yml` has `paths: ['task-back-end/**']` and `Back-end / build` is a required status check, then **a front-end-only PR can never merge** — the back-end check stays Pending forever. Known workarounds:
1. Don't make path-filtered workflows required (relies on discipline).
2. Add a second workflow with the *inverse* path filter and the *same job name* that just reports success ("skip job" / dummy job pattern).
3. Drop native path filters and do the filtering **inside** an always-running workflow (next section), so the check always reports.

### `dorny/paths-filter`-style approaches

[`dorny/paths-filter`](https://github.com/dorny/paths-filter) (currently **v4**, Node 24; checked 2026-07-31) runs as a step and emits per-filter boolean outputs plus `${FILTER}_count` / `${FILTER}_files` and a `changes` JSON array. Its README states the motivation directly: *"GitHub workflows built-in path filters don't allow this because they don't work on a level of individual jobs or steps."*

Shape: one workflow that always triggers → a small `changes` job runs the filter → downstream `backend` / `frontend` jobs gate on `needs.changes.outputs.backend == 'true'`. The workflow always reports, so required checks are satisfied; skipped jobs cost ~0 minutes but the filter job itself costs ~1 billable minute per run (see §1 — job minutes are billed per job, rounded up).

Trade-off: a third-party action in the critical path (supply-chain surface, pin by SHA), plus it needs enough git history to diff (`initial-fetch-depth`).

### Matrix / job splitting

A `strategy.matrix` over `[task-back-end, task-front-end]` gives one job definition and two parallel runs. Limits: **256 jobs per matrix**, **20–500 concurrent jobs depending on plan** ([Actions limits](https://docs.github.com/en/actions/reference/limits), checked 2026-07-31) — irrelevant at this scale. The cost is that back-end and front-end need genuinely different setup steps (setup-java + Maven cache vs setup-node + npm cache), so a matrix ends up full of `if:` conditionals; two explicit jobs are usually clearer for two heterogeneous stacks. Either way, **billing is the sum of job minutes**, so splitting into two jobs does not reduce cost — it reduces *wall-clock* time (they run in parallel) and improves failure attribution, at the price of one extra job's rounding-up.

### Maven `-pl` / incremental options

`task-back-end/pom.xml` is a **single-module** POM (no `<modules>` element). Therefore:
- `-pl` / `-am` / `-amd` (project-list and also-make) have **nothing to select** — they are only useful in a multi-module reactor. No benefit here.
- Maven 4's build cache and `-Dmaven.build.cache.enabled` are `[unverified]` for this project; the build inherits `spring-boot-starter-parent:4.0.1` and uses the wrapper, and no build-cache extension is configured in `.mvn/`.
- The realistic incremental lever is skipping *phases*: `-Dpit.skip=true` (or moving the pitest execution to a separate, non-blocking workflow) removes the ~4–5 min mutation-testing block from the gate. `-DskipTests` for a build-only job. `-o` (offline) after a warm cache restore avoids SNAPSHOT/metadata round-trips.
- `-T 1C` (parallel builds) does nothing on a single module.

### One build vs split — the trade-offs

| | One workflow, one job, builds everything | Split (path-filtered or `paths-filter`-gated jobs) |
| --- | --- | --- |
| Billable minutes per push | Always the full ~8 min (§1) | ~5 min for a back-end-only change, ~3 min for a front-end-only change |
| Wall-clock | Serial: ~8 min | Parallel: ~5 min (the slower job) |
| Required-checks safety | Trivially safe — one check, always runs | Native `paths` filters **break required checks** (see quote above); `dorny/paths-filter` avoids it |
| Failure attribution | One red X for either stack | Clear per-stack signal |
| Complexity | Lowest — one YAML file, no filter logic | One extra job + filter config, or two workflows + dummy-job trick |
| Catching cross-stack breakage | Always catches it (e.g. an API contract change) | A back-end-only change never runs front-end tests, so contract drift is caught later |
| Rounding waste | 1 job rounded up | 2–3 jobs each rounded up (up to ~2 extra billable min/run) |

At this repo's measured scale (8 min/run, 2,000 free min/month), splitting saves at most a few minutes per push and does not change whether you pay. Splitting's real payoff is wall-clock and signal clarity, not cost.

---

## 4. Self-hosted runners on the existing home server

### Setup

Download the runner tarball, run `config.sh` with a repo/org URL and a **registration token that expires after one hour**, then run the service ([Adding self-hosted runners](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners), checked 2026-07-31). Success shows `√ Connected to GitHub` / `Listening for Jobs`. On Linux it installs as a systemd service. Realistically: **30–60 minutes to first green build**, assuming Docker and a JDK are already on the box.

### GitHub's own security guidance

Quoted verbatim ([Adding self-hosted runners](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners), checked 2026-07-31):

> *"We recommend that you only use self-hosted runners with private repositories. This is because forks of your public repository can potentially run dangerous code on your self-hosted runner machine by creating a pull request that executes the code in a workflow."*

And ([Security hardening](https://docs.github.com/en/actions/reference/security/secure-use), checked 2026-07-31):

> *"Self-hosted runners should almost never be used for public repositories"* — because any pull request can compromise the environment. GitHub also notes that self-hosted runners lack the *"ephemeral and clean isolated virtual machines"* guarantee of hosted runners, creating **persistent** compromise risk, and that even for private/internal repos, users with read access can fork and open malicious PRs.

**`stainii/task` is currently PUBLIC** (verified 2026-07-31 via `gh repo view`). Under GitHub's own guidance, putting a self-hosted runner on the home server while the repo is public is the documented worst case: an untrusted fork PR would execute arbitrary code **on the same machine that hosts the application and its Postgres data**. If the repo is made private (as the brief states it is), the guidance softens to "careful oversight" rather than "almost never".

### Hardening options

| Option | What it gives | Availability for this repo |
| --- | --- | --- |
| **Ephemeral runners** (`config.sh --ephemeral`) | Runner takes **at most one job**, then deregisters — *"you can use automation to provide a clean environment for each job… limit the exposure of any sensitive resources from previous jobs"* | Available on any plan. Requires you to build the re-provisioning loop (a supervisor script or a container-per-job wrapper). |
| **Just-in-time (JIT) runners** via REST API | Same, registered programmatically with a one-shot config token | Available; more moving parts. |
| **Actions Runner Controller (ARC)** | GitHub's *"reference implementation of GitHub's scale set APIs and the recommended Kubernetes-based solution for autoscaling"*, with EphemeralRunnerSet/JIT tokens | **Requires Kubernetes.** Running k8s on a home server to get clean CI runners is a large operational step for a two-app personal project. |
| **Runner groups** | Restrict which repos can use which runners; *"by default, only private repositories can access runners in a runner group"* | **Organization-level feature; additional groups require GitHub Team.** `stainii/task` is under a **personal account**, so runner groups are **not available** without moving the repo to an org. ([Managing access to self-hosted runners](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/manage-access), checked 2026-07-31) |
| Network hygiene | GitHub advises minimising sensitive data on the runner and restricting *"network access to sensitive services like cloud metadata endpoints"* | Free, but on a home server the runner sits **inside the LAN**, which is exactly where the sensitive services are. |

Registration limits, for completeness: 1,500 runner registrations per 5 minutes, 10,000 runners per group, **job execution time cap 5 days** (vs 6 hours on hosted), job queue cap 24 hours ([Actions limits](https://docs.github.com/en/actions/reference/limits)).

### Cost: electricity + time vs hosted minutes

The home server is already running (it hosts the app), so the **marginal** electricity cost of CI is the extra draw during builds.

Assumptions (stated, not sourced): +40 W above idle while a build runs; 8 minutes per build.
- 100 builds/month → 100 × 8 min = 13.3 h → 40 W × 13.3 h = **0.53 kWh/month**
- Belgian household electricity, band DC (2,500–5,000 kWh/yr), all taxes included, **H2 2025: €0.3499/kWh** ([Eurostat, Electricity price statistics](https://ec.europa.eu/eurostat/statistics-explained/index.php?title=Electricity_price_statistics), checked 2026-07-31; EU average €0.2896/kWh)
- → **€0.19/month.** At 400 builds/month, €0.74/month.

The +40 W figure is an assumption — `[unverified]`. Even at +150 W it stays under €3/month. **Electricity is not the deciding variable.**

Against that:
- Hosted minutes for the same 100 builds: **$0** (within the 2,000-minute Free allowance, §1), or **$4.80** if every minute were billed at list.
- Since 1 March 2026, self-hosted minutes in **private** repos are **not free** either: **$0.002/min** platform charge, consuming the same plan quota ([2026 pricing changes](https://github.com/resources/insights/2026-pricing-changes-for-github-actions), checked 2026-07-31). 100 builds × 8 min × $0.002 = **$1.60/month** — *plus* the electricity, *plus* the maintenance. Note the direct contradiction with [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), which still says self-hosted is free; see Open Questions.
- Maintenance time: runner version upgrades (the runner auto-updates, but the host OS, Docker, disk space and the systemd unit do not), disk filling with Docker layers and workspaces, and the ephemeral-runner re-provisioning loop if you build one. No primary source quantifies this; call it **1–3 hours of setup and ~15–30 min/month of attention** — `[unverified]`, an estimate.

**Weighed against the stated goal of reducing operational burden:** self-hosted runners add a component to operate (the runner host), a security surface GitHub explicitly warns about, and a per-minute charge that no longer makes them free — in exchange for saving an amount of money that is currently **$0**, because the workload fits inside the free allowance. The one scenario where they do reduce burden is if the deployment target is the same box and a self-hosted runner removes the need for a separate deploy path (see §5). That is a real, non-monetary argument and it belongs to the deployment decision, not the CI-cost decision.

---

## 5. Deploying to a home server behind NAT

Four patterns, all of which exist in the wild. Effort estimates are `[unverified]` judgements, not sourced figures.

### 5a. Pull-based agent on the server (Watchtower & alternatives)

**How it works:** an agent runs on the home server, polls a registry for a new image digest, pulls it, and restarts the container with the same options. Watchtower *"gracefully shut[s] down your existing container and restart[s] it with the same options that were used when it was deployed initially"* ([Watchtower docs](https://containrrr.dev/watchtower/), checked 2026-07-31). It needs `/var/run/docker.sock` and registry credentials; **no inbound connectivity at all**.

**Status warning:** the canonical [`containrrr/watchtower`](https://github.com/containrrr/watchtower) repository is **archived** (verified via GitHub API 2026-07-31: `archived: true`, last push 2025-12-17, 24.7k stars). The actively maintained fork is [`nicholas-fedor/watchtower`](https://github.com/nicholas-fedor/watchtower) (`archived: false`, last push 2026-07-31, 4.2k stars). Alternatives in the same shape: a systemd timer running `docker compose pull && docker compose up -d`, Diun (notify-only), or a GitOps agent (Flux/Argo) if there were a Kubernetes cluster.

**Exposed:** nothing inbound. Outbound to the registry only.
**Security trade-off:** the server holds a **read-only** registry credential; GitHub never holds a credential to the server. The blast radius of a leaked CI secret does not include the house. Downside: no deploy acknowledgement — CI cannot tell you the deploy succeeded, and rollback is manual. Also, "latest tag moved" is a weak trigger; digest pinning plus a poll interval means deploys land minutes late.
**Effort:** **lowest.** ~1 hour, mostly writing the compose file. Requires a Dockerfile and a registry (§7) — neither exists yet.

### 5b. Self-hosted runner doing the deploy locally

**How it works:** the runner is already on the LAN, so a deploy job is just `docker compose up -d` (or `mvn spring-boot:run`) as a workflow step. GitHub polls *outbound* from the runner; nothing inbound.

**Exposed:** nothing inbound.
**Security trade-off:** the worst of §4 — arbitrary workflow code executes on the machine that holds the production data, and while the repo is public, fork PRs can trigger it. Mitigations: use a GitHub **Environment** with a required reviewer for the deploy job, run deploys only on `push` to `main` (never `pull_request`), and use an ephemeral runner. Even then the runner has Docker socket access, which is root-equivalent.
**Effort:** low **if** you already accept a self-hosted runner (§4); otherwise it drags in that whole decision.

### 5c. Tunnels (Cloudflare Tunnel, Tailscale)

**Cloudflare Tunnel:** `cloudflared` runs on the origin and *"initiates an outbound connection through your firewall from the origin to the Cloudflare global network"*, giving *"a secure way to connect your resources to Cloudflare without a publicly routable IP address"* ([Cloudflare Tunnel docs](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/), checked 2026-07-31). Two distinct uses: (i) publish the *app* to the internet without port-forwarding; (ii) let a hosted runner reach the box to deploy, gated behind Cloudflare Access. **Pricing could not be confirmed from a primary source** — the [Zero Trust plans page](https://www.cloudflare.com/plans/zero-trust-services/) and the [Cloudflare One docs index](https://developers.cloudflare.com/cloudflare-one/) both failed to render plan details on 2026-07-31; the docs only state *"Cloudflare Zero Trust offers both Free and Paid plans. Access to certain features depends on a customer's plan type."* Tunnel is widely believed to be free with a free Cloudflare account — `[unverified]`.

**Tailscale:** a WireGuard mesh; both the home server and a hosted runner join the tailnet, and the runner reaches the box by its tailnet IP. **Personal plan: $0 forever, up to 6 users, unlimited user devices, 50 tagged resources included ($1/month each beyond), 1,000 ephemeral-resource minutes/month, 3 ACL groups**; Standard $8/user/month; Premium $18/user/month ([Tailscale pricing](https://tailscale.com/pricing), checked 2026-07-31). The **1,000 ephemeral minutes/month** matters: a CI runner joining the tailnet per job is exactly an ephemeral node, and at 100 builds × ~8 min that is ~800 min/month — **inside the free allowance, but not by much.**

**Exposed:** no inbound ports; identity-based access instead of IP-based.
**Security trade-off:** you trade "open port" for "trust a third-party control plane" (Cloudflare or Tailscale). For Tailscale-from-CI you must put a Tailscale auth key in Actions secrets — a credential that grants network entry to the home LAN. Scope it with ACL tags and use ephemeral, pre-authorised keys.
**Effort:** medium. Tailscale + `tailscale/github-action`: ~1–2 hours. Cloudflare Tunnel + Access service tokens: ~2–4 hours (DNS, tunnel config, Access policy).

### 5d. SSH from a hosted runner to a port-forwarded box

**How it works:** forward TCP/22 (or a high port) on the router to the server; the workflow runs `ssh` with a private key from Actions secrets and executes the deploy.

**Exposed:** **an inbound SSH port on a residential IP**, permanently, to the whole internet. GitHub publishes hosted-runner IP ranges via the meta API so you *can* firewall to them, but that range is large and shared by every GitHub customer — allowlisting it is not meaningfully restrictive.
**Security trade-off:** highest of the four. A long-lived private key sits in Actions secrets (readable by anyone with write access to the repo — see §8), and the port is exposed to background scanning. Mitigations: key-only auth, non-root deploy user with a forced command, fail2ban, a non-standard port. Also depends on a stable public IP or dynamic DNS.
**Effort:** lowest *conceptually*, but the router/DDNS/hardening work makes it comparable to a tunnel — ~2 hours, plus ongoing exposure.

### Summary

| Pattern | Inbound exposure | Credential in CI | Deploy feedback to CI | Setup effort |
| --- | --- | --- | --- | --- |
| Pull agent (Watchtower fork / compose timer) | none | none (server holds a registry read token) | none | ~1 h |
| Self-hosted runner deploys locally | none | none | full | ~1 h *plus* the §4 decision |
| Tailscale from hosted runner | none | tailnet auth key | full | 1–2 h |
| Cloudflare Tunnel + Access | none | Access service token | full | 2–4 h |
| SSH to port-forwarded box | **SSH port, permanently** | long-lived SSH private key | full | ~2 h + hardening |

---

## 6. Deploying to AWS

Sizing assumption throughout: **one small Spring Boot app + one Keycloak + one Postgres, personal-project traffic, region `eu-west-1` (Ireland), 730 hours/month, on-demand, no reservations.** Prices from the **AWS Price List API** for `eu-west-1` unless stated; all checked **2026-07-31**.

### The line items people forget

| Item | eu-west-1 rate | Per month (730 h) | Source |
| --- | --- | --- | --- |
| Application Load Balancer | $0.0252/hour + $0.008/LCU-hour | **$18.40** + LCU | AWS Price List API, `AWSELB`, `eu-west-1` |
| NAT Gateway | $0.048/hour + $0.048/GB processed | **$35.04** + data | AWS Price List API, `AmazonEC2`, `eu-west-1` |
| Public IPv4 address (in-use **or** idle) | $0.005/hour **each** | **$3.65 per address** | AWS Price List API, `AmazonVPC`, `eu-west-1` |
| EBS gp3 storage | $0.088/GB-month (+$0.0055/provisioned IOPS-month) | 30 GB = **$2.64** | AWS Price List API, `AmazonEC2`, `eu-west-1` |
| RDS gp3 storage (Single-AZ) | $0.127/GB-month | 20 GB = **$2.54** | AWS Price List API, `AmazonRDS`, `eu-west-1` |
| Data transfer out to internet | first 10 TB/month **$0.09/GB** (then $0.085 / $0.070 / $0.050) | after the free 100 GB | AWS Price List API, `AWSDataTransfer` |
| Free egress allowance | **100 GB/month**, aggregated across all services and regions | — | [EC2 On-Demand pricing](https://aws.amazon.com/ec2/pricing/on-demand/) |

An ALB in two AZs consumes **two** public IPv4 addresses → **$7.30/month** on top of the $18.40. A NAT Gateway alone costs more than the whole compute budget of a personal project; the standard way to avoid it is to place tasks in **public subnets with public IPs** (each of which is then billed at $3.65/month).

### Building blocks

**Compute rates, eu-west-1:**

| Service | Rate | Source |
| --- | --- | --- |
| Fargate x86 | $0.04048/vCPU-hour + $0.004445/GB-hour; 1-minute minimum, per-second billing | Price List API `AmazonECS`; rounding per [Fargate pricing](https://aws.amazon.com/fargate/pricing/) |
| Fargate ARM (Graviton) | $0.03238/vCPU-hour + $0.00356/GB-hour | Price List API `AmazonECS` |
| Fargate ephemeral storage | $0.000122/GB-hour (20 GB free per task) | Price List API `AmazonECS` |
| App Runner | provisioned (idle) **$0.007/GB-hour**; active **$0.064/vCPU-hour + $0.007/GB-hour**; minimum **0.25 vCPU / 0.5 GB**; per-second billing, 1-minute vCPU minimum | [App Runner pricing](https://aws.amazon.com/apprunner/pricing/) |
| EC2 `t4g.micro` (2 vCPU / 1 GB) | $0.00920/h → **$6.72/mo** | Price List API `AmazonEC2` (calculator feed) |
| EC2 `t4g.small` (2 vCPU / 2 GB) | $0.01840/h → **$13.43/mo** | same |
| EC2 `t4g.medium` (2 vCPU / 4 GB) | $0.03680/h → **$26.86/mo** | same |
| EC2 `t3.small` (2 vCPU / 2 GB, x86) | $0.02280/h → **$16.64/mo** | same |
| EC2 `t3.medium` (2 vCPU / 4 GB, x86) | $0.04560/h → **$33.29/mo** | same |
| RDS PostgreSQL `db.t4g.micro` Single-AZ | $0.017/h → **$12.41/mo** | Price List API `AmazonRDS` |
| RDS PostgreSQL `db.t4g.small` Single-AZ | $0.035/h → **$25.55/mo** | same |
| Lightsail 2 GB Linux bundle (incl. IPv4) | $0.01612/h → **$11.77/mo** | Price List API `AmazonLightsail` |
| Lightsail 4 GB Linux bundle (incl. IPv4) | $0.03225/h → **$23.54/mo** | same |
| Lightsail 8 GB Linux bundle (incl. IPv4) | $0.05913/h → **$43.16/mo** | same |
| Lightsail managed DB 1 GB standard | $0.0202/h → **$14.75/mo** | same |
| Lightsail container node Nano (0.25 vCPU / 0.5 GB) | $0.0094/h → **$6.86/mo** | same |
| Lightsail container node Micro (0.25 vCPU / 1 GB) | $0.0134/h → **$9.79/mo** | same |
| Lightsail block storage / egress overage | $0.10/GB-month / $0.09/GB above bundle quota | same |

Sizing note: Keycloak in a 0.5 GB container is not realistic; 1 GB is the practical floor and 2 GB is comfortable — `[unverified]`, no primary source states a minimum. The estimates below assume **1 GB for the Spring Boot app and 1 GB for Keycloak** as the smallest thing that plausibly works.

### Shape A — ECS Fargate

Two tasks (app 0.5 vCPU/1 GB, Keycloak 0.5 vCPU/1 GB) in **public** subnets (no NAT), one ALB, RDS `db.t4g.micro` + 20 GB.

| Line | Monthly |
| --- | --- |
| Fargate app: 0.5 × $0.04048 × 730 + 1 × $0.004445 × 730 | $14.78 + $3.24 = **$18.02** |
| Fargate Keycloak: same sizing | **$18.02** |
| RDS db.t4g.micro + 20 GB gp3 | $12.41 + $2.54 = **$14.95** |
| ALB | **$18.40** (+ LCU, ~$0–6) |
| 2 × public IPv4 for the ALB | **$7.30** |
| ECR storage (~1 GB) | **$0.10** |
| **Total** | **≈ $76.79/month** |

Variants: **Graviton/ARM** tasks drop the two Fargate lines to $14.42 each → **≈ $69.60**. Adding a **NAT Gateway** (private subnets, the "proper" layout) adds **$35.04 + $0.048/GB** → **≈ $112**. Running Postgres as a third Fargate task instead of RDS trades $14.95 for ~$18 of Fargate **plus** the problem that Fargate ephemeral storage is not durable — you would need EFS, which is another line item.

### Shape B — App Runner

Two services (app, Keycloak), each 1 GB provisioned, 1 instance; RDS for Postgres. App Runner terminates TLS and gives you a URL, so **no ALB and no public IPv4 charge**.

| Line | Monthly |
| --- | --- |
| App service, 1 GB provisioned idle: 1 × $0.007 × 730 | **$5.11** |
| App service, active vCPU (assume 1 h/day at 0.5 vCPU): 0.5 × $0.064 × 30 | **$0.96** |
| Keycloak service, 1 GB provisioned idle | **$5.11** |
| Keycloak active vCPU (assume 0.5 h/day at 0.25 vCPU) | **$0.24** |
| RDS db.t4g.micro + 20 GB | **$14.95** |
| Automatic-deployment fee per application | `[unverified]` — the pricing page says a monthly fee per app exists but the amount did not render |
| **Total** | **≈ $26.4/month + the automatic-deployment fee** |

Caveat from the pricing page: App Runner does **not** scale to zero cost — *"when applications are idle, the service automatically scales back to your provisioned container instances (the default is 1 provisioned container instance)"*, so you always pay the provisioned GB-hour rate. You *can* **pause** a service via console/CLI/API to stop charges, which suits a personal project that is not always needed. There is also a **build fee** when deploying from source rather than from an image.

### Shape C — Lightsail

Everything (app + Keycloak + Postgres) in Docker Compose on one Lightsail instance. Bundles include a public IPv4 and a data-transfer allowance; a static IP is free while attached ($0.005/h only when unattached).

| Option | Monthly |
| --- | --- |
| 4 GB bundle, all three services in containers | **$23.54** |
| 8 GB bundle (comfortable), all three services | **$43.16** |
| 2 GB bundle + Lightsail managed DB 1 GB | $11.77 + $14.75 = **$26.52** |
| 4 GB bundle + Lightsail managed DB 1 GB | $23.54 + $14.75 = **$38.29** |
| Lightsail Containers: 2 × Micro nodes (app + Keycloak) + managed DB | $9.79 + $9.79 + $14.75 = **$34.33** |

Egress above the bundle quota is $0.09/GB; block storage $0.10/GB-month.

### Shape D — plain EC2

| Option | Monthly |
| --- | --- |
| `t4g.small` (2 GB) + 30 GB gp3 + 1 public IPv4 — everything in containers | $13.43 + $2.64 + $3.65 = **$19.72** |
| `t4g.medium` (4 GB) + 30 GB gp3 + 1 public IPv4 — everything in containers | $26.86 + $2.64 + $3.65 = **$33.15** |
| `t4g.medium` + 30 GB gp3 + IPv4 + RDS db.t4g.micro (Postgres off-box) | $33.15 + $14.95 = **$48.10** |
| `t3.small` (x86, 2 GB) + 30 GB gp3 + IPv4 | $16.64 + $2.64 + $3.65 = **$22.93** |

Squeezing a Spring Boot app **and** Keycloak **and** Postgres into 2 GB is optimistic; 4 GB is the honest floor — `[unverified]` judgement. Skipping the ALB (point DNS at the instance's IP, terminate TLS with Caddy/nginx + Let's Encrypt on the box) is what keeps this shape cheap; adding an ALB adds $18.40 + $7.30.

**RDS vs self-hosted Postgres in a container:** RDS `db.t4g.micro` + 20 GB is **$14.95/month** and buys automated backups, patching and point-in-time recovery. A Postgres container on the same EC2/Lightsail box is **$0 extra** and buys you the backup job as homework. For a personal project the delta is roughly "$15/month for not thinking about backups".

### Rough ranking (smallest plausible configuration)

| Shape | Monthly | What you give up |
| --- | --- | --- |
| EC2 `t4g.small`, all-in-one containers, no ALB | **≈ $20** | 2 GB is tight; you operate everything; no managed backups |
| Lightsail 4 GB, all-in-one containers | **≈ $24** | You operate everything; predictable flat price; simplest AWS billing |
| App Runner ×2 + RDS | **≈ $26** + deploy fee | Always-on provisioned cost; less control; managed TLS included |
| EC2 `t4g.medium`, all-in-one, no ALB | **≈ $33** | You operate everything, but with room |
| Lightsail 4 GB + managed DB | **≈ $38** | — |
| EC2 `t4g.medium` + RDS | **≈ $48** | — |
| Fargate ×2 (ARM) + RDS + ALB, public subnets | **≈ $70** | — |
| Fargate ×2 (x86) + RDS + ALB, public subnets | **≈ $77** | — |
| Fargate ×2 + RDS + ALB + NAT Gateway | **≈ $112** | The "textbook" layout costs 5× the simplest one |

### Free-tier caveats and expiry

The AWS Free Tier is now **credit-based**, not the old 12-month model: new accounts get **"$100 in credits immediately"**, can earn another $100, for **up to $200 over a 6-month period**, and *"the account automatically terminates after 6 months or when credits deplete, whichever occurs first"* unless upgraded to a paid plan. Separately, *"30+ AWS services are always free within monthly usage limits"* on both free and paid plans ([AWS Free Tier](https://aws.amazon.com/free/), checked 2026-07-31). The page makes **no mention** of the legacy 750-hours-of-t2/t3.micro or 750-hours-of-RDS offers; whether those still exist for accounts created before the change could not be verified — `[unverified]`.

Practical reading: **$200 of credits covers roughly 2–8 months of the shapes above, then the bill is real.** The 100 GB/month global egress allowance and the ECR 500 MB-for-12-months / ECR Public 50 GB always-free allowances (§7) survive independently.

---

## 7. Container registry

There is **no Dockerfile in this repo today**, so all of this is contingent on adding a container build.

### GHCR (GitHub Container Registry) on a private repo

- **Cost:** *"Container image storage and bandwidth for the Container registry is currently free"* for all repository types, and GitHub commits to *"at least one month in advance"* notice before changing that ([GitHub Packages billing](https://docs.github.com/en/billing/concepts/product-billing/github-packages), checked 2026-07-31). **This is the headline: GHCR is free for private repos today, with a one-month-notice caveat.**
- For **other** GitHub Packages registries (Maven, npm, NuGet…), the plan allowances apply and **storage is shared with Actions artifacts**: Free 500 MB storage / 1 GB monthly data transfer; Pro and Team 2 GB / 10 GB; Enterprise Cloud 50 GB / 100 GB. Data transferred **in** is always free, public packages are free, and downloads by an Actions workflow using `GITHUB_TOKEN` *"do not count against the usage for the hosting repository"*. Per-GB overage rates are not published on that page (it points at the pricing calculator) — `[unverified]`.
- **Auth:** in Actions, use `GITHUB_TOKEN` with `packages: write` rather than a classic PAT; the package is then **linked to the repository automatically**. Outside Actions you need a classic PAT with `read:packages` / `write:packages` / `delete:packages` ([Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry), checked 2026-07-31).
- **Visibility:** container images default to **private** on first publish; access can be inherited from the linked repo or set independently.
- **Retention / cleanup:** manual only. You can delete versions in the UI or via REST/GraphQL (workflow-driven deletion via REST is *"currently in public preview"*), and deleted packages are **restorable within 30 days** if the namespace is still free. **GitHub does not offer automatic age- or count-based retention policies for container images** ([Deleting and restoring a package](https://docs.github.com/en/packages/learn-github-packages/deleting-and-restoring-a-package), checked 2026-07-31). In practice people run a scheduled workflow with a delete-old-versions action. Since storage is free, this is housekeeping rather than cost control.

### Comparison

| Registry | Private storage cost | Egress | Pull limits | Retention/cleanup | Notes |
| --- | --- | --- | --- | --- | --- |
| **GHCR** | **Free** (currently, for containers) | Free; Actions pulls with `GITHUB_TOKEN` don't count | none documented | Manual delete + 30-day restore; **no auto-retention** | Same auth as the repo; zero extra accounts |
| **Docker Hub** | Personal (free): **1 private repo**, **100 pulls/hour**. Pro $11/mo (or $9/mo annual): unlimited private repos + unlimited pulls, "subject to fair use". Team $16/user/mo, Business $24/user/mo ([Docker pricing](https://www.docker.com/pricing/), checked 2026-07-31) | fair-use | **100 pulls/hour on the free plan** — this also affects *pulling* `postgres:latest` from CI on an unauthenticated runner | Manual | The free tier's single private repo means app + Keycloak images would not both fit privately |
| **AWS ECR (private)** | **$0.10/GB-month** (`eu-west-1`, Price List API `AmazonECR`; matches [ECR pricing](https://aws.amazon.com/ecr/pricing/)). Archive tier $0.10/GB-mo (first 150 TB) and $0.03/GB retrieval | tiered egress, aggregated with other AWS egress | none | **Yes — lifecycle policies** (native age/count-based expiry) | Free tier: **500 MB/month for one year** for new customers |
| **AWS ECR Public** | **50 GB/month always free** for public repos | 500 GB/month free to anonymous users, **5 TB/month** free to AWS-account holders, **unlimited free to AWS compute in any region** | none | lifecycle policies | Only for images you're happy to make public |
| **Self-hosted** (`registry:2`, Harbor, Zot) | disk on the home server | your uplink | none | Harbor/Zot have retention policies; plain `registry:2` needs a GC cron | Adds a service to operate — cuts against the "reduce operational burden" goal; and a home registry behind NAT is only reachable by things already inside (§5) |

Sizing for this repo: a Spring Boot JAR image on `eclipse-temurin:25-jre` is on the order of **250–400 MB** — `[unverified]`. At ECR's $0.10/GB-month, keeping 10 tags ≈ 3 GB ≈ **$0.30/month**. Registry storage cost is not a deciding factor at this scale; **pull rate limits and retention tooling are** the real differences.

One cross-cutting note for §2: the test suite pulls `postgres:latest` and `quay.io/keycloak/keycloak:26.0` from Docker Hub and quay.io on every cold CI run. Docker Hub's **100 pulls/hour** unauthenticated limit is shared per source IP, and GitHub-hosted runners share IPs. This is a known cause of flaky CI; authenticating the runner to Docker Hub (with a free account) or mirroring the images raises the ceiling. No primary source quantifies how often GitHub runners hit it — `[unverified]`.

---

## 8. Secrets

### How Actions secrets and variables work

**Scopes** ([Using secrets in GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), checked 2026-07-31):
- **Repository** secrets — available to all workflows in the repo; creating them requires write access.
- **Environment** secrets — scoped to a named deployment environment, and environments can carry **required reviewers** and **branch restrictions**. This is the mechanism that makes a deploy step gate-able.
- **Organization** secrets — shared across repos with an access policy; *"only available to private repositories for GitHub Free"*. **Not applicable here**: `stainii/task` is under a personal account.
- **Variables** are the non-sensitive counterpart: same scoping, but stored and displayed in plaintext and **not masked** in logs.
- Size: secrets larger than **48 KB** need a workaround (encrypt the payload, commit the ciphertext, store only the passphrase as a secret) — and GitHub notes it **does not mask** values handled through that workaround.

**Masking, and its limits** ([Security hardening for GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use), checked 2026-07-31). Secrets are redacted only when the runner can match **the exact secret value**. What is therefore **not** protected:
- **Transformed values** — base64, URL-encoded or otherwise re-encoded forms are *not* redacted unless registered separately.
- **Derived values** — e.g. a signed JWT minted from a secret key must be explicitly registered with `::add-mask::`.
- **Structured data** — secrets embedded in JSON/XML/YAML *"often fails to redact properly"*.
- Values that reach STDOUT/STDERR inside an error message.
- **People.** *"Users with write repository access can read all configured secrets."* On a solo repo that is one person; on a repo that accepts contributions it is a real boundary.
- Fork PRs: *"With the exception of `GITHUB_TOKEN`, secrets are not passed to the runner when a workflow is triggered from a forked repository."* Good news for a public repo — but `pull_request_target` deliberately reverses this and is the classic foot-gun.
- Once a secret leaks into a log, redaction is not retroactive: **delete the log and rotate**.

**OIDC instead of long-lived cloud keys.** GitHub's guidance: *"If your GitHub Actions workflows need to access resources from a cloud provider that supports OpenID Connect (OIDC), you can configure your workflows to authenticate directly to the cloud provider. This will let you stop storing these credentials as long-lived secrets."* For AWS ([Configuring OIDC in AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), checked 2026-07-31) you create (1) an IAM OIDC identity provider for `https://token.actions.githubusercontent.com` with audience `sts.amazonaws.com`, and (2) an IAM role whose trust policy conditions on `token.actions.githubusercontent.com:aud` and `token.actions.githubusercontent.com:sub` (e.g. `repo:stainii/task:ref:refs/heads/main`). The workflow needs `permissions: id-token: write` and uses `aws-actions/configure-aws-credentials` to exchange the JWT for short-lived credentials. **No `AWS_ACCESS_KEY_ID` is ever stored.** Pinning the `sub` claim to a branch or environment is what stops a fork or a feature branch from assuming the role.

### The runtime secrets this repo actually has

Read from `task-back-end/compose.yaml`, `task-back-end/src/main/resources/application.yml` and `task-back-end/http-requests/http-client.env.json` (the last is gitignored; only its **key structure** is reproduced here, never values).

**Nothing in this repo is parameterised today.** There is not a single `${ENV_VAR}` placeholder in `compose.yaml` or `application.yml` — every credential is a hard-coded development literal:

| Where | Key | Current state |
| --- | --- | --- |
| `compose.yaml` → `postgres` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | hard-coded dev values (`mydatabase` / `myuser` / a literal password), committed |
| `compose.yaml` → `keycloak` | `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | hard-coded `admin` / `admin`, committed |
| `compose.yaml` → `keycloak` | realm import from `./compose/keycloak/realm-export.json`, `start-dev --import-realm` | committed; **`start-dev` is explicitly a development mode** and the realm export is in git |
| `application.yml` | `spring.datasource.url` / `username` / `password` | hard-coded, pointing at `localhost:5432`, committed |
| `application.yml` | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | hard-coded `http://localhost:8081/realms/portal-realm`, committed |
| `src/test/resources/keycloak/realm-export.json` | test realm `test-realm`, client `test-client`, user `testuser` with a literal password in `AbstractIntegrationTestCases` | committed test fixture — fine, these are throwaway test credentials |
| `http-requests/http-client.env.json` (**gitignored**) | `local.host`; `local.Security.Auth.keycloak.{type, Grant Type, Username, Password, Auth URL, Token URL, Redirect URL, Client ID}` | dev-only IntelliJ HTTP-client credentials, correctly kept out of git |

So the concrete work, whatever CI/CD shape is chosen:

- **`POSTGRES_PASSWORD`** — parameterise `compose.yaml` as `POSTGRES_PASSWORD=${POSTGRES_PASSWORD}` and `application.yml` as `${POSTGRES_PASSWORD}`, then supply it per environment. For CI this is a **non-secret**: Testcontainers generates its own credentials for the ephemeral Postgres, so the CI job needs nothing. For deployment it becomes an **environment secret** (or, on AWS, an SSM Parameter Store `SecureString` / Secrets Manager secret that the task role reads at boot — which keeps it out of GitHub entirely).
- **Keycloak admin credentials** — same treatment. `admin`/`admin` with `start-dev` must not follow the app to any deployed environment; a real deployment needs `start` (production mode), a generated bootstrap admin password, and TLS. The tests do not need these at all (the Testcontainers Keycloak generates its own).
- **Keycloak *client* credentials** — the back-end is a **resource server** (`spring-boot-starter-security-oauth2-resource-server`), so it validates JWTs against `issuer-uri` and holds **no client secret**. The only client credentials in play are the *test* client (`test-client`, public, password grant, in the committed test realm) and the *developer's* HTTP-client credentials. Neither is a CI secret. If a confidential client is ever added, that secret is an environment secret / SSM parameter.
- **`http-client.env.json`** — this is a **developer-local** file, not a CI input. It is already gitignored and should stay that way. The maintainable pattern is to commit a redacted `http-client.env.json.example` (or use IntelliJ's `http-client.private.env.json` convention, which is gitignored by IntelliJ's own defaults) so a new clone knows which keys to fill in. **No CI workflow should ever need it** — it drives manual `.http` requests, not tests. If some future workflow does smoke-test a deployed environment, it should build the token request from environment secrets rather than resurrecting this file.
- **Deployment credentials**, depending on §5/§6: a registry read token on the home server (pull-based, nothing in GitHub), a Tailscale ephemeral auth key or Cloudflare Access service token (repo/environment secret), an SSH private key (repo/environment secret — the highest-risk option), or **no stored credential at all** if AWS OIDC is used.

A note on scoping: because environment secrets can require a reviewer and can be restricted to `main`, the deploy credential — whichever it is — belongs in an **Environment**, not in repository secrets, so that a workflow on an arbitrary branch cannot reach it.

---

## 9. Renovate

### Hosted GitHub App vs self-hosted

| | Mend-hosted app | Self-hosted in Actions |
| --- | --- | --- |
| Cost | **Free.** Mend Renovate Community Cloud is *"available for all across an unlimited number of public and private repositories"* ([Mend-hosted overview](https://docs.renovatebot.com/mend-hosted/overview/), checked 2026-07-31). Enterprise Cloud is paid (contact sales). | **Free apart from the Actions minutes it burns.** A Renovate run is typically 1–5 min per execution `[unverified]`; on a schedule of every 4 h that is ~180 runs/month → **180–900 min/month**, i.e. **9–45% of a Free plan's 2,000 minutes**, before any of the CI those PRs then trigger. |
| Resource limits | Community: **1 concurrent job per organization**, job scheduling **every 4 hours**, 1 vCPU / 3 GB / 15 GB disk, **30-minute job timeout**. Enterprise: 16 concurrent, hourly, 2 vCPU / 8 GB / 40 GB, 60-minute timeout. | Whatever the runner gives you (2 vCPU / 8 GB private, §1); you choose the cron. |
| Setup | Install [github.com/apps/renovate](https://github.com/apps/renovate), pick all/selected repos. Renovate opens an onboarding PR and *"will not make any changes to your repository or raise any further Pull Requests until after you merge the onboarding Pull Request"* ([Installing & onboarding](https://docs.renovatebot.com/getting-started/installing-onboarding/), checked 2026-07-31). | A workflow using [`renovatebot/github-action`](https://github.com/renovatebot/github-action) on a cron. |
| Token | Managed by Mend. | A **classic PAT with `repo` scope** (recommended default), or a GitHub App token via `actions/create-github-app-token`. Using the built-in `GITHUB_TOKEN` means *"workflows run on RenovateBot's PRs will need to be manually approved"* and you must enable "Allow GitHub Actions to create and approve pull requests". **If you want Renovate to update workflow files, the token also needs the `workflow` scope**, otherwise GitHub blocks those PRs. |
| Operational burden | none | a workflow, a token to rotate, and minutes to watch |

### Interaction with CI gates

- **PR volume knobs** (defaults read from [`lib/config/options/index.ts`](https://raw.githubusercontent.com/renovatebot/renovate/main/lib/config/options/index.ts), checked 2026-07-31): `prConcurrentLimit` **10**, `prHourlyLimit` **2**, `branchConcurrentLimit` **null** (inherits `prConcurrentLimit`), `commitHourlyLimit` **0** (unlimited), `automerge` **false**, `platformAutomerge` **true**, `ignoreTests` **false**, `dependencyDashboard` **false** *as a raw default* but **enabled since v26.0.0 as part of the widely used `config:recommended` preset** ([configuration-options](https://docs.renovatebot.com/configuration-options/)).
- **The CI-cost link is `prHourlyLimit` vs `commitHourlyLimit`.** The docs are explicit: *"`prHourlyLimit` only limits PR creation. Renovate can still rebase existing branches, which triggers additional CI runs"* whereas *"`commitHourlyLimit` limits both branch creation and automatic rebasing, giving you stricter control over CI usage. If you want strict control over CI load, use `commitHourlyLimit`."* With 10 concurrent PRs each rebasing on every merge to `main`, a single busy afternoon can multiply CI runs — at ~8 billable minutes each (§1) that is the main way Renovate could push this repo past its free allowance. Vulnerability-alert PRs **bypass** `branchConcurrentLimit`, `commitHourlyLimit`, `prConcurrentLimit`, `prHourlyLimit` and `schedule`.
- **Automerge and required checks.** `automergeType` defaults to `"pr"` (merge once checks pass); `"branch"` merges the branch silently without a PR unless tests fail or stay pending >24 h; `"pr-comment"` delegates to an external merge bot. The documented hazard: with `automerge: true` **and** `platformAutomerge: true` (the default) you *"must select at least one status check"* in the branch-protection rule — *"If you don't select any status check, and you use platform automerge, then GitHub might automerge PRs with failing tests!"* This composes badly with the **path-filter pitfall from §3**: a required check that is skipped by a path filter sits Pending forever, so Renovate PRs touching only one side of the monorepo would never automerge (or, with no required check selected at all, would automerge unchecked).
- `ignoreTests: true` exists for repos with no CI. **This repo currently has no CI and two red test suites**, so it is worth knowing that the "just automerge everything" path exists and what it costs in safety.
- **Dependency Dashboard**: one issue listing every pending/open/closed/errored update, with manual-rebase checkboxes (manual rebases *"always bypass"* the hourly limit). It is the low-noise way to run Renovate: throttle PR creation hard and drive updates from the dashboard.

### Monorepo with Maven + npm in separate directories

Renovate handles this natively — no per-directory configuration is needed.
- The **maven** manager matches `pom.xml` **including in subdirectories** (`/(^|/|\.)pom\.xml$/`), plus `pom.template.xml`, `settings.xml`, `.mvn/extensions.xml`. It extracts compile/test/runtime/provided/system/import/optional/build/**parent** and parent-root dependencies, and understands Spring Boot's OCI image customisations. Documented limitation: *"Currently maven properties are not supported for buildpack related dependencies."* ([maven manager](https://docs.renovatebot.com/modules/manager/maven/), checked 2026-07-31)
- The **npm** manager discovers `task-front-end/package.json` + `package-lock.json` the same way.
- So `task-back-end/pom.xml` and `task-front-end/package.json` are both picked up automatically. What you *would* configure is grouping and scheduling — e.g. `packageRules` matching `matchManagers: ["maven"]` vs `["npm"]`, or `matchFileNames: ["task-back-end/**"]` — so that a Maven update PR and an npm update PR are separable, and so §3's path filters line up with the PRs Renovate raises.

### Bleeding-edge dependencies (Spring Boot 4 / Java 25 / Angular 21)

This repo is at `spring-boot-starter-parent:4.0.1`, `java.version=25`, `spring-modulith 2.0.1`, `@angular/* ^21.2`, `vitest ^4`, `typescript ~5.9`. Points that matter, flagged as findings rather than advice:

- **The Spring Boot parent version is a `<parent>` coordinate**, which the maven manager explicitly extracts (`parent` / `parent-root` dependency types) — so Renovate *will* propose 4.0.x → 4.1.x parent bumps, which drag every managed dependency version with them. On a stack this new, minor bumps carry real behaviour change; `separateMinorPatch` and a `matchUpdateTypes` rule are the usual controls.
- **`<java.version>25</java.version>` is a plain Maven property**, not a dependency coordinate. Renovate's maven manager updates dependency versions, not arbitrary properties; there is no evidence in the maven manager docs that a bare `java.version` property is tracked — **`[unverified]`**, and worth testing on a branch before assuming either way. The JDK version used *in CI* (`actions/setup-java`'s `java-version`) is a separate question handled by the `github-actions` manager, which needs a token with `workflow` scope (above).
- **Version-pinning styles this repo already uses** (`${mapstruct.version}`, `${pitest-maven.version}`, `${testcontainers-keycloak.version}`, etc., declared in `<properties>` and referenced from `<dependency>`) are the standard, well-supported Maven-manager pattern — those *are* tracked, because the property is reached through a dependency's `<version>`.
- **Angular major upgrades are not mechanical.** Angular ships breaking changes with `ng update` migrations that Renovate does not run; an Angular 21 → 22 PR will typically be a lockfile+manifest change with no migration applied. The usual handling is a `packageRule` on `matchPackagePatterns: ["^@angular/"]` with `groupName: "angular"` and `automerge: false`. No primary source needed for the mechanics — but note this repo has **2 front-end spec files, both currently failing**, so the test suite would not catch an Angular regression anyway.
- **`postgres:latest` and the implicit Keycloak image.** Renovate has a `docker` manager, but this repo's images are referenced from **Java code** (`DockerImageName.parse("postgres:latest")`) and from the `testcontainers-keycloak` library's default — neither is a file Renovate scans. `compose.yaml` **is** scanned (docker-compose manager) and pins `quay.io/keycloak/keycloak:26.1`, so Renovate would update the compose file but not the test code, **widening the existing 26.1-vs-26.0 drift** noted at the top of this document. A `customManagers` regex rule over the test sources would be the way to close that, if closing it is wanted.

---

## Open questions / what could not be verified

1. **Is `stainii/task` public or private?** `gh repo view` on 2026-07-31 reports **PUBLIC**; the brief says private. This flips §1 (free vs metered minutes; 4-vCPU vs 2-vCPU runners) and materially changes §4 (GitHub says self-hosted runners should *"almost never"* be used with public repos). **Everything in §1 is contingent on this.**
2. **Which GitHub plan is the `stainii` account on?** Free (2,000 min) is assumed. Pro (3,000 min) moves the break-even from ~250 to ~375 runs/month. Not verifiable from a primary public source.
3. **Self-hosted runner billing contradiction.** [The 2026 pricing announcement](https://github.com/resources/insights/2026-pricing-changes-for-github-actions) says self-hosted runners in private repos incur $0.002/min from 1 March 2026 and consume plan quota; [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions) still says self-hosted usage is *"free"*. Both checked 2026-07-31. Resolve against an actual billing statement before relying on either.
4. **Minute multipliers.** [Billing and usage](https://docs.github.com/en/actions/concepts/billing-and-usage) still quotes Windows 2× / macOS 10×; the per-minute price table implies 1.67× / 10.3×. The dedicated `actions-minute-multipliers` reference now redirects to the price table. Irrelevant for a Linux-only pipeline, but unresolved.
5. **Actions storage overage rate.** $0.25/GB-month (billing page) vs $0.008/GB-day (elsewhere in GitHub docs) — arithmetically the same, but the canonical figure was not pinned down.
6. **GitHub Packages per-GB overage rates** for non-container registries are not published on the billing concept page; it defers to the pricing calculator.
7. **Cloudflare Zero Trust free-plan limits.** Neither [the plans page](https://www.cloudflare.com/plans/zero-trust-services/) nor [the Cloudflare One docs index](https://developers.cloudflare.com/cloudflare-one/) rendered plan details on 2026-07-31. Whether Cloudflare Tunnel + Access is free at this scale, and for how many seats, is **unverified**.
8. **App Runner's automatic-deployment fee** — the pricing page states a monthly per-application fee exists; the amount did not render. Also the source-build fee amount.
9. **AWS legacy 12-month free tier** (750 h t2/t3.micro, 750 h RDS) — the current [Free Tier page](https://aws.amazon.com/free/) does not mention it. Whether it still applies to pre-existing accounts is unverified.
10. **ALB LCU consumption** for this traffic profile — the $18.40/month is the hourly charge only; LCU charges ($0.008/LCU-hour) depend on connections, new connections/sec, processed bytes and rule evaluations. Assumed near-zero; not modelled.
11. **The 2.5–3× local-to-runner scaling factor** in §1, the **+40 W** build power draw in §4, the **1–3 h setup / 15–30 min per month** runner maintenance estimate in §4, the **250–400 MB** image size in §7, the **1 GB Keycloak floor** in §6, and the **1–5 min Renovate self-hosted run time** in §9 are all reasoned estimates, not sourced figures. Each one is called out inline as `[unverified]`.
12. **Docker Hub pull-limit exposure from GitHub-hosted runners** (§7) — the 100 pulls/hour free-tier limit is sourced, but how often shared runner IPs actually hit it is not.
13. **Whether Renovate updates a bare `<java.version>` Maven property** (§9) — not documented either way in the maven manager docs; needs an empirical test.
14. **Maven build-cache / incremental options** (§3) — no build-cache extension is configured and Maven 4 build-cache applicability to this single-module POM was not verified.

### Two pre-existing conditions any CI plan inherits

- `./mvnw verify` **fails today** at `pitest:mutationCoverage` — *"4 tests did not pass without mutation when calculating line coverage. Mutation testing requires a green suite."* (reproduced 2026-07-31). Any workflow that runs `verify` is red on day one; a workflow that runs `test` is green.
- `ng test` **fails today** — 2/2 tests error with *"Zone is needed for the waitForAsync() test helper but could not be found. Please make sure that your environment includes zone.js"* (reproduced 2026-07-31, Angular 21 + the `@angular/build:unit-test` builder on vitest).

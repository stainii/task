# CI

`.github/workflows/ci.yml`, on GitHub Actions. Built in
[#23](https://github.com/stainii/task/issues/23); what it enforces is
[`quality-bar.md`](quality-bar.md), decided in [#10](https://github.com/stainii/task/issues/10).

It runs on every pull request and every push to `main`. There is nothing else — no nightly job, no
release job, no manual trigger.

---

## 1. The jobs

| Job | What it runs | Measured, run 1 |
|---|---|---|
| `back-end` | `./mvnw verify` in `task-back-end` — tests, `ApplicationModules.verify()`, Error Prone/NullAway | 2m10s |
| `error-prone-canary` | proves Error Prone is actually running (see §3) | 25s |
| `front-end` | `npm ci`, `npm run lint`, `npm run format:check`, `npm test`, `npm run build` in `task-front-end` | 33s |
| `detect-e2e` | decides whether the Playwright job activates | 4s |
| `end-to-end` | Playwright against the real stack — **live since [#64](https://github.com/stainii/task/issues/64)**, see §4 | — |
| `green` | fails unless every job above passed | 3s |

They run in parallel: **2m18s wall clock**, about **7 billable minutes** once GitHub rounds each job
up to the minute. That lands on [#21](https://github.com/stainii/task/issues/21)'s ~8 min/push
estimate, and the repo is public, so it is free either way. Note that roughly half a minute of the
back-end job is the Surefire wait described in §2, not work.

`green` is the aggregate.

**Only `green` is required on `main`**, by the repository ruleset *"main must be green"*. Adding a
job to the workflow therefore never needs the rule to be edited — and `green` checks each job's
*result* explicitly, so a job that was skipped or cancelled can never be mistaken for a job that
passed.

Two things about that ruleset are deliberate:

- **The repository admin bypasses it.** You push straight to `main`; that is how this repo has always
  worked and this ticket did not set out to change it. The rule binds pull requests — including from
  forks, which [#31](https://github.com/stainii/task/issues/31) accepted as a real possibility on a
  public repo. Your own pushes still run CI, and [#24](https://github.com/stainii/task/issues/24)
  will only publish from a green one; the rule is not what keeps a red commit off the box.
- **Force-pushes to `main` are blocked** (`non_fast_forward`), for everyone. ADR-0007 has the box
  `git pull` this branch unattended at night. A rewritten history is the one thing that turns that
  into a failure nobody sees.

---

## 2. Reproducing a CI failure locally

Everything CI runs, you can run. There are no secrets on this path and no CI-only configuration, by
design — see §5.

```bash
sdk env && cd task-back-end && ./mvnw verify
```

```bash
cd task-front-end && nvm use && npm ci && npm run lint && npm run format:check && npm test && npm run build
```

```bash
cd task-front-end && npm run e2e
```

The third needs Docker and browsers (`npx playwright install --with-deps` once); it brings the whole
stack up itself. See `quality-bar.md` §4.

Two differences between your machine and the runner, and both have bitten this project:

- **`~/.mavenrc` beats `sdk env`.** If it sets `JAVA_HOME`, `./mvnw` uses that JDK and the build
  fails with `release version 26 not supported` — while CI, which has no `~/.mavenrc`, is green.
  Run with `MAVEN_SKIP_RC=1`, or make 26 your sdkman default. This cost a session once; it is in
  `task-back-end/README.md` too.
- **`npm ci`, not `npm install`.** CI installs from the lockfile exactly, and validates it against
  `package.json` for *every* platform — including the ones your machine skips. This is not
  theoretical: the very first CI run failed here. The committed lockfile was missing seven
  `@emnapi/*` entries, the wasm fallback bindings for `oxc-parser` and `rolldown`, which npm on
  macOS never installs and therefore never records. `npm ci` on Linux refused the whole tree. Fixed
  by regenerating the lockfile; if it happens again, `npm install --package-lock-only` and commit
  the result — check the diff is purely additive before you do, or you have quietly bumped
  everything.

One line in the back-end log looks like a failure and is not:

```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

A non-daemon thread outlives the last test, so Surefire waits its full 30 seconds before killing the
fork. It is printed at `[ERROR]` level on a build that then reports `BUILD SUCCESS`, and it costs
half a minute of every run, locally and on CI. Do not go looking for a failing test because of it.

To reproduce a specific job's environment rather than a specific failure, read the job's steps in
`.github/workflows/ci.yml` — it is deliberately plain shell, with no composite actions to chase.

### The JDK version is read from `.sdkmanrc`

CI does not hard-code the JDK major; it parses `java=` out of `.sdkmanrc`, which is the same file
`sdk env` reads. Changing the pin changes both at once. This matters more than it looks: the Error
Prone and NullAway versions are pinned as a pair *to a JDK* (`2.41.0` does not run on JDK 26 at
all), so a CI runner quietly on a different JDK is a broken gate, not a slow build.

---

## 3. The canary: Error Prone is proven, not assumed

`error-prone-canary` writes a file into `src/main` that violates `JavaTimeDefaultTimeZone` — a check
`quality-bar.md` promotes to ERROR — and **requires the compile to fail**. A green compile there
means the gate is off, and the job fails.

This exists because of what [#10](https://github.com/stainii/task/issues/10) found: this map stated
for months that the build gates on Error Prone, and Error Prone had never been in the build at all.
`error_prone.version` was dead configuration. Installing it found `JavaPeriodGetDays` on
`RecurringTaskTemplate` — defect **D1**, the bug that stopped every recurring task with a min
interval over 30 days from ever firing. Had the claimed gate been real, D1 could not have been
written.

Two ways the gate can install itself and silently do nothing, **both of which leave the build
green**:

- the `-Xplugin:ErrorProne` argument in `pom.xml` reformatted across lines, so javac cannot resolve
  the plugin;
- the Error Prone / NullAway version pair no longer running on the JDK in use.

Neither is detectable by any ordinary check. The canary catches both, because it does not ask
whether the configuration looks right — it asks whether a violation is rejected.

The canary runs in its own job so the file it writes can never reach the jar `back-end` builds.

If it ever fails, do not delete it. Read `quality-bar.md` §2 first.

---

## 4. End-to-end tests activated themselves

`quality-bar.md` §1 lists Playwright as one of the three things "green" means, and §4 says it lands
with the front-end work.

Rather than leave a gate for someone to remember, `detect-e2e` looks for
`task-front-end/playwright.config.ts`. **The day that file is committed, the `end-to-end` job starts
running** — and `green` stops accepting a skipped result. That day was
[#64](https://github.com/stainii/task/issues/64): the mechanism fired on its own, as designed, and
nothing in the workflow had to be edited to enable it.

What CI provides when it activates:

- Node and browsers (`npx playwright install --with-deps`), a JDK, and a working Docker daemon;
- a built back-end jar, at `$TASK_BACK_END_JAR`.

What CI does **not** do is bring the stack up. That belongs to `npm run e2e`, next to the tests, so
that the same command works on a laptop — `quality-bar.md` §4 expects the suite to be runnable by
hand when you touch a journey, and a stack that only exists inside a workflow file is a stack you
cannot debug.

Before running the suite, CI asserts it matched a non-zero number of tests. A Playwright config
whose `testDir` points at nothing passes silently, which is precisely how
[#32](https://github.com/stainii/task/issues/32)'s pitest run measured its own exclusion for four
months, and why [#10](https://github.com/stainii/task/issues/10) made both fold-fixture suites
assert a count.

---

## 5. What CI deliberately does not do

- **No secrets, ever.** [#22](https://github.com/stainii/task/issues/22)/ADR-0007 put zero secrets
  on the deploy path in both directions, and [#31](https://github.com/stainii/task/issues/31)
  accepted a public repo knowingly. Keeping CI secret-free is also what lets a pull request from a
  fork run the *whole* suite instead of a silently reduced one. A step that needs a secret is a
  design change, not a configuration change.
- **No image build or publish.** ADR-0007's consequences assign the two Dockerfiles, the nginx
  config and the GHCR publish step to [#24](https://github.com/stainii/task/issues/24). #23's
  original body predates that decision.
- **No deploy.** The box pulls; nothing pushes in (ADR-0007). Actions holds no credential to the
  machine.
- **No `paths:` filter.** A front-end-only commit still runs the back-end job. ADR-0007 tags both
  images with the same commit SHA *because* the fold exists in Java and TypeScript and drift between
  them is silent; publishing a pair where only one half was tested would defeat that.
- **No mutation score, no currency gate, no CVE gate.** `quality-bar.md` §7 states why, and states
  it there so they are not quietly reintroduced here.
- **Runs on `main` are never cancelled** by a newer push. A cancelled run is indistinguishable from
  a run that never happened, and [#24](https://github.com/stainii/task/issues/24) publishes from
  this result.

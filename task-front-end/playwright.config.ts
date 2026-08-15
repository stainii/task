import { defineConfig, devices } from '@playwright/test';

/** Where the signed-in session is kept between the two projects. */
export const STORAGE_STATE = 'e2e/.auth/session.json';

/**
 * End-to-end, against the real stack ([#64](https://github.com/stainii/task/issues/64)).
 *
 * **Committing this file changes what CI enforces.** `docs/ci.md` §4 has `detect-e2e` watching for
 * it, and the `end-to-end` job activates itself the moment it exists — `green` stops accepting a
 * skipped result from that commit on. That is deliberate: `docs/quality-bar.md` §1 has listed
 * end-to-end as one of the three things "green" means since #10, and a gate nobody wires up is the
 * fiction that ticket found in Error Prone.
 *
 * **The suite is narrow on purpose.** Playwright is the most expensive test in this repo, so it
 * only carries the scenarios that have no other witness: the shared golden fixtures already prove
 * the fold's *rules* in both languages, and `browserContext.setOffline(true)` is the only mechanism
 * in the whole stack that can prove the *browser* actually queues and replays. Anything a vitest
 * suite could have asserted belongs there instead.
 */
export default defineConfig({
  testDir: './e2e',

  /**
   * One worker, and no parallelism inside a file.
   *
   * The suite drives one shared Postgres, one Keycloak session and one origin's IndexedDB; two
   * tests going offline at once would be testing each other. Cheap to say and expensive to
   * discover.
   */
  workers: 1,
  fullyParallel: false,

  /**
   * **No retries, on CI or off it.**
   *
   * ADR-0007 deploys every green push to `main` with no staging, and every scenario here is about
   * a failure mode that is silent by construction. A retry would turn "the outbox dropped a patch
   * once" into a green build, which is the one report this suite exists to produce.
   */
  retries: 0,

  /** A stray `test.only` must not quietly shrink the suite that gates a deploy. */
  forbidOnly: !!process.env['CI'],

  reporter: process.env['CI'] ? [['github'], ['html', { open: 'never' }]] : [['list']],

  /**
   * Longer than it looks like it needs to be, and for a stated reason.
   *
   * A test here may wait out the outbox's 60-second retry cap twice over — see `SYNCED` in
   * `e2e/app.ts`. A timeout under that budget does not make the suite faster, it makes it report a
   * conforming client as a broken one.
   */
  timeout: 300_000,

  use: {
    baseURL: 'http://localhost:4200',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      // Signing in is a redirect to Keycloak and back, so it is done once and its cookies are
      // reused. It is a project rather than a fixture because the session belongs to the origin,
      // not to a test.
      name: 'sign-in',
      testMatch: /sign-in\.setup\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium',
      dependencies: ['sign-in'],
      use: { ...devices['Desktop Chrome'], storageState: STORAGE_STATE },
    },
  ],

  /**
   * The stack, brought up by the same script a developer runs.
   *
   * `docs/ci.md` §4: CI supplies Node, browsers, a JDK, Docker and a built jar, and nothing else.
   * The URL is checked *through* the front-end origin, so a green readiness check means the proxy,
   * the back end and Postgres are all answering — not merely that a port opened.
   */
  webServer: {
    command: 'node e2e/stack.mjs',
    url: 'http://localhost:4200/api/config',
    timeout: 240_000,
    reuseExistingServer: !process.env['CI'],
    stdout: 'pipe',
    stderr: 'pipe',
  },
});

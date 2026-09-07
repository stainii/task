import { TestBed } from '@angular/core/testing';
import type Keycloak from 'keycloak-js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService, KEYCLOAK } from './auth';
import { ClientConfigService } from './client-config';

/**
 * The two promises this service makes: **`token()` answers**, and **it says which answer it is.**
 *
 * A token or `null`, and always one of the two — because both sync loops await it before they reach
 * the network, so a `token()` that never settles is not a stalled request but a stopped client. It
 * is the failure [#71](https://github.com/stainii/task/issues/71) was, and the reason it took two
 * tickets to find is that it is invisible from every other angle: the outbox is not stalled, the
 * stream is not disconnected, no banner is raised and no request is made. Nothing is wrong; nothing
 * happens.
 *
 * The second promise is [#94](https://github.com/stainii/task/issues/94)'s. *There is no session*
 * and *I could not ask* used to be the same `null`, so both loops sent a request with no bearer and
 * read the `401` they earned as the user's problem — a sign-in bar seconds after a cold start, on a
 * session that was alive the whole time, which a plain reload made go away.
 *
 * `keycloak-js` is behind {@link KEYCLOAK} for the same reason `fetchEventSource` is behind
 * `EVENT_SOURCE`: the interesting states of this class are all states of the library it wraps.
 */
describe('the auth service', () => {
  /** What each `Keycloak` this test hands out will do when it is initialised. */
  let inits: (() => Promise<void>)[];
  let refresh: () => Promise<boolean>;
  /** Whether the silent check finds a session — one entry per `Keycloak`, the last one repeating. */
  let sessions: boolean[];
  let built: number;

  function keycloak(): Keycloak {
    const at = Math.min(built, inits.length - 1);
    const init = inits[at];
    const authenticated = sessions[Math.min(built, sessions.length - 1)];
    built++;
    return {
      init,
      authenticated,
      token: authenticated ? 'a-token' : undefined,
      updateToken: () => refresh(),
    } as unknown as Keycloak;
  }

  function auth(): AuthService {
    return TestBed.inject(AuthService);
  }

  beforeEach(() => {
    vi.useFakeTimers();
    built = 0;
    inits = [() => Promise.resolve()];
    sessions = [true];
    refresh = () => Promise.resolve(true);

    TestBed.configureTestingModule({
      providers: [
        { provide: KEYCLOAK, useValue: keycloak },
        {
          // The real one goes to `/api/config` with `fetch`, which is not what this file is about.
          provide: ClientConfigService,
          useValue: {
            config: () =>
              Promise.resolve({
                keycloak: { url: 'http://localhost/', realm: 'realm', clientId: 'task' },
                buildTime: '2026-08-17T00:00:00Z',
              }),
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('hands out the token of a session it could establish', async () => {
    await expect(auth().token()).resolves.toEqual({ kind: 'token', value: 'a-token' });
  });

  /**
   * **The distinction the whole of [#94](https://github.com/stainii/task/issues/94) turns on.**
   *
   * A silent check that came back saying *no session* is a verdict: the server was reached, it
   * answered, and only the user can change the answer. A silent check that never came back is not
   * a verdict about anything — and answering both with the same `null` is what let a failure of
   * this client's own be reported to the user as *sign in*.
   */
  it('names the check that answered *no session*', async () => {
    sessions = [false];

    await expect(auth().token()).resolves.toEqual({ kind: 'no-session' });
  });

  it('names the check that did not answer at all', async () => {
    inits = [() => Promise.reject(new Error('the silent check failed'))];

    await expect(auth().token()).resolves.toEqual({ kind: 'unknown' });
  });

  /**
   * The bug a reload used to fix, and the reason it did.
   *
   * `keycloak-js` never revisits `authenticated` on its own, so an instance kept after a check that
   * said *no* answers `null` to every later ask for the life of the page. The cause is usually a
   * moment rather than a state — Keycloak restarting behind the proxy on a nightly deploy, a round
   * trip lost on a radio still waking up — and the client is then out of the only silent way back
   * in, with the sign-in bar as its sole exit.
   */
  it('checks again after a check that said no, so a moment does not become the answer', async () => {
    sessions = [false, true];

    await expect(auth().token()).resolves.toEqual({ kind: 'no-session' });

    // The auth server is back, and the SSO cookie was there the whole time. No reload, and nothing
    // for the user to press.
    await expect(auth().token()).resolves.toEqual({ kind: 'token', value: 'a-token' });
    expect(built).toBe(2);
  });

  /**
   * The recurrence in [#71](https://github.com/stainii/task/issues/71), and it is the client's.
   *
   * `check-sso` runs through a hidden iframe and `keycloak-js` waits for that iframe to post back —
   * with **no timeout and no error path**: `#checkSsoSilently` resolves on a message and on nothing
   * else. Lose the radio while it is in flight and the iframe gets an offline document, never posts,
   * and `init()` never settles. `messageReceiveTimeout` does not cover this; it bounds the
   * third-party-cookie probe only.
   *
   * That is a promise this service cannot pass on, because it is the one thing both loops await
   * before every request. So the wait is bounded here, and the answer offline is the ordinary one.
   */
  it('answers offline rather than waiting for ever on a silent check that cannot come back', async () => {
    inits = [() => new Promise<void>(() => undefined)];

    const asked = auth().token();
    await vi.advanceTimersByTimeAsync(AuthService.ANSWER_TIMEOUT_MS);

    await expect(asked).resolves.toEqual({ kind: 'unknown' });
  });

  /**
   * And the bound is *not now*, never a verdict.
   *
   * A remembered failure would be the same permanence one layer up: the usual cause is that the
   * network was not there yet, and this app expects to spend days in that state. The next caller
   * builds a new `Keycloak` and runs the whole initialisation again — which is what makes the radio
   * coming back enough to recover, with no reload and nothing for the user to press.
   */
  it('initialises again on the next ask, so a device that comes back syncs again', async () => {
    inits = [() => new Promise<void>(() => undefined), () => Promise.resolve()];

    const asked = auth().token();
    await vi.advanceTimersByTimeAsync(AuthService.ANSWER_TIMEOUT_MS);
    await expect(asked).resolves.toEqual({ kind: 'unknown' });

    await expect(auth().token()).resolves.toEqual({ kind: 'token', value: 'a-token' });
    expect(built).toBe(2);
  });

  /**
   * The same rule on the other wait, and the reason there is one number rather than two.
   *
   * A refresh is a `fetch` and `fetch` has no timeout, so a link that accepts the connection and
   * then says nothing — a captive portal, a dead middlebox — hangs it exactly as the iframe hangs.
   * No evidence in #71 points here; it is bounded because the invariant is *this service answers*,
   * and an invariant that only holds where somebody has already been bitten is not one.
   */
  it('answers offline rather than waiting for ever on a refresh that cannot come back', async () => {
    refresh = () => new Promise<boolean>(() => undefined);

    const asked = auth().token();
    await vi.advanceTimersByTimeAsync(AuthService.ANSWER_TIMEOUT_MS);

    await expect(asked).resolves.toEqual({ kind: 'unknown' });
  });

  /**
   * A refresh that failed is not a session that ended — and this frame cannot tell them apart,
   * because `keycloak-js` rejects identically for both.
   *
   * So it guesses at neither: the instance goes, and the next ask runs the silent check against the
   * SSO cookie again. A session that is genuinely over comes back as `no-session` from *there*,
   * which is an answer rather than an inference, and one a captive portal cannot fake.
   */
  it('re-checks rather than concluding, when a refresh is what failed', async () => {
    refresh = () => Promise.reject(new Error('the refresh was refused'));

    await expect(auth().token()).resolves.toEqual({ kind: 'unknown' });

    refresh = () => Promise.resolve(true);
    await expect(auth().token()).resolves.toEqual({ kind: 'token', value: 'a-token' });
    expect(built).toBe(2);
  });
});

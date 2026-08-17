import { TestBed } from '@angular/core/testing';
import type Keycloak from 'keycloak-js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService, KEYCLOAK } from './auth';
import { ClientConfigService } from './client-config';

/**
 * The one promise this service makes: **`token()` answers.**
 *
 * A token or `null`, and always one of the two — because both sync loops await it before they reach
 * the network, so a `token()` that never settles is not a stalled request but a stopped client. It
 * is the failure [#71](https://github.com/stainii/task/issues/71) was, and the reason it took two
 * tickets to find is that it is invisible from every other angle: the outbox is not stalled, the
 * stream is not disconnected, no banner is raised and no request is made. Nothing is wrong; nothing
 * happens.
 *
 * `keycloak-js` is behind {@link KEYCLOAK} for the same reason `fetchEventSource` is behind
 * `EVENT_SOURCE`: the interesting states of this class are all states of the library it wraps.
 */
describe('the auth service', () => {
  /** What each `Keycloak` this test hands out will do when it is initialised. */
  let inits: (() => Promise<void>)[];
  let refresh: () => Promise<boolean>;
  let built: number;

  function keycloak(): Keycloak {
    const init = inits[Math.min(built, inits.length - 1)];
    built++;
    return {
      init,
      authenticated: true,
      token: 'a-token',
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
    await expect(auth().token()).resolves.toBe('a-token');
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

    await expect(asked).resolves.toBeNull();
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
    await expect(asked).resolves.toBeNull();

    await expect(auth().token()).resolves.toBe('a-token');
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

    await expect(asked).resolves.toBeNull();
  });
});

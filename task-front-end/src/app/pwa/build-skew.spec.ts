import { TestBed } from '@angular/core/testing';
import { SwUpdate } from '@angular/service-worker';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ClientConfig, ClientConfigService } from '../sync/client-config';
import { BuildSkew } from './build-skew';
import { BUILT_AT } from './build-stamp';

/**
 * **ADR-0009's second banner**, and the word *persistent* in it.
 *
 * A plain build-date mismatch fires every morning: ADR-0007 deploys nightly, and until `ngsw` swaps
 * the cached bundle the front end is legitimately a day behind the server. Daily signal is
 * wallpaper within a week — the exact failure that killed portal's mail (ADR-0012) and the passive
 * grey footer this ADR rejected. So the banner waits until the service worker has *had its chance*
 * and the dates still disagree; then it is rare, and it means a deploy half-landed.
 *
 * The collaborators here are both boundaries — the network, and the browser's update machinery —
 * so both are stubbed and nothing else is.
 */
describe('a persistent build-date mismatch', () => {
  let updateFound: boolean;
  let serviceWorkerEnabled: boolean;
  let serverBuiltAt: string | null;
  let checks: number;

  function configure(frontEndBuiltAt: string | null): BuildSkew {
    TestBed.configureTestingModule({
      providers: [
        { provide: BUILT_AT, useValue: frontEndBuiltAt },
        {
          provide: SwUpdate,
          useValue: {
            get isEnabled() {
              return serviceWorkerEnabled;
            },
            checkForUpdate: () => {
              checks++;
              return Promise.resolve(updateFound);
            },
          },
        },
        {
          provide: ClientConfigService,
          useValue: {
            refresh: (): Promise<ClientConfig> =>
              serverBuiltAt === null
                ? Promise.reject(new Error('GET /api/config answered 503.'))
                : Promise.resolve({
                    keycloak: { url: 'https://auth', realm: 'task', clientId: 'task' },
                    buildTime: serverBuiltAt,
                  }),
          },
        },
      ],
    });
    return TestBed.inject(BuildSkew);
  }

  beforeEach(() => {
    updateFound = false;
    serviceWorkerEnabled = true;
    serverBuiltAt = '2026-08-15T02:14:30.000Z';
    checks = 0;
  });

  it('says nothing on a day the two agree, and reports both dates anyway', async () => {
    const skew = configure('2026-08-15T02:10:00.000Z');

    await skew.check();

    expect(skew.persistentMismatch()).toBe(false);
    // Both remain visible passively on /status, for the question no rule can answer: *have I
    // actually pushed anything this month?*
    expect(skew.frontEndBuiltAt()).toBe('2026-08-15T02:10:00.000Z');
    expect(skew.backEndBuiltAt()).toBe('2026-08-15T02:14:30.000Z');
    expect(checks).toBe(0);
  });

  it('stays quiet while the service worker still has a newer bundle to swap in', async () => {
    // The routine morning after a nightly deploy. This is the whole reason the word *persistent* is
    // in ADR-0009 rather than a plain mismatch.
    updateFound = true;
    const skew = configure('2026-08-14T02:10:00.000Z');

    await skew.check();

    expect(checks).toBe(1);
    expect(skew.persistentMismatch()).toBe(false);
  });

  it('speaks once the worker has had its chance and the dates still disagree', async () => {
    updateFound = false;
    const skew = configure('2026-08-14T02:10:00.000Z');

    await skew.check();

    expect(skew.persistentMismatch()).toBe(true);
  });

  it('speaks straight away where no service worker will ever swap anything', async () => {
    // A tab with the worker unregistered or unsupported. Nothing is coming to fix the skew, so
    // waiting for it would be waiting for ever — silence indistinguishable from health.
    serviceWorkerEnabled = false;
    const skew = configure('2026-08-14T02:10:00.000Z');

    await skew.check();

    expect(checks).toBe(0);
    expect(skew.persistentMismatch()).toBe(true);
  });

  it('reports nothing at all when the server cannot be reached', async () => {
    // Offline is the ordinary state of this app, not a fault, and it is the *other* banner's
    // subject. An unreachable server here must not be read as a stale one.
    serverBuiltAt = null;
    const skew = configure('2026-08-14T02:10:00.000Z');

    await skew.check();

    expect(skew.persistentMismatch()).toBe(false);
    expect(skew.backEndBuiltAt()).toBeNull();
  });

  it('never fires in development, where the bundle carries no date', async () => {
    const skew = configure(null);

    await skew.check();

    expect(skew.persistentMismatch()).toBe(false);
    expect(skew.frontEndBuiltAt()).toBeNull();
  });

  it('takes the banner back down as soon as the dates agree again', async () => {
    const skew = configure('2026-08-14T02:10:00.000Z');
    await skew.check();
    expect(skew.persistentMismatch()).toBe(true);

    // 21:00 UTC, not 23:00: in Brussels that is still the fourteenth, where 23:00 would already be
    // the fifteenth. This assertion had that wrong at first, which is the rule earning its keep.
    serverBuiltAt = '2026-08-14T21:00:00.000Z';
    await skew.check();

    // Latched banners are how a warning outlives the thing it warned about and becomes furniture.
    expect(skew.persistentMismatch()).toBe(false);
  });

  it('does not let a failed check strand the app', async () => {
    // The caller is the shell, which has nothing useful to do about any of this.
    serverBuiltAt = null;
    const skew = configure('2026-08-14T02:10:00.000Z');

    await expect(skew.check()).resolves.toBeUndefined();
  });

  it('survives an update check that throws', async () => {
    const skew = configure('2026-08-14T02:10:00.000Z');
    vi.spyOn(TestBed.inject(SwUpdate), 'checkForUpdate').mockRejectedValue(new Error('no worker'));

    await skew.check();

    // A worker that cannot be asked has not had its chance, so this errs towards silence rather
    // than towards an alarm nobody can act on.
    expect(skew.persistentMismatch()).toBe(false);
  });
});

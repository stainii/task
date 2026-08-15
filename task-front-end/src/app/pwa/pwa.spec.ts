import { describe, expect, it } from 'vitest';

import { SERVICE_WORKER_SCRIPT, serviceWorkerOptions } from './pwa';

describe('service worker registration', () => {
  it('registers ngsw-worker.js', () => {
    expect(SERVICE_WORKER_SCRIPT).toBe('ngsw-worker.js');
  });

  it('is off in development', () => {
    // A service worker in `ng serve` caches the very bundles being rebuilt, so an edit stops
    // appearing and the next hour goes on the wrong problem.
    expect(serviceWorkerOptions(true).enabled).toBe(false);
  });

  it('is on everywhere else', () => {
    expect(serviceWorkerOptions(false).enabled).toBe(true);
  });

  it('never registers before the app is stable', () => {
    // ADR-0004's cold boot renders from IndexedDB before any network. Registering eagerly puts a
    // worker install — a prefetch of the whole shell — in front of the store opening, which is the
    // one thing the first paint actually waits on.
    for (const devMode of [true, false]) {
      expect(serviceWorkerOptions(devMode).registrationStrategy).toMatch(/^registerWhenStable:/);
    }
  });
});

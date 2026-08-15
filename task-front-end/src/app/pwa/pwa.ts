import { SwRegistrationOptions } from '@angular/service-worker';

/**
 * The service worker the Angular CLI emits next to the bundles when `ngsw-config.json` is wired
 * into a build target. It is generated, never written by hand — the file that *is* written by hand
 * is `ngsw-config.json`, and `pwa/ngsw-config.spec.ts` is what guards it.
 */
export const SERVICE_WORKER_SCRIPT = 'ngsw-worker.js';

/**
 * When the worker is allowed to register, and when it is allowed to start
 * ([#62](https://github.com/stainii/task/issues/62)).
 *
 * Both answers are about *not being in the way*. In development a worker caches the very bundles
 * being rebuilt, so an edit silently stops appearing. In production it must not race the first
 * paint: ADR-0004 requires every cold boot after the first to render from IndexedDB with no
 * network, and a worker installing eagerly prefetches the whole shell in front of the store
 * opening. `registerWhenStable` waits for the app to go quiet and then falls back to the timeout,
 * so a page that never stabilises still ends up installed rather than never.
 */
export function serviceWorkerOptions(devMode: boolean): SwRegistrationOptions {
  return {
    enabled: !devMode,
    registrationStrategy: 'registerWhenStable:30000',
  };
}

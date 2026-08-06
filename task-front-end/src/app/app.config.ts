import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';

// Deliberately empty beyond routing. The Keycloak wiring that used to live here was removed
// in #30, because every one of its settings is overruled by a decision already made:
// - `onLoad: 'login-required'` contradicts ADR-0004's "authenticate to sync, not to see"
// - realm `portal-realm` / client `portal-client` contradict #15's CON-006 (a neutral realm
//   with `task` as one client in it)
// - the bearer-token interceptor matched `http://localhost:8080` while the store called the
//   relative `/api/**` through the dev proxy, so no token was ever attached
//   (defect F1 in docs/repo-health.md)
// It is rebuilt, not repaired, as part of the front-end rewrite.
export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideRouter(routes)],
};

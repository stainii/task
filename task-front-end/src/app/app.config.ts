import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { bearerToken } from './sync/bearer-token';

/**
 * Routing, and the one HTTP client — with the bearer token on it and **no retry anywhere**
 * ([#56](https://github.com/stainii/task/issues/56)).
 *
 * There is deliberately **no Keycloak provider here**. `provideKeycloak` initialises the adapter in
 * an `APP_INITIALIZER`, which gates the first paint on the auth server being reachable; ADR-0004
 * requires the opposite — every cold boot after the first renders from IndexedDB with no token and
 * no network. Authentication is therefore lazy and lives in `sync/auth.ts`, and `SyncService` is
 * started *after* the shell has rendered.
 *
 * `withComponentInputBinding()` is what lets `/in/:value` and `/task/:id` arrive as component
 * inputs instead of an `ActivatedRoute` subscription in every page.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([bearerToken])),
  ],
};

import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';

/**
 * Routing only, still. The Keycloak wiring that used to live here was removed in #30 because every
 * one of its settings is overruled by a decision already made; it is rebuilt — not repaired — by
 * [#56](https://github.com/stainii/task/issues/56), together with the outbox and the stream client.
 *
 * `withComponentInputBinding()` is what lets `/in/:value` and `/task/:id` arrive as component
 * inputs instead of an `ActivatedRoute` subscription in every page.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
  ],
};

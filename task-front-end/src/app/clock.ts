import { InjectionToken } from '@angular/core';

/**
 * *Now*, as an injectable — the front-end's counterpart to the back-end's `Clock` bean.
 *
 * Same argument as `config/TimeConfig`: a date-boundary decision taken by calling `new Date()`
 * inline can only be tested by waiting for the calendar. Here a test provides its own and moves it.
 *
 * Injected rather than passed, because unlike the back-end's entities the callers are all services
 * with an injector to hand.
 */
export const NOW = new InjectionToken<() => Date>('now', {
  providedIn: 'root',
  factory: () => () => new Date(),
});

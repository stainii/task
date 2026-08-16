import { InjectionToken } from '@angular/core';

/**
 * *A number in `[0, 1)`*, as an injectable — the sibling of {@link NOW} in `clock.ts`, and there for
 * the same argument.
 *
 * A choice taken by calling `Math.random()` inline can only be tested by drawing many times and
 * hoping. Portal's own spec for the thing this exists to serve asserted that two draws differ, which
 * is a coin flip that lands wrong once in a few hundred runs — a test that fails for no reason is a
 * test that gets deleted. Here a test provides its own and says which word it wants.
 *
 * Injected rather than passed, like `NOW`: the callers are components with an injector to hand.
 */
export const RANDOM = new InjectionToken<() => number>('random', {
  providedIn: 'root',
  factory: () => () => Math.random(),
});

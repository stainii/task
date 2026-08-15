import { InjectionToken } from '@angular/core';

import { today } from '../domain/dates';

/**
 * What the bundle was built by, replaced at build time by esbuild's `define`.
 *
 * Declared and never assigned: in `ng serve` and under vitest nothing replaces it, so the
 * identifier does not exist at all — which is why every read goes through the `typeof` guard below
 * rather than through the constant itself.
 */
declare const TASK_BUILT_AT: string;

/**
 * **When this bundle was built**, as an ISO-8601 instant, or null where nothing stamped it.
 *
 * The counterpart to `GET /api/config`'s `buildTime`, and deliberately the *only* build date the
 * front end reads from itself. The back end's date is read from the back end
 * ([ADR-0009](../../../../docs/adr/0009-the-app-is-its-own-monitor.md)): `ngsw` serves a cached
 * bundle, so a server build date compiled in here would report when *this device's cache* was
 * built, which after a failed deploy is indistinguishable from a successful one.
 *
 * Null is *no information*, never *disagreement* — see {@link buildDatesDiffer}.
 */
export const FRONT_END_BUILT_AT: string | null =
  typeof TASK_BUILT_AT === 'undefined' ? null : TASK_BUILT_AT;

/**
 * The same fact, injectable — the counterpart to `NOW` and there for the same reason.
 *
 * A constant baked in by the bundler can only be exercised by running a production build, so
 * without this the whole of ADR-0009's second banner would be untestable: under vitest nothing
 * stamps the bundle, {@link FRONT_END_BUILT_AT} is permanently null, and every mismatch case would
 * be unreachable. A test provides its own date.
 */
export const BUILT_AT = new InjectionToken<string | null>('front-end built at', {
  providedIn: 'root',
  factory: () => FRONT_END_BUILT_AT,
});

/**
 * Whether the two build dates disagree — **as calendar days, in the reader's own zone.**
 *
 * Days rather than instants because ADR-0007 builds the two images minutes apart in one CI run and
 * tags them with one commit SHA; an instant comparison would fire on every deploy that ever
 * succeeded. Days rather than a threshold because a day is the unit a person judges staleness in,
 * and no number needed tuning to arrive at it.
 *
 * The reader's zone, not UTC, for `dates.ts`'s reason turned around: 23:30 UTC is already tomorrow
 * in Brussels, so a UTC comparison would report skew across a line the reader cannot see.
 *
 * **A mismatch is not yet a banner.** It becomes one only once the service worker has had its
 * chance to swap the bundle and the dates still disagree — see `BuildSkew`.
 */
export function buildDatesDiffer(frontEnd: string | null, backEnd: string | null): boolean {
  if (frontEnd === null || backEnd === null) {
    return false;
  }
  return today(new Date(frontEnd)) !== today(new Date(backEnd));
}

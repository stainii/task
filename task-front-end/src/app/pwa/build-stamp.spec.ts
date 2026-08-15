import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { buildDatesDiffer, FRONT_END_BUILT_AT } from './build-stamp';

/**
 * ADR-0009's second banner, and the stamp it compares against.
 *
 * The interesting half is not the comparison — it is that the stamp is *there*. A front-end build
 * date that silently stops being applied leaves the banner permanently quiet, which is the same
 * failure shape as #10's `-Xplugin` argument and #32's pitest exclusion: a gate that goes on
 * reporting success after it has stopped running.
 */
describe('the front-end build stamp', () => {
  it('is unknown when nothing stamped it', () => {
    // Which is the case here, and in `ng serve`. Unknown must never read as *mismatch*: a
    // development build has no deploy to have stopped happening.
    expect(FRONT_END_BUILT_AT).toBeNull();
  });

  it('is stamped by the production build command', () => {
    const scripts = (
      JSON.parse(readFileSync(resolve(process.cwd(), 'package.json'), 'utf8')) as {
        scripts: Record<string, string>;
      }
    ).scripts;

    // Not a style assertion. Delete the `--define` and every test in this file still passes, the
    // build still succeeds, and the only symptom is that a deploy which stopped happening is never
    // reported again.
    expect(scripts['build']).toContain('--define');
    expect(scripts['build']).toContain('TASK_BUILT_AT');
  });
});

describe('two build dates disagreeing', () => {
  it('does not mind two images built hours apart on one day', () => {
    // ADR-0007 tags both images with the same commit SHA, and CI builds them minutes apart. Days
    // are the unit, not instants; anything finer would fire on every single deploy.
    expect(buildDatesDiffer('2026-08-15T02:10:00.000Z', '2026-08-15T02:14:30.000Z')).toBe(false);
  });

  it('minds a front end from a different day than the server', () => {
    expect(buildDatesDiffer('2026-08-14T02:10:00.000Z', '2026-08-15T02:14:30.000Z')).toBe(true);
  });

  it('says nothing when either date is unknown', () => {
    // Offline the server's date has never been fetched, and in development the bundle has none.
    // Both are *no information*, and an alarm raised on no information is the false positive this
    // whole design is built to avoid.
    expect(buildDatesDiffer(null, '2026-08-15T02:14:30.000Z')).toBe(false);
    expect(buildDatesDiffer('2026-08-15T02:10:00.000Z', null)).toBe(false);
    expect(buildDatesDiffer(null, null)).toBe(false);
  });

  it('compares the days a person would see, in their own zone', () => {
    // 23:30 UTC is already tomorrow in Brussels, which is the zone the suite runs in
    // (`docs/quality-bar.md` §3). Two builds an hour apart across that line are one deploy, and
    // comparing UTC days would announce a skew that does not exist — the mirror of #57's finding
    // that a zone with no daylight saving cannot express the bug it was testing for.
    expect(buildDatesDiffer('2026-08-14T23:30:00.000Z', '2026-08-14T22:30:00.000Z')).toBe(false);
  });
});

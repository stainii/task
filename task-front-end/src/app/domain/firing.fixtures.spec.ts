import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { IsoDate } from './dates';
import { nextFiringDates } from './firing';
import { StoredTrigger } from './template';

/**
 * Runs `/firing-fixtures/` against the TypeScript triggers — **the same files, and the same
 * assertions, as `FiringFixtureTest` on the back end**.
 *
 * *When does this fire next?* exists twice, and the reason it does is this half: the authoring
 * screen lists the next dates under the rule it has just read back as a sentence, because
 * *"every 14 weeks on Saturday"* is unreadable until they are (ADR-0013). The two drifting apart
 * would be silent, and a preview that disagrees with the scheduler is worse than no preview.
 *
 * The third such directory, on `/render-fixtures/`'s contract exactly: adding a file adds a test on
 * both sides with nothing to register.
 */

const FIXTURES = resolve(process.cwd(), '..', 'firing-fixtures');

interface Fixture {
  readonly rule: string;
  /** The flat wire shape, read straight in — no conversion to keep honest. */
  readonly trigger: StoredTrigger;
  readonly activeSince: IsoDate;
  readonly lastClosure: IsoDate | null;
  readonly from: IsoDate;
  readonly count: number;
  readonly expected: readonly IsoDate[];
}

function fixtureFiles(): string[] {
  return readdirSync(FIXTURES)
    .filter((file) => file.endsWith('.json'))
    .sort();
}

function read(file: string): Fixture {
  return JSON.parse(readFileSync(join(FIXTURES, file), 'utf8')) as Fixture;
}

describe('the shared firing fixtures', () => {
  const files = fixtureFiles();

  it('has fixtures to run', () => {
    // A path that silently matches nothing is how #32's pitest run spent four months measuring its
    // own exclusion. A green suite that ran no fixtures is the same failure.
    expect(files.length).toBeGreaterThan(0);
  });

  for (const file of files) {
    const fixture = read(file);

    describe(file, () => {
      it(fixture.rule, () => {
        expect(
          nextFiringDates(fixture.trigger, {
            from: fixture.from,
            activeSince: fixture.activeSince,
            lastClosure: fixture.lastClosure,
            count: fixture.count,
          }),
        ).toEqual(fixture.expected);
      });

      /**
       * Asking for none is asking for none, whatever the trigger — and **a negative count is not an
       * exception**, it is the same nothing.
       *
       * The one answer all three shapes have to agree on, so it is asserted for every fixture
       * rather than as one case — and the two implementations first disagreed exactly here, one
       * throwing where the other returned an empty list. No fixture file states a count below zero,
       * so this is how that edge crosses both suites.
       */
      it.each([0, -1])('lists nothing when %i dates are asked for', (count) => {
        expect(
          nextFiringDates(fixture.trigger, {
            from: fixture.from,
            activeSince: fixture.activeSince,
            lastClosure: fixture.lastClosure,
            count,
          }),
        ).toEqual([]);
      });
    });
  }
});

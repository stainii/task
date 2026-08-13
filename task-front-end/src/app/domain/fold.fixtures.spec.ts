import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { foldOf } from './fold';
import { Task, TaskPatch } from './task';

/**
 * Runs `/fold-fixtures/` against the TypeScript fold — **the same files, and the same assertions,
 * as `FoldFixtureTest` on the back end**. The fold exists twice and the two drifting apart would be
 * silent and would corrupt real data, so the fixtures are the specification and both suites
 * enumerate the same directory. Adding a file adds a test on both sides, with nothing to register.
 */

const FIXTURES = resolve(process.cwd(), '..', 'fold-fixtures');

interface Fixture {
  readonly rule: string;
  readonly taskId: string;
  readonly patches: readonly TaskPatch[];
  readonly expected: Omit<Task, 'history'>;
}

function fixtureFiles(): string[] {
  return readdirSync(FIXTURES)
    .filter((file) => file.endsWith('.json'))
    .sort();
}

function read(file: string): Fixture {
  return JSON.parse(readFileSync(join(FIXTURES, file), 'utf8')) as Fixture;
}

describe('the shared fold fixtures', () => {
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
        const folded = foldOf(fixture.taskId, fixture.patches);

        const { history, ...fields } = folded;
        expect(fields).toEqual(fixture.expected);

        // The folded history holds one entry per distinct patch id. Without this, a duplicate being
        // applied twice is indistinguishable from it being dropped, because applying the same
        // change twice lands on the same value.
        const distinctIds = new Set(fixture.patches.map((patch) => patch.id));
        expect(history).toHaveLength(distinctIds.size);
      });

      /**
       * The same fixture folded from a shuffled history must give the same task. Arrival order is
       * the one thing the fold is not allowed to depend on, and a fixture file can only ever list
       * its patches in *some* order.
       */
      it('folds the same whatever order the patches arrive in', () => {
        const asListed = foldOf(fixture.taskId, fixture.patches);

        const reversed = [...fixture.patches].reverse();
        expect(foldOf(fixture.taskId, reversed)).toEqual(asListed);

        const rotated = [...fixture.patches.slice(-1), ...fixture.patches.slice(0, -1)];
        expect(foldOf(fixture.taskId, rotated)).toEqual(asListed);
      });
    });
  }
});

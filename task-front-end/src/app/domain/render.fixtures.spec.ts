import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { renderTemplate } from './render';
import { Firing, RenderedDefinition, TaskTemplate } from './template';

/**
 * Runs `/render-fixtures/` against the TypeScript renderer — **the same files, and the same
 * assertions, as `RenderFixtureTest` on the back end**.
 *
 * Template rendering exists twice, and the reason it does is this half: the front-end previews what
 * running a template is about to create, and mints the task for *"I already did this"* offline,
 * where no server call is possible (ADR-0011). The two drifting apart would be silent and would put
 * a wrong date on a real task, so the fixtures are the specification and both suites enumerate the
 * same directory. Adding a file adds a test on both sides, with nothing to register.
 */

const FIXTURES = resolve(process.cwd(), '..', 'render-fixtures');

/**
 * The fixture's own shape. `template` is the wire shape verbatim, minus the fields the server owns
 * — which is exactly what the README means by the trigger crossing in its flat form: nothing here
 * converts a fixture into something else before the renderer sees it.
 */
interface Fixture {
  readonly rule: string;
  readonly template: Omit<TaskTemplate, 'id' | 'active'>;
  readonly firing: Firing;
  readonly expected?: {
    readonly context: string;
    readonly definitions: readonly RenderedDefinition[];
  };
  readonly expectedError?: string;
}

function fixtureFiles(): string[] {
  return readdirSync(FIXTURES)
    .filter((file) => file.endsWith('.json'))
    .sort();
}

function read(file: string): Fixture {
  return JSON.parse(readFileSync(join(FIXTURES, file), 'utf8')) as Fixture;
}

function templateOf(fixture: Fixture): TaskTemplate {
  return { ...fixture.template, id: 'fixture-template', active: true };
}

describe('the shared render fixtures', () => {
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
        const template = templateOf(fixture);

        if (fixture.expectedError !== undefined) {
          expect(() => renderTemplate(template, fixture.firing)).toThrowError(
            new RegExp(fixture.expectedError),
          );
          return;
        }

        expect(renderTemplate(template, fixture.firing)).toEqual(fixture.expected);
      });

      /**
       * A refusal produces **no** tasks, not the ones that happened to render before it. That is
       * TODO-022 — portal named the unrenderable one `"No name"` and carried on — and it is the
       * whole point of an `expectedError` fixture, so it is asserted rather than left implied by
       * the throw.
       */
      if (fixture.expectedError !== undefined) {
        it('renders nothing at all', () => {
          expect(fixture.template.taskDefinitions.length).toBeGreaterThan(0);
          expect(() => renderTemplate(templateOf(fixture), fixture.firing)).toThrow();
        });
      }
    });
  }
});

import { describe, expect, it } from 'vitest';

import { foldOf } from './fold';
import { aTask } from './task.mother';
import { aDefinition, aTemplate, manual, minMax } from './template.mother';
import { didItPatches } from './template-completion';
import { TemplateRow } from './templates';

const NOW = new Date('2026-08-14T21:05:00.000Z');
const LAST_TUESDAY = '2026-08-11';

function row(overrides: Partial<TemplateRow> = {}): TemplateRow {
  return {
    template: overrides.template ?? aTemplate(),
    openTask: overrides.openTask ?? null,
    lastCompletedOn: overrides.lastCompletedOn ?? null,
  };
}

/**
 * *"I already did this"* — **one button with two shapes**, resolved locally from tasks the client
 * already holds, so it works offline in both (ADR-0011). There is no endpoint behind it: a server
 * firing endpoint was designed and rejected on exactly that requirement.
 */

describe('when the template already has an open task', () => {
  it('completes that task, with the date that was chosen', () => {
    const openTask = aTask({ id: 'the-bins', taskTemplateId: 'bins' });

    const patches = didItPatches(row({ openTask }), 0, LAST_TUESDAY, NOW);

    expect(patches).toHaveLength(1);
    expect(patches[0].taskId).toBe('the-bins');
    expect(patches[0].changes).toEqual({ status: 'COMPLETED', completedOn: LAST_TUESDAY });
  });
});

describe('when the template is showing nothing', () => {
  const template = aTemplate({
    id: 'bins',
    name: 'Vuilbakken',
    context: 'house',
    trigger: minMax(10, 3),
    taskDefinitions: [
      aDefinition({ name: 'Vuilbakken buitenzetten', importance: 'NOT_SO_IMPORTANT' }),
    ],
  });

  it('mints a task created and completed in the same breath', () => {
    const patches = didItPatches(row({ template }), 0, LAST_TUESDAY, NOW);

    expect(patches).toHaveLength(2);
    expect(patches[0].taskId).toBe(patches[1].taskId);
    expect(patches[0].changes).toMatchObject({
      name: 'Vuilbakken buitenzetten',
      context: 'house',
      importance: 'NOT_SO_IMPORTANT',
      status: 'OPEN',
      taskTemplateId: 'bins',
    });
    expect(patches[1].changes).toEqual({ status: 'COMPLETED', completedOn: LAST_TUESDAY });
  });

  /**
   * The assertion that matters, and the one a shape check cannot make: **the pair folds to a
   * completed task**. The importer's synthesised executions put a second between the two patches
   * for this exact reason — the fold breaks a tie on the patch id as a string, so a completion
   * sharing an instant with its own creation orders by a minted UUID, and half the time the
   * creation's `status: OPEN` is applied last.
   */
  it('folds to a task that is completed, not open', () => {
    const patches = didItPatches(row({ template }), 0, LAST_TUESDAY, NOW);

    const task = foldOf(patches[0].taskId, patches);

    expect(task.status).toBe('COMPLETED');
    expect(task.completedOn).toBe(LAST_TUESDAY);
    expect(task.name).toBe('Vuilbakken buitenzetten');
  });

  /**
   * Two clocks, and neither does the other's job (ADR-0011). The task was *done* last Tuesday and
   * *written* now: backdating the write clock would lose the fold to any later edit from another
   * device, which is the whole argument `completedOn` exists to settle.
   */
  it('dates the task by the day it was done and the patches by now', () => {
    const patches = didItPatches(row({ template }), 0, LAST_TUESDAY, NOW);

    const task = foldOf(patches[0].taskId, patches);
    expect(task.creationDateTime.slice(0, 10)).toBe(LAST_TUESDAY);
    expect(task.startDate).toBe(LAST_TUESDAY);

    expect(patches[0].dateTime).toBe(NOW.toISOString());
    expect(new Date(patches[1].dateTime).getTime()).toBeGreaterThan(NOW.getTime());
  });

  /** The dates come from the render, so the one rendering rule is not half-used here. */
  it('takes the due date the trigger supplies, measured from the day it was done', () => {
    const patches = didItPatches(row({ template }), 0, LAST_TUESDAY, NOW);
    const task = foldOf(patches[0].taskId, patches);

    // A window of three days on a min/max template: created at min, due at max.
    expect(task.dueDate).toBe('2026-08-14');
  });

  /**
   * ADR-0011: the affordance **picks a task, not a template**. Portal's *"What did you do?"*
   * dropdown listed one name each; with several definitions the equivalent is choosing which one
   * was done. Conjuring every definition as completed was rejected — it completes tasks nobody
   * named, which is template-level completion in a different hat.
   */
  it('mints only the definition that was chosen', () => {
    const both = aTemplate({
      trigger: manual(null),
      taskDefinitions: [
        aDefinition({ name: 'Beddengoed wassen' }),
        aDefinition({ name: 'Bed stofzuigen' }),
      ],
    });

    const patches = didItPatches(row({ template: both }), 1, LAST_TUESDAY, NOW);

    expect(patches).toHaveLength(2);
    expect(patches[0].changes).toMatchObject({ name: 'Bed stofzuigen' });
  });
});

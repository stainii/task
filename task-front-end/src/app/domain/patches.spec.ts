import { describe, expect, it } from 'vitest';

import { cancelPatch, completePatch, POSTPONE_PRESETS, postponePatch, undoPatch } from './patches';
import { aTask } from './task.mother';

/**
 * The four writes the overview can make. Every one of them is an ordinary patch on an ordinary
 * field — there is one write verb (ADR-0004), and *complete*, *cancel* and *postpone* are not three
 * endpoints but three sets of changes.
 */

/** 20:30 in Brussels on the 14th, which is still the 14th. */
const NOW = new Date('2026-08-14T18:30:00Z');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('completePatch', () => {
  it('closes the task and records the day the work happened', () => {
    const patch = completePatch(aTask({ id: 'a' }), NOW);

    expect(patch.changes).toEqual({ status: 'COMPLETED', completedOn: '2026-08-14' });
  });

  it('dates the completion on the local calendar, not on UTC', () => {
    // 00:30 Brussels is 22:30 UTC the day before. `completedOn` is *when did I do it* — a domain
    // value, and the answer to that question is the date the person was standing on.
    const patch = completePatch(aTask(), new Date('2026-08-14T22:30:00Z'));

    expect(patch.changes['completedOn']).toBe('2026-08-15');
  });

  it('carries the task, a fresh id, the client’s clock and no sequence', () => {
    const patch = completePatch(aTask({ id: 'a' }), NOW);

    expect(patch.taskId).toBe('a');
    expect(patch.id).toMatch(UUID);
    expect(patch.dateTime).toBe(NOW.toISOString());
    // Assigned by the server on receipt. A client that filled it in would be posing as the server,
    // and the fold may never read it.
    expect(patch.sequence).toBeNull();
    expect(patch.voids).toBeNull();
  });

  it('mints a different id every time, because the id is the idempotency key', () => {
    const task = aTask();

    expect(completePatch(task, NOW).id).not.toBe(completePatch(task, NOW).id);
  });
});

describe('cancelPatch', () => {
  it('closes the task without claiming any of it was done', () => {
    // For years the only way to clear an abandoned task was to press Complete, which wrote a false
    // completion into the history ADR-0011 reads as the min/max clock anchor. So a cancellation
    // must never carry `completedOn`.
    const patch = cancelPatch(aTask(), NOW);

    expect(patch.changes).toEqual({ status: 'CANCELLED' });
  });
});

describe('postponePatch', () => {
  it('pushes the start date and never touches the due date', () => {
    // The app stops asking; it does not stop knowing. A boiler service 62 days overdue is still 62
    // days overdue when it comes back.
    const overdue = aTask({ dueDate: '2026-06-13', startDate: '2026-06-01' });

    const patch = postponePatch(overdue, 3, NOW);

    expect(patch.changes).toEqual({ startDate: '2026-08-17' });
  });

  it('measures the offset from today, not from the start date it is replacing', () => {
    // Postponing is a statement about now, not an increment on a date you have forgotten. A task
    // already sleeping until next month moves to *tomorrow*, not to the month after.
    const sleeping = aTask({ startDate: '2026-09-30' });

    expect(postponePatch(sleeping, 1, NOW).changes).toEqual({ startDate: '2026-08-15' });
  });

  it('offers the three presets the archive supports, and only forwards', () => {
    // Shaped against six years of real pushes rather than guessed: exactly +7 days was used 55
    // times, +2…6 days was used 1,283. `Today` is the un-postpone and lives on the edit dialog —
    // postpone only ever moves forward.
    expect(POSTPONE_PRESETS.map((preset) => [preset.label, preset.days])).toEqual([
      ['Tomorrow', 1],
      ['In 3 days', 3],
      ['Next week', 7],
    ]);
    expect(POSTPONE_PRESETS.every((preset) => preset.days > 0)).toBe(true);
  });
});

describe('undoPatch', () => {
  it('names the patch it voids and changes nothing itself', () => {
    // The fold drops the voided patch's contribution entirely rather than computing a compensating
    // value, so there is nothing for this patch to say.
    const completion = completePatch(aTask({ id: 'a' }), NOW);

    const undo = undoPatch(completion, NOW);

    expect(undo.voids).toBe(completion.id);
    expect(undo.changes).toEqual({});
    expect(undo.taskId).toBe('a');
    expect(undo.id).not.toBe(completion.id);
  });
});

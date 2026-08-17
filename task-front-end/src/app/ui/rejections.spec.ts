import { describe, expect, it } from 'vitest';

import { rejectedChanges } from './rejections';
import { SyncFailure } from '../domain/sync';
import { Task, TaskPatch } from '../domain/task';
import { aTask } from '../domain/task.mother';

/** Thursday, so a weekday name is unambiguous evidence that the patch's own date-time was read. */
const NOW = new Date('2026-08-13T09:00:00+02:00');

function aPatch(overrides: Partial<TaskPatch> & { readonly taskId: string }): TaskPatch {
  return {
    id: 'patch-1',
    dateTime: '2026-08-11T18:00:00+02:00',
    sequence: null,
    voids: null,
    changes: {},
    ...overrides,
  };
}

function refused(patch: TaskPatch, status = 400): SyncFailure {
  return { patchId: patch.id, taskId: patch.taskId, status, at: '2026-08-11T18:00:04+02:00' };
}

function withHistory(task: Task, ...history: TaskPatch[]): Task {
  return { ...task, history };
}

describe('a rejected change', () => {
  it('names the act and the reason, with no status code', () => {
    // ADR-0014's row, verbatim. The code is cut because it is *constant* — a validation refusal is
    // a 400 essentially always — so it would be a fixed phrase in the position the eye reaches
    // first, on a band whose whole job is to say what went wrong *this time*.
    const patch = aPatch({
      taskId: 'a',
      changes: { status: 'COMPLETED', completedOn: '2026-08-11' },
    });
    const task = withHistory(
      aTask({ id: 'a', name: 'Boeken tandarts voor Elise', status: 'COMPLETED' }),
      aPatch({ taskId: 'a', id: 'patch-0', changes: { name: 'Boeken tandarts voor Elise' } }),
      patch,
    );

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.name).toBe('Boeken tandarts voor Elise');
    expect(row.act).toBe('marked complete');
    expect(row.when).toBe('Tuesday');
    expect(row.why).toBe('You completed it on this device, so it has already left your list.');
    expect(JSON.stringify(row)).not.toContain('400');
  });

  it('says a cancellation left the list too', () => {
    const patch = aPatch({ taskId: 'a', changes: { status: 'CANCELLED' } });
    const task = withHistory(
      aTask({ id: 'a', status: 'CANCELLED' }),
      aPatch({ taskId: 'a', id: 'patch-0', changes: { name: 'Call the plumber' } }),
      patch,
    );

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.act).toBe('cancelled');
    expect(row.why).toBe('You cancelled it on this device, so it has already left your list.');
  });

  it('says a refused creation never reached the server at all', () => {
    // The creation patch is `history[0]`, and its refusal is the one case where the *task* is the
    // thing that went missing rather than a change to it.
    const patch = aPatch({
      taskId: 'a',
      changes: { name: 'Offerte dakwerken opvolgen', context: 'house' },
    });
    const task = withHistory(aTask({ id: 'a', name: 'Offerte dakwerken opvolgen' }), patch);

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.act).toBe('created');
    expect(row.why).toBe('It is on this device only — the server never took it.');
  });

  it('calls a start-date change a postponement', () => {
    const created = aPatch({ taskId: 'a', id: 'patch-0', changes: { name: 'Onderhoud ketels' } });
    const patch = aPatch({ taskId: 'a', changes: { startDate: '2026-09-01' } });
    const task = withHistory(aTask({ id: 'a' }), created, patch);

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.act).toBe('postponed');
    expect(row.why).toBe('The change is on this device only — the server still has the old value.');
  });

  it('calls anything else an edit', () => {
    const created = aPatch({ taskId: 'a', id: 'patch-0', changes: { name: 'Old name' } });
    const patch = aPatch({ taskId: 'a', changes: { name: 'New name', importance: 'IMPORTANT' } });
    const task = withHistory(aTask({ id: 'a' }), created, patch);

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.act).toBe('edited');
  });

  it('calls a void patch an undo', () => {
    const created = aPatch({ taskId: 'a', id: 'patch-0', changes: { name: 'Call mum' } });
    const patch = aPatch({ taskId: 'a', voids: 'patch-0' });
    const task = withHistory(aTask({ id: 'a' }), created, patch);

    const [row] = rejectedChanges([refused(patch)], [task], NOW);

    expect(row.act).toBe('undone');
  });
});

describe('when it happened', () => {
  it('names the weekday for something within the last week', () => {
    const patch = aPatch({ taskId: 'a', dateTime: '2026-08-08T11:00:00+02:00' });
    const task = withHistory(aTask({ id: 'a' }), patch);

    expect(rejectedChanges([refused(patch)], [task], NOW)[0].when).toBe('Saturday');
  });

  it('says Today and Yesterday rather than naming their weekdays', () => {
    // A weekday name for today is a fact said in the least useful register available: nobody
    // computes *is Thursday today* faster than they read *Today*.
    const today = aPatch({ taskId: 'a', id: 'p1', dateTime: '2026-08-13T08:00:00+02:00' });
    const yesterday = aPatch({ taskId: 'a', id: 'p2', dateTime: '2026-08-12T08:00:00+02:00' });
    const task = withHistory(aTask({ id: 'a' }), today, yesterday);

    const rows = rejectedChanges([refused(today), refused(yesterday)], [task], NOW);

    expect(rows.map((row) => row.when)).toEqual(['Today', 'Yesterday']);
  });

  it('falls back to a calendar date once a weekday would be ambiguous', () => {
    // Seven days back the weekday names come round again, and *Thursday* would then mean either of
    // two days — which is the same failure `dateLabel` guards the year boundary against.
    const patch = aPatch({ taskId: 'a', dateTime: '2026-08-06T11:00:00+02:00' });
    const task = withHistory(aTask({ id: 'a' }), patch);

    expect(rejectedChanges([refused(patch)], [task], NOW)[0].when).toBe('6 Aug');
  });
});

describe('what reaches the band at all', () => {
  it('leaves out the orphans, which are a client bug rather than lost work', () => {
    // A `404` is a patch for a task the server has never heard of. Worth keeping as evidence — the
    // outbox does — but not worth interrupting a day over, so the band is the `400`s.
    const patch = aPatch({ taskId: 'a', changes: { name: 'Whatever' } });
    const task = withHistory(aTask({ id: 'a' }), patch);

    expect(rejectedChanges([refused(patch, 404)], [task], NOW)).toEqual([]);
  });

  it('leaves out a failure whose task this device no longer holds', () => {
    // Closed tasks are pruned a day after they close, and a failure outliving its task has no name
    // and no act — so there is no row to be made rather than a row with holes in it.
    const patch = aPatch({ taskId: 'gone', changes: { name: 'Whatever' } });

    expect(rejectedChanges([refused(patch)], [], NOW)).toEqual([]);
  });

  it('keeps them oldest first, the order the store answers in', () => {
    const first = aPatch({ taskId: 'a', id: 'p1', dateTime: '2026-08-10T09:00:00+02:00' });
    const second = aPatch({ taskId: 'a', id: 'p2', dateTime: '2026-08-11T09:00:00+02:00' });
    const task = withHistory(aTask({ id: 'a' }), first, second);

    const rows = rejectedChanges([refused(first), refused(second)], [task], NOW);

    expect(rows.map((row) => row.patchId)).toEqual(['p1', 'p2']);
  });
});

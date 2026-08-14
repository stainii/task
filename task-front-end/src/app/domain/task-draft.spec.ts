import { describe, expect, it } from 'vitest';

import { asksAfterItIsDue, changesOf, draftOf, savePatch } from './task-draft';
import { aTask } from './task.mother';

/** 20:30 in Brussels on the 14th, which is still the 14th. */
const NOW = new Date('2026-08-14T18:30:00Z');

/**
 * The dialog's six fields, as a value the form edits and a diff against the task it came from.
 *
 * Separate from the component because this is where the *rules* are — one patch carrying every
 * changed field, nothing for a field left alone — and none of them need a DOM to be true.
 */

describe('changesOf', () => {
  it('says nothing about a draft nobody touched', () => {
    // Not a nicety: an untouched Save that wrote six fields would take last-writer-wins on all of
    // them, so opening a task on a stale device and closing it again would silently revert an edit
    // made elsewhere.
    const task = aTask();

    expect(changesOf(task, draftOf(task))).toEqual({});
  });

  it('carries every field that moved, and only those', () => {
    const task = aTask({ name: 'Ketel ontluchten', context: 'house', importance: 'IMPORTANT' });

    const changes = changesOf(task, {
      ...draftOf(task),
      name: 'Ketel ontluchten en bijvullen',
      importance: 'VERY_IMPORTANT',
    });

    expect(changes).toEqual({
      name: 'Ketel ontluchten en bijvullen',
      importance: 'VERY_IMPORTANT',
    });
  });

  it('clears a due date with a present null, never by dropping it', () => {
    // Absent means *this patch had no opinion*; present-and-null means *clear the field*. Dropping
    // the null would be the fold reading the edit as *leave the due date alone*.
    const task = aTask({ dueDate: '2026-08-30' });

    const changes = changesOf(task, { ...draftOf(task), dueDate: null });

    expect(changes).toEqual({ dueDate: null });
    expect('dueDate' in changes).toBe(true);
  });
});

describe('asksAfterItIsDue', () => {
  it('is true when the task will not be asked about until after it is due', () => {
    // 4,678 of 11,579 real tasks look like this. It is not corruption and it is never rejected: it
    // is the fingerprint of postpone, which pushes the start date and never the due date, so a
    // postponed overdue task is overdue *and* asleep (ADR-0018).
    const task = aTask({ startDate: '2026-08-17', dueDate: '2026-06-30' });

    expect(asksAfterItIsDue(draftOf(task))).toBe(true);
  });

  it.each([
    ['the same day', '2026-06-30', '2026-06-30'],
    ['asked about first', '2026-06-01', '2026-06-30'],
  ])('is false when it is %s', (_case, startDate, dueDate) => {
    // The same day is the boundary and it is not a conflict: being asked on the day it is due is
    // the ordinary case, not the fingerprint.
    expect(asksAfterItIsDue(draftOf(aTask({ startDate, dueDate })))).toBe(false);
  });

  it('is false with no due date at all, which is 8% of tasks', () => {
    expect(asksAfterItIsDue(draftOf(aTask({ startDate: '2026-08-17', dueDate: null })))).toBe(
      false,
    );
  });
});

describe('savePatch', () => {
  it('writes one patch carrying every changed field, not a patch per field', () => {
    // One patch is one outbox entry, one sequence number and one thing to undo — and since the
    // fold is last-writer-wins per field, splitting them would merge identically for twice the
    // traffic (ADR-0018).
    const task = aTask({ id: 'a', name: 'Was ophangen', dueDate: null });

    const patch = savePatch(
      task,
      { ...draftOf(task), name: 'Was ophangen en opvouwen', dueDate: '2026-08-20' },
      NOW,
    );

    expect(patch?.changes).toEqual({ name: 'Was ophangen en opvouwen', dueDate: '2026-08-20' });
    expect(patch?.taskId).toBe('a');
    expect(patch?.dateTime).toBe(NOW.toISOString());
    expect(patch?.sequence).toBeNull();
    expect(patch?.voids).toBeNull();
  });

  it('writes nothing at all when nothing moved', () => {
    // An empty patch is not harmless: it is an outbox entry, a sequence number and a row in the
    // history, all saying that on this date somebody changed nothing.
    const task = aTask();

    expect(savePatch(task, draftOf(task), NOW)).toBeNull();
  });
});

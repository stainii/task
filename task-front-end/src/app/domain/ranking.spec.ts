import { describe, expect, it } from 'vitest';

import { addDays } from './dates';
import { byRank, rankingPoints } from './ranking';
import { aTask } from './task.mother';

/**
 * The ranking is portal's `task.comparator.ts`, adopted whole minus the `expectedDurationInHours`
 * term that died with the field (ADR-0018). The numbers below are read off portal's own source, not
 * recomputed the way the implementation computes them — otherwise the test could never disagree
 * with the code.
 */

const TODAY = '2026-08-14';

describe('rankingPoints', () => {
  // Undated, so there is no overdue bonus and urgency is the flat undated rule: 20 for the two
  // important grades, 0 for the other two. Totals written out rather than assembled, so the test
  // cannot agree with the code by computing it the same way.
  it.each([
    ['I_DO_NOT_REALLY_CARE', 0],
    ['NOT_SO_IMPORTANT', 15],
    ['IMPORTANT', 50],
    ['VERY_IMPORTANT', 70],
  ] as const)('scores an undated %s task at %i', (importance, total) => {
    expect(rankingPoints(aTask({ importance, dueDate: null }), TODAY)).toBe(total);
  });

  it('scores an undated important task exactly as if it were due in 30 days', () => {
    // ADR-0018 quotes portal's comment as literally true: "important tasks are assumed to be urgent
    // enough to be done within the month". 50 − 30 = 20, and undated-important is 20.
    const undated = aTask({ importance: 'IMPORTANT', dueDate: null });
    const inThirtyDays = aTask({ importance: 'IMPORTANT', dueDate: addDays(TODAY, 30) });

    expect(rankingPoints(undated, TODAY)).toBe(rankingPoints(inThirtyDays, TODAY));
  });

  it('gives an undated unimportant task no urgency at all', () => {
    expect(rankingPoints(aTask({ importance: 'NOT_SO_IMPORTANT', dueDate: null }), TODAY)).toBe(15);
  });

  it('scores urgency as 50 minus the days until due', () => {
    const task = aTask({ importance: 'I_DO_NOT_REALLY_CARE', dueDate: addDays(TODAY, 10) });

    expect(rankingPoints(task, TODAY)).toBe(40);
  });

  it('clamps urgency at zero for a date further off than fifty days', () => {
    const task = aTask({ importance: 'I_DO_NOT_REALLY_CARE', dueDate: addDays(TODAY, 120) });

    expect(rankingPoints(task, TODAY)).toBe(0);
  });

  it('scores a task due today at the full fifty, with no overdue bonus', () => {
    const task = aTask({ importance: 'I_DO_NOT_REALLY_CARE', dueDate: TODAY });

    expect(rankingPoints(task, TODAY)).toBe(50);
  });

  // Overdue: flat 50 urgency + importance + the importance-scaled bonus (5 / 10 / 25 / 30).
  // Once eight overdue tasks are on screen, ordering *among* them is the only job left, which is
  // why the bonus survived the port.
  it.each([
    ['I_DO_NOT_REALLY_CARE', 55],
    ['NOT_SO_IMPORTANT', 75],
    ['IMPORTANT', 105],
    ['VERY_IMPORTANT', 130],
  ] as const)('scores an overdue %s task at %i', (importance, total) => {
    expect(rankingPoints(aTask({ importance, dueDate: addDays(TODAY, -3) }), TODAY)).toBe(total);
  });

  it('does not grow with how overdue a task is — overdue is flat', () => {
    const yesterday = aTask({ dueDate: addDays(TODAY, -1) });
    const lastYear = aTask({ dueDate: addDays(TODAY, -365) });

    expect(rankingPoints(lastYear, TODAY)).toBe(rankingPoints(yesterday, TODAY));
  });
});

describe('byRank', () => {
  it('puts the higher score first', () => {
    const overdue = aTask({ name: 'overdue', dueDate: addDays(TODAY, -1) });
    const someday = aTask({ name: 'someday', dueDate: addDays(TODAY, 40) });

    expect([someday, overdue].sort(byRank(TODAY)).map((task) => task.name)).toEqual([
      'overdue',
      'someday',
    ]);
  });

  it('breaks a tie by the earliest creation date', () => {
    const older = aTask({ name: 'older', creationDateTime: '2026-01-01T09:00:00Z' });
    const newer = aTask({ name: 'newer', creationDateTime: '2026-06-01T09:00:00Z' });

    expect([newer, older].sort(byRank(TODAY)).map((task) => task.name)).toEqual(['older', 'newer']);
  });

  it('is a total order — two identical tasks compare equal rather than flipping', () => {
    const one = aTask({ name: 'one', creationDateTime: '2026-01-01T09:00:00Z' });
    const two = aTask({ name: 'two', creationDateTime: '2026-01-01T09:00:00Z' });

    expect(byRank(TODAY)(one, two)).toBe(0);
    expect(byRank(TODAY)(two, one)).toBe(0);
  });
});

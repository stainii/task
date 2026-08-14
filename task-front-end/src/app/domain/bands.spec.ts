import { describe, expect, it } from 'vitest';

import { CAP, visibleWork } from './bands';
import { addDays } from './dates';
import { aTask } from './task.mother';

/**
 * The visible-work rule (ADR-0006, scoped to started tasks by ADR-0015). Its two halves pull
 * against each other on purpose, and most of what is below is about where the line between them
 * falls: **everything due is shown however many there are**, and **the screen never shows more work
 * than a day holds**.
 */

const TODAY = '2026-08-14';

const names = (tasks: readonly { name: string }[]) => tasks.map((task) => task.name);

describe('visibleWork', () => {
  it('shows the day’s work topped up to five', () => {
    const tasks = Array.from({ length: 9 }, (_, index) =>
      aTask({ name: `task ${index}`, dueDate: addDays(TODAY, index + 1) }),
    );

    const work = visibleWork(tasks, TODAY);

    expect(work.visible).toHaveLength(CAP);
    expect(work.also).toHaveLength(4);
  });

  it('shows every due-or-overdue task, however many, and tops up with none', () => {
    const due = Array.from({ length: 8 }, (_, index) =>
      aTask({ name: `due ${index}`, dueDate: index % 2 === 0 ? TODAY : addDays(TODAY, -index) }),
    );
    const later = aTask({ name: 'later', dueDate: addDays(TODAY, 4) });

    const work = visibleWork([...due, later], TODAY);

    expect(work.visible).toHaveLength(8);
    expect(names(work.visible)).not.toContain('later');
    expect(names(work.also)).toEqual(['later']);
  });

  it('tops up by max(0, 5 − dueCount), not to five regardless', () => {
    const due = [
      aTask({ name: 'due a', dueDate: TODAY }),
      aTask({ name: 'due b', dueDate: TODAY }),
    ];
    const rest = Array.from({ length: 6 }, (_, index) =>
      aTask({ name: `rest ${index}`, dueDate: addDays(TODAY, index + 1) }),
    );

    const work = visibleWork([...due, ...rest], TODAY);

    expect(work.visible).toHaveLength(5);
    expect(names(work.visible).filter((name) => name.startsWith('rest'))).toEqual([
      'rest 0',
      'rest 1',
      'rest 2',
    ]);
  });

  it('reports the cap as exceeded only when the due set alone is bigger than it', () => {
    const five = Array.from({ length: 5 }, () => aTask({ dueDate: TODAY }));
    const six = [...five, aTask({ dueDate: TODAY })];

    expect(visibleWork(five, TODAY).capExceeded).toBe(false);
    expect(visibleWork(six, TODAY).capExceeded).toBe(true);
    expect(visibleWork(six, TODAY).dueCount).toBe(6);
  });

  it('treats overdue and due-today as one set, not two rules', () => {
    const overdue = aTask({ name: 'overdue', dueDate: addDays(TODAY, -30) });
    const dueToday = aTask({ name: 'today', dueDate: TODAY });

    const work = visibleWork([overdue, dueToday], TODAY);

    expect(work.dueCount).toBe(2);
  });

  it('holds a task back until its start date arrives, whatever its due date says', () => {
    // The correction ADR-0015 made to ADR-0006: the third band is a **start-date** band. This one
    // is overdue *and* postponed, and both rules cannot win — the author settled it by fixing the
    // rule rather than carving an exception, because with the guarantee absolute, postpone does
    // nothing at all for exactly the tasks you most want to postpone.
    const postponed = aTask({
      name: 'postponed',
      dueDate: addDays(TODAY, -62),
      startDate: addDays(TODAY, 3),
    });

    const work = visibleWork([postponed], TODAY);

    expect(names(work.notStarted)).toEqual(['postponed']);
    expect(work.visible).toHaveLength(0);
    expect(work.dueCount).toBe(0);
  });

  it('counts a task starting today as started', () => {
    const startingToday = aTask({ name: 'today', startDate: TODAY, dueDate: TODAY });

    expect(names(visibleWork([startingToday], TODAY).visible)).toEqual(['today']);
  });

  it('excludes both closed statuses, not only completed', () => {
    // `todo-overview.component.ts:73` filtered `status != "COMPLETED"` only, so for years every
    // cancelled task stayed on the overview for ever, ranked and coloured like live work.
    const tasks = [
      aTask({ name: 'open' }),
      aTask({ name: 'completed', status: 'COMPLETED' }),
      aTask({ name: 'cancelled', status: 'CANCELLED' }),
    ];

    const work = visibleWork(tasks, TODAY);

    expect(names(work.visible)).toEqual(['open']);
    expect(work.also).toHaveLength(0);
    expect(work.notStarted).toHaveLength(0);
  });

  it('orders every band by rank', () => {
    const undatedUnimportant = aTask({ name: 'meh', importance: 'I_DO_NOT_REALLY_CARE' });
    const overdueImportant = aTask({ name: 'late', dueDate: addDays(TODAY, -1) });

    const work = visibleWork([undatedUnimportant, overdueImportant], TODAY);

    expect(names(work.visible)).toEqual(['late', 'meh']);
  });

  it('ranks the whole visible band, rather than pinning the due set above the top-up', () => {
    // ADR-0018: "Band membership trumps the score … the points order what is inside that
    // guarantee, they never override it." Membership says who is on screen; it does not also
    // impose an order. A task due today that nobody cares about scores 50; one due in two days
    // that matters very much scores 98, and it belongs above.
    const dontCare = aTask({ name: 'dull', importance: 'I_DO_NOT_REALLY_CARE', dueDate: TODAY });
    const urgent = aTask({
      name: 'urgent',
      importance: 'VERY_IMPORTANT',
      dueDate: addDays(TODAY, 2),
    });

    const work = visibleWork([dontCare, urgent], TODAY);

    expect(names(work.visible)).toEqual(['urgent', 'dull']);
    // …and the guarantee is untouched: the due task is still on screen, just not first.
    expect(names(work.visible)).toContain('dull');
    expect(work.also).toHaveLength(0);
  });

  it('still selects the top-up by rank, not by due date', () => {
    const due = aTask({ name: 'due', dueDate: TODAY });
    const strong = aTask({
      name: 'strong',
      importance: 'VERY_IMPORTANT',
      dueDate: addDays(TODAY, 1),
    });
    const weak = Array.from({ length: 6 }, (_, index) =>
      aTask({
        name: `weak ${index}`,
        importance: 'I_DO_NOT_REALLY_CARE',
        dueDate: addDays(TODAY, 40 + index),
      }),
    );

    const work = visibleWork([...weak, due, strong], TODAY);

    expect(names(work.visible).slice(0, 2)).toEqual(['strong', 'due']);
    expect(work.visible).toHaveLength(5);
  });

  it('does not scale with anything: five is a day’s work, not screen estate', () => {
    expect(CAP).toBe(5);
  });
});

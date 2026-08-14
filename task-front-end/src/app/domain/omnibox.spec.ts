import { foldOf } from './fold';
import { capturePatch, contextsOf, lastUsedContext, matchingTasks } from './omnibox';
import { aTask } from './task.mother';

/** The date the ranking is measured against, where the test is not about dates. */
const TODAY = '2026-03-10';

describe('matchingTasks', () => {
  it('offers an open task whose name contains what was typed, whatever the case', () => {
    const bed = aTask({ name: 'Beddengoed wassen' });
    const boiler = aTask({ name: 'Onderhoud ketels' });

    expect(matchingTasks([bed, boiler], 'wassen', TODAY)).toEqual([bed]);
    expect(matchingTasks([bed, boiler], 'BEDDENGOED', TODAY)).toEqual([bed]);
  });

  it('never offers a closed task, however well it matches', () => {
    // The store keeps closed tasks for a day so undo works cold, so they are genuinely here to be
    // offered. ADR-0011's amendment refused reaching them through the omnibox in as many words:
    // completing something already completed is not a thing the author wants to be able to do.
    const done = aTask({ name: 'Beddengoed wassen', status: 'COMPLETED' });
    const dropped = aTask({ name: 'Beddengoed strijken', status: 'CANCELLED' });

    expect(matchingTasks([done, dropped], 'bedden', TODAY)).toEqual([]);
  });

  it('offers the most urgent match first, by the same ranking the overview uses', () => {
    // Points, from `ranking.ts`: overdue-and-important is 50 + 30 + 25, due-today-and-important is
    // 50 + 30, and undated-and-important is 20 + 30. So the order is not the order they arrive in,
    // and it is not alphabetical either.
    const overdue = aTask({ name: 'Was ophangen', dueDate: '2026-03-01' });
    const undated = aTask({ name: 'Was sorteren', dueDate: null });
    const dueToday = aTask({ name: 'Was vouwen', dueDate: '2026-03-10' });

    const offered = matchingTasks([undated, dueToday, overdue], 'was', '2026-03-10');

    expect(offered.map((task) => task.name)).toEqual([
      'Was ophangen',
      'Was vouwen',
      'Was sorteren',
    ]);
  });

  it('offers nothing at all until something has been typed', () => {
    // An empty needle is contained in every string, so the natural implementation of the rule above
    // opens the appbar onto the user's entire task list the moment the box is focused.
    expect(matchingTasks([aTask(), aTask()], '', TODAY)).toEqual([]);
    expect(matchingTasks([aTask(), aTask()], '   ', TODAY)).toEqual([]);
  });

  it('offers at most five, and they are the five that matter most', () => {
    // The cap is what makes the ordering load-bearing rather than cosmetic: over six years of
    // history a two-letter query matches hundreds of rows, and whichever ones fall off the end are
    // invisible. Five is ADR-0006's number for the overview's top-up, borrowed so that a screenful
    // means one thing in this app. *Decided by recommendation; no ADR covers the omnibox's length.*
    const many = Array.from({ length: 8 }, (_, index) =>
      aTask({ name: `Was ${index}`, dueDate: `2026-03-${String(index + 10).padStart(2, '0')}` }),
    );

    const offered = matchingTasks(many, 'was', TODAY);

    expect(offered.map((task) => task.name)).toEqual(['Was 0', 'Was 1', 'Was 2', 'Was 3', 'Was 4']);
  });
});

describe('capturePatch', () => {
  it('mints one patch that folds into a whole task, from a name and a context alone', () => {
    // Asserted **through the real fold** rather than against the changes map, because the claim is
    // that this one patch creates a task: the server's door takes the same view, and a creating
    // patch that leaves out a required field is a `400` on the one screen with no form to show it.
    const patch = capturePatch('Ramen lappen', 'house', new Date('2026-03-10T08:30:00Z'));

    expect(foldOf(patch.taskId, [patch])).toMatchObject({
      name: 'Ramen lappen',
      context: 'house',
      creationDateTime: '2026-03-10T08:30:00.000Z',
      // Today, so a captured task is in the day's work rather than behind `Starting in the future…`.
      startDate: '2026-03-10',
      // No due date: a made-up one lies about when the thing was needed (ADR-0018), and would make
      // ADR-0012's 07:30 push announce tasks the author never dated.
      dueDate: null,
      // `IMPORTANT` is the default, which is half of what keeps an undated capture visible at all —
      // `ranking.ts` gives an undated task 20 points only if it is important.
      importance: 'IMPORTANT',
      description: null,
      status: 'OPEN',
      completedOn: null,
      taskTemplateId: null,
      occurrenceId: null,
    });
  });

  it('gives each capture its own task', () => {
    const first = capturePatch('Ramen lappen', 'house', new Date('2026-03-10T08:30:00Z'));
    const second = capturePatch('Ramen lappen', 'house', new Date('2026-03-10T08:30:00Z'));

    expect(first.taskId).not.toBe(second.taskId);
    expect(first.id).not.toBe(second.id);
  });
});

describe('contextsOf', () => {
  it('offers each context once, in a stable order', () => {
    // The chips are the one tap that changes a capture's context before Enter (ADR-0018). They come
    // from the tasks this device already holds rather than from an endpoint, which is what makes
    // them present on a cold boot with the radio off.
    const tasks = [
      aTask({ context: 'house' }),
      aTask({ context: 'social' }),
      aTask({ context: 'house' }),
    ];

    expect(contextsOf(tasks)).toEqual(['house', 'social']);
  });

  it('keeps a context alive while only a closed task carries it', () => {
    // Closed tasks are held for a day, and clearing the last open task in a context must not make
    // the chip you were about to press disappear under your finger.
    expect(contextsOf([aTask({ context: 'garden', status: 'COMPLETED' })])).toEqual(['garden']);
  });
});

describe('lastUsedContext', () => {
  it('is the context of the most recently created task', () => {
    // *The last one you used*, before anything has been captured on this device. Found in the
    // browser: on a device holding four real contexts the first capture was offering an invented
    // `general`, because the only fallback was a literal. The data already answers the question.
    const tasks = [
      aTask({ context: 'house', creationDateTime: '2026-03-01T09:00:00Z' }),
      aTask({ context: 'social', creationDateTime: '2026-03-09T09:00:00Z' }),
      aTask({ context: 'setlist', creationDateTime: '2026-03-05T09:00:00Z' }),
    ];

    expect(lastUsedContext(tasks)).toBe('social');
  });

  it('has no answer on a device holding nothing', () => {
    expect(lastUsedContext([])).toBeNull();
  });
});

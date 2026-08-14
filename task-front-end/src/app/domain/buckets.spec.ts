import { describe, expect, it } from 'vitest';

import { bucketOf } from './buckets';
import { addDays } from './dates';
import { aTask } from './task.mother';

const TODAY = '2026-08-14';

/**
 * The four quadrants behind the stripe down a panel's left edge. Two are about importance and two
 * about proximity — telling `long-game` and `fit-in` apart is the whole reason the stripe exists,
 * so those two cases carry the weight here.
 */
describe('bucketOf', () => {
  it.each(['IMPORTANT', 'VERY_IMPORTANT'] as const)(
    'puts a near, %s task in focus',
    (importance) => {
      expect(bucketOf(aTask({ importance, dueDate: addDays(TODAY, 3) }), TODAY)).toBe('focus');
    },
  );

  it.each(['IMPORTANT', 'VERY_IMPORTANT'] as const)(
    'puts a far-off %s task in long-game',
    (importance) => {
      expect(bucketOf(aTask({ importance, dueDate: addDays(TODAY, 30) }), TODAY)).toBe('long-game');
    },
  );

  it('puts an important task with no due date in long-game, not back-burner', () => {
    expect(bucketOf(aTask({ importance: 'IMPORTANT', dueDate: null }), TODAY)).toBe('long-game');
  });

  it.each(['I_DO_NOT_REALLY_CARE', 'NOT_SO_IMPORTANT'] as const)(
    'puts a near, %s task in fit-in',
    (importance) => {
      expect(bucketOf(aTask({ importance, dueDate: addDays(TODAY, 3) }), TODAY)).toBe('fit-in');
    },
  );

  it.each(['I_DO_NOT_REALLY_CARE', 'NOT_SO_IMPORTANT'] as const)(
    'puts a far-off %s task in back-burner',
    (importance) => {
      expect(bucketOf(aTask({ importance, dueDate: addDays(TODAY, 30) }), TODAY)).toBe(
        'back-burner',
      );
    },
  );

  it('counts an overdue task as near', () => {
    expect(
      bucketOf(aTask({ importance: 'NOT_SO_IMPORTANT', dueDate: addDays(TODAY, -5) }), TODAY),
    ).toBe('fit-in');
  });

  it('draws the near boundary at seven days, inclusive of the sixth and not the seventh', () => {
    const six = aTask({ importance: 'NOT_SO_IMPORTANT', dueDate: addDays(TODAY, 6) });
    const seven = aTask({ importance: 'NOT_SO_IMPORTANT', dueDate: addDays(TODAY, 7) });

    expect(bucketOf(six, TODAY)).toBe('fit-in');
    expect(bucketOf(seven, TODAY)).toBe('back-burner');
  });
});

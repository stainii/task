import { describe, expect, it } from 'vitest';

import { visibleWork } from './bands';
import { contextCards } from './contexts';
import { addDays } from './dates';
import { Task } from './task';
import { aTask } from './task.mother';

const TODAY = '2026-08-14';

/**
 * The card row is where ADR-0015's *started tasks only* rule is at its most surgical: the badge obeys
 * it and nothing else on the card does. So most of these tests are one sleeping task away from each
 * other, and each names which half of the card it is about.
 *
 * **What is on screen is passed in, never derived here.** The cap of five is global at `/` and
 * per-context once you have entered one, and only the screen knows which it is applying — so the
 * tests that are not about the *what comes next* line say `NOTHING`, and the ones that are say
 * exactly what the bands are showing.
 */
const NOTHING: ReadonlySet<string> = new Set();

/** The ids ADR-0006's rule would put on screen for this whole list — the `/` case. */
function shown(tasks: readonly Task[]): ReadonlySet<string> {
  return new Set(visibleWork(tasks, TODAY).visible.map((task) => task.id));
}

describe('the card’s count', () => {
  it('is a true total of everything open in the context, asleep or not', () => {
    const cards = contextCards(
      [
        aTask({ context: 'house', dueDate: TODAY }),
        aTask({ context: 'house', startDate: addDays(TODAY, 10) }),
        aTask({ context: 'house', status: 'COMPLETED' }),
      ],
      TODAY,
      NOTHING,
    );

    expect(cards.map((card) => [card.value, card.count])).toEqual([['house', 2]]);
  });

  it('gives every context a card, in alphabetical order', () => {
    const cards = contextCards(
      [aTask({ context: 'work' }), aTask({ context: 'health' }), aTask({ context: 'house' })],
      TODAY,
      NOTHING,
    );

    expect(cards.map((card) => card.value)).toEqual(['health', 'house', 'work']);
  });

  it('drops a context whose every task is closed', () => {
    const cards = contextCards([aTask({ context: 'house', status: 'CANCELLED' })], TODAY, NOTHING);

    expect(cards).toEqual([]);
  });
});

describe('the card’s badge', () => {
  it('counts overdue work that has started', () => {
    const cards = contextCards(
      [
        aTask({ context: 'house', dueDate: addDays(TODAY, -3) }),
        aTask({ context: 'house', dueDate: addDays(TODAY, -1) }),
      ],
      TODAY,
      NOTHING,
    );

    expect(cards[0].badge).toEqual({ kind: 'overdue', count: 2 });
  });

  it('falls back to what is due today when nothing is overdue', () => {
    const cards = contextCards([aTask({ context: 'house', dueDate: TODAY })], TODAY, NOTHING);

    expect(cards[0].badge).toEqual({ kind: 'today', count: 1 });
  });

  it('says nothing about a sleeping task, however late it is', () => {
    // ADR-0015's reversal, and the whole reason the badge exists as its own rule: a card claiming
    // `1 overdue` over a context you can click into and find nothing overdue in is a card that does
    // not survive being checked. `Onderhoud ketels` is 62 days late and asleep, and the card is
    // silent about it — deliberately, and at the cost of postpone having no mitigation at all.
    const cards = contextCards(
      [
        aTask({
          context: 'house',
          name: 'Onderhoud ketels',
          dueDate: addDays(TODAY, -62),
          startDate: addDays(TODAY, 5),
        }),
      ],
      TODAY,
      NOTHING,
    );

    expect(cards[0].badge).toBeNull();
    // …while still being inside the count, which is the asymmetry the author chose.
    expect(cards[0].count).toBe(1);
  });
});

describe('the card’s colour bar', () => {
  it('draws the importance buckets of the six soonest, sleepers included', () => {
    const cards = contextCards(
      [
        aTask({ context: 'house', dueDate: addDays(TODAY, 1), importance: 'VERY_IMPORTANT' }),
        aTask({
          context: 'house',
          dueDate: addDays(TODAY, 2),
          importance: 'VERY_IMPORTANT',
          startDate: addDays(TODAY, 30),
        }),
        aTask({ context: 'house', dueDate: addDays(TODAY, 30), importance: 'NOT_SO_IMPORTANT' }),
      ],
      TODAY,
      NOTHING,
    );

    expect(cards[0].segments).toEqual(['focus', 'focus', 'back-burner']);
  });

  it('never draws more than six segments', () => {
    const cards = contextCards(
      Array.from({ length: 9 }, (_, index) =>
        aTask({ context: 'house', dueDate: addDays(TODAY, index) }),
      ),
      TODAY,
      NOTHING,
    );

    expect(cards[0].segments).toHaveLength(6);
  });

  it('puts undated work last, because it is the least soon thing there is', () => {
    const cards = contextCards(
      [
        aTask({ context: 'house', importance: 'I_DO_NOT_REALLY_CARE' }),
        aTask({ context: 'house', dueDate: addDays(TODAY, 2), importance: 'VERY_IMPORTANT' }),
      ],
      TODAY,
      NOTHING,
    );

    expect(cards[0].segments).toEqual(['focus', 'back-burner']);
  });
});

describe('what comes next after the visible work', () => {
  const fiveOnScreen = Array.from({ length: 5 }, (_, index) =>
    aTask({ context: 'house', name: `shown ${index}`, dueDate: addDays(TODAY, index) }),
  );

  it('names the soonest task that is not already on screen', () => {
    const tasks = [
      ...fiveOnScreen,
      aTask({ context: 'house', name: 'Renew bike insurance', dueDate: addDays(TODAY, 9) }),
    ];

    expect(contextCards(tasks, TODAY, shown(tasks))[0].next?.name).toBe('Renew bike insurance');
  });

  it('will happily name a sleeper', () => {
    // The badge is the only part of the card scoped to started work. This line describes the
    // context, so it names the genuinely soonest task whether or not it is asleep — and a sleeper
    // is never on screen, so it is always a candidate.
    const tasks = [
      ...fiveOnScreen,
      aTask({
        context: 'house',
        name: 'Onderhoud ketels',
        dueDate: addDays(TODAY, 6),
        startDate: addDays(TODAY, 30),
      }),
    ];

    expect(contextCards(tasks, TODAY, shown(tasks))[0].next?.name).toBe('Onderhoud ketels');
  });

  it('is nothing at all when the visible work is the whole context', () => {
    const tasks = [aTask({ context: 'house', dueDate: TODAY })];

    expect(contextCards(tasks, TODAY, shown(tasks))[0].next).toBeNull();
  });

  it('names a task the global cap folded away, which its own context would have shown', () => {
    // The defect this argument exists to prevent. At `/` the cap of five is spent across every
    // context, so `house`'s only task can be off screen while `house` alone would have shown it —
    // and a card deriving its own visible set would call it *already on screen* and name the one
    // behind it, or nothing at all.
    const health = Array.from({ length: 5 }, (_, index) =>
      aTask({ context: 'health', name: `health ${index}`, dueDate: TODAY }),
    );
    const house = aTask({ context: 'house', name: 'Ramen lappen', dueDate: addDays(TODAY, 3) });
    const tasks = [...health, house];

    const cards = contextCards(tasks, TODAY, shown(tasks));

    expect(cards.find((card) => card.value === 'house')?.next?.name).toBe('Ramen lappen');
  });
});

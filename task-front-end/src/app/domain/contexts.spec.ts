import { describe, expect, it } from 'vitest';

import { addDays } from './dates';
import { contextCards } from './contexts';
import { aTask } from './task.mother';

const TODAY = '2026-08-14';

/**
 * The card row is where ADR-0015's *started tasks only* rule is at its most surgical: the badge obeys
 * it and nothing else on the card does. So most of these tests are one sleeping task away from each
 * other, and each names which half of the card it is about.
 */

describe('the card’s count', () => {
  it('is a true total of everything open in the context, asleep or not', async () => {
    const cards = contextCards(
      [
        aTask({ context: 'house', dueDate: TODAY }),
        aTask({ context: 'house', startDate: addDays(TODAY, 10) }),
        aTask({ context: 'house', status: 'COMPLETED' }),
      ],
      TODAY,
    );

    expect(cards.map((card) => [card.value, card.count])).toEqual([['house', 2]]);
  });

  it('gives every context a card, in alphabetical order', () => {
    const cards = contextCards(
      [aTask({ context: 'work' }), aTask({ context: 'health' }), aTask({ context: 'house' })],
      TODAY,
    );

    expect(cards.map((card) => card.value)).toEqual(['health', 'house', 'work']);
  });

  it('drops a context whose every task is closed', () => {
    const cards = contextCards([aTask({ context: 'house', status: 'CANCELLED' })], TODAY);

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
    );

    expect(cards[0].badge).toEqual({ kind: 'overdue', count: 2 });
  });

  it('falls back to what is due today when nothing is overdue', () => {
    const cards = contextCards([aTask({ context: 'house', dueDate: TODAY })], TODAY);

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
    );

    expect(cards[0].segments).toEqual(['focus', 'focus', 'back-burner']);
  });

  it('never draws more than six segments', () => {
    const cards = contextCards(
      Array.from({ length: 9 }, (_, index) =>
        aTask({ context: 'house', dueDate: addDays(TODAY, index) }),
      ),
      TODAY,
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
    );

    expect(cards[0].segments).toEqual(['focus', 'back-burner']);
  });
});

describe('what comes next after the visible work', () => {
  it('names the soonest task that is not already on screen', () => {
    const cards = contextCards(
      [
        ...Array.from({ length: 5 }, (_, index) =>
          aTask({ context: 'house', name: `shown ${index}`, dueDate: addDays(TODAY, index) }),
        ),
        aTask({ context: 'house', name: 'Renew bike insurance', dueDate: addDays(TODAY, 9) }),
      ],
      TODAY,
    );

    expect(cards[0].next?.name).toBe('Renew bike insurance');
  });

  it('will happily name a sleeper', () => {
    // The badge is the only part of the card scoped to started work. This line describes the
    // context, so it names the genuinely soonest task whether or not it is asleep.
    const cards = contextCards(
      [
        ...Array.from({ length: 5 }, (_, index) =>
          aTask({ context: 'house', name: `shown ${index}`, dueDate: addDays(TODAY, index) }),
        ),
        aTask({
          context: 'house',
          name: 'Onderhoud ketels',
          dueDate: addDays(TODAY, 6),
          startDate: addDays(TODAY, 30),
        }),
      ],
      TODAY,
    );

    expect(cards[0].next?.name).toBe('Onderhoud ketels');
  });

  it('is nothing at all when the visible work is the whole context', () => {
    const cards = contextCards([aTask({ context: 'house', dueDate: TODAY })], TODAY);

    expect(cards[0].next).toBeNull();
  });
});

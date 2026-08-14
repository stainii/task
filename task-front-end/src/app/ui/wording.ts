import { daysUntil, IsoDate } from '../domain/dates';

/**
 * How a date is said on screen.
 *
 * **Facts are words** (ADR-0019), and no word is deleted to save space: pruning was built, driven
 * and rejected by the author on the grounds that it loses too much interesting information — the
 * leading indicator being a templates row where *792 days ago · 7 Jun '24* collapsed to one date,
 * deleting a distinction the author had themselves added. So this reads `62 days overdue`, not
 * `62d`, and it says `no due date` rather than rendering nothing.
 *
 * The ladder coarsens with distance because precision stops being useful: the difference between 41
 * and 42 days is not a difference you can act on, and *in 6 weeks* is the answer to the question
 * actually being asked.
 */
export function dueLabel(dueDate: IsoDate | null, today: IsoDate): string {
  if (dueDate === null) {
    return 'no due date';
  }

  const days = daysUntil(today, dueDate);
  if (days < 0) {
    return plural(-days, 'day') + ' overdue';
  }
  if (days === 0) {
    return 'today';
  }
  if (days === 1) {
    return 'tomorrow';
  }
  if (days < 14) {
    return `in ${plural(days, 'day')}`;
  }
  if (days < 60) {
    return `in ${plural(Math.round(days / 7), 'week')}`;
  }
  return `in ${plural(Math.round(days / 30), 'month')}`;
}

/** Which of the three states a due date is in, for the colour that backs the words up. */
export function dueTone(dueDate: IsoDate | null, today: IsoDate): 'overdue' | 'today' | 'later' {
  if (dueDate === null) {
    return 'later';
  }
  const days = daysUntil(today, dueDate);
  return days < 0 ? 'overdue' : days === 0 ? 'today' : 'later';
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

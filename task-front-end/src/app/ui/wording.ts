import { daysUntil, dueIn, IsoDate } from '../domain/dates';
import { Importance } from '../domain/task';

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
  const days = dueIn(dueDate, today);
  if (days === null) {
    return 'no due date';
  }

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
  const days = dueIn(dueDate, today);
  if (days === null) {
    return 'later';
  }
  return days < 0 ? 'overdue' : days === 0 ? 'today' : 'later';
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * A calendar date, said out loud: `30 Jun`, or `7 Jun '24` once it is a different year.
 *
 * The dialog needs this where the overview does not. On a row only one of the two dates can ever be
 * live — the task is visible, so *ask me from* is by definition already past — but in the dialog
 * both are editable and side by side, which is the narrowing ADR-0019 records against its own floor
 * 1. Naming a bare `30 Jun` on a date two years back would be the same failure as a calendar glyph:
 * it says *a date* without saying **which**.
 *
 * Formatted here rather than through `Intl`, for `dates.ts`'s reason: the input is a `YYYY-MM-DD`
 * with no zone in it, and the round trip through a `Date` is how `2026-03-01` becomes `2026-02-28`.
 */
export function dateLabel(date: IsoDate, today: IsoDate): string {
  const [year, month, day] = date.split('-');
  const said = `${Number(day)} ${MONTHS[Number(month) - 1]}`;
  return year === today.slice(0, 4) ? said : `${said} '${year.slice(2)}`;
}

/**
 * *When was this template last done?* — **as an elapsed count and a calendar date, both**.
 *
 * The two halves answer different questions and neither substitutes for the other, which is the
 * whole of ADR-0014's argument for the templates list existing beside the omnibox: *792 days ago*
 * is arithmetic, and *7 Jun '24* is a memory. A pruning pass that collapsed this row to one date
 * was built, driven and rejected by the author on exactly that — it deleted a distinction they had
 * added themselves, and it is the leading example in this file's own note about not deleting words.
 *
 * The two nearest days are the exception, and it is not pruning: *last 0 days ago · 14 Aug* on the
 * fourteenth is the same fact said twice, and the date has stopped being a memory because it is
 * today.
 */
export function lastDoneLabel(lastCompletedOn: IsoDate | null, today: IsoDate): string {
  if (lastCompletedOn === null) {
    // Said out loud rather than left blank. An empty cell reads as *this row has no data*, where
    // the fact is *this has never been done* — which on the reminding list is the loudest fact there
    // is, and the reason the row sorts to the top.
    return 'never done';
  }

  const days = -daysUntil(today, lastCompletedOn);
  if (days === 0) {
    return 'last done today';
  }
  if (days === 1) {
    return 'last done yesterday';
  }
  return `last ${plural(days, 'day')} ago · ${dateLabel(lastCompletedOn, today)}`;
}

/**
 * The four importance grades, in words a person would say.
 *
 * Facts are words (ADR-0019), and these are the labels portal used. The enum names are the wire
 * shape and belong on the wire: `I_DO_NOT_REALLY_CARE` in a dropdown is a database talking.
 */
export function importanceLabel(importance: Importance): string {
  return IMPORTANCE_LABELS[importance];
}

const IMPORTANCE_LABELS: Record<Importance, string> = {
  I_DO_NOT_REALLY_CARE: 'I don’t really care',
  NOT_SO_IMPORTANT: 'Not so important',
  IMPORTANT: 'Important',
  VERY_IMPORTANT: 'Very important',
};

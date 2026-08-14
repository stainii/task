import { describe, expect, it } from 'vitest';

import { dateLabel } from './wording';

/**
 * Facts are words (ADR-0019). This is the calendar date said out loud, which the dialog needs
 * because *asking from 17 Aug, still due 30 Jun* has to name **which** date it means — the pair
 * ADR-0015 exists to keep apart, side by side and both editable.
 *
 * Tested at its boundaries per `docs/quality-bar.md` §5: the year is the boundary, and a date one
 * day the wrong side of it reads as the wrong year for ever.
 */

/** August 2026, so the year boundary is four and a half months away in each direction. */
const TODAY = '2026-08-14';

describe('dateLabel', () => {
  it('says the day and the month for a date in the current year', () => {
    expect(dateLabel('2026-06-30', TODAY)).toBe('30 Jun');
  });

  it('does not pad the day, because a person does not say “07 Jun”', () => {
    expect(dateLabel('2026-06-07', TODAY)).toBe('7 Jun');
  });

  it('adds the year as soon as it is a different one', () => {
    // Portal's overdue tasks run years back — `Onderhoud ketels` is 62 days overdue on a template
    // last fired in 2024 — and `30 Jun` alone would lie about which June it meant.
    expect(dateLabel('2024-06-07', TODAY)).toBe("7 Jun '24");
  });

  it.each([
    ['the last day of this year', '2026-12-31', '31 Dec'],
    ['the first day of the next', '2027-01-01', "1 Jan '27"],
  ])('gets %s right', (_case, date, expected) => {
    expect(dateLabel(date, TODAY)).toBe(expected);
  });
});

import { describe, expect, it } from 'vitest';

import { builtLabel, dateLabel, lastDoneLabel, syncedLabel } from './wording';

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

/**
 * The templates row's second half, and the reason `wording.ts` refuses to prune: *792 days ago* is
 * arithmetic, *7 Jun '24* is a memory, and the author added the second one themselves. A version
 * that collapsed the two to one date was built, driven and rejected on exactly that.
 */
describe('lastDoneLabel', () => {
  it('says both the elapsed count and the calendar date', () => {
    // ADR-0014's own top row, on real archive data — `Onderhoud ketels`, last done 7 June 2024. The
    // ADR says *792 days*, counted from the day it was written; from this file's `TODAY` the same
    // date is 798 days back. The date is the fixed fact and the count is the arithmetic, which is
    // the distinction this label exists to keep.
    expect(lastDoneLabel('2024-06-07', TODAY)).toBe("last 798 days ago · 7 Jun '24");
  });

  it('says a template has never been done rather than showing nothing', () => {
    expect(lastDoneLabel(null, TODAY)).toBe('never done');
  });

  it.each([
    ['today', TODAY, 'last done today'],
    ['yesterday', '2026-08-13', 'last done yesterday'],
    ['the day before that', '2026-08-12', 'last 2 days ago · 12 Aug'],
  ])('says %s plainly, where a count and a date would repeat themselves', (_case, on, expected) => {
    expect(lastDoneLabel(on, TODAY)).toBe(expected);
  });
});

/**
 * ADR-0009's two facts, said out loud on `/status`.
 *
 * They are the passive half of the design: the banners handle the two conditions that need no
 * threshold, and these answer the question no rule can — *have I actually pushed anything this
 * month?* A grey line that can never alarm is worthless on its own, which is why the banners exist;
 * it is not worthless *beside* them.
 */
describe('the facts on the status screen', () => {
  const NOW_AT = new Date('2026-08-14T09:12:00+02:00');

  it('says the clock time for a sync that happened today', () => {
    // The question today is *how long ago*, and a date would answer it with today's date — true,
    // and no help at all.
    expect(syncedLabel('2026-08-14T08:12:00+02:00', NOW_AT)).toBe('08:12');
  });

  it('says the date for a sync from any other day', () => {
    // And the moment it is not today, the clock time stops mattering and the day is the fact.
    expect(syncedLabel('2026-08-03T22:40:00+02:00', NOW_AT)).toBe('3 Aug');
    expect(syncedLabel('2024-06-07T10:00:00+02:00', NOW_AT)).toBe("7 Jun '24");
  });

  it('says a device has never synced rather than showing nothing', () => {
    // An empty value reads as *this line is broken*; the fact is *this device has never reached the
    // server*, which on this screen is the loudest thing there is.
    expect(syncedLabel(null, NOW_AT)).toBe('never');
  });

  it('says a build date as a date, because a date is self-evidently stale', () => {
    expect(builtLabel('2026-08-14T02:10:00Z', NOW_AT)).toBe('14 Aug');
    expect(builtLabel('2026-07-12T02:10:00Z', NOW_AT)).toBe('12 Jul');
  });

  it('says a build date it does not know rather than inventing one', () => {
    // Offline the server's date has never been fetched, and in development the bundle carries none.
    expect(builtLabel(null, NOW_AT)).toBe('unknown');
  });
});

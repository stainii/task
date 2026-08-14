/**
 * Calendar-day arithmetic on ISO local dates (`YYYY-MM-DD`), the client's half of what `LocalDate`
 * does for free on the back end.
 *
 * **Every prototype in `prototypes/` got this wrong, and ADR-0019 wrote it down**: they all compute
 * `new Date(dt.getTime() + n * 86400000)`, and adding fixed milliseconds across the CET→CEST
 * boundary lands the result at 01:00 the same day — so `due > today` is true for a task due today
 * and it sorts into the wrong band. Cosmetic in a prototype. Here it decides which band a task
 * lands in, which is the whole of the screen.
 *
 * The fix is to do the arithmetic where no boundary exists. A date is carried as its `YYYY-MM-DD`
 * string and computed through `Date.UTC`, which has no daylight saving anywhere in it. The only
 * function that reads the ambient zone at all is {@link today}, and it reads *calendar fields*
 * rather than doing arithmetic on an instant.
 *
 * Dates stay strings for the same reason `domain/task.ts` keeps them that way: a `Date` cannot hold
 * a `LocalDate` without inventing a time zone, and the round trip through one is how `2026-03-01`
 * becomes `2026-02-28`.
 */

/** An ISO local date, `YYYY-MM-DD`. The same shape the wire uses. */
export type IsoDate = string;

const MILLIS_PER_DAY = 24 * 60 * 60 * 1000;

/**
 * The calendar date this clock is standing on, in the browser's own zone.
 *
 * Local fields, never `toISOString()`: at 00:30 in Brussels the UTC date is still yesterday, so a
 * client reading UTC would spend an hour of every night with the bands one day behind.
 */
export function today(now: Date): IsoDate {
  return format(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/** The same date, `days` calendar days later. Negative goes back. */
export function addDays(date: IsoDate, days: number): IsoDate {
  const moved = new Date(utcOf(date) + days * MILLIS_PER_DAY);
  return format(moved.getUTCFullYear(), moved.getUTCMonth() + 1, moved.getUTCDate());
}

/**
 * Whole calendar days from `from` to `to`: 0 when they are the same day, negative when `to` is in
 * the past.
 *
 * This is the comparison the ranking and the bands are built on, so it may not be off by one on the
 * two days a year that are 23 or 25 hours long.
 */
export function daysUntil(from: IsoDate, to: IsoDate): number {
  return (utcOf(to) - utcOf(from)) / MILLIS_PER_DAY;
}

/** Midnight UTC on that calendar date — a fixed point, with no zone in it to shift. */
function utcOf(date: IsoDate): number {
  const [year, month, day] = date.split('-').map(Number);
  return Date.UTC(year, month - 1, day);
}

function format(year: number, month: number, day: number): IsoDate {
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

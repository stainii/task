import { addDays, daysUntil, IsoDate } from './dates';
import { Ordinal, StoredTrigger, Weekday, WEEKDAYS } from './template';

/**
 * *When does this template fire next?* — the TypeScript half of `Trigger#nextFiringDates` and
 * `CalendarRule#firstOccurrenceOnOrAfter`.
 *
 * It exists because the authoring screen lists the next dates under the rule it has just read back
 * as a sentence: *"every 14 weeks on Saturday"* is unreadable until *2026-09-05, 2026-10-03,
 * 2026-11-07* are printed beneath it, which
 * [ADR-0013](../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md) calls the
 * strongest case for the preview existing at all.
 *
 * **A second answer to a question already answered in Java**, so it is pinned the way the other two
 * are: [`/firing-fixtures/`](../../../../firing-fixtures/README.md), enumerated by both suites, on
 * `/render-fixtures/`'s contract. `docs/quality-bar.md` §5 — *a third implementation of anything
 * gets the same treatment*.
 *
 * ### Dates stay strings
 *
 * All of it is `YYYY-MM-DD` arithmetic through `domain/dates.ts`, for that module's reason: a `Date`
 * cannot hold a `LocalDate` without inventing a time zone. **Two ISO dates are compared with `<`**,
 * which is exact — the format is fixed-width and zero-padded, so lexicographic order *is*
 * chronological order, with no instant and no zone in between.
 */

/**
 * What a preview knows: where to look forward from, and how many dates it has room for.
 *
 * Deliberately **not** called a window. `CONTEXT.md` already spends that word on min/max's *interval
 * plus a window*, and a second meaning in the one file that computes min/max dates would be the
 * glossary drift `docs/agents/domain.md` warns about.
 */
export interface Lookahead {
  /** The date to look forward from — *today*, on the authoring screen. */
  readonly from: IsoDate;
  /** *The date this template began firing under its current rule* — the floor and the phase. */
  readonly activeSince: IsoDate;
  /**
   * **The day the most recently closed task was closed**, read by `MIN_MAX` alone.
   *
   * The day it *closed*, not the day it fired
   * ([ADR-0022](../../../../docs/adr/0022-a-min-max-round-starts-when-you-closed-it.md)): a round
   * that starts at the firing date is anchored to a grid the template inherited from its own first
   * firing, so a chore done later than its interval comes back the same day, already overdue.
   */
  readonly lastClosedOn: IsoDate | null;
  readonly count: number;
}

/**
 * The dates this trigger is going to come round on next, at most `count` of them.
 *
 * **The three shapes answer with different lengths, and the difference is the model showing
 * through** rather than an inconsistency:
 *
 * - `MANUAL` lists **nothing** — its dates come from an anchor someone types.
 * - `MIN_MAX` lists **exactly one**, however many are asked for. The round after the next one starts
 *   at a closure that has not happened, so a second date would be a guess dressed as a schedule —
 *   and drawing it as a schedule would draw it as the calendar it deliberately is not (ADR-0001).
 * - `CALENDAR` lists **`count`**, because a rule enumerates its own firings with nobody typing
 *   anything.
 *
 * `MIN_MAX`'s one date **may lie before `from`**, and that is the truth being shown: a template past
 * its round is already due. A calendar rule's dates never do.
 */
export function nextFiringDates(trigger: StoredTrigger, lookahead: Lookahead): IsoDate[] {
  if (lookahead.count <= 0) {
    return [];
  }
  switch (trigger.type) {
    case 'MANUAL':
      return [];
    case 'MIN_MAX': {
      // The same round start the firing itself uses — the later of the day it was closed and
      // `activeSince` — so the preview and the scheduler cannot name different days.
      const closedOn = lookahead.lastClosedOn;
      const roundStarted =
        closedOn === null || closedOn < lookahead.activeSince ? lookahead.activeSince : closedOn;
      return [addDays(roundStarted, present(trigger.minDays, 'minDays'))];
    }
    default:
      return nextOccurrences(trigger, lookahead);
  }
}

/**
 * The one thing that walks, and it walks exactly `count` steps: the enumeration is the preview's, so
 * its length is however many dates a screen shows.
 */
function nextOccurrences(trigger: StoredTrigger, lookahead: Lookahead): IsoDate[] {
  const dates: IsoDate[] = [];
  let cursor = floorOf(lookahead.from, lookahead.activeSince);

  for (let remaining = lookahead.count; remaining > 0; remaining--) {
    const next = firstOccurrenceOnOrAfter(trigger, cursor, lookahead.activeSince);
    dates.push(next);
    cursor = addDays(next, 1);
  }
  return dates;
}

/** The first date this rule names on or after `from`, never earlier than `anchor`. */
function firstOccurrenceOnOrAfter(trigger: StoredTrigger, from: IsoDate, anchor: IsoDate): IsoDate {
  const floor = floorOf(from, anchor);
  const interval = present(trigger.calendarInterval, 'calendarInterval');

  switch (trigger.calendarRule) {
    case 'DAYS': {
      const overshoot = daysUntil(anchor, floor) % interval;
      return overshoot === 0 ? floor : addDays(floor, interval - overshoot);
    }
    case 'WEEKS':
      return firstInQualifyingWeek(floor, anchor, interval, weekdaysOf(trigger));
    case 'NTH_WEEKDAY': {
      // Both refused rather than defaulted, because Java's `NthWeekday` cannot be constructed
      // without them: a missing weekday would resolve to `-1` and quietly name a date a day and a
      // half out, and an invented `FIRST` would name the wrong week of every month for ever.
      const ordinal = present(trigger.calendarOrdinal, 'calendarOrdinal');
      const weekday = present(weekdaysOf(trigger)[0], 'calendarWeekdays');
      return firstInQualifyingMonth(floor, anchor, interval, (year, month) =>
        nthWeekdayOf(year, month, ordinal, weekday),
      );
    }
    default: {
      const dayOfMonth = present(trigger.calendarDayOfMonth, 'calendarDayOfMonth');
      // The day clamps to the end of a short month rather than skipping it (ADR-0013).
      return firstInQualifyingMonth(floor, anchor, interval, (year, month) =>
        dateOf(year, month, Math.min(dayOfMonth, lengthOfMonth(year, month))),
      );
    }
  }
}

/**
 * The weekly stepper.
 *
 * Terminates on its second pass at the latest: the floor may sit in a qualifying week but after the
 * last weekday that week names, and the next qualifying week names all of them. A rule with no
 * weekday would spin here, which is why neither side lets one be saved.
 */
function firstInQualifyingWeek(
  floor: IsoDate,
  anchor: IsoDate,
  interval: number,
  weekdays: readonly Weekday[],
): IsoDate {
  const anchorWeek = mondayOf(anchor);
  const elapsedWeeks = daysUntil(anchorWeek, mondayOf(floor)) / 7;
  const overshoot = elapsedWeeks % interval;

  for (
    let weeks = overshoot === 0 ? elapsedWeeks : elapsedWeeks + (interval - overshoot);
    ;
    weeks += interval
  ) {
    const weekStart = addDays(anchorWeek, weeks * 7);
    const named = weekdays
      // The week starts on Monday, so a weekday is its distance from it.
      .map((weekday) => addDays(weekStart, WEEKDAYS.indexOf(weekday)))
      .filter((date) => date >= floor)
      .sort();
    if (named.length > 0) {
      return named[0];
    }
  }
}

/**
 * The monthly stepper, shared by `MONTHS` and `NTH_WEEKDAY`: find the earliest qualifying month at
 * or after the floor, ask the rule which date it names there, and step forward one interval if that
 * date is already behind the floor. Terminates on its second pass at the latest.
 */
function firstInQualifyingMonth(
  floor: IsoDate,
  anchor: IsoDate,
  interval: number,
  dateInMonth: (year: number, month: number) => IsoDate,
): IsoDate {
  const [anchorYear, anchorMonth] = yearMonthOf(anchor);
  const [floorYear, floorMonth] = yearMonthOf(floor);
  const elapsedMonths = (floorYear - anchorYear) * 12 + (floorMonth - anchorMonth);
  const overshoot = elapsedMonths % interval;

  for (
    let months = overshoot === 0 ? elapsedMonths : elapsedMonths + (interval - overshoot);
    ;
    months += interval
  ) {
    const month = anchorMonth - 1 + months;
    const occurrence = dateInMonth(anchorYear + Math.floor(month / 12), (month % 12) + 1);
    if (occurrence >= floor) {
      return occurrence;
    }
  }
}

/** *The first / second / third / fourth / **last** given weekday* of one month. */
function nthWeekdayOf(year: number, month: number, ordinal: Ordinal, weekday: Weekday): IsoDate {
  const target = WEEKDAYS.indexOf(weekday);

  if (ordinal === 'LAST') {
    const end = dateOf(year, month, lengthOfMonth(year, month));
    return addDays(end, -mod(weekdayIndexOf(end) - target, 7));
  }
  const first = dateOf(year, month, 1);
  const ordinals: readonly Ordinal[] = ['FIRST', 'SECOND', 'THIRD', 'FOURTH'];
  return addDays(first, mod(target - weekdayIndexOf(first), 7) + ordinals.indexOf(ordinal) * 7);
}

/**
 * `WEEKS` names several weekdays and `NTH_WEEKDAY` names one, so both cross as one comma list.
 *
 * **Never empty.** A weekly rule with no weekday names no date, so the stepper below it would spin
 * for ever looking for one — where Java's `Weeks` constructor simply refuses to exist. Refused here
 * for that reason, and it is a stronger reason than the others in this file: the failure is a hang,
 * not a wrong date.
 */
function weekdaysOf(trigger: StoredTrigger): Weekday[] {
  const weekdays = (trigger.calendarWeekdays ?? '')
    .split(',')
    .map((weekday) => weekday.trim())
    .filter((weekday) => weekday !== '') as Weekday[];
  if (weekdays.length === 0) {
    throw new Error('A calendar rule of this kind needs at least one weekday.');
  }
  return weekdays;
}

/**
 * **The anchor is the floor as well as the phase**: a template names no date before it began firing
 * under its current rule (ADR-0017). Said once, for `CalendarRule#floorOf`'s reason — every forward
 * rule needs it, and several spellings of one comparison is several chances to get it backwards.
 */
function floorOf(from: IsoDate, anchor: IsoDate): IsoDate {
  return from < anchor ? anchor : from;
}

/** The Monday of that date's own week, which is where every weekly rule counts from. */
function mondayOf(date: IsoDate): IsoDate {
  return addDays(date, -weekdayIndexOf(date));
}

/** Monday is 0 and Sunday is 6 — `WEEKDAYS`' own order, which is `DayOfWeek`'s. */
function weekdayIndexOf(date: IsoDate): number {
  return mod(new Date(`${date}T00:00:00Z`).getUTCDay() - 1, 7);
}

/** The year and the 1-based month of an ISO date, read as fields rather than through a `Date`. */
function yearMonthOf(date: IsoDate): [number, number] {
  const [year, month] = date.split('-').map(Number);
  return [year, month];
}

function lengthOfMonth(year: number, month: number): number {
  // Day zero of the next month is the last day of this one.
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function dateOf(year: number, month: number, day: number): IsoDate {
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

/** Euclidean, so a negative numerator still lands inside the range. */
function mod(value: number, modulus: number): number {
  return ((value % modulus) + modulus) % modulus;
}

/**
 * A column the discriminator needs, refused rather than defaulted when it is absent.
 *
 * Java's rules refuse the same shapes in their constructors, and matching that is the whole point:
 * a client that quietly invented a default would compute dates from a rule the server would not
 * accept, which is the silent disagreement `/firing-fixtures/` exists to make impossible.
 */
function present<T>(value: T | null | undefined, field: string): T {
  if (value === null || value === undefined) {
    throw new Error(`A trigger of this kind needs ${field}.`);
  }
  return value;
}

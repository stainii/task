package be.stijnhooft.task.backend.template.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/// A **small fixed vocabulary** of four calendar rules — deliberately not RRULE and not cron
/// ([ADR-0013 §166](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
///
/// *Yearly on a date* is **not** one of them: `Months(n × 12, day)` already is it, with the month
/// coming from the anchor like every other rule's phase. The slot it would have taken is spent on
/// [NthWeekday], which buys *every first Saturday* and *every last Friday* — shapes the other three
/// genuinely cannot express. `Weeks(4, [SATURDAY])` drifts off the month, and `Months(1, day)` names
/// a date rather than a weekday.
///
/// ### Phase comes from the anchor
///
/// Every rule counts its interval from `anchor` — the template's `active_since`. So the *same*
/// field is both the floor of the enumeration and its phase, which is why re-ruling a template
/// rewrites it (ADR-0017): a bin template moved from Tuesdays to Thursdays must not look back at
/// its Tuesdays.
///
/// ### No walk
///
/// Each rule computes its latest occurrence directly, at worst stepping back one interval when
/// today falls inside a qualifying period but before the day it names. That is arithmetic, not
/// enumeration — ADR-0017's reason for not needing a lookback window at all.
public sealed interface CalendarRule
        permits CalendarRule.Days, CalendarRule.Weeks, CalendarRule.Months, CalendarRule.NthWeekday {

    /// The latest date this rule names on or before `today`, never earlier than `anchor`.
    /// Empty when the rule has not come round yet.
    Optional<LocalDate> latestOccurrenceOnOrBefore(LocalDate today, LocalDate anchor);

    /// The first date this rule names on or after `from`, never earlier than `anchor`.
    ///
    /// The forward mirror of [#latestOccurrenceOnOrBefore], and the primitive the authoring
    /// preview is built on ([#68](https://github.com/stainii/task/issues/68)). A calendar rule runs
    /// for ever, so there is always one and the answer is a date rather than an `Optional`.
    ///
    /// Like its mirror it is arithmetic and not a walk: each rule computes the answer directly, at
    /// worst stepping *forward* one interval when `from` falls inside a qualifying period but after
    /// the day it names.
    LocalDate firstOccurrenceOnOrAfter(LocalDate from, LocalDate anchor);

    /// The next `count` dates this rule names, in order, starting at the later of `from` and
    /// `anchor` — *2026-09-05, 2026-10-03, 2026-11-07* under *the first Saturday of every month*.
    ///
    /// The one thing that walks, and it walks exactly `count` steps: the enumeration is the
    /// preview's, so its length is however many dates a screen shows.
    ///
    /// **Asking for none, or for fewer than none, lists none** rather than throwing. It has to be
    /// the same answer the TypeScript half gives — an exception on one side and an empty list on
    /// the other is precisely the divergence `/firing-fixtures/` exists to prevent, and both suites
    /// assert it for every fixture.
    default List<LocalDate> nextOccurrencesOnOrAfter(LocalDate from, LocalDate anchor, int count) {
        if (count <= 0) {
            return List.of();
        }
        var dates = new ArrayList<LocalDate>(count);
        var cursor = floorOf(from, anchor);
        for (var remaining = count; remaining > 0; remaining--) {
            var next = firstOccurrenceOnOrAfter(cursor, anchor);
            dates.add(next);
            cursor = next.plusDays(1);
        }
        return List.copyOf(dates);
    }

    /// Every `interval` days, counting from the anchor.
    record Days(int interval) implements CalendarRule {

        public Days {
            requirePositive(interval);
        }

        @Override
        public Optional<LocalDate> latestOccurrenceOnOrBefore(LocalDate today, LocalDate anchor) {
            if (today.isBefore(anchor)) {
                return Optional.empty();
            }
            var elapsed = ChronoUnit.DAYS.between(anchor, today);
            return Optional.of(anchor.plusDays(elapsed - elapsed % interval));
        }

        @Override
        public LocalDate firstOccurrenceOnOrAfter(LocalDate from, LocalDate anchor) {
            var floor = floorOf(from, anchor);
            var overshoot = ChronoUnit.DAYS.between(anchor, floor) % interval;
            return overshoot == 0 ? floor : floor.plusDays(interval - overshoot);
        }
    }

    /// Every `interval` weeks, on the given weekdays — **several in one rule**, so *"Tuesday and
    /// Thursday"* is one template rather than two. Portal could not say this.
    ///
    /// Weeks are counted from the anchor's own week, Monday to Sunday. An **even** interval
    /// preserves ISO week parity, which is what makes *"every odd week"* expressible; a 53-week
    /// ISO year flips it once, a limit ADR-0013 states and accepts.
    record Weeks(int interval, Set<DayOfWeek> weekdays) implements CalendarRule {

        public Weeks {
            requirePositive(interval);
            if (weekdays.isEmpty()) {
                throw new IllegalArgumentException("A weekly rule needs at least one weekday.");
            }
            weekdays = Set.copyOf(weekdays);
        }

        @Override
        public Optional<LocalDate> latestOccurrenceOnOrBefore(LocalDate today, LocalDate anchor) {
            if (today.isBefore(anchor)) {
                return Optional.empty();
            }
            var anchorWeek = mondayOf(anchor);
            var elapsedWeeks = ChronoUnit.WEEKS.between(anchorWeek, mondayOf(today));
            var qualifyingWeeks = elapsedWeeks - elapsedWeeks % interval;

            // At most one step back: today may sit in a qualifying week but before the earliest
            // weekday that week names, in which case the answer is in the previous qualifying week.
            for (var step = 0; step < 2; step++) {
                var weeksBack = qualifyingWeeks - (long) step * interval;
                if (weeksBack < 0) {
                    break;
                }
                var weekStart = anchorWeek.plusWeeks(weeksBack);
                var occurrence = weekdays.stream()
                        .map(weekday -> weekStart.with(TemporalAdjusters.nextOrSame(weekday)))
                        .filter(date -> !date.isAfter(today) && !date.isBefore(anchor))
                        .max(Comparator.naturalOrder());
                if (occurrence.isPresent()) {
                    return occurrence;
                }
            }
            return Optional.empty();
        }

        @Override
        public LocalDate firstOccurrenceOnOrAfter(LocalDate from, LocalDate anchor) {
            var floor = floorOf(from, anchor);
            var anchorWeek = mondayOf(anchor);
            var elapsedWeeks = ChronoUnit.WEEKS.between(anchorWeek, mondayOf(floor));
            var overshoot = elapsedWeeks % interval;
            var qualifyingWeeks = overshoot == 0 ? elapsedWeeks : elapsedWeeks + (interval - overshoot);

            // Terminates on the second pass at the latest: the floor may sit in a qualifying week but
            // after the last weekday that week names, and the next qualifying week names all of them.
            // A rule with no weekday would spin here, which is why the constructor refuses one.
            for (var weeks = qualifyingWeeks; ; weeks += interval) {
                var weekStart = anchorWeek.plusWeeks(weeks);
                var occurrence = weekdays.stream()
                        .map(weekday -> weekStart.with(TemporalAdjusters.nextOrSame(weekday)))
                        .filter(date -> !date.isBefore(floor))
                        .min(Comparator.naturalOrder());
                if (occurrence.isPresent()) {
                    return occurrence.get();
                }
            }
        }

        private static LocalDate mondayOf(LocalDate date) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
    }

    /// Every `interval` months, on `dayOfMonth`, counting from the anchor's month.
    ///
    /// **The day clamps** to the end of a short month: day 31 produces 31 Aug, 30 Sep, 31 Oct,
    /// 30 Nov, 31 Dec, 28 Feb. Skipping February instead was considered and rejected — a monthly
    /// chore that silently misses a month is worse than one that lands a day or three early.
    record Months(int interval, int dayOfMonth) implements CalendarRule {

        public Months {
            requirePositive(interval);
            if (dayOfMonth < 1 || dayOfMonth > 31) {
                throw new IllegalArgumentException("A monthly rule needs a day of the month between 1 and 31, but was " + dayOfMonth + ".");
            }
        }

        @Override
        public Optional<LocalDate> latestOccurrenceOnOrBefore(LocalDate today, LocalDate anchor) {
            return latestQualifyingMonth(today, anchor, interval,
                    month -> month.atDay(Math.min(dayOfMonth, month.lengthOfMonth())));
        }

        @Override
        public LocalDate firstOccurrenceOnOrAfter(LocalDate from, LocalDate anchor) {
            return firstInQualifyingMonth(from, anchor, interval,
                    month -> month.atDay(Math.min(dayOfMonth, month.lengthOfMonth())));
        }
    }

    /// The *first / second / third / fourth / **last*** given weekday of every `interval` months —
    /// *every first Saturday*, *every last Friday*. The shape #36 asked for and no other rule here
    /// can produce.
    record NthWeekday(int interval, Ordinal ordinal, DayOfWeek weekday) implements CalendarRule {

        public NthWeekday {
            requirePositive(interval);
        }

        @Override
        public Optional<LocalDate> latestOccurrenceOnOrBefore(LocalDate today, LocalDate anchor) {
            return latestQualifyingMonth(today, anchor, interval, month -> ordinal.resolve(month, weekday));
        }

        @Override
        public LocalDate firstOccurrenceOnOrAfter(LocalDate from, LocalDate anchor) {
            return firstInQualifyingMonth(from, anchor, interval, month -> ordinal.resolve(month, weekday));
        }
    }

    /// Which one of the month's weekdays [NthWeekday] names. There is no `FIFTH`: a month has at
    /// least 28 days so the fourth always exists, while a fifth would be absent most months —
    /// `LAST` is what people mean by it anyway.
    enum Ordinal {
        FIRST, SECOND, THIRD, FOURTH, LAST;

        LocalDate resolve(YearMonth month, DayOfWeek weekday) {
            if (this == LAST) {
                return month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday));
            }
            return month.atDay(1).with(TemporalAdjusters.nextOrSame(weekday)).plusWeeks(ordinal());
        }
    }

    /// The forward mirror of [#latestQualifyingMonth], shared by [Months] and [NthWeekday]: find the
    /// earliest qualifying month at or after the floor, ask the rule which date it names there, and
    /// step *forward* one interval if that date is already behind the floor.
    ///
    /// The loop terminates on its second pass at the latest — every qualifying month names exactly
    /// one date, and the month after the floor's own names one that cannot precede it.
    private static LocalDate firstInQualifyingMonth(LocalDate from, LocalDate anchor, int interval,
                                                    Function<YearMonth, LocalDate> dateInMonth) {
        var floor = floorOf(from, anchor);
        var anchorMonth = YearMonth.from(anchor);
        var elapsedMonths = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(floor));
        var overshoot = elapsedMonths % interval;
        var qualifyingMonths = overshoot == 0 ? elapsedMonths : elapsedMonths + (interval - overshoot);

        for (var months = qualifyingMonths; ; months += interval) {
            var occurrence = dateInMonth.apply(anchorMonth.plusMonths(months));
            if (!occurrence.isBefore(floor)) {
                return occurrence;
            }
        }
    }

    /// The month-stepping shared by [Months] and [NthWeekday]: find the latest qualifying month on
    /// or before today, ask the rule which date it names there, and step back one interval if that
    /// date has not arrived yet.
    private static Optional<LocalDate> latestQualifyingMonth(LocalDate today, LocalDate anchor, int interval,
                                                             Function<YearMonth, LocalDate> dateInMonth) {
        if (today.isBefore(anchor)) {
            return Optional.empty();
        }
        var anchorMonth = YearMonth.from(anchor);
        var elapsedMonths = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(today));
        var qualifyingMonths = elapsedMonths - elapsedMonths % interval;

        for (var step = 0; step < 2; step++) {
            var monthsBack = qualifyingMonths - (long) step * interval;
            if (monthsBack < 0) {
                break;
            }
            var occurrence = dateInMonth.apply(anchorMonth.plusMonths(monthsBack));
            if (!occurrence.isAfter(today) && !occurrence.isBefore(anchor)) {
                return Optional.of(occurrence);
            }
        }
        return Optional.empty();
    }

    /// **The anchor is the floor as well as the phase**: a template names no date before it began
    /// firing under its current rule (ADR-0017). Said once, because every forward rule needs it and
    /// four spellings of one comparison is four chances to spell it `isAfter` by mistake.
    private static LocalDate floorOf(LocalDate from, LocalDate anchor) {
        return from.isBefore(anchor) ? anchor : from;
    }

    private static void requirePositive(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("A calendar rule needs a positive interval, but was " + interval + ".");
        }
    }
}

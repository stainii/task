package be.stijnhooft.task.backend.template.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Boundary tests for the four calendar rules. Every one of these is a date comparison, which is
/// exactly the family `docs/quality-bar.md` §5 says no checker can judge: all of it is correct Java
/// and only a test can say whether it lands on the right day.
///
/// The recurring assertion in each rule is the three-point boundary — *the day before, the day
/// itself, the day after* — because a rule that is one day out is still a working rule.
class CalendarRuleTest {

    @Nested
    @DisplayName("Days")
    class Days {

        private final CalendarRule rule = new CalendarRule.Days(10);
        private final LocalDate anchor = LocalDate.of(2026, 3, 1);

        @Test
        void landsOnTheAnchorItself() {
            assertThat(rule.latestOccurrenceOnOrBefore(anchor, anchor)).contains(anchor);
        }

        @Test
        void holdsTheOccurrenceUntilTheNextOneArrives() {
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.plusDays(9), anchor)).contains(anchor);
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.plusDays(10), anchor)).contains(anchor.plusDays(10));
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.plusDays(11), anchor)).contains(anchor.plusDays(10));
        }

        /// A long outage collapses to the most recent missed date — one task, not one per date.
        @Test
        void collapsesAnOutageToTheMostRecentDate() {
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.plusDays(95), anchor)).contains(anchor.plusDays(90));
        }

        /// The anchor is the floor: a template cannot have come round before it began firing under
        /// its current rule.
        @Test
        void namesNothingBeforeTheAnchor() {
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.minusDays(1), anchor)).isEmpty();
        }

        /// Long intervals are where D1 lived. `Period.getDays()` would read a 45-day gap as 14.
        @Test
        void crossesMonthBoundariesWithoutLosingDays() {
            var everySeventyDays = new CalendarRule.Days(70);
            assertThat(everySeventyDays.latestOccurrenceOnOrBefore(anchor.plusDays(69), anchor)).contains(anchor);
            assertThat(everySeventyDays.latestOccurrenceOnOrBefore(anchor.plusDays(70), anchor))
                    .contains(anchor.plusDays(70));
        }

        @Test
        void refusesANonPositiveInterval() {
            assertThatThrownBy(() -> new CalendarRule.Days(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Weeks")
    class Weeks {

        /// Wednesday 4 March 2026.
        private final LocalDate anchor = LocalDate.of(2026, 3, 4);

        @Test
        void namesSeveralWeekdaysInOneRule() {
            // Every week, Tuesday and Thursday. Portal needed two templates for this.
            var rule = new CalendarRule.Weeks(1, EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY));

            // The anchor is a Wednesday, so the week's Tuesday (3 March) is before it and excluded.
            assertThat(rule.latestOccurrenceOnOrBefore(anchor, anchor)).isEmpty();
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 5), anchor))
                    .contains(LocalDate.of(2026, 3, 5));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 9), anchor))
                    .contains(LocalDate.of(2026, 3, 5));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 10), anchor))
                    .contains(LocalDate.of(2026, 3, 10));
        }

        /// Today sits inside a qualifying week but before the weekday it names, so the answer is in
        /// the previous qualifying week. This is the one step back the rules ever take.
        @Test
        void stepsBackWhenTodayPrecedesTheWeekdayInItsOwnQualifyingWeek() {
            // Every two weeks on Friday, anchored in the week of Mon 2 March.
            var rule = new CalendarRule.Weeks(2, Set.of(DayOfWeek.FRIDAY));

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 6), anchor))
                    .contains(LocalDate.of(2026, 3, 6));
            // Mon 16 March starts the next qualifying week; its Friday has not arrived.
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 16), anchor))
                    .contains(LocalDate.of(2026, 3, 6));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 20), anchor))
                    .contains(LocalDate.of(2026, 3, 20));
        }

        /// An even interval preserves ISO week parity, which is what makes *"every odd week"*
        /// expressible at all. ADR-0013 states the limit: a 53-week ISO year flips it once.
        @Test
        void anEvenIntervalKeepsWeekParity() {
            var rule = new CalendarRule.Weeks(2, Set.of(DayOfWeek.SATURDAY));
            var saturday = LocalDate.of(2026, 3, 7);

            assertThat(rule.latestOccurrenceOnOrBefore(saturday, saturday)).contains(saturday);
            assertThat(rule.latestOccurrenceOnOrBefore(saturday.plusWeeks(2), saturday))
                    .contains(saturday.plusWeeks(2));
            assertThat(rule.latestOccurrenceOnOrBefore(saturday.plusWeeks(3), saturday))
                    .contains(saturday.plusWeeks(2));
        }

        @Test
        void namesNothingBeforeTheAnchor() {
            var rule = new CalendarRule.Weeks(1, Set.of(DayOfWeek.MONDAY));
            assertThat(rule.latestOccurrenceOnOrBefore(anchor.minusDays(1), anchor)).isEmpty();
        }

        @Test
        void refusesARuleWithNoWeekday() {
            assertThatThrownBy(() -> new CalendarRule.Weeks(1, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Months")
    class Months {

        private final LocalDate anchor = LocalDate.of(2026, 1, 31);

        /// **Day-of-month clamping is a decision, not a default** (ADR-0013). Skipping February
        /// instead was rejected: a monthly chore that silently misses a month is worse than one that
        /// lands a few days early.
        @ParameterizedTest(name = "day 31 in {0} clamps to {1}")
        @CsvSource({
                "2026-01-31, 2026-01-31",
                "2026-02-28, 2026-02-28",
                "2026-03-31, 2026-03-31",
                "2026-04-30, 2026-04-30",
                "2026-06-30, 2026-06-30"
        })
        void clampsToTheEndOfShortMonths(LocalDate today, LocalDate expected) {
            var rule = new CalendarRule.Months(1, 31);
            assertThat(rule.latestOccurrenceOnOrBefore(today, anchor)).contains(expected);
        }

        @Test
        void holdsTheOccurrenceUntilTheNextMonthsDayArrives() {
            var rule = new CalendarRule.Months(1, 14);
            var march14 = LocalDate.of(2026, 3, 14);

            assertThat(rule.latestOccurrenceOnOrBefore(march14.minusDays(1), LocalDate.of(2026, 3, 1)))
                    .isEmpty();
            assertThat(rule.latestOccurrenceOnOrBefore(march14, LocalDate.of(2026, 3, 1))).contains(march14);
            assertThat(rule.latestOccurrenceOnOrBefore(march14.plusDays(1), LocalDate.of(2026, 3, 1)))
                    .contains(march14);
        }

        /// *Yearly on a date* is not a stored rule — `Months(12, day)` already is it, with the month
        /// coming from the anchor like every other rule's phase. The picker still offers *years*.
        @Test
        void yearlyIsTwelveMonths() {
            var rule = new CalendarRule.Months(12, 14);
            var anchor = LocalDate.of(2026, 3, 1);

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2027, 3, 13), anchor))
                    .contains(LocalDate.of(2026, 3, 14));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2027, 3, 14), anchor))
                    .contains(LocalDate.of(2027, 3, 14));
        }

        @Test
        void refusesADayOutsideTheMonth() {
            assertThatThrownBy(() -> new CalendarRule.Months(1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CalendarRule.Months(1, 32)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("NthWeekday")
    class NthWeekday {

        private final LocalDate anchor = LocalDate.of(2026, 3, 1);

        /// #36's own motivating example, and the reason this rule took the slot *yearly* would have
        /// had: no combination of the other three produces *every first Saturday*.
        @Test
        void everyFirstSaturday() {
            var rule = new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.FIRST, DayOfWeek.SATURDAY);

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 6), anchor)).isEmpty();
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 7), anchor))
                    .contains(LocalDate.of(2026, 3, 7));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 4, 3), anchor))
                    .contains(LocalDate.of(2026, 3, 7));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 4, 4), anchor))
                    .contains(LocalDate.of(2026, 4, 4));
        }

        /// The other half of the hole the four-rule vocabulary would otherwise have.
        @Test
        void everyLastFriday() {
            var rule = new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.LAST, DayOfWeek.FRIDAY);

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 26), anchor))
                    .isEmpty();
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 3, 27), anchor))
                    .contains(LocalDate.of(2026, 3, 27));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 4, 23), anchor))
                    .contains(LocalDate.of(2026, 3, 27));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 4, 24), anchor))
                    .contains(LocalDate.of(2026, 4, 24));
        }

        /// A month has at least 28 days, so the fourth occurrence always exists — which is why there
        /// is no `FIFTH` and `LAST` carries that meaning instead.
        @Test
        void theFourthAlwaysExistsEvenInFebruary() {
            var rule = new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.FOURTH, DayOfWeek.WEDNESDAY);
            var february = LocalDate.of(2026, 2, 1);

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 2, 28), february))
                    .contains(LocalDate.of(2026, 2, 25));
        }

        @Test
        void countsItsOwnMonthInterval() {
            var rule = new CalendarRule.NthWeekday(3, CalendarRule.Ordinal.FIRST, DayOfWeek.MONDAY);

            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 5, 31), anchor))
                    .contains(LocalDate.of(2026, 3, 2));
            assertThat(rule.latestOccurrenceOnOrBefore(LocalDate.of(2026, 6, 1), anchor))
                    .contains(LocalDate.of(2026, 6, 1));
        }
    }
}

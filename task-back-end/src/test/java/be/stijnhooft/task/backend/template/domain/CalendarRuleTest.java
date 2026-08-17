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

        @Test
        void listsItsNextDatesForward() {
            assertThat(rule.nextOccurrencesOnOrAfter(anchor, anchor, 3))
                    .containsExactly(anchor, anchor.plusDays(10), anchor.plusDays(20));
        }

        /// The floor is *on or after*, so a date landing exactly on it is the first one listed and a
        /// floor between two dates skips to the later. The boundary the preview is read against.
        @Test
        void startsAtTheFirstDateNotBeforeTheFloor() {
            assertThat(rule.nextOccurrencesOnOrAfter(anchor.plusDays(10), anchor, 2))
                    .containsExactly(anchor.plusDays(10), anchor.plusDays(20));
            assertThat(rule.nextOccurrencesOnOrAfter(anchor.plusDays(11), anchor, 2))
                    .containsExactly(anchor.plusDays(20), anchor.plusDays(30));
        }

        /// The anchor is the floor of the enumeration as well as its phase, so a floor before the
        /// anchor cannot pull dates back in front of it.
        @Test
        void namesNothingBeforeTheAnchorGoingForward() {
            assertThat(rule.nextOccurrencesOnOrAfter(anchor.minusDays(5), anchor, 2))
                    .containsExactly(anchor, anchor.plusDays(10));
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

        /// *Every 2 weeks on Tuesday and Thursday* — the sentence #68 exists to put dates under.
        /// Both weekdays of a qualifying week come out before the rule skips the quiet one.
        @Test
        void listsEveryWeekdayOfAQualifyingWeekBeforeSkippingTheNext() {
            var rule = new CalendarRule.Weeks(2, EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY));

            // The anchor is Wednesday 4 March, so its own week's Tuesday is behind it.
            assertThat(rule.nextOccurrencesOnOrAfter(anchor, anchor, 4))
                    .containsExactly(
                            LocalDate.of(2026, 3, 5),
                            LocalDate.of(2026, 3, 17),
                            LocalDate.of(2026, 3, 19),
                            LocalDate.of(2026, 3, 31));
        }

        /// The forward mirror of the one step back: the floor sits in a qualifying week but after
        /// the last weekday it names, so the answer is in the next qualifying week.
        @Test
        void stepsForwardWhenTheFloorHasPassedTheWeekdayInItsOwnQualifyingWeek() {
            var rule = new CalendarRule.Weeks(2, Set.of(DayOfWeek.FRIDAY));

            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 3, 6), anchor, 1))
                    .containsExactly(LocalDate.of(2026, 3, 6));
            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 3, 7), anchor, 2))
                    .containsExactly(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 3));
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

        /// The clamp is visible in the *list* rather than only in one date, which is the whole point
        /// of showing dates: *31 Aug, 30 Sep, 31 Oct* is a rule you can check by reading it.
        @Test
        void listsTheClampedDaysOfEachMonth() {
            var rule = new CalendarRule.Months(1, 31);

            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 2, 1), anchor, 4))
                    .containsExactly(
                            LocalDate.of(2026, 2, 28),
                            LocalDate.of(2026, 3, 31),
                            LocalDate.of(2026, 4, 30),
                            LocalDate.of(2026, 5, 31));
        }

        /// The forward mirror of the one step back: the floor is past this month's day, so the first
        /// date listed is next month's.
        @Test
        void stepsForwardWhenTheFloorHasPassedThisMonthsDay() {
            var rule = new CalendarRule.Months(1, 14);
            var march = LocalDate.of(2026, 3, 1);

            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 3, 14), march, 1))
                    .containsExactly(LocalDate.of(2026, 3, 14));
            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 3, 15), march, 2))
                    .containsExactly(LocalDate.of(2026, 4, 14), LocalDate.of(2026, 5, 14));
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

        /// #68's own worked example: *the first Saturday of every month* is unreadable as four form
        /// controls until the dates are listed under it.
        @Test
        void listsTheFirstSaturdayOfTheNextMonths() {
            var rule = new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.FIRST, DayOfWeek.SATURDAY);

            assertThat(rule.nextOccurrencesOnOrAfter(LocalDate.of(2026, 8, 17), anchor, 3))
                    .containsExactly(
                            LocalDate.of(2026, 9, 5),
                            LocalDate.of(2026, 10, 3),
                            LocalDate.of(2026, 11, 7));
        }

        /// A rule whose interval skips months lists only the months it qualifies, which is the shape
        /// that is hardest to read off the controls and easiest to read off dates.
        @Test
        void listsOnlyItsOwnQualifyingMonths() {
            var rule = new CalendarRule.NthWeekday(3, CalendarRule.Ordinal.LAST, DayOfWeek.FRIDAY);

            assertThat(rule.nextOccurrencesOnOrAfter(anchor, anchor, 3))
                    .containsExactly(
                            LocalDate.of(2026, 3, 27),
                            LocalDate.of(2026, 6, 26),
                            LocalDate.of(2026, 9, 25));
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

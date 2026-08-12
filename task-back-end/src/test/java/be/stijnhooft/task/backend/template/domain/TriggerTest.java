package be.stijnhooft.task.backend.template.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Boundary tests for the three triggers, per `docs/quality-bar.md` §5: *date and comparison logic
/// is tested at its boundaries*. Error Prone owns the API misuse; only a test can say whether a
/// trigger fires on the right **day**.
class TriggerTest {

    private static final LocalDate ACTIVE_SINCE = LocalDate.of(2026, 3, 1);

    @Nested
    @DisplayName("Manual")
    class ManualTrigger {

        @Test
        void neverComesRoundOnItsOwn() {
            var trigger = new Trigger.Manual("When is the workshop?");

            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE.plusYears(5), ACTIVE_SINCE, null)).isEmpty();
            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE.plusYears(5), ACTIVE_SINCE, ACTIVE_SINCE)).isEmpty();
        }

        @Test
        void mayHaveNoAnchorLabel() {
            assertThat(new Trigger.Manual(null).anchorLabel()).isNull();
        }
    }

    @Nested
    @DisplayName("MinMax")
    class MinMaxTrigger {

        /// **The D1 canary.** `Period.between(...).getDays()` reads only the day *component*, so it
        /// saw 14 where 45 days had passed and the template never became due. Any interval that
        /// crosses a month boundary is a canary; 45 is the one #18 measured the defect with.
        @ParameterizedTest(name = "an interval of {0} days is due exactly {0} days later")
        @ValueSource(ints = {1, 30, 31, 45, 90, 365, 720})
        void becomesDueAfterTheWholeInterval(int interval) {
            var trigger = new Trigger.MinMax(interval, interval);
            var lastClosure = ACTIVE_SINCE;
            var dueDate = lastClosure.plusDays(interval);

            assertThat(trigger.latestFiringDateOn(dueDate.minusDays(1), ACTIVE_SINCE, lastClosure)).isEmpty();
            assertThat(trigger.latestFiringDateOn(dueDate, ACTIVE_SINCE, lastClosure)).contains(dueDate);
            assertThat(trigger.latestFiringDateOn(dueDate.plusDays(1), ACTIVE_SINCE, lastClosure)).contains(dueDate);
        }

        /// A fortnight of downtime costs one task, not fourteen: the firing date is still the day it
        /// became due, so the task arrives already overdue and honestly so.
        @Test
        void aLongOutageStillProducesOneFiringDate() {
            var trigger = new Trigger.MinMax(7, 10);
            var lastClosure = ACTIVE_SINCE;

            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE.plusDays(60), ACTIVE_SINCE, lastClosure))
                    .contains(ACTIVE_SINCE.plusDays(7));
        }

        /// With no closed task there is nothing to measure from, so `activeSince` is the seed —
        /// which is where REC-003's explicit start date went. Portal's fallback made a new template
        /// due the moment you saved it, so `min` meant nothing on the one firing you were watching.
        @Test
        void seedsFromActiveSinceWhenNothingHasClosedYet() {
            var trigger = new Trigger.MinMax(10, 10);

            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE, ACTIVE_SINCE, null)).isEmpty();
            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE.plusDays(9), ACTIVE_SINCE, null)).isEmpty();
            assertThat(trigger.latestFiringDateOn(ACTIVE_SINCE.plusDays(10), ACTIVE_SINCE, null))
                    .contains(ACTIVE_SINCE.plusDays(10));
        }

        /// It drifts on purpose: the clock restarts from the closure, not from the calendar.
        @Test
        void driftsFromTheLastClosureRatherThanTheCalendar() {
            var trigger = new Trigger.MinMax(10, 10);
            var lateClosure = ACTIVE_SINCE.plusDays(37);

            assertThat(trigger.latestFiringDateOn(lateClosure.plusDays(10), ACTIVE_SINCE, lateClosure))
                    .contains(lateClosure.plusDays(10));
        }

        /// `min == max` is *due immediately* — ten of the 44 real templates say exactly this, and
        /// under interval-plus-window it is one field rather than the same number typed twice.
        @Test
        void aZeroWindowIsDueOnTheDayItAppears() {
            var trigger = Trigger.MinMax.ofIntervalAndWindow(5, 0);

            assertThat(trigger.min()).isEqualTo(5);
            assertThat(trigger.max()).isEqualTo(5);
            assertThat(trigger.window()).isZero();
            assertThat(trigger.defaultDueDateFor(ACTIVE_SINCE)).contains(ACTIVE_SINCE);
        }

        /// Created at `min`, due at `max` (REC-006): the final warning is just a task going overdue,
        /// so no outbound notification channel is needed.
        @Test
        void isDueAtMax() {
            var trigger = Trigger.MinMax.ofIntervalAndWindow(10, 11);

            assertThat(trigger.max()).isEqualTo(21);
            assertThat(trigger.defaultDueDateFor(ACTIVE_SINCE.plusDays(10))).contains(ACTIVE_SINCE.plusDays(21));
        }

        @Test
        void refusesAnIntervalThatIsNotPositive() {
            assertThatThrownBy(() -> new Trigger.MinMax(0, 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesToBeDueBeforeItIsCreated() {
            assertThatThrownBy(() -> new Trigger.MinMax(10, 9))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Trigger.MinMax.ofIntervalAndWindow(10, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Calendar")
    class CalendarTrigger {

        /// Absolute dates: a closure moves nothing. That difference from `MinMax` is the whole
        /// reason both triggers exist, so it is asserted rather than assumed.
        @Test
        void ignoresTheLastClosureEntirely() {
            var trigger = new Trigger.Calendar(new CalendarRule.Days(7));
            var today = ACTIVE_SINCE.plusDays(20);

            var withoutClosure = trigger.latestFiringDateOn(today, ACTIVE_SINCE, null);
            var withRecentClosure = trigger.latestFiringDateOn(today, ACTIVE_SINCE, today.minusDays(1));

            assertThat(withoutClosure).contains(ACTIVE_SINCE.plusDays(14));
            assertThat(withRecentClosure).isEqualTo(withoutClosure);
        }
    }
}

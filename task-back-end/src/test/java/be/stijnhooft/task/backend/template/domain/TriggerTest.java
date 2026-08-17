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

        /// Nothing to preview: the dates come from an anchor someone types, which is why the
        /// authoring screen shows a manual template's *shape* instead (ADR-0013).
        @Test
        void hasNoDatesToPreview() {
            assertThat(new Trigger.Manual("When is the workshop?")
                    .nextFiringDates(ACTIVE_SINCE, ACTIVE_SINCE, null, 3)).isEmpty();
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

        /// **One date and never more, however many are asked for.** The firing after the next one
        /// starts its clock at a closure that has not happened, so a second date would be a guess
        /// dressed as a schedule — and drift is precisely what distinguishes this trigger from
        /// `Calendar` (ADR-0001).
        @Test
        void previewsExactlyOneDateHoweverManyAreAsked() {
            var trigger = Trigger.MinMax.ofIntervalAndWindow(10, 3);

            assertThat(trigger.nextFiringDates(ACTIVE_SINCE, ACTIVE_SINCE, null, 5))
                    .containsExactly(ACTIVE_SINCE.plusDays(10));
        }

        /// The same round start the firing itself uses — the later of the closure and `activeSince`
        /// — so the preview cannot say one day and the scheduler another.
        @Test
        void previewsFromTheSameRoundStartAsTheFiring() {
            var trigger = Trigger.MinMax.ofIntervalAndWindow(10, 0);
            var lateClosure = ACTIVE_SINCE.plusDays(37);

            assertThat(trigger.nextFiringDates(lateClosure, ACTIVE_SINCE, lateClosure, 1))
                    .containsExactly(lateClosure.plusDays(10));
            // A closure from before the template was re-ruled loses to `activeSince`, exactly as it
            // does in `latestFiringDateOn`.
            assertThat(trigger.nextFiringDates(ACTIVE_SINCE, ACTIVE_SINCE, ACTIVE_SINCE.minusDays(5), 1))
                    .containsExactly(ACTIVE_SINCE.plusDays(10));
        }

        /// A template already past its round shows the date it became due, not a tidied-up future
        /// one: *this fires on 11 March* is true and *it fires next month* would not be.
        @Test
        void showsADateAlreadyBehindWhenTheTemplateIsOverdue() {
            var trigger = Trigger.MinMax.ofIntervalAndWindow(10, 0);

            assertThat(trigger.nextFiringDates(ACTIVE_SINCE.plusDays(40), ACTIVE_SINCE, null, 3))
                    .containsExactly(ACTIVE_SINCE.plusDays(10));
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

        /// **The case #68 exists for**: a rule can enumerate its own firings with nobody typing
        /// anything, and the closure it ignores does not move them here either.
        @Test
        void listsAsManyDatesAsAsked() {
            var trigger = new Trigger.Calendar(new CalendarRule.Days(7));

            assertThat(trigger.nextFiringDates(ACTIVE_SINCE, ACTIVE_SINCE, ACTIVE_SINCE.plusDays(3), 3))
                    .containsExactly(ACTIVE_SINCE, ACTIVE_SINCE.plusDays(7), ACTIVE_SINCE.plusDays(14));
        }
    }
}

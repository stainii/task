package be.stijnhooft.task.backend.template.domain;

import be.stijnhooft.task.backend.task.Importance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// **The firing predicate, at its boundaries** — ADR-0017's three rules as one condition, which is
/// the whole point of [TaskTemplate#firingDateOn] existing rather than living in the scheduler.
///
/// A unit test rather than an integration test on purpose: every part of rule 3 is a date
/// comparison, and `docs/quality-bar.md` §5 asks for those to be asserted a day either side of the
/// boundary. `DueTemplateCheckerIntegrationTest` proves the same rules reach the database; here
/// they are proved one day at a time, which no integration test can afford.
class TaskTemplateFiringTest {

    private static final LocalDate ACTIVE_SINCE = LocalDate.of(2026, 3, 1);

    /// The first Tuesday after [#ACTIVE_SINCE], which is a Sunday - so a weekly Tuesday rule names
    /// a date that is not the anchor itself.
    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);

    @Nested
    @DisplayName("Rule 1: the template is active")
    class Active {

        @Test
        void aDeactivatedTemplateNeverFires() {
            var template = deactivated(minMax(5, 5));

            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(5), false, null)).isEmpty();
        }

        /// Deactivation is not deletion, so the template still answers - it just answers *no*, for
        /// as long as it takes someone to reactivate it (ADR-0013).
        @Test
        void aDeactivatedTemplateStaysSilentHoweverLateItIs() {
            var template = deactivated(minMax(5, 5));

            assertThat(template.firingDateOn(ACTIVE_SINCE.plusYears(2), false, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rule 2: nothing of this template is open")
    class OpenTask {

        @Test
        void anOpenTaskSuppressesAMinMaxTemplate() {
            var template = template(minMax(5, 5));

            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(5), true, null)).isEmpty();
            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(5), false, null))
                    .contains(ACTIVE_SINCE.plusDays(5));
        }

        /// The rule calendar could not do without: a bin template whose Monday task is left open
        /// would otherwise fire again every week, and a month of neglect gives four open bin tasks
        /// - the accumulation `activeTask` was deleted to prevent, arriving from the other side.
        @Test
        void anOpenTaskSuppressesACalendarTemplate() {
            var template = template(weekly(DayOfWeek.TUESDAY));

            assertThat(template.firingDateOn(TUESDAY.plusWeeks(4), true, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rule 3a: the date is at or after active_since")
    class ActiveSince {

        /// The boundary itself. A template anchored on a Sunday, following a weekly Sunday rule,
        /// fires *that* Sunday: the floor is inclusive, or a template created on the very day its
        /// rule names would skip a week.
        @Test
        void firesOnActiveSinceItself() {
            var template = template(weekly(DayOfWeek.SUNDAY), LocalDate.of(2026, 3, 8));

            assertThat(template.firingDateOn(LocalDate.of(2026, 3, 8), false, null))
                    .contains(LocalDate.of(2026, 3, 8));
        }

        /// Re-ruling a template rewrites `active_since` (ADR-0017), and a calendar template's old
        /// dates must not drag it back before the day the new rule started: a bin template moved
        /// from Tuesdays to Thursdays finds no task on any Thursday, and without the floor it
        /// immediately fires a backdated one.
        @Test
        void aReRuledCalendarTemplateDoesNotFireForADateBeforeItsNewRule() {
            var reRuledOnAThursday = LocalDate.of(2026, 6, 4);
            var template = template(weekly(DayOfWeek.THURSDAY), reRuledOnAThursday);

            assertThat(template.firingDateOn(reRuledOnAThursday.minusDays(1), false, null)).isEmpty();
            assertThat(template.firingDateOn(reRuledOnAThursday, false, null)).contains(reRuledOnAThursday);
        }

        /// **The canary for a freeze that would never have surfaced.** A min/max template closed in
        /// March and re-ruled in June computes its round from the *later* of the two dates. Read the
        /// closure alone and the answer is a March date, which the floor then refuses — on this tick
        /// and on every tick after it, for the rest of the template's life. Reactivating a template
        /// takes the same path.
        @Test
        void aReRuledMinMaxTemplateCountsFromTheReRuleAndNotFromAnOlderClosure() {
            var reRuledOn = LocalDate.of(2026, 6, 1);
            var template = template(minMax(5, 5), reRuledOn);
            var closedLongBefore = LocalDate.of(2026, 3, 10);

            assertThat(template.firingDateOn(reRuledOn.plusDays(4), false, closedLongBefore)).isEmpty();
            assertThat(template.firingDateOn(reRuledOn.plusDays(5), false, closedLongBefore))
                    .contains(reRuledOn.plusDays(5));
        }
    }

    @Nested
    @DisplayName("Rule 3b: the date is strictly after the last closure")
    class LastClosure {

        /// **The 09:00 completion that must not refire at 10:00.** Complete today's bin task and
        /// the next tick still sees today's calendar date with nothing open - so without rule 3 it
        /// fires again, and again, every hour until midnight. ADR-0016 stated this as a same-date
        /// rule of its own; here it is simply rule 3 saying no.
        @Test
        void aDateAlreadyClosedDoesNotFireAgain() {
            var template = template(weekly(DayOfWeek.TUESDAY));

            assertThat(template.firingDateOn(TUESDAY, false, TUESDAY)).isEmpty();
        }

        /// The other side of the same boundary: the *next* date the rule names does fire, and the
        /// closure of the previous one does not move it. Suppression pauses the rhythm; the dates
        /// never move.
        @Test
        void theNextDateAfterAClosureFires() {
            var template = template(weekly(DayOfWeek.TUESDAY));

            assertThat(template.firingDateOn(TUESDAY.plusWeeks(1), false, TUESDAY))
                    .contains(TUESDAY.plusWeeks(1));
        }

        /// ADR-0017's worked example. A bin task fires 21 July and is completed three weeks late;
        /// nothing is open, and the latest Tuesday before the completion has no task. Reading *any*
        /// task would hand you another bin task the moment you finally did the first one - so the
        /// dates that passed while it was open are satisfied by closing it.
        @Test
        void datesThatPassedWhileATaskWasOpenAreSatisfiedByClosingIt() {
            var template = template(weekly(DayOfWeek.TUESDAY));
            var firedOn = LocalDate.of(2026, 7, 21);
            var closedThreeWeeksLater = LocalDate.of(2026, 8, 11);

            // The task fired on 21 July and was closed on 11 August, so the closure is dated by the
            // firing: three Tuesdays went by while it sat there, and none of them comes back.
            assertThat(template.firingDateOn(closedThreeWeeksLater, false, firedOn))
                    .contains(LocalDate.of(2026, 8, 11));
            assertThat(template.firingDateOn(LocalDate.of(2026, 8, 10), false, firedOn))
                    .contains(LocalDate.of(2026, 8, 4));
        }
    }

    @Nested
    @DisplayName("MinMax: the interval, at the day either side")
    class MinMaxFiring {

        /// #44's canary, now over the predicate rather than the trigger: a five-day minimum is not
        /// due on day four and is due on day five. It fails if the clock stops moving.
        @ParameterizedTest
        @ValueSource(ints = {1, 5, 30, 31, 45, 90, 365, 720})
        void firesTheDayTheIntervalIsUpAndNotTheDayBefore(int interval) {
            var template = template(minMax(interval, interval));

            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(interval - 1L), false, null)).isEmpty();
            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(interval), false, null))
                    .contains(ACTIVE_SINCE.plusDays(interval));
        }

        /// A brand-new template has nothing closed, so `active_since` is the seed the first firing
        /// counts from (ADR-0017). Portal made a never-executed template due the moment you saved
        /// it, which made `min` mean nothing on the one firing you are paying attention to.
        @Test
        void aBrandNewTemplateCountsFromActiveSince() {
            var template = template(minMax(7, 7));

            assertThat(template.firingDateOn(ACTIVE_SINCE, false, null)).isEmpty();
            assertThat(template.firingDateOn(ACTIVE_SINCE.plusDays(7), false, null))
                    .contains(ACTIVE_SINCE.plusDays(7));
        }

        /// **A cancelled task buys a full interval of quiet.** The closure is a closure whichever
        /// way it ended, so the clock restarts from it - which is the bug `lastCompletionOf` would
        /// have caused: nothing open to suppress the template, the last completion still in the
        /// past, so it fires again tomorrow and every day after (ADR-0011).
        @Test
        void aClosureRestartsTheInterval() {
            var template = template(minMax(10, 10));
            var closedOn = ACTIVE_SINCE.plusDays(10);

            assertThat(template.firingDateOn(closedOn.plusDays(9), false, closedOn)).isEmpty();
            assertThat(template.firingDateOn(closedOn.plusDays(10), false, closedOn))
                    .contains(closedOn.plusDays(10));
        }

        /// **Min/max drifts on purpose** - and a fortnight of downtime still costs exactly one
        /// task, dated the day it became due rather than today.
        @Test
        void aFortnightOfDowntimeCostsOneTaskDatedWhenItWasDue() {
            var template = template(minMax(7, 7));
            var dueOn = ACTIVE_SINCE.plusDays(7);

            assertThat(template.firingDateOn(dueOn.plusDays(14), false, null)).contains(dueOn);
        }
    }

    @Nested
    @DisplayName("Calendar: never drifts, and comes back once")
    class CalendarFiring {

        /// The calendar counterpart of the same outage: three Tuesdays go by with the app down and
        /// the template returns **once**, anchored on the most recent one, already overdue and
        /// honestly so. Two weeks away must not produce fourteen bin tasks.
        @Test
        void aFortnightOfDowntimeCostsOneTaskForTheLatestMissedDate() {
            var template = template(weekly(DayOfWeek.TUESDAY));
            var threeTuesdaysLater = TUESDAY.plusWeeks(3);

            assertThat(template.firingDateOn(threeTuesdaysLater.plusDays(1), false, TUESDAY.minusWeeks(1)))
                    .contains(threeTuesdaysLater);
        }

        /// A closure does not move a calendar date, which is the difference from `MinMax` and the
        /// reason both triggers exist. Complete Tuesday's task on Wednesday and the next firing is
        /// still the following Tuesday, not seven days from Wednesday.
        @Test
        void aLateClosureDoesNotMoveTheNextDate() {
            var template = template(weekly(DayOfWeek.TUESDAY));

            assertThat(template.firingDateOn(TUESDAY.plusDays(3), false, TUESDAY)).isEmpty();
            assertThat(template.firingDateOn(TUESDAY.plusWeeks(1), false, TUESDAY))
                    .contains(TUESDAY.plusWeeks(1));
        }
    }

    @Nested
    @DisplayName("Manual")
    class ManualFiring {

        /// A manual template is run by hand and never by the tick, whatever the rest of the
        /// predicate says.
        @Test
        void neverFiresOnItsOwn() {
            var template = template(new Trigger.Manual("When is the workshop?"));

            assertThat(template.firingDateOn(ACTIVE_SINCE.plusYears(1), false, null)).isEmpty();
        }
    }

    private static Trigger minMax(int min, int max) {
        return new Trigger.MinMax(min, max);
    }

    private static Trigger weekly(DayOfWeek weekday) {
        return new Trigger.Calendar(new CalendarRule.Weeks(1, Set.of(weekday)));
    }

    private static TaskTemplate template(Trigger trigger) {
        return template(trigger, ACTIVE_SINCE);
    }

    private static TaskTemplate template(Trigger trigger, LocalDate activeSince) {
        return TaskTemplate.of(UUID.randomUUID(), "Bin", "house", activeSince, trigger, List.of(definition()));
    }

    private static TaskTemplate deactivated(Trigger trigger) {
        var active = template(trigger);
        return new TaskTemplate(active.id(), active.name(), active.context(), false, active.activeSince(),
                active.storedTrigger(), active.taskDefinitions(), active.version());
    }

    private static TaskDefinition definition() {
        return TaskDefinition.of(UUID.randomUUID(), "Bin out", 0, null, Importance.IMPORTANT, null);
    }
}

package be.stijnhooft.task.backend.template.domain;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/// *When does this template come round?* — asked of the trigger itself, never of a scheduler.
///
/// Three shapes, sealed, so a fourth cannot be half-implemented
/// ([ADR-0001](../../../../../../../../docs/adr/0001-one-task-aggregate-with-triggered-templates.md)).
/// The three are a **creation choice**, not three aggregates: one `TaskTemplate` carries exactly
/// one of them.
///
/// ### The one question
///
/// [#latestFiringDateOn] answers *"when did you last come round, on or before today?"* — the
/// mirror of ADR-0001's *"when do I next fire?"*, and the form ADR-0017 needs, because a template
/// that slept through a date must come back **once**, anchored on the date it should have fired.
/// There is no walk: every shape computes its answer directly.
///
/// The answer is a **date, not a verdict**. Whether that date actually produces tasks is the
/// firing predicate's business ([#49](https://github.com/stainii/task/issues/49)): the template
/// must also be active, have no open task, and — for `Calendar` — the date must beat the most
/// recent closed task's firing date.
///
/// ### Why the two scheduled shapes are not one
///
/// `MinMax` **drifts on purpose**: its clock restarts from the last closure, so a chore done late
/// is next asked for late. `Calendar` **never drifts**: its dates come from the calendar and a
/// closure moves nothing. That difference is the whole reason both exist, and it is visible right
/// here — `MinMax` reads `lastClosure` and `Calendar` ignores it.
public sealed interface Trigger permits Trigger.Manual, Trigger.MinMax, Trigger.Calendar {

    /// The date this trigger last came round, on or before `today`; empty when it has not come
    /// round at all.
    ///
    /// @param today        the application's notion of today, from the `Clock` bean
    /// @param activeSince  the date this template began firing under its current rule — the floor
    ///                     of the enumeration, and the phase every calendar rule is measured from
    ///                     (ADR-0017)
    /// @param lastClosure  the firing date of the template's most recently **closed** task, or
    ///                     `null` when it has none. *Any* closure ends a round, so a cancelled task
    ///                     buys a full interval of quiet
    ///                     ([ADR-0011](../../../../../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)).
    Optional<LocalDate> latestFiringDateOn(LocalDate today, LocalDate activeSince, @Nullable LocalDate lastClosure);

    /// The due date a definition with no due offset falls back to, for a firing on `firingDate`.
    ///
    /// Only [MinMax] has one — its `max` is *the day this stops being a suggestion* (REC-006), and
    /// the old reminder→urgent escalation is exactly that task going overdue. A calendar firing
    /// names a date and nothing else, so a definition that sets no due offset produces a task with
    /// no due date.
    ///
    /// Asked of the trigger rather than switched on by the caller, for the same reason
    /// [#latestFiringDateOn] is: adding a fourth shape must not compile until it has answered both.
    Optional<LocalDate> defaultDueDateFor(LocalDate firingDate);

    /// The dates this trigger is going to come round on next, at most `count` of them — what the
    /// authoring screen lists under the rule it has just read back as a sentence
    /// ([ADR-0013](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)'s
    /// *strongest case for the preview existing at all*, built by
    /// [#68](https://github.com/stainii/task/issues/68)).
    ///
    /// **The three shapes answer with different lengths, and the difference is the model showing
    /// through** rather than an inconsistency:
    ///
    /// - [Manual] lists **nothing**. Its dates come from an anchor someone types.
    /// - [MinMax] lists **exactly one**, however many are asked for. The firing after the next one
    ///   starts its clock at a closure that has not happened, so a second date would be a guess
    ///   dressed as a schedule.
    /// - [Calendar] lists **`count`**, because a rule enumerates its own firings with nobody typing
    ///   anything.
    ///
    /// [MinMax]'s one date **may lie before `from`**, and that is the truth being shown: a template
    /// past its round is already due, and *this fires on 11 March* beats a tidied-up future date.
    /// A [Calendar] rule's dates never do — they are floored at `from`.
    ///
    /// Pinned across both implementations by `/firing-fixtures/`, on `/render-fixtures/`'s contract.
    ///
    /// @param from         the date to look forward from — *today*, in the preview
    /// @param activeSince  as on [#latestFiringDateOn]: the floor and the phase
    /// @param lastClosure  as on [#latestFiringDateOn], and read by [MinMax] alone
    /// @param count        how many dates the caller has room for
    List<LocalDate> nextFiringDates(LocalDate from, LocalDate activeSince, @Nullable LocalDate lastClosure, int count);

    /// Run by hand. It never comes round on its own, so it never fires: someone opens the template
    /// and types the anchor date.
    ///
    /// ### The anchor has a name
    ///
    /// [ADR-0013 §98](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)
    /// gives the template author the anchor's wording — *"When is the workshop?"*, *"When do you
    /// leave?"* — so the run-it dialog asks a question instead of presenting a date picker, and the
    /// authoring preview has a label to read.
    ///
    /// It lives here rather than on the template because only a manual trigger has an anchor to
    /// name. On disk it is still "one string on the template", as ADR-0013 puts it: the whole
    /// trigger persists as columns of `task_template`. See the amendment recorded in ADR-0013 —
    /// that ADR also calls `Manual` a marker record, in the paragraph that refused to move
    /// `variableNames` here, and this field is the one thing that does belong to it.
    record Manual(@Nullable String anchorLabel) implements Trigger {

        @Override
        public Optional<LocalDate> latestFiringDateOn(LocalDate today, LocalDate activeSince, @Nullable LocalDate lastClosure) {
            return Optional.empty();
        }

        @Override
        public Optional<LocalDate> defaultDueDateFor(LocalDate firingDate) {
            return Optional.empty();
        }

        @Override
        public List<LocalDate> nextFiringDates(LocalDate from, LocalDate activeSince, @Nullable LocalDate lastClosure, int count) {
            return List.of();
        }
    }

    /// *Comes round every `min` days, and I have until `max` to do it.*
    ///
    /// Authored as **interval plus window** and stored as two day counts
    /// ([ADR-0013 §150](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)):
    /// the form asks *"every N days, and I have M days to do it"* and writes `min = N`,
    /// `max = N + M`. `min == max` — a window of zero — means **due immediately**, which is what
    /// ten of the 44 real templates say.
    ///
    /// The task is created at `min` and due at `max` (REC-006), so the old reminder→urgent
    /// escalation is just one task going overdue.
    ///
    /// **This is where defect D1 lived.** The old class asked `Period.between(...).getDays()`,
    /// which reads only the day *component* of a year/month/day period — so a template with a
    /// 45-day interval saw 15 and never became due. There is no `Period` here: dates are compared
    /// as dates, and `docs/quality-bar.md` promotes `JavaPeriodGetDays` to ERROR so the shape
    /// cannot come back.
    record MinMax(int min, int max) implements Trigger {

        public MinMax {
            if (min <= 0) {
                throw new IllegalArgumentException("A min/max trigger needs a positive interval, but min was " + min + ".");
            }
            if (max < min) {
                throw new IllegalArgumentException("A min/max trigger cannot be due before it is created: min " + min + ", max " + max + ".");
            }
        }

        /// The form's own vocabulary: *every `interval` days, and I have `window` days to do it*.
        public static MinMax ofIntervalAndWindow(int interval, int window) {
            if (window < 0) {
                throw new IllegalArgumentException("A min/max window cannot be negative, but was " + window + ".");
            }
            return new MinMax(interval, interval + window);
        }

        /// The soft period: how long after it appears the task stays merely suggested. Zero means
        /// due the day it appears.
        public int window() {
            return max - min;
        }

        /// Created at `min`, due at `max` — both measured from the same round start, so the due
        /// date is the firing date plus the window.
        @Override
        public Optional<LocalDate> defaultDueDateFor(LocalDate firingDate) {
            return Optional.of(firingDate.plusDays(window()));
        }

        /// The round starts at **the later of the last closure and `active_since`**, and the
        /// `active_since` half is not a formality.
        ///
        /// Reading the closure alone freezes a re-ruled or reactivated template *permanently*: a
        /// template last closed in March and re-ruled in June computes a firing date in March,
        /// which the predicate then refuses for being below `active_since` — and it will compute
        /// the same March date on every tick for the rest of its life. `activeTask`'s freeze bug,
        /// arriving through the field ADR-0017 added to prevent exactly this class of thing.
        ///
        /// Taking the later of the two is also ADR-0017's stated direction for a reset: it can only
        /// ever *prevent* a firing, never lose one.
        @Override
        public Optional<LocalDate> latestFiringDateOn(LocalDate today, LocalDate activeSince, @Nullable LocalDate lastClosure) {
            var roundStarted = lastClosure == null || lastClosure.isBefore(activeSince) ? activeSince : lastClosure;
            var firingDate = roundStarted.plusDays(min);
            return firingDate.isAfter(today) ? Optional.empty() : Optional.of(firingDate);
        }

        /// **One date, and `count` cannot buy a second.** The round after this one begins at a
        /// closure that has not happened, so every further date would be invented — and inventing
        /// them would draw this trigger as the calendar it deliberately is not.
        ///
        /// The round start is computed exactly as [#latestFiringDateOn] computes it, so the preview
        /// and the scheduler cannot name different days.
        @Override
        public List<LocalDate> nextFiringDates(LocalDate from, LocalDate activeSince, @Nullable LocalDate lastClosure, int count) {
            if (count <= 0) {
                return List.of();
            }
            var roundStarted = lastClosure == null || lastClosure.isBefore(activeSince) ? activeSince : lastClosure;
            return List.of(roundStarted.plusDays(min));
        }
    }

    /// On the calendar, following one [CalendarRule]. Its dates are absolute, so a closure never
    /// moves them and `lastClosure` is deliberately unread here — the predicate compares against it
    /// instead ([ADR-0017 §105](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)).
    record Calendar(CalendarRule rule) implements Trigger {

        @Override
        public Optional<LocalDate> latestFiringDateOn(LocalDate today, LocalDate activeSince, @Nullable LocalDate lastClosure) {
            return rule.latestOccurrenceOnOrBefore(today, activeSince);
        }

        /// A calendar rule names a date, not a window. A definition with no due offset produces a
        /// task with no due date.
        @Override
        public Optional<LocalDate> defaultDueDateFor(LocalDate firingDate) {
            return Optional.empty();
        }

        /// The rule enumerates itself, and `lastClosure` is unread here for the same reason it is
        /// unread above: a closure moves no calendar date.
        @Override
        public List<LocalDate> nextFiringDates(LocalDate from, LocalDate activeSince, @Nullable LocalDate lastClosure, int count) {
            return rule.nextOccurrencesOnOrAfter(from, activeSince, count);
        }
    }
}

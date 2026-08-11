package be.stijnhooft.task.backend.template.domain;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
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
        public LocalDate dueDateFor(LocalDate firingDate) {
            return firingDate.plusDays(window());
        }

        @Override
        public Optional<LocalDate> latestFiringDateOn(LocalDate today, LocalDate activeSince, @Nullable LocalDate lastClosure) {
            var roundStarted = lastClosure == null ? activeSince : lastClosure;
            var firingDate = roundStarted.plusDays(min);
            return firingDate.isAfter(today) ? Optional.empty() : Optional.of(firingDate);
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
    }
}

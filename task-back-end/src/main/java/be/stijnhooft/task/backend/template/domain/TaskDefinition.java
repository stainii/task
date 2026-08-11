package be.stijnhooft.task.backend.template.domain;

import be.stijnhooft.task.backend.task.Importance;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/// One task a [TaskTemplate] produces when it fires. A template with several definitions produces
/// several tasks in one occurrence.
///
/// ### One anchor, two offsets
///
/// Every firing has **one anchor date** — typed by the user for a manual template, the firing date
/// for min/max, the rule's date for a calendar template — and a definition says *starts N days from
/// the anchor, due M days from the anchor*
/// ([ADR-0013 §72](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
///
/// The `startDateDeviationBase` / `dueDateDeviationBase` selectors this replaces were measured
/// across all 11 real definitions: ten set them to the default pairing and the eleventh set them
/// **inconsistently**, four definitions anchoring to the due date and one to the start date with
/// identical offsets either way — a mis-click that never surfaced because that template is always
/// run with the same date in both fields. Under one anchor it is unrepresentable.
///
/// A null offset means the resulting task simply has no such date.
@Table("task_definition")
public record TaskDefinition(

        @Id UUID id,

        /// May contain `${…}` placeholders. There is no declared list — the placeholders *are* the
        /// variables.
        String name,

        /// Days from the firing's anchor. Negative is before it: *"send the preparation mail two
        /// weeks before the workshop"* is `-14`.
        @Nullable Integer startDateOffsetDays,

        @Nullable Integer dueDateOffsetDays,

        /// **Non-null, defaulting to `IMPORTANT`**
        /// ([ADR-0013 amendment](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)),
        /// so a definition cannot produce a task that must have one from a value that does not.
        /// It lives here rather than on the template because it genuinely varies: `Opvolgen
        /// workshop` has three `IMPORTANT` definitions and one `NOT_SO_IMPORTANT` chase-up.
        Importance importance,

        /// Task instructions, per definition and usually absent (2 of 11 real ones). There is no
        /// template-level description.
        @Nullable String description) {

    /// The one place the default lives. `importance` is absent from most payloads and from every
    /// row portal ever wrote — its `recurring_task` table had no importance column at all — so the
    /// absence is normalised here rather than carried into the model as a null the task side would
    /// have to rule on again.
    public static TaskDefinition of(UUID id, String name, @Nullable Integer startDateOffsetDays,
                                    @Nullable Integer dueDateOffsetDays, @Nullable Importance importance,
                                    @Nullable String description) {
        return new TaskDefinition(id, name, startDateOffsetDays, dueDateOffsetDays,
                importance == null ? Importance.IMPORTANT : importance, description);
    }

    public Optional<LocalDate> startDateFrom(@Nullable LocalDate anchor) {
        return offsetFrom(anchor, startDateOffsetDays);
    }

    public Optional<LocalDate> dueDateFrom(@Nullable LocalDate anchor) {
        return offsetFrom(anchor, dueDateOffsetDays);
    }

    private static Optional<LocalDate> offsetFrom(@Nullable LocalDate anchor, @Nullable Integer offsetDays) {
        if (anchor == null || offsetDays == null) {
            return Optional.empty();
        }
        return Optional.of(anchor.plusDays(offsetDays));
    }
}

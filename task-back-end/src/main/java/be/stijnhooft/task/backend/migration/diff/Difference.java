package be.stijnhooft.task.backend.migration.diff;

import org.jspecify.annotations.Nullable;

/// One field of one task where portal's stored document and the fold disagree, together with the
/// reason they are allowed to.
///
/// @param taskId   portal's own task id, so a later session can find the document
/// @param field    the new model's field name
/// @param stored   what portal's document held, rendered as text
/// @param folded   what the fold computed from the same patches, rendered as text
/// @param cause    the entry from ADR-0005's acceptable-cause list this fits, or
///                 [Cause#UNEXPLAINED]
/// @param openTask whether the task folded to `OPEN` — the tasks the author actually wakes up to
///                 on cutover morning
public record Difference(
        String taskId,
        String field,
        @Nullable String stored,
        @Nullable String folded,
        Cause cause,
        boolean openTask) {

    /// A `status` difference on a task that is still open is the one thing ADR-0005 escalates
    /// regardless of how well explained it is: it is the difference you would *notice*, on day one,
    /// as a task that has silently vanished from the list.
    public boolean isEscalated() {
        return openTask && "status".equals(field);
    }
}

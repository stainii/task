package be.stijnhooft.task.backend.migration.portal;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/// The archive as it actually is — portal's rows, in portal's vocabulary, before anything is
/// decided about them.
///
/// Reading and mapping are kept apart on purpose: these records say what portal *wrote*, and
/// `migration.map` says what it *becomes*. A reader that mapped as it read would make the two
/// impossible to test separately, and every interesting question here is about the mapping.
public final class PortalArchive {

    private PortalArchive() {
    }

    /// A `todo.task` document. The stored fields are read only so [#53](https://github.com/stainii/task/issues/53)
    /// can diff them against the fold; **none of them is imported** — ADR-0005 discards the stored
    /// document because portal's merge is defect D2 and its rows are already unjustifiable by their
    /// own history.
    ///
    /// `flowId` is null for the 3,023 hand-made tasks, and also for the 11 earliest generated ones,
    /// whose *id* is the flow id.
    public record PortalTask(
            String id,
            @Nullable String flowId,
            String name,
            @Nullable String context,
            @Nullable String status,
            @Nullable String importance,
            @Nullable Instant creationDateTime) {

        /// The provenance string for this task: its `flowId`, or its own id when that is what
        /// portal used before the UUID scheme (`Health-1`, `Housagotchi-52`, …).
        public @Nullable String provenance() {
            return flowId != null ? flowId : id;
        }
    }

    /// A `todo.taskPatch` document. `changes` values are nullable — a change *to* null is how
    /// portal cleared a field, and 48 of them are real.
    public record PortalPatch(String id, String taskId, Instant dateTime, Map<String, String> changes) {
    }

    /// A `todo.taskTemplate` document: run by hand, `${…}` answered by a person. Becomes a
    /// `Trigger.Manual` template.
    public record PortalTaskTemplate(String id, String name, List<PortalTaskDefinition> definitions) {
    }

    /// An embedded definition. `expectedDurationInHours` and both `*DeviationBase` selectors are
    /// read and discarded here rather than silently not read, so the drop is visible at the seam.
    public record PortalTaskDefinition(
            String name,
            @Nullable String description,
            @Nullable String context,
            @Nullable String importance,
            @Nullable Integer startDateDeviationDays,
            @Nullable Integer dueDateDeviationDays) {
    }

    /// A `recurring_task` row from one of the four deployment databases. There is no importance
    /// column at all, which is the corroboration ADR-0018 leaned on.
    public record PortalRecurringTask(String deployment, long id, String name, int min, int max) {
    }

    /// An `execution` row: *I did this on this date*, recorded without any todo task needing to
    /// exist. Housagotchi's entire UI was tapping a creature to say "done".
    public record PortalExecution(String deployment, long id, long recurringTaskId, LocalDate date) {
    }
}

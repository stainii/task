package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.task.exception.IncompleteTaskHistoryException;
import be.stijnhooft.task.backend.task.util.ObjectUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/// A task is **the fold of its own patch history**, materialised into columns so it can be
/// queried ([ADR-0004](../../../../../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)).
/// The stored fields are never the truth; [#history] is, and every one of them is recomputed from
/// it whenever a patch arrives.
///
/// ### The fold
///
/// Sort the history, resolve voids, blank the task, replay from the creation patch. **For each
/// field the winner is the newest patch that touches it**, whatever order the patches arrived in -
/// which is the whole point, because a patch written on a train on Monday can reach the server on
/// Wednesday and must still lose to a Tuesday edit of the same field.
///
/// The rules are listed in ADR-0004 and each one is pinned by a fixture in `/fold-fixtures/`,
/// because this algorithm also exists in TypeScript and a divergence between the two would be
/// silent. **No fold rule without a fixture.**
///
/// A task receives the time, it never reads it (#44): an entity has no `Clock` to inject, so
/// [#builderForInitialTask(Clock)] takes one from its caller. Deliberately not Lombok - a record
/// cannot hide a `now()` in a generated `$default$` method, the hole #44 found in the Error Prone
/// gate.
public record Task(

        @Id UUID id,

        String name,

        /// An `Instant`, like a patch's date-time: the two are the same clock, and the creation
        /// patch carries this value verbatim. The zone is the reader's problem.
        Instant creationDateTime,

        LocalDate startDate,

        @Nullable LocalDate dueDate,

        String context,

        /// Non-null, defaulting to `IMPORTANT` ([ADR-0018](../../../../../../../../docs/adr/0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md)):
        /// portal's ranking scored a null importance *above* `NOT_SO_IMPORTANT` while its buckets
        /// treated it as low. Null was undefined, not unimportant, so the case is deleted rather
        /// than ruled on.
        Importance importance,

        @Nullable String description,

        TaskStatus status,

        /// *When did I do it?* - a domain value, set by whoever completes the task and defaulting
        /// to today at the point the patch is minted, never here. It is patchable like any other
        /// field, so correcting it is just a later patch winning
        /// ([ADR-0011](../../../../../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)).
        @Nullable LocalDate completedOn,

        /// The template that fired this task, when one did. Provenance is a real reference, not an
        /// event ([ADR-0001](../../../../../../../../docs/adr/0001-one-task-aggregate-with-triggered-templates.md)).
        @Nullable UUID taskTemplateId,

        /// Groups the tasks of one firing. An occurrence is derived, never stored, so this key is
        /// all that remains of it.
        @Nullable UUID occurrenceId,

        /// Held in fold order, so `history.getFirst()` is always the creation patch.
        @MappedCollection(idColumn = "task_id", keyColumn = "order_index") List<TaskPatch> history,

        @Version @JsonIgnore long version) {

    /// Fold order: the client's clock, ties broken by patch id.
    ///
    /// The tie-break is not decoration. Two devices can mint patches in the same millisecond, and
    /// `sequence` cannot break the tie because the client folds patches it has not yet sent and so
    /// does not have one. The id is the only ordering both sides can compute, and comparing it as
    /// a string rather than through `UUID.compareTo` keeps Java and TypeScript in step -
    /// `UUID.compareTo` compares signed longs and orders differently.
    public static final Comparator<TaskPatch> FOLD_ORDER =
            Comparator.comparing(TaskPatch::dateTime)
                    .thenComparing(patch -> patch.id().toString());

    /// Keys that appear in the creation patch's field dump but name no foldable field.
    /// `id` is the task's identity, which arrives as the patch's `taskId`; `version` belongs to
    /// the row, not to the task.
    private static final Set<String> NOT_FOLDED = Set.of("id", "version", "history");

    public Task {
        history = List.copyOf(history);
    }

    /// Appends a patch and refolds. A patch id already in the history is a **no-op**: it is the
    /// idempotency key, so a retry after a lost response changes nothing (ADR-0010).
    public Task patch(TaskPatch patch) {
        if (history.stream().anyMatch(existing -> existing.id().equals(patch.id()))) {
            return this;
        }
        var extended = new ArrayList<>(history);
        extended.add(patch);
        return foldOf(id, extended, version);
    }

    /// Stamps every patch that has not yet been through the server with a delivery sequence.
    /// Patches that already carry one keep it - a sequence is assigned once and never reissued,
    /// because a client's cursor is a promise that number N always means the same patch.
    public Task withSequencesFrom(LongSupplier sequences) {
        var stamped = history.stream()
                .map(patch -> patch.sequence() == null ? patch.withSequence(sequences.getAsLong()) : patch)
                .toList();
        return new Task(id, name, creationDateTime, startDate, dueDate, context, importance, description, status,
                completedOn, taskTemplateId, occurrenceId, stamped, version);
    }

    /// The fold. See the class comment; the rules live in ADR-0004 and in `/fold-fixtures/`.
    public static Task foldOf(UUID taskId, List<TaskPatch> history, long version) {
        if (history.isEmpty()) {
            throw new IllegalArgumentException("A task cannot be folded from an empty history: " + taskId);
        }

        // Sorted, then deduplicated by id: a client sees its own patches echo back on the stream
        // and a retry may have stored one twice, so the same patch turning up twice must fold to
        // the same task as it turning up once.
        var seen = new HashSet<UUID>();
        var ordered = history.stream()
                .sorted(FOLD_ORDER)
                .filter(patch -> seen.add(patch.id()))
                .toList();
        var creationPatch = ordered.getFirst();
        var voids = resolveVoids(ordered, creationPatch);

        var folded = new FoldState();
        for (TaskPatch patch : ordered) {
            if (!voids.voided().contains(patch.id())) {
                apply(folded, patch);
            }
        }

        // Voiding the creation patch cannot un-create a task, so it completes it instead
        // (ADR-0004). The creation patch itself still applies - skipping it would erase every
        // field the fold has to produce.
        if (voids.creationVoided()) {
            folded.status = TaskStatus.COMPLETED;
        }

        return folded.toTask(taskId, ordered, version);
    }

    private record ResolvedVoids(Set<UUID> voided, boolean creationVoided) {
    }

    /// Resolves which patches are voided, **walking the history backwards**.
    ///
    /// Backwards, because a void can itself be voided - undo-of-undo is voiding the void - and a
    /// void only ever removes a patch that precedes it in fold order. Walking from the end means a
    /// void that has itself been voided is already known to be inactive by the time it would have
    /// removed anything, and it makes a cycle of two patches voiding each other impossible to
    /// express rather than something the algorithm has to survive.
    ///
    /// A void naming a patch that is not in the history, or that sorts after it, is dangling and
    /// does nothing: it cannot be right, and guessing at what it meant is how an undo comes to
    /// remove a change it never saw.
    private static ResolvedVoids resolveVoids(List<TaskPatch> ordered, TaskPatch creationPatch) {
        var voided = new HashSet<UUID>();
        var creationVoided = false;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            var patch = ordered.get(i);
            if (voided.contains(patch.id())) {
                continue;
            }
            var target = patch.voids();
            if (target == null) {
                continue;
            }
            if (target.equals(creationPatch.id())) {
                creationVoided = true;
            } else {
                voided.add(target);
            }
        }
        return new ResolvedVoids(voided, creationVoided);
    }

    private static void apply(FoldState state, TaskPatch patch) {
        for (Map.Entry<String, String> change : patch.changes().entrySet()) {
            var value = change.getValue();
            switch (change.getKey()) {
                case "name" -> state.name = value;
                case "creationDateTime" -> state.creationDateTime = parse(value, Instant::parse);
                case "startDate" -> state.startDate = parse(value, LocalDate::parse);
                case "dueDate" -> state.dueDate = parse(value, LocalDate::parse);
                case "context" -> state.context = value;
                case "importance" -> state.importance = parse(value, Importance::valueOf);
                case "description" -> state.description = value;
                case "status" -> state.status = parse(value, TaskStatus::parse);
                case "completedOn" -> state.completedOn = parse(value, LocalDate::parse);
                case "taskTemplateId" -> state.taskTemplateId = parse(value, UUID::fromString);
                case "occurrenceId" -> state.occurrenceId = parse(value, UUID::fromString);

                // A key the fold does not know names no field, so it changes nothing. The API
                // rejects unknown keys at the door (ADR-0004) rather than here, because the fold
                // also replays years of migrated history that the API never saw.
                default -> {
                }
            }
        }
    }

    private static <T> @Nullable T parse(@Nullable String value, Function<String, T> parser) {
        return value == null ? null : parser.apply(value);
    }

    @JsonIgnore
    public TaskPatch getCreationPatch() {
        return history.getFirst();
    }

    /// A builder for a task that does not exist yet. It mints the creation patch, which carries
    /// **every** field (TODO-046) - load-bearing, because the fold replays from it and a field
    /// missing there is a field the fold can never produce.
    ///
    /// The clock is a parameter rather than a field default because the zone belongs to the
    /// `Clock` bean (#44) and an entity cannot inject one.
    public static InitialTaskBuilder builderForInitialTask(Clock clock) {
        return new InitialTaskBuilder(clock);
    }

    public static final class InitialTaskBuilder {

        private final Clock clock;
        private @Nullable UUID id;
        private @Nullable String name;
        private @Nullable Instant creationDateTime;
        private @Nullable LocalDate startDate;
        private @Nullable LocalDate dueDate;
        private @Nullable String context;
        private @Nullable Importance importance;
        private @Nullable String description;
        private @Nullable TaskStatus status;
        private @Nullable LocalDate completedOn;
        private @Nullable UUID taskTemplateId;
        private @Nullable UUID occurrenceId;

        private InitialTaskBuilder(Clock clock) {
            this.clock = clock;
        }

        public InitialTaskBuilder id(@Nullable UUID id) {
            this.id = id;
            return this;
        }

        public InitialTaskBuilder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        public InitialTaskBuilder creationDateTime(@Nullable Instant creationDateTime) {
            this.creationDateTime = creationDateTime;
            return this;
        }

        public InitialTaskBuilder startDate(@Nullable LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public InitialTaskBuilder dueDate(@Nullable LocalDate dueDate) {
            this.dueDate = dueDate;
            return this;
        }

        public InitialTaskBuilder context(@Nullable String context) {
            this.context = context;
            return this;
        }

        public InitialTaskBuilder importance(@Nullable Importance importance) {
            this.importance = importance;
            return this;
        }

        public InitialTaskBuilder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        public InitialTaskBuilder status(@Nullable TaskStatus status) {
            this.status = status;
            return this;
        }

        public InitialTaskBuilder completedOn(@Nullable LocalDate completedOn) {
            this.completedOn = completedOn;
            return this;
        }

        public InitialTaskBuilder taskTemplateId(@Nullable UUID taskTemplateId) {
            this.taskTemplateId = taskTemplateId;
            return this;
        }

        public InitialTaskBuilder occurrenceId(@Nullable UUID occurrenceId) {
            this.occurrenceId = occurrenceId;
            return this;
        }

        public Task build() {
            var taskId = id == null ? UUID.randomUUID() : id;
            var createdAt = creationDateTime == null ? Instant.now(clock) : creationDateTime;
            var task = new Task(
                    taskId,
                    require(name, taskId, "name"),
                    createdAt,
                    startDate == null ? LocalDate.now(clock) : startDate,
                    dueDate,
                    require(context, taskId, "context"),
                    importance == null ? Importance.IMPORTANT : importance,
                    description,
                    status == null ? TaskStatus.OPEN : status,
                    completedOn,
                    taskTemplateId,
                    occurrenceId,
                    List.of(),
                    0L);

            var changes = ObjectUtils.getAllFieldsAndTheirValues(task);
            NOT_FOLDED.forEach(changes::remove);
            var creationPatch = TaskPatch.builder()
                    .taskId(taskId)
                    .dateTime(createdAt)
                    .changes(changes)
                    .build();

            return foldOf(taskId, List.of(creationPatch), 0L);
        }

        private static <T> T require(@Nullable T value, UUID taskId, String field) {
            if (value == null) {
                throw new IllegalStateException("A task cannot be created without a " + field + ": " + taskId);
            }
            return value;
        }
    }

    /// The blank task the fold replays onto. Mutable and private, so the aggregate itself never is.
    private static final class FoldState {

        private @Nullable String name;
        private @Nullable Instant creationDateTime;
        private @Nullable LocalDate startDate;
        private @Nullable LocalDate dueDate;
        private @Nullable String context;
        private @Nullable Importance importance;
        private @Nullable String description;
        private @Nullable TaskStatus status;
        private @Nullable LocalDate completedOn;
        private @Nullable UUID taskTemplateId;
        private @Nullable UUID occurrenceId;

        private Task toTask(UUID taskId, List<TaskPatch> orderedHistory, long version) {
            return new Task(
                    taskId,
                    required(name, taskId, "name"),
                    required(creationDateTime, taskId, "creationDateTime"),
                    required(startDate, taskId, "startDate"),
                    dueDate,
                    required(context, taskId, "context"),
                    importance == null ? Importance.IMPORTANT : importance,
                    description,
                    status == null ? TaskStatus.OPEN : status,
                    completedOn,
                    taskTemplateId,
                    occurrenceId,
                    orderedHistory,
                    version);
        }

        private static <T> T required(@Nullable T value, UUID taskId, String field) {
            if (value == null) {
                throw new IncompleteTaskHistoryException(taskId, field);
            }
            return value;
        }
    }
}

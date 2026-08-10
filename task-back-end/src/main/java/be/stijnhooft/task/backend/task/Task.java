package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.task.util.DateTimeUtils;
import be.stijnhooft.task.backend.task.util.ObjectUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(exclude = "version")
/// Parked by #10 (docs/quality-bar.md). NullAway: Lombok @Builder and @Data cannot prove
/// the non-null fields are initialised; records do not have this problem, which is evidence
/// for #19's 'Lombok is judgment' when this entity is rebuilt under ADR-0004.
///
/// A task receives the time, it never reads it (#44): an entity has no Clock to inject, so
/// every method here that needs *now* takes one from its caller.
@SuppressWarnings("NullAway")
public class Task {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private String name;

    private LocalDateTime creationDateTime;

    private LocalDate startDate;

    @Nullable
    private LocalDate dueDate;

    private String context;

    @Nullable
    private Importance importance;

    @Nullable
    private String description;

    @Builder.Default
    private TaskStatus status = TaskStatus.OPEN;

    @Setter(AccessLevel.PRIVATE)
    @Builder.Default
    @MappedCollection(idColumn = "task_id", keyColumn = "order_index")
    private List<TaskPatch> history = new ArrayList<>();

    @Version
    private long version;

    public void patch(TaskPatch taskPatch) {
        // apply patch
        patchIfNeeded(taskPatch, "name", this::setName);
        patchIfNeeded(taskPatch, "startDate", newValue -> this.setStartDate(DateTimeUtils.parseAsLocalDate(newValue)));
        patchIfNeeded(taskPatch, "dueDate", newValue -> this.setDueDate(DateTimeUtils.parseAsLocalDate(newValue)));
        patchIfNeeded(taskPatch, "context", this::setContext);
        patchIfNeeded(taskPatch, "importance", newValue -> this.setImportance(newValue == null ? null : Importance.valueOf(newValue)));
        patchIfNeeded(taskPatch, "description", this::setDescription);
        patchIfNeeded(taskPatch, "status", newValue -> this.setStatus(newValue == null ? null : TaskStatus.valueOf(newValue)));

        // add patch to history
        if (history == null) {
            history = new ArrayList<>();
        }
        history.add(taskPatch);

        // reapply newer patches
        history.stream()
                .filter(otherUpdate -> otherUpdate.getDateTime().isAfter(taskPatch.getDateTime()))
                .findFirst()
                .ifPresent(this::patch);
    }

    private void patchIfNeeded(TaskPatch taskPatch, String field, Consumer<String> action) {
        if (taskPatch.containsChange(field)) {
            action.accept(taskPatch.getChange(field));
        }
    }

    public void undoPatch(TaskPatch taskPatch, Clock clock) {
        HashMap<String, String> revertedChanges = calculateUndoPatchChanges(taskPatch);

        var undoPatch = TaskPatch.builder()
                .id(UUID.randomUUID())
                .dateTime(LocalDateTime.now(clock))
                .taskId(id)
                .changes(revertedChanges)
                .build();

        patch(undoPatch);
    }

    @JsonIgnore
    public TaskPatch getCreationPatch() {
        return history.getFirst();
    }

    private HashMap<String, String> calculateUndoPatchChanges(TaskPatch taskPatchToUndo) {
        int patchIndexInHistory = history.indexOf(taskPatchToUndo);
        if (patchIndexInHistory < 0) {
            throw new IllegalArgumentException("Patch with id " + taskPatchToUndo.getId() + " is not in the history of task with id " + this.id);
        } else if (patchIndexInHistory == 0) {
            // reverting the "create task" patch. That can't actually be done: once a task is made it is made. We do the next best thing: completing the task
            var changes = new HashMap<String, String>();
            changes.put("status", "COMPLETED");
            return changes;
        } else {
            Task copyOfTaskWithPatchReverted = new Task();
            history.stream()
                    .filter(taskPatch -> !taskPatch.equals(taskPatchToUndo))
                    .forEach(copyOfTaskWithPatchReverted::patch);

            // check what has been reverted and which are the new values
            var revertedChanges = new HashMap<String, String>();
            for (String change : taskPatchToUndo.getChanges().keySet()) {
                Object taskValue = copyOfTaskWithPatchReverted.get(change);
                String revertedPatchValue = taskPatchToUndo.getChange(change);
                if (!Objects.equals(taskValue, revertedPatchValue)) {
                    if (taskValue == null) {
                        revertedChanges.put(change, null);
                    } else {
                        revertedChanges.put(change, taskValue.toString());
                    }
                }
            }
            return revertedChanges;
        }
    }

    @SneakyThrows
    private Object get(String field) {
        return ObjectUtils.getAllFieldsAndTheirValues(this)
                .get(field);
    }

    /// private method, use the other builder methods
    private static TaskBuilder builder() {
        throw new UnsupportedOperationException();
    }

    /// The clock supplies the creation date-time and the start date when the caller does not
    /// set them. It is a parameter rather than a field default because the zone belongs to the
    /// Clock bean (#44) and an entity cannot inject one.
    public static TaskBuilder builderForInitialTask(Clock clock) {
        return new TaskBuilder() {
            public Task build() {
                var task = super.build();

                if (task.creationDateTime == null) {
                    task.creationDateTime = LocalDateTime.now(clock);
                }
                if (task.startDate == null) {
                    task.startDate = LocalDate.now(clock);
                }

                Map<String, String> changes = ObjectUtils.getAllFieldsAndTheirValues(task);
                changes.remove("history");
                var initialPatch = TaskPatch.builder()
                        .id(UUID.randomUUID())
                        .taskId(task.getId())
                        .dateTime(task.getCreationDateTime())
                        .changes(changes)
                        .build();
                task.history.addFirst(initialPatch);

                return task;
            }
        };
    }
}

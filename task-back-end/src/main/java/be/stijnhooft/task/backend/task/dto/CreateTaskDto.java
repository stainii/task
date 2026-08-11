package be.stijnhooft.task.backend.task.dto;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskStatus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;

/// Deleted by #46, which collapses the write surface to one verb: the first patch for a task id
/// creates it, so a whole-task body carries nothing the creation patch does not.
public record CreateTaskDto(
        @NonNull String name,
        @Nullable Instant creationDateTime,
        @Nullable LocalDate startDate,
        @Nullable LocalDate dueDate,
        @NonNull String context,
        @Nullable Importance importance,
        @Nullable String description,
        @Nullable TaskStatus status) {
}

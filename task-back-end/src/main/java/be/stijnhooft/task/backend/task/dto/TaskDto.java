package be.stijnhooft.task.backend.task.dto;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskStatus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskDto(
        UUID id,
        @NonNull String name,
        Instant creationDateTime,
        LocalDate startDate,
        @Nullable LocalDate dueDate,
        @NonNull String context,
        Importance importance,
        @Nullable String description,
        TaskStatus status,
        @Nullable LocalDate completedOn,
        @Nullable UUID taskTemplateId,
        @Nullable UUID occurrenceId,
        List<TaskPatchDto> history) {
}

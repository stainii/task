package be.stijnhooft.task.backend.task.dto;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskStatus;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TaskDto(
        UUID id,
        @NonNull String name,
        LocalDateTime creationDateTime,
        LocalDate startDate,
        LocalDate dueDate,
        @NonNull String context,
        Importance importance,
        String description,
        TaskStatus status,
        List<TaskPatchDto> history) {
}

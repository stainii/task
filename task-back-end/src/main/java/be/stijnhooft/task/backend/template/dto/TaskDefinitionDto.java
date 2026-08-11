package be.stijnhooft.task.backend.template.dto;

import be.stijnhooft.task.backend.task.Importance;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/// The wire shape of one task definition. `importance` may be absent — the domain defaults it to
/// `IMPORTANT` rather than letting a null reach a task that must have one.
public record TaskDefinitionDto(
        @Nullable UUID id,
        String name,
        @Nullable Integer startDateOffsetDays,
        @Nullable Integer dueDateOffsetDays,
        @Nullable Importance importance,
        @Nullable String description) {
}

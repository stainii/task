package be.stijnhooft.task.backend.template.dto;

import be.stijnhooft.task.backend.task.Importance;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/// The wire shape of one task definition. `importance` may be absent — the domain defaults it to
/// `IMPORTANT` rather than letting a null reach a task that must have one.
public record TaskDefinitionDto(
        @Nullable UUID id,

        /// A blank name renders to a blank task name, which `TaskTemplate#render` refuses at firing
        /// time with a loud `IllegalStateException` (TODO-022). Refusing it at save time turns a
        /// template that throws once an hour into a `400` on the screen that caused it.
        @NotBlank String name,
        @Nullable Integer startDateOffsetDays,
        @Nullable Integer dueDateOffsetDays,
        @Nullable Importance importance,
        @Nullable String description) {
}

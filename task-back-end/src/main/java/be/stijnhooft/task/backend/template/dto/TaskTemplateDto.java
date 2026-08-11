package be.stijnhooft.task.backend.template.dto;

import be.stijnhooft.task.backend.template.StoredTrigger;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/// The wire shape of a task template.
///
/// The trigger crosses the wire in its **flat** form ([StoredTrigger]) rather than as a polymorphic
/// JSON object: one shape for the table and the API means one conversion to keep honest instead of
/// two. [#50](https://github.com/stainii/task/issues/50) rebuilds this surface — validation, the
/// create/update split this single record cannot express, and the endpoints for deactivating and
/// running a template — so it is kept deliberately thin here.
public record TaskTemplateDto(
        @Nullable UUID id,
        String name,
        String context,
        boolean active,
        @Nullable LocalDate activeSince,
        StoredTrigger trigger,
        List<TaskDefinitionDto> taskDefinitions) {
}

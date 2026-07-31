package be.stijnhooft.task.backend.template.dto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Map;

public record TaskTemplateEntry(
        Map<String, String> variables,

        @Nullable
        LocalDate startDateOfMainTask,

        @Nullable
        LocalDate dueDateOfMainTask
) {
}

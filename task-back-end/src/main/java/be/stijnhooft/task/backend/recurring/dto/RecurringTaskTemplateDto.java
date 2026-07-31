package be.stijnhooft.task.backend.recurring.dto;

import be.stijnhooft.task.backend.task.Importance;

import java.util.UUID;

public record RecurringTaskTemplateDto(
        UUID id,
        String name,

        /*
         * The minimum number of days between each execution of this task.
         * It's not necessary to execute this task more regularly.
         */
        int minNumberOfDaysBetweenExecutions,

        /*
         * The maximum number of days between each execution of this task.
         * If this value gets exceeded, a final warning will be sent.
         */
        int maxNumberOfDaysBetweenExecutions,

        Importance importance,
        String context,
        String description
) {
}

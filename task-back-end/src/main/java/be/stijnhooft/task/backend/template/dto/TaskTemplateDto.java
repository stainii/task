package be.stijnhooft.task.backend.template.dto;

import be.stijnhooft.task.backend.template.TaskDefinition;
import be.stijnhooft.task.backend.template.TaskTemplateVariableName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record TaskTemplateDto(
        @Nullable UUID id,
        String name,
        List<TaskDefinition> taskDefinitions,
        @Nullable List<TaskTemplateVariableName> variableNames
) {

    @Override
    public List<TaskTemplateVariableName> variableNames() {
        if(variableNames == null) {
            return new ArrayList<>();
        }

        return variableNames;
    }


}

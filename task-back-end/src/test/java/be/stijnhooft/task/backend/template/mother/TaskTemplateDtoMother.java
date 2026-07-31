package be.stijnhooft.task.backend.template.mother;

import be.stijnhooft.task.backend.template.TaskTemplateVariableName;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import org.instancio.Instancio;

import static org.instancio.Select.field;

public class TaskTemplateDtoMother {

    public static TaskTemplateDto createRandomTaskTemplateDto() {
        var taskTemplate = Instancio.of(TaskTemplateDto.class)
                .ignore(field(TaskTemplateDto::variableNames))
                .create();

        // (possibly) add variables to task definitions
        taskTemplate.taskDefinitions()
                .forEach(taskDefinition -> {
                    if ((int) (Math.random() * 5) > 2) {
                        var variableName = Instancio.create(String.class);
                        taskDefinition.setName(taskDefinition.getName() + " ${" + variableName + "}");
                        taskTemplate.variableNames().add(new TaskTemplateVariableName(variableName));
                    }
                });

        return taskTemplate;
    }

}

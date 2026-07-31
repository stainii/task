package be.stijnhooft.task.backend.template.mother;

import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.TaskTemplateVariableName;
import org.instancio.Instancio;

import static org.instancio.Select.field;

public class TaskTemplateMother {

    public static TaskTemplate createRandomTaskTemplate() {
        var taskTemplate = Instancio.of(TaskTemplate.class)
                .ignore(field(TaskTemplate::getVariableNames))
                .ignore(field(TaskTemplate::getVersion))
                .create();

        // (possibly) add variables to task definitions
        taskTemplate.getTaskDefinitions()
                .forEach(taskDefinition -> {
                    if ((int) (Math.random() * 5) > 2) {
                        var variableName = Instancio.create(String.class);
                        taskDefinition.setName(taskDefinition.getName() + " ${" + variableName + "}");
                        taskTemplate.getVariableNames().add(new TaskTemplateVariableName(variableName));
                    }
                });

        return taskTemplate;
    }

}

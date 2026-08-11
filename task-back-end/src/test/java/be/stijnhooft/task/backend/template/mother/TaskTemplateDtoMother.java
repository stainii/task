package be.stijnhooft.task.backend.template.mother;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;

import java.util.List;
import java.util.UUID;

public class TaskTemplateDtoMother {

    public static TaskTemplateDto manualTemplateDto() {
        return templateDtoWith(new Trigger.Manual("When is the workshop?"));
    }

    public static TaskTemplateDto templateDtoWith(Trigger trigger) {
        return new TaskTemplateDto(
                UUID.randomUUID(),
                "Template " + UUID.randomUUID(),
                "house",
                true,
                null,
                StoredTrigger.of(trigger),
                List.of(new TaskDefinitionDto(UUID.randomUUID(), "Beddengoed wassen ${who}",
                        0, 2, Importance.IMPORTANT, null)));
    }
}

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

    /// Variables are manual-only, so the default definition carries none: a mother that put a
    /// `${…}` in every template would make every scheduled shape unsavable.
    public static TaskTemplateDto templateDtoWith(Trigger trigger) {
        return templateDtoWith(trigger, "Beddengoed wassen");
    }

    /// A manual template that asks a question — the only shape allowed to.
    public static TaskTemplateDto manualTemplateDtoWithVariables() {
        return templateDtoWith(new Trigger.Manual("When is the workshop?"), "Beddengoed wassen ${who}");
    }

    public static TaskTemplateDto templateDtoWith(Trigger trigger, String definitionName) {
        return new TaskTemplateDto(
                UUID.randomUUID(),
                "Template " + UUID.randomUUID(),
                "house",
                true,
                null,
                StoredTrigger.of(trigger),
                List.of(new TaskDefinitionDto(UUID.randomUUID(), definitionName,
                        0, 2, Importance.IMPORTANT, null)),
                null);
    }
}

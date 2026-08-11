package be.stijnhooft.task.backend.template.mapper;

import be.stijnhooft.task.backend.template.TaskDefinition;
import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/// Hand-written rather than MapStruct, for one reason: the aggregate is a record with an embedded
/// trigger, so there is no `@MappingTarget` to update in place — every "update" is a new instance
/// that must decide, explicitly, which of the server's own values survive. `id`, `version` and
/// `activeSince` are the server's; a generated in-place mapper would have quietly let a client
/// overwrite all three.
@Component
public class TaskTemplateMapper {

    /// A template as the client asked for it, with the server owning identity and the clock.
    /// `activeSince` is *today* on creation, never whatever the payload said.
    public TaskTemplate toNewDomain(TaskTemplateDto dto, LocalDate today) {
        return new TaskTemplate(
                dto.id() == null ? UUID.randomUUID() : dto.id(),
                dto.name(),
                dto.context(),
                dto.active(),
                today,
                dto.trigger(),
                toDefinitions(dto.taskDefinitions()),
                0L);
    }

    /// Applies an edit to an existing template, keeping `id`, `version` and `activeSince` — the
    /// three values a client does not own. Whether the new trigger *moves* `activeSince` is the
    /// service's call, not this one's, because that depends on comparing the two.
    public TaskTemplate applyEdit(TaskTemplateDto dto, TaskTemplate existing) {
        return new TaskTemplate(
                existing.id(),
                dto.name(),
                dto.context(),
                dto.active(),
                existing.activeSince(),
                dto.trigger(),
                toDefinitions(dto.taskDefinitions()),
                existing.version());
    }

    public TaskTemplateDto toDto(TaskTemplate template) {
        return new TaskTemplateDto(
                template.id(),
                template.name(),
                template.context(),
                template.active(),
                template.activeSince(),
                template.storedTrigger(),
                template.taskDefinitions().stream()
                        .map(definition -> new TaskDefinitionDto(
                                definition.id(),
                                definition.name(),
                                definition.startDateOffsetDays(),
                                definition.dueDateOffsetDays(),
                                definition.importance(),
                                definition.description()))
                        .toList());
    }

    public List<TaskTemplateDto> toDtos(Iterable<TaskTemplate> templates) {
        return StreamSupport.stream(templates.spliterator(), false)
                .map(this::toDto)
                .toList();
    }

    private List<TaskDefinition> toDefinitions(List<TaskDefinitionDto> dtos) {
        return dtos.stream()
                .map(dto -> TaskDefinition.of(
                        dto.id() == null ? UUID.randomUUID() : dto.id(),
                        dto.name(),
                        dto.startDateOffsetDays(),
                        dto.dueDateOffsetDays(),
                        dto.importance(),
                        dto.description()))
                .toList();
    }
}

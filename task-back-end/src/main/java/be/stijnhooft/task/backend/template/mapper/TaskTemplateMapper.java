package be.stijnhooft.task.backend.template.mapper;

import be.stijnhooft.task.backend.template.domain.TaskDefinition;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import org.springframework.stereotype.Component;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/// Hand-written rather than MapStruct, for one reason: the aggregate is a record with an embedded
/// trigger, so there is no `@MappingTarget` to update in place — every "update" is a new instance
/// that must decide, explicitly, which of the server's own values survive. `id`, `version` and
/// `activeSince` are the server's; a generated in-place mapper would have quietly let a client
/// overwrite all three.
@Component
public class TaskTemplateMapper {

    /// A template as the client asked for it, with the server owning identity and the clock.
    /// `activeSince` is *today* on creation, never whatever the payload said — and a new template is
    /// **active**, whatever the payload said, because deactivating something that has never fired is
    /// a way of writing "no".
    public TaskTemplate toNewDomain(TaskTemplateDto dto, LocalDate today) {
        return new TaskTemplate(
                dto.id() == null ? UUID.randomUUID() : dto.id(),
                dto.name(),
                dto.context(),
                true,
                today,
                dto.trigger(),
                toDefinitions(dto.taskDefinitions()),
                0L);
    }

    /// Applies an edit to an existing template, keeping `id`, `version`, `active` and `activeSince` —
    /// the four values a client does not own.
    ///
    /// **`active` is kept deliberately**, and it is the field this mapper exists for. Taking it from
    /// the payload would make a `PUT` a second path to reactivation, and that path does not write
    /// `activeSince` — so a calendar template switched back on by an edit would immediately catch up
    /// on a date it spent the pause not firing for. Activation has its own two endpoints so that
    /// changing it and moving `activeSince` cannot come apart.
    ///
    /// Whether the new trigger *moves* `activeSince` is the service's call, not this one's, because
    /// that depends on comparing the two.
    public TaskTemplate applyEdit(TaskTemplateDto dto, TaskTemplate existing) {
        return new TaskTemplate(
                existing.id(),
                dto.name(),
                dto.context(),
                existing.active(),
                existing.activeSince(),
                dto.trigger(),
                toDefinitions(dto.taskDefinitions()),
                existing.version());
    }

    /// `lastCompletedOn` is passed in rather than read from the template: it is a fact about the
    /// template's **tasks**, derived by the service from `task`'s query port (ADR-0011), and the
    /// domain `TaskTemplate` has no field for it. Null is a legitimate value — a template no chore
    /// of which has ever been completed — and the caller decides whether to derive it at all
    /// (`GET /api/task-templates` must; the mutation responses need not).
    public TaskTemplateDto toDto(TaskTemplate template, @Nullable LocalDate lastCompletedOn) {
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
                        .toList(),
                lastCompletedOn);
    }

    /// The list mapping. `lastCompletedOn` looks up each template's last completion through the
    /// supplied function — `taskOccurrences.lastCompletionOf(id).orElse(null)` at the call site.
    ///
    /// One `SELECT MAX(completed_on)` per template (N+1): negligible at the current ~43 templates
    /// (#85). If template volume grows an order of magnitude, widen the port to a batched
    /// `GROUP BY` / `IN` method and pass a prebuilt map here instead.
    public List<TaskTemplateDto> toDtos(Iterable<TaskTemplate> templates,
                                        Function<UUID, @Nullable LocalDate> lastCompletedOn) {
        return StreamSupport.stream(templates.spliterator(), false)
                .map(template -> toDto(template, lastCompletedOn.apply(template.id())))
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

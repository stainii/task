package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import be.stijnhooft.task.backend.template.TaskDefinition;
import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateAlreadyExistsException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateNotFoundException;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static be.stijnhooft.task.backend.template.util.VariableUtils.fillInVariables;

@Service
@RequiredArgsConstructor
public class TaskTemplateService {

    private final TaskTemplateRepository taskTemplateRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public Iterable<TaskTemplate> findAll() {
        return taskTemplateRepository.findAll();
    }

    public Optional<TaskTemplate> findById(UUID id) {
        return taskTemplateRepository.findById(id);
    }

    public TaskTemplate create(TaskTemplate taskTemplate) {
        if (taskTemplateRepository.existsById(taskTemplate.id())) {
            throw new TaskTemplateAlreadyExistsException(taskTemplate.id());
        }
        return taskTemplateRepository.save(taskTemplate);
    }

    /// Saves an edit, and **moves `activeSince` when the trigger changed**.
    ///
    /// That is one of the field's three writing events (ADR-0017), and the one that is easy to
    /// miss: a bin template that has fired every Tuesday since January, re-ruled to Thursdays,
    /// finds no task on any Thursday and would immediately fire a backdated one. Resetting can only
    /// ever prevent a firing, never lose one.
    ///
    /// The other two writes — deactivate and reactivate — arrive with their endpoints in
    /// [#50](https://github.com/stainii/task/issues/50).
    public TaskTemplate update(TaskTemplate edited, TaskTemplate existing) {
        var withTrigger = edited.storedTrigger().equals(existing.storedTrigger())
                ? edited
                : edited.withTrigger(edited.trigger(), LocalDate.now(clock));
        return taskTemplateRepository.save(withTrigger);
    }

    public void delete(UUID id) {
        if (!taskTemplateRepository.existsById(id)) {
            throw new TaskTemplateNotFoundException(id);
        }
        taskTemplateRepository.deleteById(id);
    }

    /// Renders a template into tasks and hands them over. One firing, one `occurrenceId`: an
    /// occurrence is not stored anywhere, so this group key is all that remains of it.
    ///
    /// Every definition's dates come from **one anchor** — the date typed for a manual template,
    /// the firing date for a scheduled one — and each definition's own two offsets.
    public List<Task> createTasksWithTemplate(TaskTemplate taskTemplate, TaskTemplateEntry entry) {
        var tasks = renderTasks(taskTemplate, entry.variables(), entry.anchorDate(), null);
        eventPublisher.publishEvent(new TaskCreationRequestedEvent(tasks));
        return tasks;
    }

    /// Renders a firing's tasks without publishing them, so a caller that already knows the anchor
    /// and the due date — the scheduler — can use the same renderer.
    ///
    /// The name is rendered **before any task is built**: a template whose name resolves to nothing
    /// fails loudly and creates none of its tasks, rather than part of them (TODO-022).
    public List<Task> renderTasks(TaskTemplate taskTemplate, Map<String, String> variables,
                                  @Nullable LocalDate anchor, @Nullable LocalDate defaultDueDate) {
        var occurrenceId = UUID.randomUUID();
        var context = fillInVariables(taskTemplate.context(), variables)
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + taskTemplate.id() + " renders to an empty context."));

        return taskTemplate.taskDefinitions().stream()
                .map(definition -> render(taskTemplate, definition, variables, context, anchor, defaultDueDate, occurrenceId))
                .toList();
    }

    private Task render(TaskTemplate taskTemplate, TaskDefinition definition, Map<String, String> variables,
                        String context, @Nullable LocalDate anchor, @Nullable LocalDate defaultDueDate,
                        UUID occurrenceId) {
        var name = fillInVariables(definition.name(), variables)
                .filter(rendered -> !rendered.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition " + definition.id() + " of template " + taskTemplate.id()
                                + " renders to an empty name."));

        return Task.builderForInitialTask(clock)
                .name(name)
                .context(context)
                .description(fillInVariables(definition.description(), variables).orElse(null))
                .importance(definition.importance())
                .startDate(definition.startDateFrom(anchor).orElse(null))
                .dueDate(definition.dueDateFrom(anchor).orElse(defaultDueDate))
                .taskTemplateId(taskTemplate.id())
                .occurrenceId(occurrenceId)
                .build();
    }
}

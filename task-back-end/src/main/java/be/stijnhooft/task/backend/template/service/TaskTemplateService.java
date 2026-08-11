package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.task.TaskTemplateFired;
import be.stijnhooft.task.backend.template.domain.TaskDefinition;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
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

    /// Runs a template by hand: it fires **today**, anchored on whatever date the person typed.
    ///
    /// Firing date and anchor are two different dates and this is the case that shows it. The
    /// anchor is the workshop, the trip, the thing the tasks are *about*, and it can be months out;
    /// the firing date is when the template came round, which for a manual run is now.
    public void createTasksWithTemplate(TaskTemplate taskTemplate, TaskTemplateEntry entry) {
        eventPublisher.publishEvent(
                render(taskTemplate, entry.variables(), LocalDate.now(clock), entry.anchorDate(), null));
    }

    /// Renders a firing into the fact that it happened: `${…}` substituted, offsets resolved to
    /// real dates, nothing left for the listener to look up.
    ///
    /// **Rendering lives here, in the publisher**, because `TaskDefinition` owns the placeholders
    /// and the offsets (ADR-0002). What crosses the module boundary is the result, never a `Task` —
    /// building one of those is `task`'s own business, and it was `template` constructing them that
    /// kept `Task` in the exposed base package.
    ///
    /// The template's **name** is rendered before any definition is: a template whose name resolves
    /// to nothing fails loudly and produces no tasks at all, rather than some of them (TODO-022).
    ///
    /// @param firingDate  the date the template came round — today for a manual run, the rule's
    ///                    date for a scheduled one, which after an outage is in the past
    /// @param anchor  the date the firing's tasks are measured from, or null when a manual template
    ///                was run without one
    /// @param defaultDueDate  the due date a definition with no due offset falls back to — a
    ///                        `MinMax` trigger's `max`, and null for every other shape
    public TaskTemplateFired render(TaskTemplate taskTemplate, Map<String, String> variables,
                                    LocalDate firingDate, @Nullable LocalDate anchor,
                                    @Nullable LocalDate defaultDueDate) {
        var context = fillInVariables(taskTemplate.context(), variables)
                .filter(rendered -> !rendered.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + taskTemplate.id() + " renders to an empty context."));

        var definitions = taskTemplate.taskDefinitions().stream()
                .map(definition -> render(taskTemplate, definition, variables, firingDate, anchor, defaultDueDate))
                .toList();

        return new TaskTemplateFired(taskTemplate.id(), UUID.randomUUID(), firingDate, context, definitions);
    }

    private TaskTemplateFired.RenderedDefinition render(TaskTemplate taskTemplate, TaskDefinition definition,
                                                       Map<String, String> variables, LocalDate firingDate,
                                                       @Nullable LocalDate anchor,
                                                       @Nullable LocalDate defaultDueDate) {
        var name = fillInVariables(definition.name(), variables)
                .filter(rendered -> !rendered.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition " + definition.id() + " of template " + taskTemplate.id()
                                + " renders to an empty name."));

        return new TaskTemplateFired.RenderedDefinition(
                name,
                fillInVariables(definition.description(), variables).orElse(null),
                definition.importance(),
                // No start offset means the task starts the day the template came round. It used to
                // mean "today", which is the same date for a manual run and the wrong one for a
                // calendar template catching up on a date it slept through.
                definition.startDateFrom(anchor).orElse(firingDate),
                definition.dueDateFrom(anchor).orElse(defaultDueDate));
    }
}

package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.task.TaskOccurrences;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateAlreadyExistsException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateInUseException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateNotFoundException;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskTemplateService {

    private final TaskTemplateRepository taskTemplateRepository;
    private final TaskOccurrences taskOccurrences;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public Iterable<TaskTemplate> findAll() {
        return taskTemplateRepository.findAll();
    }

    public Optional<TaskTemplate> findById(UUID id) {
        return taskTemplateRepository.findById(id);
    }

    public TaskTemplate findOne(UUID id) {
        return taskTemplateRepository.findById(id)
                .orElseThrow(() -> new TaskTemplateNotFoundException(id));
    }

    public TaskTemplate create(TaskTemplate taskTemplate) {
        if (taskTemplateRepository.existsById(taskTemplate.id())) {
            throw new TaskTemplateAlreadyExistsException(taskTemplate.id());
        }
        taskTemplate.validateForSaving();
        return taskTemplateRepository.save(taskTemplate);
    }

    /// Saves an edit, and **moves `activeSince` when the trigger changed**.
    ///
    /// That is one of the three writing events (ADR-0017), and the one that is easy to miss: a bin
    /// template that has fired every Tuesday since January, re-ruled to Thursdays, finds no task on
    /// any Thursday and would immediately fire a backdated one. Resetting can only ever prevent a
    /// firing, never lose one.
    ///
    /// The other two writes are [#deactivate] and [#reactivate], which is *why* an edit cannot flip
    /// `active` — see `TaskTemplateMapper#applyEdit`. Routing an activation change through here
    /// would have given it a path that writes `activeSince` and one that does not.
    public TaskTemplate update(TaskTemplate edited, TaskTemplate existing) {
        edited.validateForSaving();
        var withTrigger = edited.storedTrigger().equals(existing.storedTrigger())
                ? edited
                : edited.withTrigger(edited.trigger(), LocalDate.now(clock));
        return taskTemplateRepository.save(withTrigger);
    }

    public TaskTemplate deactivate(UUID id) {
        return taskTemplateRepository.save(findOne(id).deactivated(LocalDate.now(clock)));
    }

    public TaskTemplate reactivate(UUID id) {
        return taskTemplateRepository.save(findOne(id).reactivated(LocalDate.now(clock)));
    }

    /// **Deletion survives only while a template has no tasks at all** — the typo you made a minute
    /// ago, and nothing else. Anything with history is deactivated instead, because `taskTemplateId`
    /// is the only provenance link a task has now that an occurrence is derived rather than stored,
    /// and portal measured what breaking it costs: 49% of its recurring tasks point at a template
    /// that was deleted out from under them ([#35](https://github.com/stainii/task/issues/35)).
    ///
    /// The check is a count, not a judgement — *closed* tasks are history too, so an old template
    /// whose every task was completed years ago is exactly the one this protects.
    public void delete(UUID id) {
        if (!taskTemplateRepository.existsById(id)) {
            throw new TaskTemplateNotFoundException(id);
        }
        if (taskOccurrences.hasAnyOccurrence(id)) {
            throw new TaskTemplateInUseException(id);
        }
        taskTemplateRepository.deleteById(id);
    }

    /// Runs a template by hand: it fires **today**, anchored on whatever date the person typed.
    ///
    /// Firing date and anchor are two different dates and this is the case that shows it. The anchor
    /// is the workshop, the trip, the thing the tasks are *about*, and it can be months out; the
    /// firing date is when the template came round, which for a manual run is now.
    ///
    /// **The tasks are created immediately** (ADR-0013): the front-end's preview is the check, and
    /// it renders from the same fixtures this rendering is pinned by, so there is no confirmation
    /// step and nothing to acknowledge here.
    public void createTasksWithTemplate(TaskTemplate taskTemplate, TaskTemplateEntry entry) {
        eventPublisher.publishEvent(
                taskTemplate.render(entry.variables(), LocalDate.now(clock), entry.anchorDate()));
    }
}

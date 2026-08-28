package be.stijnhooft.task.backend.template.controller;

import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateInvalidException;
import be.stijnhooft.task.backend.template.mapper.TaskTemplateMapper;
import be.stijnhooft.task.backend.template.service.TaskTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/// The one CRUD surface over templates, now that there is one aggregate: the recurring controller
/// died with `RecurringTaskTemplate`.
///
/// Four things about the shape of it are the corrections `docs/quality-bar.md` §6 turned into
/// project-wide rules after portal's controller broke each of them (**D4**):
///
/// - **Every mapping names its verb.** Portal's `@RequestMapping("/{id}")` answered *all* of them,
///   so a `PATCH` returned the template instead of `405`.
/// - **No trailing slashes.** `@RequestMapping("/")` stopped matching in Spring Boot 3, which is
///   why `GET /api/recurring-task-templates` returned `404` while its siblings worked.
/// - **The service layer, never the repository.** (REC-013)
/// - **`@Valid` on every body**, so a template that could never render anything is a `400` on the
///   screen that caused it rather than an ERROR line once an hour for ever.
///
/// ### Deactivation is two endpoints, not a field
///
/// `active` is read-only on the DTO. Both writes move `activeSince`, and giving them their own
/// endpoints is what makes that unconditional — an edit that could flip the field would be a path to
/// reactivation that forgot to move the date, and a calendar template would come back catching up on
/// the months it was switched off.
@RestController
@RequestMapping("/api/task-templates")
@RequiredArgsConstructor
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;
    private final TaskTemplateMapper taskTemplateMapper;
    private final Clock clock;

    /// The one response that reaches the client's store: `TemplateService.save/deactivate/reactivate`
    /// all discard the mutation response and immediately `refresh()` through this list. So this is
    /// where `lastCompletedOn` has to be derived — the mutation responses below carry it too, for a
    /// DTO that never lies, but nothing observes it there.
    @GetMapping
    public List<TaskTemplateDto> findAllTemplates() {
        return taskTemplateMapper.toDtos(
                taskTemplateService.findAll(), taskTemplateService::lastCompletionOf);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskTemplateDto create(@Valid @RequestBody TaskTemplateDto taskTemplate) {
        var created = taskTemplateService.create(
                taskTemplateMapper.toNewDomain(taskTemplate, LocalDate.now(clock)));
        // A brand-new template has no tasks, so its last completion is null without asking.
        return taskTemplateMapper.toDto(created, null);
    }

    @PutMapping("/{id}")
    public TaskTemplateDto update(@Valid @RequestBody TaskTemplateDto taskTemplate, @PathVariable UUID id) {
        if (!id.equals(taskTemplate.id())) {
            throw new TaskTemplateInvalidException("The id in the url is not the same as the id in the payload.");
        }

        var existing = taskTemplateService.findOne(id);

        var updated = taskTemplateService.update(taskTemplateMapper.applyEdit(taskTemplate, existing), existing);
        return taskTemplateMapper.toDto(updated, taskTemplateService.lastCompletionOf(updated.id()));
    }

    /// **Stop firing.** The template keeps its tasks and its history; it drops out of the list.
    @PostMapping("/{id}/deactivation")
    public TaskTemplateDto deactivate(@PathVariable UUID id) {
        var deactivated = taskTemplateService.deactivate(id);
        return taskTemplateMapper.toDto(deactivated, taskTemplateService.lastCompletionOf(id));
    }

    /// **Start firing again, from today** — never from where it left off.
    @PostMapping("/{id}/reactivation")
    public TaskTemplateDto reactivate(@PathVariable UUID id) {
        var reactivated = taskTemplateService.reactivate(id);
        return taskTemplateMapper.toDto(reactivated, taskTemplateService.lastCompletionOf(id));
    }

    /// `409` once the template has produced a single task, whatever became of it. Deleting is for
    /// the template you created by mistake a minute ago; everything else is deactivated.
    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        taskTemplateService.delete(id);
    }

    /// **Running a template creates its tasks immediately** (ADR-0013). The check is the preview the
    /// front-end renders before the call, from the same `/render-fixtures/` this rendering is pinned
    /// by — not a confirmation step here, because the result is ordinary tasks that are editable one
    /// screen later.
    ///
    /// There is no successor to portal's `POST /{id}/execution/`: *"I already did this"* is a
    /// client-minted task through the patch outbox, because it has to work offline
    /// ([ADR-0011](../../../../../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)).
    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public void createTasksWithTemplate(@PathVariable UUID id, @Valid @RequestBody TaskTemplateEntry taskTemplateEntry) {
        taskTemplateService.createTasksWithTemplate(taskTemplateService.findOne(id), taskTemplateEntry);
    }
}

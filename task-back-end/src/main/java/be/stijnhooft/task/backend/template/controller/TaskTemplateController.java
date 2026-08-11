package be.stijnhooft.task.backend.template.controller;

import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateInvalidException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateNotFoundException;
import be.stijnhooft.task.backend.template.mapper.TaskTemplateMapper;
import be.stijnhooft.task.backend.template.service.TaskTemplateService;
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
/// [#50](https://github.com/stainii/task/issues/50) rebuilds this — `@Valid` on the bodies, the
/// create/update split a single DTO cannot express, deactivate/reactivate instead of delete once a
/// template has tasks, and the run-a-template flow. What lands here is the merge, not the new API.
@RestController
@RequestMapping("/api/task-templates")
@RequiredArgsConstructor
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;
    private final TaskTemplateMapper taskTemplateMapper;
    private final Clock clock;

    @GetMapping
    public List<TaskTemplateDto> findAllTemplates() {
        return taskTemplateMapper.toDtos(taskTemplateService.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskTemplateDto create(@RequestBody TaskTemplateDto taskTemplate) {
        var created = taskTemplateService.create(
                taskTemplateMapper.toNewDomain(taskTemplate, LocalDate.now(clock)));
        return taskTemplateMapper.toDto(created);
    }

    @PutMapping("/{id}")
    public TaskTemplateDto update(@RequestBody TaskTemplateDto taskTemplate, @PathVariable UUID id) {
        if (!id.equals(taskTemplate.id())) {
            throw new TaskTemplateInvalidException("The id in the url is not the same as the id in the payload.");
        }

        var existing = taskTemplateService.findById(id)
                .orElseThrow(() -> new TaskTemplateNotFoundException(id));

        var updated = taskTemplateService.update(taskTemplateMapper.applyEdit(taskTemplate, existing), existing);
        return taskTemplateMapper.toDto(updated);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        taskTemplateService.delete(id);
    }

    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public void createTasksWithTemplate(@PathVariable UUID id, @RequestBody TaskTemplateEntry taskTemplateEntry) {
        var taskTemplate = taskTemplateService.findById(id)
                .orElseThrow(() -> new TaskTemplateNotFoundException(id));
        taskTemplateService.createTasksWithTemplate(taskTemplate, taskTemplateEntry);
    }
}

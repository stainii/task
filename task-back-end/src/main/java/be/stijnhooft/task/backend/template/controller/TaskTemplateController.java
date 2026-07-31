package be.stijnhooft.task.backend.template.controller;

import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateInvalidException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateNotFoundException;
import be.stijnhooft.task.backend.template.mapper.TaskTemplateMapper;
import be.stijnhooft.task.backend.template.service.TaskTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/task-templates")
@RequiredArgsConstructor
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;
    private final TaskTemplateMapper taskTemplateMapper;

    @GetMapping
    public Iterable<TaskTemplateDto> findAllTemplates() {
        return taskTemplateMapper.toDtos(
                taskTemplateService.findAll()
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskTemplate create(@RequestBody TaskTemplateDto taskTemplate) {
        return taskTemplateService.create(
                taskTemplateMapper.toDomain(taskTemplate)
        );
    }

    @PutMapping("/{id}")
    public TaskTemplate update(@RequestBody TaskTemplateDto taskTemplate, @PathVariable UUID id) {
        if (!id.equals(taskTemplate.id())) {
            throw new TaskTemplateInvalidException("The id in the url is not the same as the id in the payload.");
        }

        var originalTaskTemplate = taskTemplateService.findById(id)
                .orElseThrow(() -> new TaskTemplateNotFoundException(taskTemplate.id()));
        var updatedTaskTemplate = taskTemplateMapper.updateDomain(taskTemplate, originalTaskTemplate);

        return taskTemplateService.update(updatedTaskTemplate);
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

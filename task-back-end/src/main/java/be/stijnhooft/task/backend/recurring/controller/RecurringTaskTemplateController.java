package be.stijnhooft.task.backend.recurring.controller;

import be.stijnhooft.task.backend.recurring.Execution;
import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.dto.ExecutionDto;
import be.stijnhooft.task.backend.recurring.dto.RecurringTaskTemplateDto;
import be.stijnhooft.task.backend.recurring.exception.RecurringTaskTemplateNotFoundException;
import be.stijnhooft.task.backend.recurring.exception.UpdatingIdIsNotAllowedException;
import be.stijnhooft.task.backend.recurring.mapper.RecurringTaskTemplateMapper;
import be.stijnhooft.task.backend.recurring.repository.RecurringTaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurring-task-templates")
@RequiredArgsConstructor
public class RecurringTaskTemplateController {

    private final RecurringTaskTemplateRepository repo;
    private final RecurringTaskTemplateMapper mapper;

    @RequestMapping("/")
    public Iterable<RecurringTaskTemplateDto> findAll() {
        return mapper.toDtos(repo.findAll());
    }

    @RequestMapping("/{id}")
    public ResponseEntity<RecurringTaskTemplateDto> findById(@PathVariable UUID id) {
        Optional<RecurringTaskTemplate> recurringTask = repo.findById(id);
        return recurringTask
                .map(mapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringTaskTemplateDto create(@RequestBody RecurringTaskTemplateDto recurringTaskTemplate) {
        var savedRecurringTaskTemplate = repo.save(
                mapper.toDomain(recurringTaskTemplate)
        );
        return mapper.toDto(savedRecurringTaskTemplate);
    }

    @PutMapping("/{id}")
    public RecurringTaskTemplateDto update(@RequestBody RecurringTaskTemplateDto recurringTaskTemplate, @PathVariable UUID id) {
        if (!id.equals(recurringTaskTemplate.id())) {
            throw new UpdatingIdIsNotAllowedException();
        }

        var recurringTaskTemplateToUpdate = repo.findById(id)
                .orElseThrow(() -> new RecurringTaskTemplateNotFoundException(id));

        var updatedDomainToPersist = mapper.updateDomain(recurringTaskTemplate, recurringTaskTemplateToUpdate);
        var updatedDomain = repo.save(updatedDomainToPersist);

        return mapper.toDto(updatedDomain);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        repo.deleteById(id);
    }

    @PostMapping("/{id}/execution")
    @ResponseStatus(HttpStatus.CREATED)
    public void addExecution(@RequestBody ExecutionDto execution, @PathVariable("id") UUID recurringTaskId) {
        var recurringTaskTemplate = repo.findById(recurringTaskId)
                .orElseThrow(() -> new RecurringTaskTemplateNotFoundException(recurringTaskId));

        recurringTaskTemplate.addExecution(
                Execution.builder()
                        .id(UUID.randomUUID())
                        .date(execution.date())
                        .build()
        );
        repo.save(recurringTaskTemplate);
    }
}

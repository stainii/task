package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import be.stijnhooft.task.backend.template.DeviationBase;
import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import be.stijnhooft.task.backend.template.exception.TaskTemplateAlreadyExistsException;
import be.stijnhooft.task.backend.template.exception.TaskTemplateNotFoundException;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static be.stijnhooft.task.backend.template.util.DateTimeUtils.addDaysTo;
import static be.stijnhooft.task.backend.template.util.VariableUtils.fillInVariables;

@Service
@RequiredArgsConstructor
public class TaskTemplateService {

    private final TaskTemplateRepository taskTemplateRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Iterable<TaskTemplate> findAll() {
        return taskTemplateRepository.findAll();
    }

    public TaskTemplate create(TaskTemplate taskTemplate) {
        if (taskTemplateRepository.existsById(taskTemplate.getId())) {
            throw new TaskTemplateAlreadyExistsException(taskTemplate.getId());
        }
        return taskTemplateRepository.save(taskTemplate);
    }

    public TaskTemplate update(TaskTemplate taskTemplate) {
        if (!taskTemplateRepository.existsById(taskTemplate.getId())) {
            throw new TaskTemplateNotFoundException(taskTemplate.getId());
        }
        return taskTemplateRepository.save(taskTemplate);
    }

    public void delete(UUID id) {
        if (!taskTemplateRepository.existsById(id)) {
            throw new TaskTemplateNotFoundException(id);
        }
        taskTemplateRepository.deleteById(id);
    }

    public Optional<TaskTemplate> findById(UUID id) {
        return taskTemplateRepository.findById(id);
    }

    public void createTasksWithTemplate(TaskTemplate taskTemplate, TaskTemplateEntry taskTemplateEntry) {
        var tasks = taskTemplate.getTaskDefinitions()
                .stream()
                .map(taskDefinition -> {
                    // fill in all variables in all strings
                    String name = fillInVariables(taskDefinition.getName(), taskTemplateEntry.variables())
                            .orElseThrow(() -> new IllegalStateException("A task should always have a name, but after filling in the variables, the name is empty."));
                    String description = fillInVariables(taskDefinition.getDescription(), taskTemplateEntry.variables())
                            .orElse(null);
                    String context = fillInVariables(taskDefinition.getContext(), taskTemplateEntry.variables())
                            .orElse(null);

                    // calculate dates
                    var startDate = calculateDateWithDeviation(taskDefinition.getStartDateDeviationDays(), taskDefinition.getStartDateDeviationBase(), taskTemplateEntry.startDateOfMainTask(), taskTemplateEntry.dueDateOfMainTask())
                            .orElse(null);
                    var dueDate = calculateDateWithDeviation(taskDefinition.getDueDateDeviationDays(), taskDefinition.getDueDateDeviationBase(), taskTemplateEntry.startDateOfMainTask(), taskTemplateEntry.dueDateOfMainTask())
                            .orElse(null);

                    // other variables
                    var importance = taskDefinition.getImportance();

                    // assemble task
                    return Task.builderForInitialTask()
                            .name(name)
                            .startDate(startDate)
                            .dueDate(dueDate)
                            .context(context)
                            .importance(importance)
                            .description(description)
                            .build();
                })
                .collect(Collectors.toList());
        eventPublisher.publishEvent(new TaskCreationRequestedEvent(tasks));
    }

    private Optional<LocalDate> calculateDateWithDeviation(@Nullable Integer deviationDays,
                                                           @Nullable DeviationBase deviationBase,
                                                           @Nullable LocalDate startDateOfMainTask,
                                                           @Nullable LocalDate dueDateOfMainTask) {
        if (deviationBase == null) {
            return Optional.empty();
        }

        return switch (deviationBase) {
            case START_DATE -> addDaysTo(startDateOfMainTask, deviationDays);
            case DUE_DATE -> addDaysTo(dueDateOfMainTask, deviationDays);
        };
    }

}

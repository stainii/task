package be.stijnhooft.task.backend.recurring.scheduler;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.repository.RecurringTaskTemplateRepository;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
@Slf4j
@EnableScheduling
@Transactional
public class CreateDueTasks {

    public static final String $_RECURRING_TASKS_CREATE_DUE_TASKS_CRON = "${recurring-tasks.create-due-tasks.cron}";
    private final RecurringTaskTemplateRepository recurringTaskTemplateRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    public CreateDueTasks(RecurringTaskTemplateRepository recurringTaskTemplateRepository,
                          @Value($_RECURRING_TASKS_CREATE_DUE_TASKS_CRON) String cron,
                          ApplicationEventPublisher applicationEventPublisher,
                          Clock clock) {
        this.recurringTaskTemplateRepository = recurringTaskTemplateRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
        log.info("I will publish overtime recurring tasks following this cron: {}", cron);
    }

    @Scheduled(cron = $_RECURRING_TASKS_CREATE_DUE_TASKS_CRON)
    public void createDueTasks() {
        log.info("Checking if recurring tasks are due and a concrete task should be created for them");

        var overtimeRecurringTaskTemplates = findDueRecurringTaskTemplatesForWhichTaskShouldBeCreated();

        var tasksToCreate = overtimeRecurringTaskTemplates.stream()
                .map(this::mapToTask)
                .toList();
        applicationEventPublisher.publishEvent(new TaskCreationRequestedEvent(tasksToCreate));

        overtimeRecurringTaskTemplates.forEach(recurringTaskTemplate -> {
            recurringTaskTemplate.setActiveTask(true);
            recurringTaskTemplateRepository.save(recurringTaskTemplate);
        });
    }

    private Set<RecurringTaskTemplate> findDueRecurringTaskTemplatesForWhichTaskShouldBeCreated() {
        return StreamSupport.stream(recurringTaskTemplateRepository.findAll().spliterator(), false)
                .filter(recurringTask -> recurringTask.shouldTaskBeCreatedBecauseItIsDue(LocalDate.now(clock)))
                .collect(Collectors.toSet());
    }

    private Task mapToTask(RecurringTaskTemplate recurringTaskTemplate) {
        return Task.builderForInitialTask(clock)
                .name(recurringTaskTemplate.getName())
                .creationDateTime(LocalDateTime.now(clock))
                .dueDate(recurringTaskTemplate.getLastExecutionDateOrCreationDate().plusDays(recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions()))
                .importance(recurringTaskTemplate.getImportance())
                .context(recurringTaskTemplate.getContext())
                .description(recurringTaskTemplate.getDescription())
                .build();
    }

}

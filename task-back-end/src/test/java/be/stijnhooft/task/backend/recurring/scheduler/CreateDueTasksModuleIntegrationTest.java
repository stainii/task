package be.stijnhooft.task.backend.recurring.scheduler;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.recurring.Execution;
import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.repository.RecurringTaskTemplateRepository;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static be.stijnhooft.task.backend.recurring.mother.RecurringTaskTemplateMother.createRandomRecurringTaskTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(
        extraIncludes = "config",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CreateDueTasksModuleIntegrationTest extends AbstractIntegrationTestCases {

    @Autowired
    private RecurringTaskTemplateRepository repo;

    @Autowired
    private CreateDueTasks createDueTasks;


    @Test
    void shouldCreateDueTasks(Scenario scenario) {
        repo.deleteAll();

        var notDueWithExecutions = createRecurringTaskTemplate(
                "taskWithExecutionsThatIsNotDue",
                false,
                15,
                5,
                10,
                10, 4
        );

        var notDueWithoutExecutions = createRecurringTaskTemplate(
                "taskWithoutExecutionsThatIsNotDue",
                false,
                5,
                6,
                10
        );

        var dueButHasAlreadyAnActiveTask = createRecurringTaskTemplate(
                "taskThatIsDueButHasActiveTask",
                true,
                15,
                5,
                10,
                9
        );

        var dueTodayWithExecutions = createRecurringTaskTemplate(
                "taskThatIsDueTodaySinceLastExecution",
                false,
                15,
                5,
                10,
                5, 10
        );

        var overdueWithExecutions = createRecurringTaskTemplate(
                "taskThatHasBeenDueForAFewDaysSinceLastExecution",
                false,
                15,
                5,
                10,
                6
        );

        var dueTodayNoExecutions = createRecurringTaskTemplate(
                "taskThatIsDueTodayAndHasNoExecutions",
                false,
                5,
                5,
                10
        );

        var overdueNoExecutions = createRecurringTaskTemplate(
                "taskThatHasBeenDueForAFewDaysAndHasNoExecutions",
                false,
                6,
                5,
                10
        );

        repo.saveAll(List.of(
                notDueWithExecutions,
                notDueWithoutExecutions,
                dueButHasAlreadyAnActiveTask,
                dueTodayWithExecutions,
                overdueWithExecutions,
                dueTodayNoExecutions,
                overdueNoExecutions
        ));


        var expected = List.of(
                dueTodayWithExecutions,
                overdueWithExecutions,
                dueTodayNoExecutions,
                overdueNoExecutions
        );


        scenario.stimulate(createDueTasks::createDueTasks)
                .andWaitForEventOfType(TaskCreationRequestedEvent.class)
                .toArriveAndVerify(event ->
                        assertThat(event.tasks())
                                .extracting("name")
                                .containsExactlyInAnyOrderElementsOf(
                                        expected.stream()
                                                .map(RecurringTaskTemplate::getName)
                                                .toList()
                                )
                );


        expected.forEach(t ->
                assertThat(repo.findById(t.getId())
                        .orElseThrow()
                        .isActiveTask())
                        .isTrue()
        );
    }


    private RecurringTaskTemplate createRecurringTaskTemplate(
            String name,
            boolean activeTask,
            int createdDaysAgo,
            int minDays,
            int maxDays,
            Integer... executionDaysAgo
    ) {
        var today = LocalDate.now();

        var recurringTaskTemplate = createRandomRecurringTaskTemplate();
        recurringTaskTemplate.setName(name);
        recurringTaskTemplate.setActiveTask(activeTask);
        recurringTaskTemplate.setCreationDate(today.minusDays(createdDaysAgo));
        recurringTaskTemplate.setMinNumberOfDaysBetweenExecutions(minDays);
        recurringTaskTemplate.setMaxNumberOfDaysBetweenExecutions(maxDays);

        recurringTaskTemplate.setExecutions(Arrays.stream(executionDaysAgo)
                .map(days ->
                        Execution.builder()
                                .id(UUID.randomUUID())
                                .date(today.minusDays(days))
                                .build()
                )
                .toList());

        return recurringTaskTemplate;
    }
}

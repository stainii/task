package be.stijnhooft.task.backend.recurring.scheduler;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.recurring.Execution;
import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.repository.RecurringTaskTemplateRepository;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    @Autowired
    private ApplicationEventPublisher eventPublisher;


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


    /// The boundary itself, proved by moving the clock rather than by waiting for tomorrow
    /// (#44). A template with a five-day minimum is not due on day four and is due on day five;
    /// nothing else about the scheduler can be asserted at a date boundary without this.
    ///
    /// It drives its own `CreateDueTasks` built on a `TestClock` instead of the autowired bean,
    /// which keeps the application context - and therefore the container - shared with the test
    /// above, and asserts only on the template it created, by id.
    @Test
    void shouldConsiderATemplateDueOnItsMinimumDayAndNotTheDayBefore() {
        var lastExecutedOn = LocalDate.of(2026, 8, 10);
        var minDays = 5;

        var template = createRecurringTaskTemplate("boundaryTemplate-" + UUID.randomUUID(), false, 30, minDays, 10);
        template.setExecutions(List.of(Execution.builder().id(UUID.randomUUID()).date(lastExecutedOn).build()));
        var id = repo.save(template).getId();

        var clock = TestClock.atNoonOn(lastExecutedOn.plusDays(minDays - 1L));
        var createDueTasksOnAMovableClock = new CreateDueTasks(repo, "never", eventPublisher, clock);

        createDueTasksOnAMovableClock.createDueTasks();
        assertThat(repo.findById(id).orElseThrow().isActiveTask())
                .as("not due yet on day %s of a %s-day minimum", minDays - 1, minDays)
                .isFalse();

        clock.moveTo(lastExecutedOn.plusDays(minDays));

        createDueTasksOnAMovableClock.createDueTasks();
        assertThat(repo.findById(id).orElseThrow().isActiveTask())
                .as("due on day %s of a %s-day minimum", minDays, minDays)
                .isTrue();
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

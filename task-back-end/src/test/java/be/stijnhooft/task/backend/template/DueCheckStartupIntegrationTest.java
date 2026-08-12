package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import be.stijnhooft.task.backend.template.domain.TaskDefinition;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/// **The one context in the suite where the schedule is switched on, and it exists to prove the
/// startup fire really happens** — ADR-0016 says this test is not optional, and the reason is a
/// lesson this project has learned twice: `#10`'s `-Xplugin` line break and `#23`'s Error Prone
/// canary were both mechanisms that installed themselves, silently did nothing, and left a green
/// build behind. A startup check nobody proves is a comment.
///
/// It proves the annotation, not the predicate: with `fixedDelay`, Spring's first execution lands
/// right after context refresh, so *check on startup* and *check periodically* are the same line.
/// Everything the check then decides is
/// [DueTemplateCheckerIntegrationTest](DueTemplateCheckerIntegrationTest)'s.
///
/// The template is seeded from a bean **constructor**, which runs while singletons are still being
/// created and therefore before the scheduler starts on context refresh. Nothing else in the suite
/// can rely on that ordering, which is why the schedule is off everywhere else.
@SpringBootTest(properties = "task.due-check.enabled=true")
class DueCheckStartupIntegrationTest extends AbstractIntegrationTestCases {

    private static final UUID TEMPLATE_ID = UUID.randomUUID();

    /// Long enough to survive a slow container, short enough that a schedule that never starts
    /// fails the build rather than hanging it. The tick itself is immediate.
    private static final Duration WAIT_FOR_THE_TICK = Duration.ofSeconds(20);

    @Autowired
    private TaskRepository taskRepository;

    @TestConfiguration
    static class ADueTemplateThatExistsBeforeTheSchedulerStarts {

        ADueTemplateThatExistsBeforeTheSchedulerStarts(TaskTemplateRepository taskTemplateRepository, Clock clock) {
            // Active since yesterday with a one-day interval, so it is due today and its task is
            // dated today rather than backdated into another test class's window.
            taskTemplateRepository.save(TaskTemplate.of(
                    TEMPLATE_ID,
                    "Startup " + TEMPLATE_ID,
                    "house",
                    LocalDate.now(clock).minusDays(1),
                    Trigger.MinMax.ofIntervalAndWindow(1, 0),
                    List.of(TaskDefinition.of(UUID.randomUUID(), "Bin out", null, null,
                            Importance.IMPORTANT, null))));
        }
    }

    @Test
    void startingTheApplicationIsOneOfTheTicks() throws InterruptedException {
        var tasks = awaitTasksOfTheSeededTemplate();

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.name()).isEqualTo("Bin out");
            assertThat(task.taskTemplateId()).isEqualTo(TEMPLATE_ID);
        });
    }

    private List<Task> awaitTasksOfTheSeededTemplate() throws InterruptedException {
        var deadline = Instant.now().plus(WAIT_FOR_THE_TICK);
        while (Instant.now().isBefore(deadline)) {
            var tasks = tasksOfTheSeededTemplate();
            if (!tasks.isEmpty()) {
                return tasks;
            }
            Thread.sleep(100);
        }
        return tasksOfTheSeededTemplate();
    }

    private List<Task> tasksOfTheSeededTemplate() {
        return StreamSupport.stream(taskRepository.findAll().spliterator(), false)
                .filter(task -> TEMPLATE_ID.equals(task.taskTemplateId()))
                .toList();
    }
}

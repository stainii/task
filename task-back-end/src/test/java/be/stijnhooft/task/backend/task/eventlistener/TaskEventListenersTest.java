package be.stijnhooft.task.backend.task.eventlistener;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskTemplateFired;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The inbound port, from the outside: a fact arrives carrying descriptions and `task` builds its
/// own aggregates from it.
@ApplicationModuleTest(extraIncludes = "config")
class TaskEventListenersTest extends AbstractIntegrationTestCases {

    /// In the past, so nothing written here poses as future-dated data for another test class
    /// sharing the container (`docs/quality-bar.md` §5).
    private static final LocalDate FIRING_DATE = LocalDate.of(2026, 2, 17);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private Clock clock;

    @Test
    void aFiringBecomesOneTaskPerRenderedDefinition() {
        var event = firingOf(
                new TaskTemplateFired.RenderedDefinition("wash the sheets", "the blue ones",
                        Importance.IMPORTANT, FIRING_DATE, FIRING_DATE.plusDays(3)),
                new TaskTemplateFired.RenderedDefinition("hoover under the bed", null,
                        Importance.NOT_SO_IMPORTANT, FIRING_DATE, null));

        eventPublisher.publishEvent(event);

        var created = tasksOf(event);
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(task -> {
            assertThat(task.context()).isEqualTo("the house");
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.occurrenceId()).isEqualTo(event.occurrenceId());
        });
        assertThat(created)
                .extracting(Task::name, Task::description, Task::importance, Task::startDate, Task::dueDate)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("wash the sheets", "the blue ones",
                                Importance.IMPORTANT, FIRING_DATE, FIRING_DATE.plusDays(3)),
                        Tuple.tuple("hoover under the bed", null,
                                Importance.NOT_SO_IMPORTANT, FIRING_DATE, null));
    }

    /// **The firing date is the task's creation date**, and this is the case that makes it matter:
    /// a template catching up on a date it slept through must produce a task dated for the date it
    /// was for, not for the day the catch-up ran. `TaskOccurrences#lastClosureOf` reads it back as
    /// the firing date, so a task stamped "now" would have the predicate compare today against
    /// today and fire again tomorrow.
    @Test
    void theFiringDateBecomesTheCreationDate() {
        var event = firingOf(new TaskTemplateFired.RenderedDefinition("put the bins out", null,
                Importance.IMPORTANT, FIRING_DATE, FIRING_DATE));

        eventPublisher.publishEvent(event);

        assertThat(tasksOf(event))
                .singleElement()
                .satisfies(task -> assertThat(LocalDate.ofInstant(task.creationDateTime(), clock.getZone()))
                        .isEqualTo(FIRING_DATE));
    }

    /// Provenance is a real reference, not an event: the task keeps pointing at the template that
    /// made it, years after the firing (ADR-0001).
    @Test
    void everyTaskOfTheFiringCarriesItsTemplateAndItsOccurrence() {
        var event = firingOf(new TaskTemplateFired.RenderedDefinition("water the plants", null,
                Importance.I_DO_NOT_REALLY_CARE, FIRING_DATE, null));

        eventPublisher.publishEvent(event);

        assertThat(tasksOf(event))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.taskTemplateId()).isEqualTo(event.templateId());
                    assertThat(task.occurrenceId()).isEqualTo(event.occurrenceId());
                });
    }

    private static TaskTemplateFired firingOf(TaskTemplateFired.RenderedDefinition... definitions) {
        return new TaskTemplateFired(UUID.randomUUID(), UUID.randomUUID(), FIRING_DATE, "the house",
                List.of(definitions));
    }

    /// Only what this test created, found by its own ids - the container is shared and nothing is
    /// cleaned up between classes.
    private List<Task> tasksOf(TaskTemplateFired event) {
        return taskRepository.findByStatus(TaskStatus.OPEN).stream()
                .filter(task -> event.occurrenceId().equals(task.occurrenceId()))
                .toList();
    }
}

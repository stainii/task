package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import be.stijnhooft.task.backend.task.mother.TaskMother;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The day's-work port, from the outside — `notification`'s only question, and the boundary in it is
/// a date (`docs/quality-bar.md` §5).
///
/// Every test picks its own day, years away from any other test class's data, and asserts on the
/// names it created: the container is shared and nothing is cleaned between classes.
@ApplicationModuleTest(extraIncludes = "config")
class DueTasksIntegrationTest extends AbstractIntegrationTestCases {

    @Autowired
    private DueTasks dueTasks;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void anEmptyDayIsEmpty() {
        assertThat(dueTasks.namesOfTasksDueOn(LocalDate.of(2021, 2, 3))).isEmpty();
    }

    /// **The boundary is the whole rule**: a task announces itself on its due day and never again,
    /// so the day before and the day after must both be silent. The day after is the one that
    /// matters — it is what makes an overdue task silent, which is the entire difference from the
    /// daily mail this replaced.
    @Test
    void aTaskIsDueOnItsDueDateAndOnNoOtherDay() {
        var day = LocalDate.of(2021, 3, 10);
        var name = save(TaskMother.taskDueOn(day, Importance.IMPORTANT, TaskStatus.OPEN));

        assertThat(dueTasks.namesOfTasksDueOn(day.minusDays(1))).doesNotContain(name);
        assertThat(dueTasks.namesOfTasksDueOn(day)).contains(name);
        assertThat(dueTasks.namesOfTasksDueOn(day.plusDays(1))).doesNotContain(name);
    }

    /// Something finished at 07:00 is not announced at 07:30, and something abandoned is not
    /// announced at all.
    @Test
    void onlyOpenTasksAreAnnounced() {
        var day = LocalDate.of(2021, 4, 14);
        var open = save(TaskMother.taskDueOn(day, Importance.IMPORTANT, TaskStatus.OPEN));
        var completed = save(TaskMother.taskDueOn(day, Importance.IMPORTANT, TaskStatus.COMPLETED));
        var cancelled = save(TaskMother.taskDueOn(day, Importance.IMPORTANT, TaskStatus.CANCELLED));

        assertThat(dueTasks.namesOfTasksDueOn(day))
                .contains(open)
                .doesNotContain(completed, cancelled);
    }

    /// The order decides which names survive into *"+2 more"*, so it is a rule and not presentation.
    @Test
    void namesTheMostImportantFirst() {
        var day = LocalDate.of(2021, 5, 20);
        var dontCare = save(TaskMother.taskDueOn(day, Importance.I_DO_NOT_REALLY_CARE, TaskStatus.OPEN));
        var veryImportant = save(TaskMother.taskDueOn(day, Importance.VERY_IMPORTANT, TaskStatus.OPEN));
        var important = save(TaskMother.taskDueOn(day, Importance.IMPORTANT, TaskStatus.OPEN));

        // Filtered to this test's own three names: the Postgres container is reused between runs,
        // so an earlier run of this very class has already left rows on this date. Asserting on
        // the whole day is the same mistake `docs/quality-bar.md` §5 was written about.
        assertThat(dueTasks.namesOfTasksDueOn(day))
                .filteredOn(List.of(veryImportant, important, dontCare)::contains)
                .containsExactly(veryImportant, important, dontCare);
    }

    private String save(be.stijnhooft.task.backend.task.domain.Task task) {
        return taskRepository.save(task).name();
    }
}

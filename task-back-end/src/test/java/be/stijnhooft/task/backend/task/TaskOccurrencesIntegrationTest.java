package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import be.stijnhooft.task.backend.task.mother.TaskMother;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The query port, from the outside: the three questions `template` is allowed to ask, and the two
/// of them that must not share an answer.
///
/// Every test invents its own template id, so it asserts only on data it created - the container is
/// shared and nothing is cleaned up between classes.
@ApplicationModuleTest(extraIncludes = "config")
class TaskOccurrencesIntegrationTest extends AbstractIntegrationTestCases {

    /// In the past, so nothing written here poses as future-dated data for another test class
    /// (`docs/quality-bar.md` §5).
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 12);

    @Autowired
    private TaskOccurrences taskOccurrences;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void aTemplateThatHasNeverFiredAnswersNothing() {
        var templateId = UUID.randomUUID();

        assertThat(taskOccurrences.hasOpenOccurrence(templateId)).isFalse();
        assertThat(taskOccurrences.lastCompletionOf(templateId)).isEmpty();
        assertThat(taskOccurrences.lastClosureOf(templateId)).isEmpty();
    }

    /// ADR-0001's suppression rule, and the one thing that stops a neglected template handing you
    /// four bin tasks in a month.
    @Test
    void anOpenTaskSuppressesTheTemplateAndAClosedOneDoesNot() {
        var suppressed = fired(JANUARY, TaskStatus.OPEN, null);
        var free = fired(JANUARY, TaskStatus.COMPLETED, JANUARY.plusDays(1));

        assertThat(taskOccurrences.hasOpenOccurrence(suppressed)).isTrue();
        assertThat(taskOccurrences.hasOpenOccurrence(free)).isFalse();
    }

    /// *When did I last actually do this?* is the day the work happened, which is `completedOn` and
    /// not the day the patch was written - a completion backdated to last Tuesday means last
    /// Tuesday (ADR-0011).
    @Test
    void theLastCompletionIsTheLatestDayWorkHappened() {
        var templateId = UUID.randomUUID();
        save(TaskMother.firedTask(templateId, JANUARY, TaskStatus.COMPLETED, JANUARY.plusDays(2)));
        save(TaskMother.firedTask(templateId, JANUARY.plusDays(30), TaskStatus.COMPLETED, JANUARY.plusDays(33)));
        save(TaskMother.firedTask(templateId, JANUARY.plusDays(14), TaskStatus.COMPLETED, JANUARY.plusDays(16)));

        assertThat(taskOccurrences.lastCompletionOf(templateId)).contains(JANUARY.plusDays(33));
    }

    /// **The two questions, and the bug they compose into when one answer serves both.**
    ///
    /// The template was done in January and its February task was cancelled. *When did I last do
    /// this?* is January - cancelling is not doing. *When should I next be asked?* is February,
    /// because any closure ends the round. Read the completion for both and nothing is open to
    /// suppress the template while the last completion stays weeks in the past, so it fires again
    /// tomorrow, and the day after, until something is completed (ADR-0011).
    @Test
    void aCancelledTaskEndsTheRoundWithoutBeingACompletion() {
        var templateId = UUID.randomUUID();
        var done = JANUARY;
        var cancelled = JANUARY.plusDays(28);
        save(TaskMother.firedTask(templateId, done, TaskStatus.COMPLETED, done.plusDays(1)));
        save(TaskMother.firedTask(templateId, cancelled, TaskStatus.CANCELLED, null));

        assertThat(taskOccurrences.lastCompletionOf(templateId)).contains(done.plusDays(1));
        assertThat(taskOccurrences.lastClosureOf(templateId)).contains(cancelled);
    }

    /// **A closure reports the firing date, not the closing date.** The mother closes its tasks
    /// twenty days after they fire, so a query reading the wrong date is off by three weeks rather
    /// than by something a boundary might hide - and a calendar template compares that answer
    /// against its rule's dates, so being late by three weeks is being wrong by three firings.
    @Test
    void aClosureIsDatedByTheFiringAndNotByTheClosing() {
        var templateId = fired(JANUARY, TaskStatus.COMPLETED, JANUARY.plusDays(40));

        assertThat(taskOccurrences.lastClosureOf(templateId)).contains(JANUARY);
    }

    /// An open task is not a closure, however long it has been sitting there.
    @Test
    void anOpenTaskIsNotACloseAndDoesNotMoveTheClock() {
        var templateId = UUID.randomUUID();
        save(TaskMother.firedTask(templateId, JANUARY, TaskStatus.COMPLETED, JANUARY));
        save(TaskMother.firedTask(templateId, JANUARY.plusDays(60), TaskStatus.OPEN, null));

        assertThat(taskOccurrences.lastClosureOf(templateId)).contains(JANUARY);
    }

    /// A task nobody's template made - which is most of them - belongs to no template and must
    /// answer for none.
    @Test
    void aTaskWithNoTemplateBelongsToNoTemplate() {
        save(TaskMother.createRandomTask());

        assertThat(taskOccurrences.lastClosureOf(UUID.randomUUID())).isEmpty();
    }

    private UUID fired(LocalDate firingDate, TaskStatus status, LocalDate completedOn) {
        var templateId = UUID.randomUUID();
        save(TaskMother.firedTask(templateId, firingDate, status, completedOn));
        return templateId;
    }

    private void save(Task task) {
        taskRepository.save(task);
    }
}

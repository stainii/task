package be.stijnhooft.task.backend.task.domain;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.exception.IncompleteTaskHistoryException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's own behaviour: creating a task, appending to it, and what it refuses.
///
/// **The fold's rules are not tested here** - they live in `/fold-fixtures/`, because the fold also
/// exists in TypeScript and a rule proven only in Java is a rule the two can silently disagree on.
/// See `FoldFixtureTest`.
class TaskTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    private final TestClock clock = TestClock.atNoonOn(TODAY);

    @Test
    void builderForInitialTaskFillsInDefaultFieldsWhenNull() {
        var id = UUID.randomUUID();

        var task = Task.builderForInitialTask(clock)
                .id(id)
                .name("test")
                .context("test")
                .build();

        assertThat(task.id()).isEqualTo(id);
        assertThat(task.creationDateTime()).isEqualTo(clock.instant());
        assertThat(task.startDate()).isEqualTo(TODAY);
        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(task.history()).hasSize(1);
    }

    @Test
    void theCreationPatchCarriesEveryFieldThatIsSet() {
        var creationDateTime = Instant.parse("2019-01-01T01:01:00Z");
        var templateId = UUID.randomUUID();
        var occurrenceId = UUID.randomUUID();

        var task = Task.builderForInitialTask(clock)
                .id(UUID.randomUUID())
                .name("original")
                .status(TaskStatus.OPEN)
                .description("original")
                .context("original")
                .creationDateTime(creationDateTime)
                .startDate(LocalDate.of(2019, 1, 1))
                .dueDate(LocalDate.of(2019, 1, 2))
                .importance(Importance.I_DO_NOT_REALLY_CARE)
                .completedOn(LocalDate.of(2019, 1, 3))
                .taskTemplateId(templateId)
                .occurrenceId(occurrenceId)
                .build();

        var creationPatch = task.getCreationPatch();

        assertThat(task.history()).hasSize(1);
        assertThat(creationPatch.taskId()).isEqualTo(task.id());
        assertThat(creationPatch.dateTime()).isEqualTo(creationDateTime);
        assertThat(creationPatch.sequence()).as("assigned by the server on receipt, not here").isNull();
        assertThat(creationPatch.changes())
                .containsEntry("name", "original")
                .containsEntry("status", "OPEN")
                .containsEntry("description", "original")
                .containsEntry("context", "original")
                .containsEntry("creationDateTime", "2019-01-01T01:01:00Z")
                .containsEntry("startDate", "2019-01-01")
                .containsEntry("dueDate", "2019-01-02")
                .containsEntry("importance", "I_DO_NOT_REALLY_CARE")
                .containsEntry("completedOn", "2019-01-03")
                .containsEntry("taskTemplateId", templateId.toString())
                .containsEntry("occurrenceId", occurrenceId.toString());
    }

    /// The creation patch is dumped from the task's own fields, so a field added to `Task` and
    /// forgotten here would be a field the fold can never produce. The dump is what makes
    /// forgetting impossible; this asserts it stays that way.
    @Test
    void theCreationPatchNamesEveryFoldableFieldOfTheTask() {
        var task = Task.builderForInitialTask(clock)
                .name("n")
                .context("c")
                .dueDate(LocalDate.of(2026, 9, 1))
                .description("d")
                .completedOn(LocalDate.of(2026, 8, 1))
                .taskTemplateId(UUID.randomUUID())
                .occurrenceId(UUID.randomUUID())
                .build();

        assertThat(task.getCreationPatch().changes().keySet())
                .containsExactlyInAnyOrder("name", "creationDateTime", "startDate", "dueDate", "context",
                        "importance", "description", "status", "completedOn", "taskTemplateId", "occurrenceId");
    }

    @Test
    void patchAppendsToTheHistoryAndRefolds() {
        var task = baseTask();

        var patched = task.patch(TaskPatch.builder()
                .taskId(task.id())
                .dateTime(clock.instant())
                .change("name", "new")
                .change("dueDate", "2019-02-02")
                .build());

        assertThat(patched.name()).isEqualTo("new");
        assertThat(patched.dueDate()).isEqualTo(LocalDate.of(2019, 2, 2));
        assertThat(patched.description()).as("untouched fields survive").isEqualTo("original");
        assertThat(patched.history()).hasSize(2);
        assertThat(task.history()).as("the original task is not mutated").hasSize(1);
    }

    @Test
    void patchingWithAPatchThatIsAlreadyInTheHistoryChangesNothing() {
        var task = baseTask();
        var patch = TaskPatch.builder()
                .taskId(task.id())
                .dateTime(clock.instant())
                .change("name", "new")
                .build();

        var once = task.patch(patch);
        var twice = once.patch(patch);

        assertThat(twice).isSameAs(once);
    }

    @Test
    void aTaskCannotBeFoldedFromAnEmptyHistory() {
        var taskId = UUID.randomUUID();

        assertThatThrownBy(() -> Task.foldOf(taskId, List.of(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHistoryWhoseFirstPatchIsIncompleteDoesNotFold() {
        var taskId = UUID.randomUUID();
        var incompleteCreation = TaskPatch.builder()
                .taskId(taskId)
                .dateTime(clock.instant())
                .change("name", "no context, no creation date-time")
                .build();

        assertThatThrownBy(() -> Task.foldOf(taskId, List.of(incompleteCreation), 0L))
                .isInstanceOf(IncompleteTaskHistoryException.class);
    }

    @Test
    void withSequencesFromStampsOnlyThePatchesThatHaveNoneYet() {
        var base = baseTask();
        var task = base
                .patch(TaskPatch.builder()
                        .taskId(base.id())
                        .dateTime(clock.instant().plus(1, ChronoUnit.DAYS))
                        .sequence(41L)
                        .change("name", "already through the server")
                        .build());

        var sequences = new java.util.concurrent.atomic.AtomicLong(100);
        var stamped = task.withSequencesFrom(sequences::getAndIncrement);

        assertThat(stamped.history().getFirst().sequence()).isEqualTo(100L);
        assertThat(stamped.history().getLast().sequence()).as("never reissued").isEqualTo(41L);
    }

    private Task baseTask() {
        return Task.builderForInitialTask(clock)
                .id(UUID.randomUUID())
                .name("original")
                .status(TaskStatus.OPEN)
                .description("original")
                .context("original")
                .creationDateTime(Instant.parse("2019-01-01T01:01:01Z"))
                .startDate(LocalDate.of(2019, 1, 1))
                .dueDate(LocalDate.of(2019, 1, 1))
                .importance(Importance.I_DO_NOT_REALLY_CARE)
                .build();
    }
}

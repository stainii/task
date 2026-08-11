package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskPatch;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// Tasks built the way the application builds them: through the creation patch and the fold.
///
/// Instancio is deliberately not used here any more. A task assembled field by field is a task
/// whose columns do not agree with its own history, which is a state the application cannot reach
/// and a test asserting on it proves nothing. It also minted dates decades ahead, which #10 banned
/// after a stream test received a patch dated 2071 from another test class.
public class TaskMother {

    private static final TestClock CLOCK = TestClock.atNoonOn(LocalDate.of(2026, 3, 1));

    /// **Negative**, and that is the point. `sequence` is unique in the schema and the integration
    /// tests share one reused Postgres, so these numbers have to miss the real sequence - but they
    /// must also never *pose* as it. Minted above it, as they were, they became the highest sequence
    /// in the database, so `TaskPatchSequence.watermark()` reported a cursor a thousand times past
    /// the end of history and every stream resumed from a point no real patch will reach for years.
    /// The server only ever issues positive numbers, so a negative one is unmistakably test data.
    private static final AtomicLong SEQUENCES =
            new AtomicLong(ThreadLocalRandom.current().nextLong(-1_000_000_000_000L, -1_000_000L));

    /// A task with a creation patch and two later patches, all uniquely identified, so a test can
    /// assert on its own data by id.
    public static Task createRandomTask() {
        var task = Task.builderForInitialTask(CLOCK)
                .name("task " + UUID.randomUUID())
                .context("context " + UUID.randomUUID())
                .description("description " + UUID.randomUUID())
                .dueDate(LocalDate.of(2026, 3, 8))
                .build();

        return task
                .patch(patchOn(task.id(), 1, "name", "renamed " + UUID.randomUUID()))
                .patch(patchOn(task.id(), 2, "dueDate", "2026-03-09"))
                .withSequencesFrom(SEQUENCES::getAndIncrement);
    }

    private static TaskPatch patchOn(UUID taskId, int daysAfterCreation, String field, String value) {
        return TaskPatch.builder()
                .taskId(taskId)
                .dateTime(CLOCK.instant().plus(daysAfterCreation, ChronoUnit.DAYS))
                .change(field, value)
                .build();
    }
}

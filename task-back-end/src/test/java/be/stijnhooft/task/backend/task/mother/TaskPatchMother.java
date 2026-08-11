package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.task.TaskPatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class TaskPatchMother {

    /// Dated in the past on purpose: Instancio mints dates decades ahead, and #10 banned
    /// future-dated test data after a time-windowed stream assertion received a patch from another
    /// test class dated 2071.
    public static TaskPatch createRandomTaskPatch() {
        return createRandomTaskPatch(UUID.randomUUID());
    }

    public static TaskPatch createRandomTaskPatch(UUID taskId) {
        return TaskPatch.builder()
                .taskId(taskId)
                .dateTime(LocalDate.of(2026, 3, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .change("name", "name " + UUID.randomUUID())
                .build();
    }

    public static TaskPatch taskPatchAt(UUID taskId, Instant dateTime, String field, String value) {
        return TaskPatch.builder()
                .taskId(taskId)
                .dateTime(dateTime)
                .change(field, value)
                .build();
    }
}

package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.util.ObjectUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    private final TestClock clock = TestClock.atNoonOn(LocalDate.of(2026, 8, 10));

    @Test
    void builderForInitialTaskFillsInDefaultFieldsWhenNull() {
        var id = UUID.randomUUID();
        Task task = Task.builderForInitialTask(clock)
                .id(id)
                .name("test")
                .context("test")
                .build();

        assertThat(task.getId()).isEqualTo(id);
        assertThat(task.getCreationDateTime()).isNotNull();
        assertThat(task.getStartDate()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.getHistory()).hasSize(1);
    }

    @Test
    void initialPatchGetsCreated() {
        var id = UUID.randomUUID();

        Task task = Task.builderForInitialTask(clock)
                .id(id)
                .name("original")
                .status(TaskStatus.OPEN)
                .description("original")
                .context("original")
                .creationDateTime(LocalDateTime.of(2019, 1, 1, 1, 1))
                .startDate(LocalDate.of(2019, 1, 1))
                .dueDate(LocalDate.of(2019, 1, 1))
                .importance(Importance.I_DO_NOT_REALLY_CARE)
                .build();

        var initialPatch = task.getHistory().getFirst();

        assertThat(task.getHistory()).hasSize(1);
        assertThat(initialPatch.getDateTime()).isNotNull();
        assertThat(initialPatch.getTaskId()).isEqualTo(task.getId());
        assertThat(initialPatch.getChange("name")).isEqualTo("original");
        assertThat(initialPatch.getChange("status")).isEqualTo("OPEN");
        assertThat(initialPatch.getChange("description")).isEqualTo("original");
        assertThat(initialPatch.getChange("creationDateTime")).isEqualTo("2019-01-01T01:01");
        assertThat(initialPatch.getChange("startDate")).isEqualTo("2019-01-01");
        assertThat(initialPatch.getChange("dueDate")).isEqualTo("2019-01-01");
        assertThat(initialPatch.getChange("importance")).isEqualTo("I_DO_NOT_REALLY_CARE");
        assertThat(initialPatch.getChange("context")).isEqualTo("original");
        assertThat(initialPatch.getChange("version")).isEqualTo("0");
        assertThat(initialPatch.getChange("id")).isNotNull();
        assertThat(initialPatch.getChanges()).hasSize(10);
    }

    @Test
    void patchWhenThereIsNothingToPatch() {
        Task task = baseTask();

        TaskPatch taskPatch = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 1, 1, 2))
                .changes(new HashMap<>())
                .build();

        task.patch(taskPatch);

        assertThat(task.getName()).isEqualTo("original");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.getDescription()).isEqualTo("original");
        assertThat(task.getContext()).isEqualTo("original");
        assertThat(task.getStartDate()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(task.getImportance()).isEqualTo(Importance.I_DO_NOT_REALLY_CARE);
    }

    @Test
    void patchWhenAllFieldsArePatched() {
        Task task = baseTask();

        TaskPatch taskPatch = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 1, 1, 2))
                .change("name", "new")
                .change("status", "COMPLETED")
                .change("description", "new")
                .change("context", "new")
                .change("expectedDurationInHours", "1")
                .change("startDate", "2019-02-02")
                .change("dueDate", "2019-02-02")
                .change("importance", "VERY_IMPORTANT")
                .build();

        task.patch(taskPatch);

        assertThat(task.getName()).isEqualTo("new");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getDescription()).isEqualTo("new");
        assertThat(task.getContext()).isEqualTo("new");
        assertThat(task.getCreationDateTime()).isEqualTo(LocalDateTime.of(2019, 1, 1, 1, 1, 1));
        assertThat(task.getStartDate()).isEqualTo(LocalDate.of(2019, 2, 2));
        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2019, 2, 2));
        assertThat(task.getImportance()).isEqualTo(Importance.VERY_IMPORTANT);
    }

    @Test
    void patchWhenTheTaskHasBeenCompleted() {
        Task task = baseTask();

        var taskPatch = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 1, 1, 2))
                .change("status", "COMPLETED")
                .build();

        task.patch(taskPatch);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void patchWhenTheDueDateTimeHasBeenChanged() {
        Task task = baseTask();

        var taskPatch = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 1, 1, 2))
                .change("dueDate", "2019-02-02")
                .build();

        task.patch(taskPatch);

        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2019, 2, 2));
    }

    @Test
    void undoPatch() {
        Task task = baseTask();

        Map<String, String> changes = ObjectUtils.getAllFieldsAndTheirValues(task);
        changes.remove("history");

        TaskPatch taskPatch1 = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 1, 1, 2))
                .changes(changes)
                .build();

        TaskPatch taskPatch2 = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 2, 1, 1))
                .change("dueDate", "2019-03-03")
                .change("description", "new description")
                .change("importance", "NOT_SO_IMPORTANT")
                .build();

        TaskPatch taskPatch3 = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 3, 1, 1))
                .change("dueDate", "2019-04-04")
                .change("importance", "IMPORTANT")
                .build();

        TaskPatch taskPatch4 = TaskPatch.builder()
                .id(UUID.randomUUID())
                .taskId(task.getId())
                .dateTime(LocalDateTime.of(2019, 1, 4, 1, 1))
                .change("importance", "NOT_SO_IMPORTANT")
                .build();

        task.patch(taskPatch1);
        task.patch(taskPatch2);
        task.patch(taskPatch3);
        task.patch(taskPatch4);

        task.undoPatch(taskPatch2, clock);

        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2019, 4, 4));
        assertThat(task.getDescription()).isEqualTo("original");
    }

    @Test
    void undoPatchWhenUndoingCreation() {
        Task task = baseTask();

        TaskPatch creationPatch = task.getHistory().getFirst();

        task.undoPatch(creationPatch, clock);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);

        assertThat(task.getHistory()).hasSize(2);
        assertThat(task.getHistory().get(1).getDateTime()).isNotNull();
        assertThat(task.getHistory().get(1).getTaskId()).isEqualTo(task.getId());
        assertThat(task.getHistory().get(1).getChange("status")).isEqualTo("COMPLETED");
        assertThat(task.getHistory().get(1).getChanges()).hasSize(1);
    }

    private Task baseTask() {
        return Task.builderForInitialTask(clock)
                .id(UUID.randomUUID())
                .name("original")
                .status(TaskStatus.OPEN)
                .description("original")
                .context("original")
                .creationDateTime(LocalDateTime.of(2019, 1, 1, 1, 1, 1))
                .startDate(LocalDate.of(2019, 1, 1))
                .dueDate(LocalDate.of(2019, 1, 1))
                .importance(Importance.I_DO_NOT_REALLY_CARE)
                .build();
    }

}

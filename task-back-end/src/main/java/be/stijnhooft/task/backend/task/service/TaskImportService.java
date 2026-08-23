package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.TaskImport;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskPatch;
import be.stijnhooft.task.backend.task.exception.IncompleteTaskHistoryException;
import be.stijnhooft.task.backend.task.repository.SyncEpoch;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/// `task`'s half of the portal import: it takes translated patches and folds a real aggregate out
/// of them ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// It deliberately does **not** go through [TaskPatchService]. That is the *door*, and a door has
/// jobs the importer must not have: it rejects a patch whose changes are empty, and it emits on the
/// SSE stream. ADR-0005 requires an emptied patch to survive **as an empty patch** — the timestamp
/// is still a true fact and deleting it is the collapsing [#4](https://github.com/stainii/task/issues/4)
/// forbade — and there is nobody listening to a stream during a one-shot import.
///
/// What it shares with the door is the only thing that matters: **the fold**. `Task.foldOf` and
/// `Task#withSequencesFrom` are the same code the running application uses, so a migrated task is
/// indistinguishable from one the app produced.
@Service
@RequiredArgsConstructor
public class TaskImportService implements TaskImport {

    private final TaskRepository taskRepository;
    private final TaskPatchSequence taskPatchSequence;
    private final SyncEpoch syncEpoch;
    private final JdbcClient jdbcClient;

    /// Truncate, not delete-all-through-the-repository: the point is a free dry run, and cascading
    /// 38,000 patch rows one aggregate at a time turns a re-run into a coffee break.
    ///
    /// The three statements are one transaction because they are one fact: **from the moment this
    /// commits, sequence 41 no longer means what the phone in the author's pocket thinks it means**.
    /// Emptying the tables, rewinding the counter and naming the new lineage cannot become visible
    /// at different times without opening the window ADR-0004's epoch was written to close.
    @Override
    @Transactional
    public long startNewLineage() {
        jdbcClient.sql("TRUNCATE TABLE task_patch, task CASCADE").update();
        jdbcClient.sql("ALTER SEQUENCE task_patch_sequence RESTART WITH 1").update();
        return syncEpoch.bump();
    }

    @Override
    @Transactional
    public FoldedTask importTask(UUID taskId, List<ImportedPatch> patches) {
        if (patches.isEmpty()) {
            throw new IllegalArgumentException("Task " + taskId + " has no patches, so nothing can be folded.");
        }

        var history = patches.stream()
                .map(patch -> TaskPatch.builder()
                        .id(patch.id())
                        .taskId(taskId)
                        .dateTime(patch.dateTime())
                        .changes(patch.changes())
                        .build())
                .toList();

        Task task;
        try {
            task = Task.foldOf(taskId, history, 0L);
        } catch (IncompleteTaskHistoryException e) {
            // ADR-0005: fail loudly, never fall back to portal's stored row. The whole argument for
            // discarding that row is that its own history does not justify it.
            throw new IllegalArgumentException(
                    "Task " + taskId + " cannot be folded from its portal history: " + e.getMessage(), e);
        }

        taskRepository.save(task.withSequencesFrom(taskPatchSequence::next));

        // Reported from the folded aggregate, not from the row just saved: #53 is asking what the
        // fold computed, and a round trip through the database would answer a different question.
        return new FoldedTask(
                task.name(),
                task.creationDateTime(),
                task.startDate(),
                task.dueDate(),
                task.context(),
                task.importance(),
                task.description(),
                task.status().name());
    }

    @Override
    public long taskCount() {
        return count("task");
    }

    @Override
    public long patchCount() {
        return count("task_patch");
    }

    @Override
    public long openTaskCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM task WHERE status = 'OPEN'").query(Long.class).single();
    }

    private long count(String table) {
        // The table names are two literals chosen here, never anything a caller supplies.
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

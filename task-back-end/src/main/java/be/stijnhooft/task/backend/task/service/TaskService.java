package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskStatus;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.repository.SyncEpoch;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskPatchSseEmitterService taskPatchSseEmitterService;
    private final TaskPatchSequence taskPatchSequence;
    private final SyncEpoch syncEpoch;

    /// The open tasks and the cursor they were read at.
    ///
    /// **The watermark is read first, deliberately.** Read afterwards it could name a patch that is
    /// already in the tasks below it, so the client would stream from a point past something it
    /// never received. Read first, the worst case is that the client is handed a task *and* the
    /// patch that built it - an overlap, which costs nothing, against a gap, which is unrecoverable.
    public Snapshot snapshot() {
        var epoch = syncEpoch.current();
        var watermark = taskPatchSequence.watermark();
        return new Snapshot(epoch, watermark, taskRepository.findByStatus(TaskStatus.OPEN));
    }

    public record Snapshot(long epoch, long watermark, List<Task> tasks) {
    }

    /// Creating a whole task in one call survives only for template firings; a client writes
    /// patches (ADR-0004), and its first patch for an id is the create.
    public Task create(@NonNull Task task) {
        if (taskRepository.existsById(task.id())) {
            throw new TaskAlreadyExistsException(task.id());
        }

        var createdTask = taskRepository.save(task.withSequencesFrom(taskPatchSequence::next));

        taskPatchSseEmitterService.emitNewlyCreatedTaskPatch(createdTask.getCreationPatch());

        return createdTask;
    }

    public List<Task> create(@NonNull List<Task> tasks) {
        return tasks.stream()
                .map(this::create)
                .toList();
    }
}

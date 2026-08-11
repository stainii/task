package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TaskPatchService {

    private final TaskPatchRepository taskPatchRepository;
    private final TaskRepository taskRepository;
    private final TaskPatchSseEmitterService taskPatchSseEmitterService;
    private final TaskPatchSequence taskPatchSequence;

    public Optional<TaskPatch> findById(UUID id) {
        return taskPatchRepository.findById(id);
    }

    /// Appends a patch to its task and refolds.
    ///
    /// A patch id already in the history is a **no-op that burns no sequence number**: the id is
    /// the idempotency key, so a retry after a lost response must change nothing at all - not the
    /// task, and not the cursor every client reads by (ADR-0010).
    public void patch(TaskPatch taskPatch) {
        var task = taskRepository.findById(taskPatch.taskId())
                .orElseThrow(() -> new TaskNotFoundException(taskPatch.taskId()));

        if (task.history().stream().anyMatch(existing -> existing.id().equals(taskPatch.id()))) {
            log.debug("Patch {} is already part of task {}; nothing to do.", taskPatch.id(), task.id());
            return;
        }

        var sequenced = taskPatch.withSequence(taskPatchSequence.next());
        taskRepository.save(task.patch(sequenced));

        taskPatchSseEmitterService.emitNewlyCreatedTaskPatch(sequenced);
    }

    public SseEmitter tail(@Nullable Instant since) {
        // todo: avoid disconnect after 30s (nginx still left)
        var sseEmitter = taskPatchSseEmitterService.createListener();

        if (since != null) {
            taskPatchSseEmitterService.emitEarlierCreatedTaskPatches(sseEmitter, taskPatchRepository.findByDateTimeAfter(since));
        }

        return sseEmitter;
    }
}

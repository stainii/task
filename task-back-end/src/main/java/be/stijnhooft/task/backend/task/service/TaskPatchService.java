package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
/// Parked by #10 (docs/quality-bar.md): orElseThrow is called for its side effect only, to
/// assert the task exists. ADR-0004 replaces this method (the first patch creates the task),
/// so the check is rewritten rather than restyled.
@SuppressWarnings("ReturnValueIgnored")
public class TaskPatchService {

    private final TaskPatchRepository taskPatchRepository;
    private final TaskRepository taskRepository;
    private final TaskPatchSseEmitterService taskPatchSseEmitterService;
    private final Clock clock;

    public Optional<TaskPatch> findById(UUID id) {
        return taskPatchRepository.findById(id);
    }

    public void patch(TaskPatch taskPatch) {
        taskRepository.findById(taskPatch.getTaskId())
                .orElseThrow(() -> new TaskNotFoundException(taskPatch.getTaskId()));
        applyToTask(taskPatch, task -> task.patch(taskPatch));
    }

    public void undoPatch(TaskPatch taskPatch) {
        applyToTask(taskPatch, task -> task.undoPatch(taskPatch, clock));
    }

    public SseEmitter tail(@Nullable LocalDateTime since) {
        // todo: avoid disconnect after 30s (nginx still left)
        var sseEmitter = taskPatchSseEmitterService.createListener();

        if (since != null) {
            taskPatchSseEmitterService.emitEarlierCreatedTaskPatches(sseEmitter, taskPatchRepository.findByDateTimeAfter(since));
        }

        return sseEmitter;
    }

    private void applyToTask(TaskPatch taskPatch, Consumer<Task> patchAction) {
        var task = taskRepository.findById(taskPatch.getTaskId())
                .orElseThrow(() -> new TaskNotFoundException(taskPatch.getTaskId()));

        patchAction.accept(task);
        taskRepository.save(task);

        taskPatchSseEmitterService.emitNewlyCreatedTaskPatch(taskPatch);
    }
}

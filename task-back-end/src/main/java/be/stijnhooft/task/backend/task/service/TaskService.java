package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskStatus;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.jspecify.annotations.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskPatchSseEmitterService taskPatchSseEmitterService;

    public List<Task> findAllActiveTasks() {
        return taskRepository.findByStatus(TaskStatus.OPEN);
    }

    public Task create(@NonNull Task task) {
        if (taskRepository.existsById(task.getId())) {
            throw new TaskAlreadyExistsException(task.getId());
        }

        var createdTask = taskRepository.save(task);

        taskPatchSseEmitterService.emitNewlyCreatedTaskPatch(createdTask.getCreationPatch());

        return createdTask;
    }

    public List<Task> create(@NonNull List<Task> tasks) {
        return tasks.stream()
                .map(this::create)
                .toList();
    }
}

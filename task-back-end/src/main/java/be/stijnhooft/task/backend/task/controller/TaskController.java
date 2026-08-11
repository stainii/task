package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.dto.TaskSnapshotDto;
import be.stijnhooft.task.backend.task.mapper.TaskMapper;
import be.stijnhooft.task.backend.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;

    /// The snapshot: **first run and hard reset only** (ADR-0004).
    ///
    /// There is no `POST` beside it. A client writes patches and nothing else, and its first patch
    /// for a task id is the create - so the whole-task body carried nothing the creation patch does
    /// not, while costing the client a second item shape in its outbox and a rule about which order
    /// the two go in. That rule is what breaks on a flaky reconnect.
    @GetMapping
    public TaskSnapshotDto snapshot() {
        var snapshot = taskService.snapshot();
        return new TaskSnapshotDto(
                snapshot.epoch(),
                snapshot.watermark(),
                snapshot.tasks().stream().map(taskMapper::toDto).toList());
    }
}

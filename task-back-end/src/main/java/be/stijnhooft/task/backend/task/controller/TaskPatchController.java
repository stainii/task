package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import be.stijnhooft.task.backend.task.exception.TaskPatchNotFoundException;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/task-patches")
@RequiredArgsConstructor
public class TaskPatchController {

    private final TaskPatchService taskPatchService;
    private final TaskPatchMapper taskPatchMapper;

    @GetMapping
    public ResponseEntity<SseEmitter> tail(@RequestParam(required = false) @Nullable ZonedDateTime since) {
        return ResponseEntity.ok(taskPatchService.tail(since == null ? null : LocalDateTime.ofInstant(since.toInstant(), ZoneId.systemDefault())));
    }

    /// We expect the client to work offline often. When it comes online, the client will send out its updates.
    /// Now, when multiple clients update the same task, the last sent change will be applied.
    /// To limit possible data loss, it is important to only update the necessary fields.
    ///
    /// For example:
    /// client A updates the status
    /// client B updates the description
    ///
    /// Expected result: both the status and the description is updated.
    /// This is only possible when the client only sends out the changed field, instead of the whole object.
    ///
    /// So, clients are expected to only patch the changed fields.
    @PostMapping
    public void patch(@RequestBody @Valid TaskPatchDto taskPatch) {
        taskPatchService.patch(
                taskPatchMapper.toDomain(taskPatch)
        );
    }

    @DeleteMapping("/{id}")
    public void undoPatch(@PathVariable UUID id) {
        TaskPatch taskPatch = taskPatchService.findById(id)
                .orElseThrow(() -> new TaskPatchNotFoundException(id));
        taskPatchService.undoPatch(taskPatch);
    }

}

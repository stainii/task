package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

@RestController
@RequestMapping("/api/task-patches")
@RequiredArgsConstructor
public class TaskPatchController {

    private final TaskPatchService taskPatchService;
    private final TaskPatchMapper taskPatchMapper;

    @GetMapping
    public ResponseEntity<SseEmitter> tail(@RequestParam(required = false) @Nullable Instant since) {
        return ResponseEntity.ok(taskPatchService.tail(since));
    }

    /// We expect the client to work offline often. When it comes online, the client will send out its updates.
    /// Clients therefore patch only the fields they changed: the fold merges per field, so two devices
    /// editing different fields of the same task both keep their edit.
    ///
    /// Undo is a patch too. It carries `voids`, naming the patch it removes, and nothing else -
    /// deriving a compensating value from what the device happens to know is how an offline undo
    /// silently overwrites another device's later edit (ADR-0004).
    @PostMapping
    public void patch(@RequestBody @Valid TaskPatchDto taskPatch) {
        taskPatchService.patch(
                taskPatchMapper.toDomain(taskPatch)
        );
    }

}

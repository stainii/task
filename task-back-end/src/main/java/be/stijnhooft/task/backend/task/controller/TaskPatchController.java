package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/task-patches")
@RequiredArgsConstructor
public class TaskPatchController {

    private final TaskPatchService taskPatchService;
    private final TaskPatchMapper taskPatchMapper;

    /// The stream: catch-up and live tail on one connection.
    ///
    /// Both cursors are here because they serve different callers. `Last-Event-ID` is the browser's
    /// own reconnect, which happens without the application being asked; `?since=&epoch=` is a
    /// client booting from local storage and presenting the cursor it persisted. The header wins
    /// when both are present, because it is the fresher of the two.
    @GetMapping
    public ResponseEntity<SseEmitter> tail(
            @RequestHeader(value = "Last-Event-ID", required = false) @Nullable String lastEventId,
            @RequestParam(required = false) @Nullable Long since,
            @RequestParam(required = false) @Nullable Long epoch) {
        return ResponseEntity.ok(taskPatchService.tail(lastEventId, since, epoch));
    }

    /// We expect the client to work offline often. When it comes online, the client will send out its updates.
    /// Clients therefore patch only the fields they changed: the fold merges per field, so two devices
    /// editing different fields of the same task both keep their edit.
    ///
    /// This is the **only** way a client writes (ADR-0004). The first patch for a task id creates
    /// it; a patch naming a task that does not exist and not creating it is a `404`; a patch id
    /// already stored is a `200`, because a client-minted id is an idempotency key and a retry after
    /// a lost response must not be an error.
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

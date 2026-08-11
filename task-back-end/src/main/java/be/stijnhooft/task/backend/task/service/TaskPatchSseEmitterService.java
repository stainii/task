package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.SyncCursor;
import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.repository.SyncEpoch;
import be.stijnhooft.task.backend.task.service.helper.SseEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TaskPatchSseEmitterService {

    private static final String PATCH_EVENT = "patch";
    private static final String RESYNC_EVENT = "resync";

    private final SseEmitters sseEmitters;
    private final TaskPatchMapper taskPatchMapper;
    private final SyncEpoch syncEpoch;

    public void emitNewlyCreatedTaskPatch(TaskPatch patch) {
        sseEmitters.emitToAllListeners(PATCH_EVENT, eventIdOf(patch), taskPatchMapper.toDto(patch));
    }

    public void emitEarlierCreatedTaskPatches(SseEmitter sseEmitter, List<TaskPatch> patches) {
        patches.forEach(patch ->
                sseEmitters.emit(sseEmitter, PATCH_EVENT, eventIdOf(patch), taskPatchMapper.toDto(patch)));
    }

    /// Tells the client to throw its local state away and re-snapshot, then ends the stream. The
    /// stream ends because there is nothing useful left to send down it: everything after this point
    /// is relative to a cursor the client is about to abandon.
    public void emitResync(SseEmitter sseEmitter) {
        sseEmitters.emit(sseEmitter, RESYNC_EVENT, null, syncEpoch.current());
        sseEmitter.complete();
    }

    /// `epoch:sequence`, so the cursor the browser maintains for free carries its lineage with it.
    /// See [SyncCursor].
    private String eventIdOf(TaskPatch patch) {
        var sequence = Objects.requireNonNull(patch.sequence(),
                () -> "Patch " + patch.id() + " reached the stream without a sequence; it is not durable yet.");
        return new SyncCursor(syncEpoch.current(), sequence).format();
    }

    public SseEmitter createListener() {
        return sseEmitters.createListener();
    }
}

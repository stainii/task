package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.service.helper.SseEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskPatchSseEmitterService {

    private static final String PATCH_EVENT = "patch";
    private final SseEmitters sseEmitters;

    public void emitNewlyCreatedTaskPatch(TaskPatch patch) {
        sseEmitters.emitToAllListeners(PATCH_EVENT, patch.getId(), patch);
    }

    public void emitEarlierCreatedTaskPatches(SseEmitter sseEmitter, List<TaskPatch> patches) {
        patches.forEach(patch -> sseEmitters.emit(sseEmitter, PATCH_EVENT, patch.getId(), patch));
    }

    public SseEmitter createListener() {
        return sseEmitters.createListener();
    }
}

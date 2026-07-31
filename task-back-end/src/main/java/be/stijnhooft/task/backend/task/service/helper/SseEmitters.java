package be.stijnhooft.task.backend.task.service.helper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

@Repository
@Slf4j
@RequiredArgsConstructor
public class SseEmitters {

    final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    @Qualifier("sseScheduler")
    private final TaskScheduler scheduler;

    public SseEmitter createListener() {
        var sseEmitter = createAndConfigureNewSseEmitter();
        this.sseEmitters.add(sseEmitter);
        return sseEmitter;
    }

    public void emitToAllListeners(String name, UUID id, Object data) {
        sseEmitters.forEach(sseEmitter -> emit(sseEmitter, name, id, data));
    }

    public void emit(SseEmitter emitter, String name, UUID id, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .id(id.toString())
                    .data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private @NonNull SseEmitter createAndConfigureNewSseEmitter() {
        SseEmitter sseEmitter = new SseEmitter(0L);

        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            emit(sseEmitter, "heartbeat", UUID.randomUUID(), "keepalive");
        }, Duration.ofSeconds(15));

        sseEmitter.onTimeout(() -> {
            log.warn("SSE Emitter {} timed out, unregistering listener", sseEmitter);
            this.sseEmitters.remove(sseEmitter);
            heartbeat.cancel(true);
        });
        sseEmitter.onCompletion(() -> {
            this.sseEmitters.remove(sseEmitter);
            heartbeat.cancel(true);
        });
        sseEmitter.onError(e -> {
            log.warn("SSE Emitter {} encountered error {}, unregistering listener", sseEmitter, e.getMessage());
            this.sseEmitters.remove(sseEmitter);
            heartbeat.cancel(true);
        });

        return sseEmitter;
    }

}

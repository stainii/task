package be.stijnhooft.task.backend.task.service.helper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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

    /// An SSE request never finishes on its own, so at shutdown Tomcat's graceful shutdown sits
    /// waiting for one that cannot complete until `spring.lifecycle.timeout-per-shutdown-phase`
    /// expires - 30 seconds, on every deploy (ADR-0007 recreates the container nightly) and on
    /// every test run that opened a stream, which is the `Surefire is going to kill self fork
    /// JVM` message #23 measured at a quarter of the back-end job.
    ///
    /// So we complete them ourselves first. `ContextClosedEvent` is published before the
    /// lifecycle beans are stopped, which is why this is an event listener and not `@PreDestroy`
    /// (bean destruction happens after the web server has already done its waiting). Completing
    /// the emitter is also the right thing to tell a client: the stream ended, reconnect - which
    /// is precisely what ADR-0004's resume path is for.
    @EventListener(ContextClosedEvent.class)
    public void completeAllListenersOnShutdown() {
        if (!sseEmitters.isEmpty()) {
            log.info("Completing {} open SSE listener(s) before shutdown", sseEmitters.size());
        }
        sseEmitters.forEach(SseEmitter::complete);
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

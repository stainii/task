package be.stijnhooft.task.backend.task.service.helper;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

@Repository
@Slf4j
public class SseEmitters {

    final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    private final TaskScheduler scheduler;

    /// How long a stream is allowed to live before the server closes it and the client reconnects.
    ///
    /// A property rather than a constant because it is the one knob the tests have to turn: the
    /// point of bounding the lifetime is that **it makes the resume path self-testing**, and a test
    /// that has to wait twenty minutes to watch a reconnect is a test nobody runs. See ADR-0004 -
    /// reconnect-and-resume is the most load-bearing and least observable mechanism in the sync
    /// contract, and both of the SSE defects #12 found lived in it.
    private final Duration connectionLifetime;

    /// Keeps proxies from idling the connection out. A property for the same reason as the lifetime:
    /// a test that has to wait fifteen seconds to see one heartbeat is a test that makes the suite
    /// slower than the thing it proves is worth.
    private final Duration heartbeatInterval;

    public SseEmitters(@Qualifier("sseScheduler") TaskScheduler scheduler,
                       @Value("${task.sse.connection-lifetime}") Duration connectionLifetime,
                       @Value("${task.sse.heartbeat-interval}") Duration heartbeatInterval) {
        this.scheduler = scheduler;
        this.connectionLifetime = connectionLifetime;
        this.heartbeatInterval = heartbeatInterval;
    }

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

    public void emitToAllListeners(String name, @Nullable String id, Object data) {
        sseEmitters.forEach(sseEmitter -> emit(sseEmitter, name, id, data));
    }

    /// A null id means *this event is not a place in the history* - which is what a heartbeat is.
    /// Stamping one with an id of its own overwrites the browser's stored `Last-Event-ID` with a
    /// value naming no patch, so the resume it exists to enable asks to continue from nowhere.
    public void emit(SseEmitter emitter, String name, @Nullable String id, Object data) {
        try {
            var event = SseEmitter.event().name(name).data(data);
            if (id != null) {
                event = event.id(id);
            }
            emitter.send(event);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private @NonNull SseEmitter createAndConfigureNewSseEmitter() {
        SseEmitter sseEmitter = new SseEmitter(0L);

        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> emit(sseEmitter, "heartbeat", null, "keepalive"), heartbeatInterval);

        // Bounded lifetime, closed cleanly rather than dropped: the client reconnects with a fresh
        // token, and the resume path runs several times an hour on every device instead of only
        // when the wifi happens to fail.
        ScheduledFuture<?> lifetime = scheduler.schedule(
                sseEmitter::complete, Instant.now(scheduler.getClock()).plus(connectionLifetime));

        Runnable unregister = () -> {
            this.sseEmitters.remove(sseEmitter);
            heartbeat.cancel(true);
            lifetime.cancel(false);
        };

        sseEmitter.onTimeout(() -> {
            log.warn("SSE Emitter {} timed out, unregistering listener", sseEmitter);
            unregister.run();
        });
        sseEmitter.onCompletion(unregister);
        sseEmitter.onError(e -> {
            log.warn("SSE Emitter {} encountered error {}, unregistering listener", sseEmitter, e.getMessage());
            unregister.run();
        });

        return sseEmitter;
    }

}

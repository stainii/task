package be.stijnhooft.task.backend.notification;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.notification.domain.PushSubscription;
import be.stijnhooft.task.backend.notification.repository.PushSubscriptionRepository;
import be.stijnhooft.task.backend.notification.service.DailyPushService;
import be.stijnhooft.task.backend.task.DueTasks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// **The whole push path, against a push service that is really there.**
///
/// A stub `HttpServer` stands in for `fcm.googleapis.com`: the encryption, the VAPID signature, the
/// headers and the response handling all run for real, and what is asserted is what a push service
/// would see. Mocking `WebPushClient` here would leave the one thing that leaves the box untested,
/// and the two failures that actually matter — a message the device cannot read, and a dead
/// subscription that is never cleaned up — both live inside it.
///
/// The keys are RFC 8291's, so the ciphertext is one the RFC itself vouches for
/// ([WebPushEncryptionTest](webpush/WebPushEncryptionTest)).
@ApplicationModuleTest(extraIncludes = "config")
class DailyPushIntegrationTest extends AbstractIntegrationTestCases {

    private static final String A_REAL_P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String A_REAL_AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired
    private DailyPushService dailyPushService;

    @Autowired
    private PushSubscriptionRepository repository;

    @Autowired
    private Clock clock;

    @MockitoBean
    private DueTasks dueTasks;

    private StubPushService pushService;

    private final List<UUID> registered = new java.util.ArrayList<>();

    @BeforeEach
    void startThePushService() throws IOException {
        pushService = new StubPushService();
    }

    /// Only the devices this class registered — the suite shares one Postgres and `deleteAll` here
    /// would quietly empty another class's rows (`docs/quality-bar.md` §5).
    @AfterEach
    void stopThePushService() {
        pushService.stop();
        registered.forEach(repository::deleteById);
        registered.clear();
    }

    @Test
    void tellsEveryRegisteredDeviceWhatIsDue() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of("Vacuum the house", "Call Jan"));
        var phone = register();
        var tablet = register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(pushService.received).hasSize(2);
        assertThat(pushService.received)
                .extracting(Received::path)
                .containsExactlyInAnyOrder(phone.endpointPath(), tablet.endpointPath());
    }

    /// It is encrypted for that device, and it is a push message rather than a `POST` that happens
    /// to carry bytes: `aes128gcm`, a VAPID token naming us, and a TTL.
    @Test
    void sendsAnEncryptedMessageWithTheHeadersAPushServiceRequires() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of("Vacuum the house"));
        register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(pushService.received).singleElement().satisfies(request -> {
            assertThat(request.headers().get("Content-encoding")).isEqualTo("aes128gcm");
            assertThat(request.headers().get("Ttl")).isEqualTo("14400");
            assertThat(request.headers().get("Authorization")).startsWith("vapid t=");
            // 16 bytes of salt, 4 of record size, 1 length byte, 65 of key, then the ciphertext -
            // and nothing anywhere in it that reads as the task's name.
            assertThat(request.body().length).isGreaterThan(86);
            assertThat(new String(request.body(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("Vacuum");
        });
    }

    /// **A day with nothing due sends nothing** — not an empty notification, and not a *"nothing due
    /// today"*. A notification that arrives every single morning is the wallpaper failure that
    /// killed the mail (ADR-0012).
    @Test
    void aDayWithNothingDueSendsNothing() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of());
        register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(pushService.received).isEmpty();
    }

    /// **The server's one job in keeping the channel healthy**: `410 Gone` means that subscription
    /// no longer exists, so the row goes. Left in place it would be pushed to every morning for
    /// ever, and Chrome rotating an endpoint is the ordinary case rather than an error.
    @Test
    void prunesASubscriptionThePushServiceSaysIsGone() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of("Bin out"));
        pushService.answerWith(410);
        var dead = register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(repository.findByEndpoint(dead.endpoint())).isEmpty();
    }

    /// A push service having a bad morning keeps its device: tomorrow's push tries again. Only
    /// `404`/`410` mean *gone*, and confusing the two either loses a real device or keeps a dead one
    /// for ever.
    @Test
    void keepsASubscriptionWhenThePushServiceFails() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of("Bin out"));
        pushService.answerWith(503);
        var device = register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(repository.findByEndpoint(device.endpoint())).isPresent();
    }

    /// One dead device does not silence the others — `DueTemplateChecker`'s rule, and the reason the
    /// loop catches per subscription.
    @Test
    void oneDeadDeviceDoesNotStopTheNextOne() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of("Bin out"));
        var brokenId = UUID.randomUUID();
        registered.add(brokenId);
        var broken = repository.save(PushSubscription.of(brokenId,
                pushService.endpointFor("broken-" + brokenId),
                "not-a-key", "not-a-secret", clock.instant()));
        var healthy = register();

        dailyPushService.pushWhatIsDueToday();

        assertThat(repository.findByEndpoint(broken.endpoint())).isEmpty();
        assertThat(pushService.received).extracting(Received::path).contains(healthy.endpointPath());
    }

    @Test
    void asksForTheTasksDueToday() {
        when(dueTasks.namesOfTasksDueOn(any())).thenReturn(List.of());

        dailyPushService.pushWhatIsDueToday();

        org.mockito.Mockito.verify(dueTasks).namesOfTasksDueOn(LocalDate.now(clock));
    }

    private Device register() {
        var id = UUID.randomUUID();
        var endpoint = pushService.endpointFor(id.toString());
        registered.add(id);
        repository.save(PushSubscription.of(id, endpoint, A_REAL_P256DH, A_REAL_AUTH_SECRET, Instant.now(clock)));
        return new Device(endpoint, "/push/" + id);
    }

    private record Device(String endpoint, String endpointPath) {
    }

    private record Received(String path, Map<String, String> headers, byte[] body) {
    }

    /// A push service in twenty lines: it accepts anything, remembers what it was sent, and can be
    /// told to answer with a status instead.
    private final class StubPushService {

        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private volatile int status = 201;

        private StubPushService() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/push/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            var body = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getFirst()));
            received.add(new Received(exchange.getRequestURI().getPath(), headers, body));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        private void answerWith(int status) {
            this.status = status;
        }

        private String endpointFor(String id) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/push/" + id;
        }

        private void stop() {
            server.stop(0);
        }
    }
}

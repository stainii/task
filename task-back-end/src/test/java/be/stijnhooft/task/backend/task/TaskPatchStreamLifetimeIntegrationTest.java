package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// The two things a stream does on its own, on a clock small enough to watch.
///
/// Both are the *make the rarely-used path self-testing* rule ADR-0008 applied to backups and
/// ADR-0004 applied here: a bounded lifetime exists so that reconnect-and-resume runs several times
/// an hour on every device rather than only when the wifi fails, and a heartbeat exists so a proxy
/// does not idle the connection out. Neither has a symptom when it stops working - the stream simply
/// looks fine and goes quiet.
@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "task.sse.connection-lifetime=4s",
        "task.sse.heartbeat-interval=1s"
})
public class TaskPatchStreamLifetimeIntegrationTest extends AbstractIntegrationTestCases {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> EVENT =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;
    private String authorizationHeader;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        this.authorizationHeader = getAuthorizationHeaderForUser();
    }

    /// The server ends the stream and the client reconnects. It completes rather than errors,
    /// because *the stream ended, reconnect* is a different thing to say than *something broke*.
    @Test
    void theServerClosesTheStreamWhenItsLifetimeIsUp() {
        StepVerifier.create(stream())
                .thenConsumeWhile(event -> true)
                .expectComplete()
                .verify(Duration.ofSeconds(20));
    }

    /// **A heartbeat carries no id.** Stamping one with a random UUID - which is what this code did
    /// - overwrites the browser's stored `Last-Event-ID` with a value naming no patch, so the resume
    /// it exists to enable asks to continue from nowhere. One of #12's two SSE defects.
    @Test
    void heartbeatsCarryNoEventId() {
        var heartbeats = new ArrayList<ServerSentEvent<String>>();

        StepVerifier.create(stream().filter(event -> "heartbeat".equals(event.event())))
                .recordWith(() -> heartbeats)
                .expectNextCount(2)
                .thenCancel()
                .verify(Duration.ofSeconds(20));

        assertThat(heartbeats).allSatisfy(heartbeat -> assertThat(heartbeat.id()).isNull());
    }

    private Flux<ServerSentEvent<String>> stream() {
        return webTestClient.get()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(EVENT)
                .getResponseBody();
    }
}

package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.dto.TaskDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves a client can lose its connection mid-stream and pick up what it missed.
///
/// The drop is the interesting part: {@link StepVerifier#thenCancel()} cancels the subscription,
/// which closes the underlying socket - a real disconnect, not a simulated one.
@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskPatchStreamResumeIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void aClientThatDropsAndReconnectsReceivesWhatItMissed() {
        var authorizationHeader = getAuthorizationHeaderForUser();
        var task = createTask(authorizationHeader, Instant.now().minus(1, ChronoUnit.HOURS));

        // 1. connect, and wait until the stream is provably alive
        StepVerifier.create(openStream(authorizationHeader, null))
                .expectNextMatches(event -> event.contains("keepalive"))
                // 2. drop the connection
                .thenCancel()
                .verify(Duration.ofSeconds(20));

        var disconnectedAt = Instant.now();

        // 3. a patch happens while this client is away
        var patchDateTime = Instant.now().plusSeconds(1);
        patchTask(authorizationHeader, task.id(), patchDateTime, "Patched while away");

        // 4. reconnect, asking for everything since the disconnect
        StepVerifier.create(openStream(authorizationHeader, disconnectedAt)
                        // other tests share this database and leave randomly-dated patches behind,
                        // so skip anything that is not ours
                        .filter(event -> event.contains(task.id().toString())))
                .assertNext(event -> assertThat(event)
                        .contains(task.id().toString())
                        .contains("Patched while away"))
                .thenCancel()
                .verify(Duration.ofSeconds(20));
    }

    private Flux<String> openStream(String authorizationHeader, Instant since) {
        return webTestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/task-patches");
                    if (since != null) {
                        uriBuilder.queryParam("since", since.toString());
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", authorizationHeader)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
    }

    private TaskDto createTask(String authorizationHeader, Instant creationDateTime) {
        return webTestClient.post()
                .uri("/api/tasks")
                .header("Authorization", authorizationHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "task that outlives a disconnect",
                          "importance": "IMPORTANT",
                          "description": "This is a test task",
                          "context": "Personal",
                          "creationDateTime": "%s"
                        }
                        """.formatted(creationDateTime))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(TaskDto.class)
                .getResponseBody()
                .blockFirst();
    }

    private void patchTask(String authorizationHeader, UUID taskId, Instant dateTime, String newName) {
        webTestClient.post()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "id": "%s",
                          "taskId": "%s",
                          "dateTime": "%s",
                          "changes": {
                            "name": "%s"
                          }
                        }
                        """.formatted(UUID.randomUUID(), taskId, dateTime, newName))
                .exchange()
                .expectStatus().isOk();
    }
}

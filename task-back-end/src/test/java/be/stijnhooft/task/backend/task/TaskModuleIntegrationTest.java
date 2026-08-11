package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.dto.TaskSnapshotDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The write path end to end: one verb, and the first patch for a task id creates it.
@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskModuleIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;
    private String authorizationHeader;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        this.authorizationHeader = getAuthorizationHeaderForUser();
    }

    @Test
    void streamAndCreateAndPatchTasks() {
        var taskId = UUID.randomUUID();
        var creationDateTime = Instant.now().minus(1, ChronoUnit.HOURS);
        var patchDateTime = Instant.now();

        StepVerifier.create(openTaskPatchStream())

                // 1. Wait until stream is alive
                .expectNextMatches(event -> event.contains("keepalive"))

                // 2. The first patch for this id is the create - there is no other way to write.
                .then(() -> createTask(taskId, creationDateTime))

                // 3. Patch it
                .then(() -> patchTask(taskId, patchDateTime))

                // 4. Ignore noise until our event arrives. Matching on the patched name rather than
                //    on a reference: this line used to read `taskIdRef.toString()`, which is the
                //    *reference's* toString and renders as the literal "null" until the task exists.
                .thenConsumeWhile(event -> !event.contains("Patched task"))

                // 5. Assert the actual event, which carries the DTO and not the domain object
                .assertNext(event ->
                        assertThat(event)
                                .contains(taskId.toString())
                                .contains(patchDateTime.toString())
                                .contains("\"sequence\"")
                                .contains("Patched task")
                )

                .thenCancel()
                .verify();
    }

    /// A retry after a lost response is the ordinary case, not an error. A `500` here stalls that
    /// device's outbox permanently while ADR-0009's banner reports a system with nothing wrong
    /// (ADR-0010).
    @Test
    void thePatchThatIsSentTwiceIsAcceptedTwiceAndStoredOnce() {
        var taskId = UUID.randomUUID();
        var body = creationBody(taskId, Instant.now().minus(1, ChronoUnit.HOURS));

        post(body).expectStatus().isOk();
        var watermarkAfterFirst = snapshot().watermark();

        post(body).expectStatus().isOk();

        assertThat(snapshot().watermark())
                .as("a duplicate must burn no sequence number: a gap looks like a lost patch")
                .isEqualTo(watermarkAfterFirst);
        assertThat(snapshot().tasks())
                .filteredOn(task -> task.id().equals(taskId))
                .singleElement()
                .satisfies(task -> assertThat(task.history()).hasSize(1));
    }

    /// An orphan is a `404`, so the client drops it and keeps draining. As a `500` it would look
    /// like *the server is down* and retry forever, freezing every write behind it.
    @Test
    void aPatchForAnUnknownTaskIs404() {
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": { "name": "a task the server has never heard of" }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
                .expectStatus().isNotFound();
    }

    /// **D5**: this used to be a `500` out of the mapper.
    @Test
    void aPatchWithoutChangesIs400() {
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
                .expectStatus().isBadRequest();
    }

    /// The API refuses what the fold would silently ignore: the log is append-only and ADR-0005
    /// replays it forever, so anything accepted is accepted permanently.
    @Test
    void aPatchNamingNoFieldIs400() {
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": { "colour": "blue" }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
                .expectStatus().isBadRequest();
    }

    private Flux<String> openTaskPatchStream() {
        return webTestClient.get()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
    }

    private TaskSnapshotDto snapshot() {
        return Objects.requireNonNull(webTestClient.get()
                .uri("/api/tasks")
                .header("Authorization", authorizationHeader)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskSnapshotDto.class)
                .returnResult()
                .getResponseBody());
    }

    private void createTask(UUID taskId, Instant creationDateTime) {
        post(creationBody(taskId, creationDateTime)).expectStatus().isOk();
    }

    /// A creating patch is a dump of every field (TODO-046) - which is also what marks it as one,
    /// since nothing but a create ever restates `creationDateTime`.
    private static String creationBody(UUID taskId, Instant creationDateTime) {
        return """
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": {
                    "name": "test task",
                    "creationDateTime": "%s",
                    "startDate": "2026-08-11",
                    "context": "Personal",
                    "importance": "IMPORTANT",
                    "description": "This is a test task",
                    "status": "OPEN"
                  }
                }
                """.formatted(UUID.randomUUID(), taskId, creationDateTime, creationDateTime);
    }

    private void patchTask(UUID taskId, Instant dateTime) {
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": {
                    "name": "Patched task"
                  }
                }
                """.formatted(UUID.randomUUID(), taskId, dateTime))
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec post(String body) {
        return webTestClient.post()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }
}

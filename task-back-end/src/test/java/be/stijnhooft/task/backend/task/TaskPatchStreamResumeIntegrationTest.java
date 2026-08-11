package be.stijnhooft.task.backend.task;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.dto.TaskSnapshotDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves a client can lose its connection mid-stream and pick up **exactly** what it missed.
///
/// The drop is the interesting part: {@link StepVerifier#thenCancel()} cancels the subscription,
/// which closes the underlying socket - a real disconnect, not a simulated one. ADR-0004 calls
/// reconnect-and-resume the most load-bearing and least observable mechanism in the sync contract,
/// and it is where both of #12's SSE defects lived.
///
/// Every assertion here reads the **event id**, not the payload, because that is what a browser
/// resumes on and what the id-carrying heartbeat used to destroy.
@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskPatchStreamResumeIntegrationTest extends AbstractIntegrationTestCases {

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

    /// #46's *done when*: drop mid-stream, resume, no gap and no duplicate.
    ///
    /// It resumes on `Last-Event-ID`, which is what a browser sends on the reconnect it performs by
    /// itself - the path that runs after every bounded-lifetime close, and therefore the one that
    /// has to work. This test used to resume on a *timestamp*, which is the defect ADR-0004 was
    /// written to kill: a patch made offline before the reader's cursor is never delivered at all.
    @Test
    void aClientThatDropsAndReconnectsReceivesWhatItMissedExactlyOnce() {
        var task = createTask("task that outlives a disconnect");
        var cursorBeforeLeaving = cursorFromSnapshot();

        var firstPatch = patchTask(task, "Patched while away");
        var secondPatch = patchTask(task, "Patched while away, again");

        // 1. connect on the cursor, read the first of the two, and drop the connection
        var caughtUp = new ArrayList<ServerSentEvent<String>>();
        StepVerifier.create(patchesFor(task, cursorBeforeLeaving))
                .recordWith(() -> caughtUp)
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(20));

        var eventId = Objects.requireNonNull(caughtUp.getFirst().id());
        assertThat(caughtUp.getFirst().data()).contains(firstPatch.toString());
        assertThat(eventId)
                .as("an event id is epoch:sequence, or the browser's own reconnect has no lineage")
                .matches("\\d+:\\d+");

        // 2. reconnect from exactly where the drop happened
        var afterResume = new ArrayList<ServerSentEvent<String>>();
        StepVerifier.create(patchesFor(task, eventId))
                .recordWith(() -> afterResume)
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(20));

        // no gap: the patch made while away arrived. no duplicate: the one already seen did not.
        assertThat(afterResume).hasSize(1);
        assertThat(afterResume.getFirst().data()).contains(secondPatch.toString());
        assertThat(afterResume.getFirst().data()).doesNotContain(firstPatch.toString());
    }

    /// A cursor from a previous lineage of history - what restoring a backup leaves every client
    /// holding. Answered with a resync rather than a stream, because the same numbers now name
    /// different patches and a live tail would look healthy forever (ADR-0004's epoch).
    @Test
    void aClientOnAnOlderEpochIsToldToResync() {
        var currentEpoch = snapshot().epoch();

        StepVerifier.create(withoutHeartbeats(stream((currentEpoch - 1) + ":1")))
                .expectNextMatches(event -> "resync".equals(event.event()))
                .expectComplete()
                .verify(Duration.ofSeconds(20));
    }

    /// A `Last-Event-ID` this server never wrote names nothing servable. Serving a live tail would
    /// look healthy while silently skipping whatever the cursor could not name.
    @Test
    void aClientWithAnUnreadableCursorIsToldToResync() {
        StepVerifier.create(withoutHeartbeats(stream("who-knows")))
                .expectNextMatches(event -> "resync".equals(event.event()))
                .expectComplete()
                .verify(Duration.ofSeconds(20));
    }

    /// The two halves of a cursor travel together or not at all. Defaulting the epoch to the
    /// server's own would make a cursor that can never be found stale - the epoch switched off for
    /// exactly the client that needed it.
    @Test
    void aSinceCursorWithoutItsEpochIsRejected() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/task-patches").queryParam("since", 1).build())
                .header("Authorization", authorizationHeader)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest();
    }

    /// The snapshot's whole reason to exist beyond the tasks: without the watermark, every patch
    /// between the `GET` completing and the stream attaching is lost, invisibly, because both calls
    /// succeeded.
    @Test
    void theSnapshotCarriesTheCursorItWasReadAt() {
        var before = snapshot();
        var task = createTask("a task created after a snapshot was taken");

        var after = snapshot();

        assertThat(after.watermark()).isGreaterThan(before.watermark());
        assertThat(after.epoch()).isEqualTo(before.epoch());
        assertThat(after.tasks()).anySatisfy(dto -> assertThat(dto.id()).isEqualTo(task));
    }

    /// A heartbeat can win the race to the socket against the resync, since it fires as soon as the
    /// emitter is registered. It carries no id and no meaning here, so it is not part of the
    /// assertion.
    private Flux<ServerSentEvent<String>> withoutHeartbeats(Flux<ServerSentEvent<String>> events) {
        return events.filter(event -> !"heartbeat".equals(event.event()));
    }

    /// Other tests share this database, so a stream carries their patches too. Filtering by task id
    /// is #10's rule: assert only on data you created.
    private Flux<ServerSentEvent<String>> patchesFor(UUID taskId, String lastEventId) {
        return stream(lastEventId)
                .filter(event -> "patch".equals(event.event()))
                .filter(event -> String.valueOf(event.data()).contains(taskId.toString()));
    }

    private Flux<ServerSentEvent<String>> stream(@Nullable String lastEventId) {
        return webTestClient.get()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .headers(headers -> {
                    if (lastEventId != null) {
                        headers.set("Last-Event-ID", lastEventId);
                    }
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(EVENT)
                .getResponseBody();
    }

    /// What a real client does on first run: snapshot, then stream from the watermark it was read
    /// at. The cursor is the same `epoch:sequence` the server puts on every event.
    private String cursorFromSnapshot() {
        var snapshot = snapshot();
        return snapshot.epoch() + ":" + snapshot.watermark();
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

    /// The only way to write: the first patch for a task id creates it.
    private UUID createTask(String name) {
        var taskId = UUID.randomUUID();
        var createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": {
                    "name": "%s",
                    "creationDateTime": "%s",
                    "startDate": "2026-08-11",
                    "context": "Personal",
                    "importance": "IMPORTANT",
                    "description": "This is a test task",
                    "status": "OPEN"
                  }
                }
                """.formatted(UUID.randomUUID(), taskId, createdAt, name, createdAt))
                .expectStatus().isOk();
        return taskId;
    }

    private UUID patchTask(UUID taskId, String newName) {
        var patchId = UUID.randomUUID();
        post("""
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "%s",
                  "changes": {
                    "name": "%s"
                  }
                }
                """.formatted(patchId, taskId, Instant.now(), newName))
                .expectStatus().isOk();
        return patchId;
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

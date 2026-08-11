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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static be.stijnhooft.task.backend.task.Importance.IMPORTANT;
import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskModuleIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void streamAndCreateAndPatchTasks() {
        var authorizationHeader = getAuthorizationHeaderForUser();
        var taskCreationDateTime = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        var taskPatchCreationDateTime = Instant.now().toString();

        AtomicReference<UUID> taskIdRef = new AtomicReference<>();

        Flux<String> eventStream = openTaskPatchStream(authorizationHeader);

        StepVerifier.create(eventStream)

                // 1. Wait until stream is alive
                .expectNextMatches(event -> event.contains("keepalive"))

                // 2. Create task
                .then(() -> {
                    var task = createTask(authorizationHeader, taskCreationDateTime);
                    taskIdRef.set(task.id());
                })

                // 3. Patch task
                .then(() -> patchTask(
                        authorizationHeader,
                        taskIdRef.get(),
                        taskPatchCreationDateTime
                ))

                // 4. Ignore noise until our event arrives.
                //    Matching on the patched name rather than on the reference: this line used to
                //    read `taskIdRef.toString()`, which is the *reference's* toString and renders
                //    as the literal "null" until the task exists - harmless only for as long as no
                //    event payload contained the word null, which `"voids": null` now does.
                .thenConsumeWhile(event -> !event.contains("Patched task"))

                // 5. Assert the actual event
                .assertNext(event ->
                        assertThat(event)
                                .contains(taskIdRef.get().toString())
                                .contains(taskPatchCreationDateTime)
                                .contains("Patched task")
                )

                .thenCancel()
                .verify();
    }

    private Flux<String> openTaskPatchStream(String authorizationHeader) {
        return webTestClient.get()
                .uri("/api/task-patches")
                .header("Authorization", authorizationHeader)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .doOnNext(event -> System.out.println("SSE EVENT: " + event)); // optional debug
    }

    private TaskDto createTask(String authorizationHeader, String creationDateTime) {
        return webTestClient.post()
                .uri("/api/tasks")
                .header("Authorization", authorizationHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                    {
                      "name": "test task",
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

    private void patchTask(
            String authorizationHeader,
            UUID taskId,
            String dateTime
    ) {
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
                        "name": "Patched task"
                      }
                    }
                    """.formatted(UUID.randomUUID(), taskId, dateTime))
                .exchange()
                .expectStatus().isOk();
    }
}


package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TemplateApplicationIntegrationTest extends AbstractIntegrationTestCases {

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
    void createTasksFromTaskTemplate() {
        var authorizationHeader = getAuthorizationHeaderForUser();

        // first, open a stream to receive task patch events
        Flux<String> eventStream =
                webTestClient.get()
                        .uri("/api/task-patches")
                        .header("Authorization", authorizationHeader)
                        .header("Connection", "close")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isOk()
                        .returnResult(String.class)
                        .getResponseBody();

        StepVerifier.create(eventStream)
                // 2. First we expect a keepalive
                .assertNext(event ->
                        assertThat(event).contains("keepalive")
                )

                // 3. While the stream is open, create a task template and create tasks with it
                .then(() -> {
                    var taskDefinitions = List.of(
                            new TaskDefinition(
                                    UUID.randomUUID(),
                                    "test task 1",
                                    0,
                                    DeviationBase.START_DATE,
                                    10,
                                    DeviationBase.DUE_DATE,
                                    "test context 1",
                                    Importance.IMPORTANT,
                                    "This is the first test task ${variable1}"
                            ),
                            new TaskDefinition(
                                    UUID.randomUUID(),
                                    "test task 2 ${variable2}",
                                    10,
                                    DeviationBase.START_DATE,
                                    0,
                                    DeviationBase.DUE_DATE,
                                    "test context 2",
                                    Importance.NOT_SO_IMPORTANT,
                                    "This is the second test task"
                            )
                    );

                    var taskTemplate = new TaskTemplate(UUID.randomUUID(), "test template",
                            taskDefinitions,
                            List.of(new TaskTemplateVariableName("variable1"), new TaskTemplateVariableName("variable2")),
                            0);

                    webTestClient.post()
                            .uri("/api/task-templates")
                            .header("Authorization", authorizationHeader)
                            .bodyValue(taskTemplate)
                            .exchange()
                            .expectStatus().isEqualTo(HttpStatus.CREATED);


                    // prepare variables (if template defines variable names, provide values; otherwise empty map)
                    var variables = taskTemplate.getVariableNames().stream()
                            .collect(toMap(TaskTemplateVariableName::variableName, v -> "value-of-" + v.variableName()));

                    var entry = new TaskTemplateEntry(
                            variables,
                            LocalDate.now(),
                            LocalDate.now().plusDays(1)
                    );

                    webTestClient.post()
                            .uri("/api/task-templates/" + taskTemplate.getId() + "/tasks")
                            .header("Authorization", authorizationHeader)
                            .bodyValue(entry)
                            .exchange()
                            .expectStatus().isEqualTo(HttpStatus.CREATED);
                })

                // 4. Expect the creation events on the stream
                .assertNext(event ->
                        assertThat(event)
                                .contains("\"taskId\"")
                                .contains("\"importance\":\"IMPORTANT\"")
                                .contains("\"name\":\"test task 1\"")
                                .contains("\"context\":\"test context 1\"")
                                .contains("\"description\":\"This is the first test task value-of-variable1\"")
                                .contains("\"status\":\"OPEN\"")
                )
                .assertNext(event ->
                        assertThat(event)
                                .contains("\"taskId\"")
                                .contains("\"importance\":\"NOT_SO_IMPORTANT\"")
                                .contains("\"name\":\"test task 2 value-of-variable2\"")
                                .contains("\"context\":\"test context 2\"")
                                .contains("\"description\":\"This is the second test task\"")
                                .contains("\"status\":\"OPEN\"")
                )

                // 5. Stop the stream
                .thenCancel()
                .verify();
    }
}

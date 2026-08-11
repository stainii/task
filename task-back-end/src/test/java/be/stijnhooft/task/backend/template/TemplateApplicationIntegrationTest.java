package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TemplateApplicationIntegrationTest extends AbstractIntegrationTestCases {

    /// A date in the past, so nothing this test writes poses as future-dated data for another test
    /// class sharing the container (`docs/quality-bar.md` §5).
    private static final LocalDate ANCHOR = LocalDate.of(2026, 3, 4);

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /// One anchor, two offsets, one context, and variables that were never declared anywhere —
    /// the whole merge, end to end, seen from the patch stream.
    @Test
    void createTasksFromTaskTemplate() {
        var authorizationHeader = getAuthorizationHeaderForUser();

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
                .assertNext(event -> assertThat(event).contains("keepalive"))

                .then(() -> {
                    var taskTemplate = new TaskTemplateDto(
                            UUID.randomUUID(),
                            "test template",
                            "test context ${variable1}",
                            true,
                            null,
                            StoredTrigger.of(new Trigger.Manual("When is the workshop?")),
                            List.of(
                                    new TaskDefinitionDto(UUID.randomUUID(), "test task 1", 0, 10,
                                            Importance.IMPORTANT, "This is the first test task ${variable1}"),
                                    new TaskDefinitionDto(UUID.randomUUID(), "test task 2 ${variable2}", -14, -7,
                                            Importance.NOT_SO_IMPORTANT, "This is the second test task")));

                    webTestClient.post()
                            .uri("/api/task-templates")
                            .header("Authorization", authorizationHeader)
                            .bodyValue(taskTemplate)
                            .exchange()
                            .expectStatus().isEqualTo(HttpStatus.CREATED);

                    // Nothing declared these. They are the ${...} in the text, which is the only
                    // list there is now.
                    var entry = new TaskTemplateEntry(
                            Map.of("variable1", "value-of-variable1", "variable2", "value-of-variable2"),
                            ANCHOR);

                    webTestClient.post()
                            .uri("/api/task-templates/" + taskTemplate.id() + "/tasks")
                            .header("Authorization", authorizationHeader)
                            .bodyValue(entry)
                            .exchange()
                            .expectStatus().isEqualTo(HttpStatus.CREATED);
                })

                .assertNext(event ->
                        assertThat(event)
                                .contains("\"taskId\"")
                                .contains("\"importance\":\"IMPORTANT\"")
                                .contains("\"name\":\"test task 1\"")
                                // The context is the template's, rendered once for every task.
                                .contains("\"context\":\"test context value-of-variable1\"")
                                .contains("\"description\":\"This is the first test task value-of-variable1\"")
                                .contains("\"startDate\":\"2026-03-04\"")
                                .contains("\"dueDate\":\"2026-03-14\"")
                                .contains("\"status\":\"OPEN\"")
                )
                .assertNext(event ->
                        assertThat(event)
                                .contains("\"taskId\"")
                                .contains("\"importance\":\"NOT_SO_IMPORTANT\"")
                                .contains("\"name\":\"test task 2 value-of-variable2\"")
                                .contains("\"context\":\"test context value-of-variable1\"")
                                .contains("\"description\":\"This is the second test task\"")
                                // Negative offsets: two weeks before the anchor, due one week before it.
                                .contains("\"startDate\":\"2026-02-18\"")
                                .contains("\"dueDate\":\"2026-02-25\"")
                                .contains("\"status\":\"OPEN\"")
                )

                .thenCancel()
                .verify();
    }
}

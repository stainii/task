package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import be.stijnhooft.task.backend.task.mother.TaskMother;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
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

    @Autowired
    private TaskRepository taskRepository;

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
                                            Importance.NOT_SO_IMPORTANT, "This is the second test task")),
                            null);

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

    /// #88, end to end through the real query port: `GET /api/task-templates` carries the latest
    /// `completedOn` among a template's completed tasks, whatever order they fired in, and a
    /// cancelled occurrence later than all of them does not move it (ADR-0011's two anchors).
    @Test
    void listReportsATemplatesLastCompletionAcrossItsWholeHistory() {
        var templateId = UUID.randomUUID();
        var template = new TaskTemplateDto(templateId, "Boiler nakijken", "house", true, null,
                StoredTrigger.of(Trigger.MinMax.ofIntervalAndWindow(60, 7)),
                List.of(new TaskDefinitionDto(UUID.randomUUID(), "Boiler nakijken", 0, 7,
                        Importance.IMPORTANT, null)),
                null);

        webTestClient.post()
                .uri("/api/task-templates")
                .header("Authorization", getAuthorizationHeaderForUser())
                .bodyValue(template)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);

        // Fired out of order, and the middle firing is the one done most recently. Every date stays
        // in the past (quality-bar §5): ANCHOR is 2026-03-04 and the latest offset below is +150.
        taskRepository.save(TaskMother.firedTask(templateId, ANCHOR, TaskStatus.COMPLETED, ANCHOR.plusDays(2)));
        taskRepository.save(TaskMother.firedTask(templateId, ANCHOR.plusDays(120), TaskStatus.COMPLETED, ANCHOR.plusDays(121)));
        taskRepository.save(TaskMother.firedTask(templateId, ANCHOR.plusDays(60), TaskStatus.COMPLETED, ANCHOR.plusDays(140)));
        // A cancellation later than every completion — a closure, not a day the chore was done.
        taskRepository.save(TaskMother.firedTask(templateId, ANCHOR.plusDays(150), TaskStatus.CANCELLED, null));

        var listed = webTestClient.get()
                .uri("/api/task-templates")
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(new ParameterizedTypeReference<List<TaskTemplateDto>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(listed)
                .filteredOn(dto -> templateId.equals(dto.id()))
                .singleElement()
                .extracting(TaskTemplateDto::lastCompletedOn)
                .isEqualTo(ANCHOR.plusDays(140));
    }
}

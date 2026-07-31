package be.stijnhooft.task.backend.recurring;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.recurring.dto.ExecutionDto;
import be.stijnhooft.task.backend.recurring.dto.RecurringTaskTemplateDto;
import be.stijnhooft.task.backend.recurring.repository.RecurringTaskTemplateRepository;
import be.stijnhooft.task.backend.task.Importance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static be.stijnhooft.task.backend.recurring.mother.RecurringTaskTemplateDtoMother.createRandomRecurringTaskTemplateDto;
import static be.stijnhooft.task.backend.recurring.mother.RecurringTaskTemplateMother.createRandomRecurringTaskTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.*;

@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecurringTaskTemplateModuleIntegrationTest extends AbstractIntegrationTestCases {

    @Autowired
    private RecurringTaskTemplateRepository repo;

    @LocalServerPort
    private int port;

    private RestTestClient restTestClient;

    @BeforeEach
    void setUp() {
        this.restTestClient = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void findAll_returnsTasks() {
        RecurringTaskTemplate recurringTaskTemplate1 = createRandomRecurringTaskTemplate();
        RecurringTaskTemplate recurringTaskTemplate2 = createRandomRecurringTaskTemplate();

        repo.deleteAll();
        repo.saveAll(List.of(recurringTaskTemplate1, recurringTaskTemplate2));

        restTestClient.get()
                .uri("/api/recurring-task-templates/")
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()

                .expectStatus().is2xxSuccessful()

                .expectBody(new ParameterizedTypeReference<List<RecurringTaskTemplateDto>>() {
                }).isEqualTo(List.of(
                        new RecurringTaskTemplateDto(
                                recurringTaskTemplate1.getId(),
                                recurringTaskTemplate1.getName(),
                                recurringTaskTemplate1.getMinNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate1.getMaxNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate1.getImportance(),
                                recurringTaskTemplate1.getContext(),
                                recurringTaskTemplate1.getDescription()
                        ),
                        new RecurringTaskTemplateDto(
                                recurringTaskTemplate2.getId(),
                                recurringTaskTemplate2.getName(),
                                recurringTaskTemplate2.getMinNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate2.getMaxNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate2.getImportance(),
                                recurringTaskTemplate2.getContext(),
                                recurringTaskTemplate2.getDescription()
                        )));
    }

    @Test
    void findById_found() {
        RecurringTaskTemplate recurringTaskTemplate = createRandomRecurringTaskTemplate();
        repo.save(recurringTaskTemplate);

        restTestClient.get()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplate.getId())
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()

                .expectStatus().is2xxSuccessful()

                .expectBody(RecurringTaskTemplateDto.class).isEqualTo(
                        new RecurringTaskTemplateDto(
                                recurringTaskTemplate.getId(),
                                recurringTaskTemplate.getName(),
                                recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions(),
                                recurringTaskTemplate.getImportance(),
                                recurringTaskTemplate.getContext(),
                                recurringTaskTemplate.getDescription()
                        ));
    }


    @Test
    void findById_notFound() {
        UUID id = UUID.randomUUID();

        restTestClient.get()
                .uri("/api/recurring-task-templates/" + id)
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(NOT_FOUND);
    }

    @Test
    void create_returnsCreated() {
        RecurringTaskTemplateDto recurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();

        var createdRecurringTaskTemplateDto = restTestClient.post()
                .uri("/api/recurring-task-templates/")
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(recurringTaskTemplateDto)
                .exchange()

                .expectStatus().isEqualTo(CREATED)

                .expectBody(RecurringTaskTemplateDto.class)
                .returnResult()
                .getResponseBody();

        assertThat(createdRecurringTaskTemplateDto).isNotNull();
        assertThat(createdRecurringTaskTemplateDto.id()).isNotNull();
        assertThat(createdRecurringTaskTemplateDto.minNumberOfDaysBetweenExecutions()).isEqualTo(recurringTaskTemplateDto.minNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplateDto.maxNumberOfDaysBetweenExecutions()).isEqualTo(recurringTaskTemplateDto.maxNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplateDto.context()).isEqualTo(recurringTaskTemplateDto.context());
        assertThat(createdRecurringTaskTemplateDto.description()).isEqualTo(recurringTaskTemplateDto.description());
        assertThat(createdRecurringTaskTemplateDto.name()).isEqualTo(recurringTaskTemplateDto.name());

        var createdRecurringTaskTemplate = repo.findById(createdRecurringTaskTemplateDto.id())
                .orElseThrow();

        assertThat(createdRecurringTaskTemplate.getId()).isNotNull();
        assertThat(createdRecurringTaskTemplate.getMinNumberOfDaysBetweenExecutions()).isEqualTo(recurringTaskTemplateDto.minNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions()).isEqualTo(recurringTaskTemplateDto.maxNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplate.getContext()).isEqualTo(recurringTaskTemplateDto.context());
        assertThat(createdRecurringTaskTemplate.getDescription()).isEqualTo(recurringTaskTemplateDto.description());
        assertThat(createdRecurringTaskTemplate.getName()).isEqualTo(recurringTaskTemplateDto.name());
        assertThat(createdRecurringTaskTemplate.getCreationDate()).isEqualTo(LocalDate.now());
        assertThat(createdRecurringTaskTemplate.getExecutions()).isEmpty();
        assertThat(createdRecurringTaskTemplate.getVersion()).isEqualTo(1);
    }


    @Test
    void update_ok() {
        var recurringTaskTemplate = createRandomRecurringTaskTemplate();
        repo.save(recurringTaskTemplate);

        // make sure that creation date doesn't get updated
        // and that the executions don't get deleted
        // this will also increase the version
        var yesterday = LocalDate.now().minusDays(1);
        var executions = List.of(Execution.builder().id(UUID.randomUUID()).date(LocalDate.now()).build());

        var savedRecurringTask = repo.findById(recurringTaskTemplate.getId()).orElseThrow();
        savedRecurringTask.setCreationDate(yesterday);
        savedRecurringTask.setExecutions(
                executions
        );
        repo.save(savedRecurringTask);


        // update
        var updatedRecurringTaskTemplateDto = new RecurringTaskTemplateDto(
                recurringTaskTemplate.getId(),
                "Updated name",
                recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions() + 1,
                recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions() + 1,
                Importance.IMPORTANT,
                "Updated context",
                "Updated description"
        );

        var resultDto = restTestClient.put()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplate.getId())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(updatedRecurringTaskTemplateDto)
                .exchange()

                .expectStatus().isEqualTo(OK)

                .expectBody(RecurringTaskTemplateDto.class)
                .returnResult()
                .getResponseBody();

        assertThat(resultDto).isNotNull();
        assertThat(resultDto.id()).isNotNull();
        assertThat(resultDto.minNumberOfDaysBetweenExecutions()).isEqualTo(updatedRecurringTaskTemplateDto.minNumberOfDaysBetweenExecutions());
        assertThat(resultDto.maxNumberOfDaysBetweenExecutions()).isEqualTo(updatedRecurringTaskTemplateDto.maxNumberOfDaysBetweenExecutions());
        assertThat(resultDto.context()).isEqualTo(updatedRecurringTaskTemplateDto.context());
        assertThat(resultDto.description()).isEqualTo(updatedRecurringTaskTemplateDto.description());
        assertThat(resultDto.name()).isEqualTo(updatedRecurringTaskTemplateDto.name());

        var createdRecurringTaskTemplate = repo.findById(resultDto.id())
                .orElseThrow();

        assertThat(createdRecurringTaskTemplate.getId()).isNotNull();
        assertThat(createdRecurringTaskTemplate.getMinNumberOfDaysBetweenExecutions()).isEqualTo(updatedRecurringTaskTemplateDto.minNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions()).isEqualTo(updatedRecurringTaskTemplateDto.maxNumberOfDaysBetweenExecutions());
        assertThat(createdRecurringTaskTemplate.getContext()).isEqualTo(updatedRecurringTaskTemplateDto.context());
        assertThat(createdRecurringTaskTemplate.getDescription()).isEqualTo(updatedRecurringTaskTemplateDto.description());
        assertThat(createdRecurringTaskTemplate.getName()).isEqualTo(updatedRecurringTaskTemplateDto.name());
        assertThat(createdRecurringTaskTemplate.getCreationDate()).isEqualTo(yesterday);
        assertThat(createdRecurringTaskTemplate.getExecutions()).isEqualTo(executions);
        assertThat(createdRecurringTaskTemplate.getVersion()).isEqualTo(3);
    }


    @Test
    void update_idMismatch_returns400() {
        restTestClient.put()
                .uri("/api/recurring-task-templates/" + UUID.randomUUID())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(createRandomRecurringTaskTemplateDto())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void update_nonExistent_returns404() {
        var recurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();
        restTestClient.put()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplateDto.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(recurringTaskTemplateDto)
                .exchange()
                .expectStatus().isEqualTo(NOT_FOUND);
    }

    @Test
    void delete_ok() {
        var recurringTaskTemplate = createRandomRecurringTaskTemplate();
        repo.save(recurringTaskTemplate);

        restTestClient.delete()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplate.getId())
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().is2xxSuccessful();

        assertThat(repo.findById(recurringTaskTemplate.getId())).isEmpty();
    }


    @Test
    void addExecution_ok() {
        var recurringTaskTemplate = createRandomRecurringTaskTemplate();
        recurringTaskTemplate.setExecutions(new ArrayList<>());
        repo.save(recurringTaskTemplate);

        // execution 1
        var executionDate1 = new ExecutionDto(LocalDate.now().minusDays(1));

        restTestClient.post()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplate.getId() + "/execution")
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(executionDate1)
                .exchange()
                .expectStatus().isEqualTo(CREATED);

        var result1 = repo.findById(recurringTaskTemplate.getId()).orElseThrow().getExecutions();
        assertThat(result1).hasSize(1);
        assertThat(result1).extracting(Execution::getDate).containsExactly(executionDate1.date());
        assertThat(result1).extracting(Execution::getId).doesNotContainNull();

        // execution 2
        var executionDate2 = new ExecutionDto(LocalDate.now());

        restTestClient.post()
                .uri("/api/recurring-task-templates/" + recurringTaskTemplate.getId() + "/execution")
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(executionDate2)
                .exchange()
                .expectStatus().isEqualTo(CREATED);

        var result2 = repo.findById(recurringTaskTemplate.getId()).orElseThrow().getExecutions();
        assertThat(result2).hasSize(2);
        assertThat(result2).extracting(Execution::getDate).containsExactlyInAnyOrder(executionDate1.date(), executionDate2.date());
        assertThat(result2).extracting(Execution::getId).doesNotContainNull();
    }

    @Test
    void addExecution_notFound_returns404() {
        var execution = Execution.builder().id(UUID.randomUUID()).date(LocalDate.now()).build();

        restTestClient.post()
                .uri("/api/recurring-task-templates/" + UUID.randomUUID() + "/execution")
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(execution)
                .exchange()
                .expectStatus().isEqualTo(NOT_FOUND);
    }

}

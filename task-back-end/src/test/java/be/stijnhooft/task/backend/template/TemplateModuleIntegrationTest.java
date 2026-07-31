package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.template.mother.TaskTemplateDtoMother;
import be.stijnhooft.task.backend.template.mother.TaskTemplateMother;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TemplateModuleIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    @Autowired
    private TaskTemplateRepository taskTemplateRepository;

    private RestTestClient restTestClient;

    @BeforeEach
    void setUp() {
        this.restTestClient = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void createTaskTemplate() {
        var taskTemplate = TaskTemplateDtoMother.createRandomTaskTemplateDto();

        var authorizationHeader = getAuthorizationHeaderForUser();

        restTestClient.post()
                .uri("/api/task-templates")
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);

        var read = taskTemplateRepository.findById(taskTemplate.id());
        assertThat(read).isPresent();
        assertThat(read.get().getName()).isEqualTo(taskTemplate.name());
    }

    @Test
    void createTaskTemplateTwice() {
        var taskTemplate = TaskTemplateDtoMother.createRandomTaskTemplateDto();
        var authorizationHeader = getAuthorizationHeaderForUser();

        restTestClient.post()
                .uri("/api/task-templates")
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);

        restTestClient.post()
                .uri("/api/task-templates")
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().is4xxClientError();

    }

    @Test
    void updateTaskTemplate() {
        var taskTemplate = TaskTemplateMother.createRandomTaskTemplate();
        taskTemplateRepository.save(taskTemplate);
        var authorizationHeader = getAuthorizationHeaderForUser();

        var updatedName = taskTemplate.getName() + "-updated";
        taskTemplate.setName(updatedName);

        restTestClient.put()
                .uri("/api/task-templates/" + taskTemplate.getId())
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        var read = taskTemplateRepository.findById(taskTemplate.getId());
        assertThat(read).isPresent();
        assertThat(read.get().getName()).isEqualTo(updatedName);
    }

    @Test
    void updateTaskTemplateInvalidUrl() {
        var taskTemplate = TaskTemplateMother.createRandomTaskTemplate();
        taskTemplateRepository.save(taskTemplate);
        var authorizationHeader = getAuthorizationHeaderForUser();

        restTestClient.put()
                .uri("/api/task-templates/" + UUID.randomUUID())
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void updateTaskTemplateWhenItDoesNotExist() {
        var taskTemplate = TaskTemplateDtoMother.createRandomTaskTemplateDto();
        var authorizationHeader = getAuthorizationHeaderForUser();

        restTestClient.put()
                .uri("/api/task-templates/" + taskTemplate.id())
                .header("Authorization", authorizationHeader)
                .body(taskTemplate)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void deleteTaskTemplate() {
        var taskTemplate = TaskTemplateMother.createRandomTaskTemplate();
        taskTemplateRepository.save(taskTemplate);
        var authorizationHeader = getAuthorizationHeaderForUser();

        restTestClient.delete()
                .uri("/api/task-templates/" + taskTemplate.getId())
                .header("Authorization", authorizationHeader)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(taskTemplate.getId())).isEmpty();
    }

}

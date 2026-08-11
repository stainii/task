package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.template.domain.CalendarRule;
import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
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

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
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
        var taskTemplate = TaskTemplateDtoMother.manualTemplateDto();

        create(taskTemplate).expectStatus().isEqualTo(HttpStatus.CREATED);

        var read = taskTemplateRepository.findById(taskTemplate.id());
        assertThat(read).isPresent();
        assertThat(read.get().name()).isEqualTo(taskTemplate.name());
        assertThat(read.get().context()).isEqualTo(taskTemplate.context());
        assertThat(read.get().active()).isTrue();
    }

    /// The three shapes survive a round trip through the table, which is the one place the sealed
    /// type and its columns can drift apart.
    @Test
    void createsATemplateOfEveryTriggerShape() {
        List<Trigger> triggers = List.of(
                new Trigger.Manual("When do you leave?"),
                Trigger.MinMax.ofIntervalAndWindow(45, 3),
                new Trigger.Calendar(new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.FIRST, DayOfWeek.SATURDAY)),
                new Trigger.Calendar(new CalendarRule.Weeks(2, Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))));

        for (Trigger trigger : triggers) {
            var dto = TaskTemplateDtoMother.templateDtoWith(trigger);

            create(dto).expectStatus().isEqualTo(HttpStatus.CREATED);

            var read = taskTemplateRepository.findById(dto.id());
            assertThat(read).isPresent();
            assertThat(read.get().trigger()).isEqualTo(trigger);
        }
    }

    /// `activeSince` is the server's, not the payload's: it is *the date this template began firing
    /// under its current rule*, and a client cannot know that.
    @Test
    void stampsActiveSinceOnCreation() {
        var taskTemplate = TaskTemplateDtoMother.manualTemplateDto();

        create(taskTemplate).expectStatus().isEqualTo(HttpStatus.CREATED);

        assertThat(taskTemplateRepository.findById(taskTemplate.id()))
                .get()
                .extracting(TaskTemplate::activeSince)
                .isNotNull();
    }

    @Test
    void createTaskTemplateTwice() {
        var taskTemplate = TaskTemplateDtoMother.manualTemplateDto();

        create(taskTemplate).expectStatus().isEqualTo(HttpStatus.CREATED);
        create(taskTemplate).expectStatus().is4xxClientError();
    }

    @Test
    void updateTaskTemplate() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());
        var updatedName = stored.name() + "-updated";

        restTestClient.put()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(dtoOf(stored, updatedName))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .extracting(TaskTemplate::name)
                .isEqualTo(updatedName);
    }

    /// ADR-0017's easy miss: a bin template re-ruled from Tuesdays to Thursdays finds no task on
    /// any Thursday, so without this reset it would immediately fire a backdated one.
    @Test
    void changingTheTriggerMovesActiveSince() {
        var stored = taskTemplateRepository.save(
                TaskTemplateMother.templateWith(new Trigger.Calendar(
                        new CalendarRule.Weeks(1, Set.of(DayOfWeek.TUESDAY)))));

        var reRuled = new TaskTemplateDto(stored.id(), stored.name(), stored.context(), true, null,
                StoredTrigger.of(new Trigger.Calendar(new CalendarRule.Weeks(1, Set.of(DayOfWeek.THURSDAY)))),
                List.of());

        restTestClient.put()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(reRuled)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .extracting(TaskTemplate::activeSince)
                .isNotEqualTo(TaskTemplateMother.ACTIVE_SINCE);
    }

    /// Renaming is not re-ruling. Editing a definition's name or description writes nothing to
    /// `activeSince`, or every edit would silently reset the template's history floor.
    @Test
    void renamingLeavesActiveSinceAlone() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());

        restTestClient.put()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(dtoOf(stored, stored.name() + "-renamed"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .extracting(TaskTemplate::activeSince)
                .isEqualTo(TaskTemplateMother.ACTIVE_SINCE);
    }

    @Test
    void updateTaskTemplateInvalidUrl() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());

        restTestClient.put()
                .uri("/api/task-templates/" + UUID.randomUUID())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(dtoOf(stored, stored.name()))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void updateTaskTemplateWhenItDoesNotExist() {
        var taskTemplate = TaskTemplateDtoMother.manualTemplateDto();

        restTestClient.put()
                .uri("/api/task-templates/" + taskTemplate.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(taskTemplate)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void deleteTaskTemplate() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());

        restTestClient.delete()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id())).isEmpty();
    }

    private RestTestClient.ResponseSpec create(TaskTemplateDto dto) {
        return restTestClient.post()
                .uri("/api/task-templates")
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(dto)
                .exchange();
    }

    private TaskTemplateDto dtoOf(TaskTemplate template, String name) {
        return new TaskTemplateDto(template.id(), name, template.context(), template.active(),
                template.activeSince(), template.storedTrigger(), List.of());
    }
}

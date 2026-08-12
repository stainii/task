package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.TaskOccurrences;
import be.stijnhooft.task.backend.template.domain.CalendarRule;
import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.dto.TaskDefinitionDto;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import be.stijnhooft.task.backend.template.mother.TaskTemplateDtoMother;
import be.stijnhooft.task.backend.template.mother.TaskTemplateMother;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TemplateModuleIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    @Autowired
    private TaskTemplateRepository taskTemplateRepository;

    /// `template` bootstraps alone here, and since #49 it reads `task`'s query port to decide
    /// whether a template has come round. Nothing in this class fires anything - the mock is what
    /// keeps the module test standalone, which is the point of it.
    @MockitoBean
    private TaskOccurrences taskOccurrences;

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
                definitionsOf(stored));

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

    /// A template with no definitions renders no tasks, and `TaskTemplateFired` refuses an empty
    /// firing - so before this validation it threw once an hour for as long as the row existed, and
    /// the only trace was an ERROR line. #49 left five such rows in this suite's shared database.
    @Test
    void refusesATemplateThatCouldNeverProduceATask() {
        var withoutDefinitions = new TaskTemplateDto(UUID.randomUUID(), "Empty", "house", true, null,
                StoredTrigger.of(new Trigger.Manual("When?")), List.of());

        create(withoutDefinitions).expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(taskTemplateRepository.findById(withoutDefinitions.id())).isEmpty();
    }

    /// Variables are manual-only (ADR-0013). Nothing is present at 04:00 to answer a `${…}`, so a
    /// scheduled template carrying one renders a task literally named `${who}`.
    @Test
    void refusesAVariableInAScheduledTemplate() {
        var scheduled = TaskTemplateDtoMother.templateDtoWith(
                Trigger.MinMax.ofIntervalAndWindow(10, 3), "Beddengoed wassen ${who}");

        create(scheduled).expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(taskTemplateRepository.findById(scheduled.id())).isEmpty();
    }

    /// The other half of the same rule, asserted beside it: a manual template is exactly where a
    /// variable belongs, because someone is standing there to answer it.
    @Test
    void acceptsAVariableInAManualTemplate() {
        var manual = TaskTemplateDtoMother.manualTemplateDtoWithVariables();

        create(manual).expectStatus().isEqualTo(HttpStatus.CREATED);

        assertThat(taskTemplateRepository.findById(manual.id())).isPresent();
    }

    /// `@Valid`, the fourth of D4's four fixes. A blank name renders to a blank task name, which the
    /// firing refuses loudly - this turns that into a `400` on the screen that caused it.
    @Test
    void refusesABlankDefinitionName() {
        var blank = TaskTemplateDtoMother.templateDtoWith(new Trigger.Manual("When?"), "  ");

        create(blank).expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// D4's first fix, asserted rather than assumed: portal's `@RequestMapping("/{id}")` carried no
    /// verb, so a `PATCH` silently returned the template with a `200` instead of refusing it.
    @Test
    void answersOnlyTheVerbsItDeclares() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());

        restTestClient.method(HttpMethod.PATCH)
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(dtoOf(stored, stored.name()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void deactivatingStopsATemplateAndMovesActiveSince() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.calendarTemplate());

        restTestClient.post()
                .uri("/api/task-templates/" + stored.id() + "/deactivation")
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .satisfies(template -> {
                    assertThat(template.active()).isFalse();
                    assertThat(template.activeSince()).isNotEqualTo(TaskTemplateMother.ACTIVE_SINCE);
                });
    }

    /// **Reactivation starts today, never where it left off.** Without the `activeSince` write, a
    /// calendar template switched back on after months away catches up on a date it spent the pause
    /// deliberately not firing for (ADR-0013's amendment).
    @Test
    void reactivatingStartsFromTodayRatherThanWhereItLeftOff() {
        var stored = taskTemplateRepository.save(
                TaskTemplateMother.calendarTemplate().deactivated(TaskTemplateMother.ACTIVE_SINCE));

        restTestClient.post()
                .uri("/api/task-templates/" + stored.id() + "/reactivation")
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .satisfies(template -> {
                    assertThat(template.active()).isTrue();
                    assertThat(template.activeSince()).isAfter(TaskTemplateMother.ACTIVE_SINCE);
                });
    }

    /// The reason activation has its own endpoints at all: an edit that could flip `active` would be
    /// a second path to reactivation, and that path does not move `activeSince`.
    @Test
    void anEditCannotSwitchATemplateBackOn() {
        var stored = taskTemplateRepository.save(
                TaskTemplateMother.calendarTemplate().deactivated(TaskTemplateMother.ACTIVE_SINCE));

        var claimingActive = new TaskTemplateDto(stored.id(), stored.name(), stored.context(), true, null,
                stored.storedTrigger(), definitionsOf(stored));

        restTestClient.put()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .body(claimingActive)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        assertThat(taskTemplateRepository.findById(stored.id()))
                .get()
                .extracting(TaskTemplate::active)
                .isEqualTo(false);
    }

    /// `taskTemplateId` is the only provenance a task has now that an occurrence is derived, and #35
    /// measured what deleting costs: 49% of portal's recurring tasks point at nothing. History is
    /// enough to refuse - the tasks here need not be open.
    @Test
    void refusesToDeleteATemplateThatHasTasks() {
        var stored = taskTemplateRepository.save(TaskTemplateMother.manualTemplate());
        when(taskOccurrences.hasAnyOccurrence(stored.id())).thenReturn(true);

        restTestClient.delete()
                .uri("/api/task-templates/" + stored.id())
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(taskTemplateRepository.findById(stored.id())).isPresent();
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
                template.activeSince(), template.storedTrigger(), definitionsOf(template));
    }

    /// A `PUT` carries the definitions it means to keep. Sending none used to be accepted and left a
    /// template that throws every time it fires - five of them are still in this suite's shared
    /// database, put there by exactly this call before #50 refused it.
    private static List<TaskDefinitionDto> definitionsOf(TaskTemplate template) {
        return template.taskDefinitions().stream()
                .map(definition -> new TaskDefinitionDto(definition.id(), definition.name(),
                        definition.startDateOffsetDays(), definition.dueDateOffsetDays(),
                        definition.importance(), definition.description()))
                .toList();
    }
}

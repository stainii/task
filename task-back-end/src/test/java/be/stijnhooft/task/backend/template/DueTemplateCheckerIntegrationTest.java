package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskPatch;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import be.stijnhooft.task.backend.task.mother.TaskMother;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import be.stijnhooft.task.backend.template.domain.CalendarRule;
import be.stijnhooft.task.backend.template.domain.TaskDefinition;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import be.stijnhooft.task.backend.template.service.DueTemplateChecker;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// **The firing engine against a real database**: the predicate, the render, the event and the
/// tasks it leaves behind, driven a day at a time by moving the clock.
///
/// A full `@SpringBootTest` rather than an `@ApplicationModuleTest`, because what is under test is
/// the round trip *through* the module boundary — `template` fires, `task` listens and writes, and
/// the next check reads back what `task` wrote. Scheduling stays off for the whole suite (see
/// `src/test/resources/application.properties`) and the checker is called directly, which is
/// exactly what splitting the check from the schedule bought;
/// [DueCheckStartupIntegrationTest](DueCheckStartupIntegrationTest) is the one context that turns
/// the schedule on.
///
/// Every test invents its own template and asserts only on tasks carrying that id — the Postgres
/// container is shared and nothing is cleaned up between classes (`docs/quality-bar.md` §5).
@SpringBootTest
class DueTemplateCheckerIntegrationTest extends AbstractIntegrationTestCases {

    /// In the past, so nothing written here poses as future-dated data for another test class. The
    /// clock only ever moves forward from it by days, never as far as today.
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

    /// The first Tuesday after [#MARCH], which is a Sunday.
    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);

    @Autowired
    private DueTemplateChecker dueTemplateChecker;

    @Autowired
    private TaskTemplateRepository taskTemplateRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskPatchService taskPatchService;

    @Autowired
    private TestClock clock;

    @TestConfiguration
    static class MovableClock {

        /// `@Primary` rather than a bean named `clock`: definition overriding is off by default, so
        /// `TimeConfig`'s bean stays where it is and simply stops being the one that gets injected.
        @Bean
        @Primary
        TestClock testClock() {
            return TestClock.atNoonOn(MARCH);
        }
    }

    /// A min/max template is created at `min` and **due at `max`** (REC-006) — which is where the
    /// old reminder→urgent escalation went: the final warning is that same task going overdue, so
    /// nothing has to notify anyone.
    @Test
    void aMinMaxTemplateFiresAtMinAndIsDueAtMax() {
        clock.moveTo(MARCH);
        var template = save(minMaxTemplate(7, 3));

        clock.moveTo(MARCH.plusDays(6));
        dueTemplateChecker.check();
        assertThat(tasksOf(template)).isEmpty();

        clock.moveTo(MARCH.plusDays(7));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).singleElement().satisfies(task -> {
            // The firing date is the task's creation date - the only record of a firing there is.
            assertThat(firingDateOf(task)).isEqualTo(MARCH.plusDays(7));
            assertThat(task.startDate()).isEqualTo(MARCH.plusDays(7));
            assertThat(task.dueDate()).isEqualTo(MARCH.plusDays(10));
            assertThat(task.context()).isEqualTo(template.context());
            assertThat(task.taskTemplateId()).isEqualTo(template.id());
            assertThat(task.occurrenceId()).isNotNull();
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        });
    }

    /// **A fortnight of downtime costs one task**, dated the day it became due rather than the day
    /// the app came back — so it arrives already overdue, and honestly so.
    @Test
    void aFortnightOfDowntimeCostsExactlyOneTask() {
        clock.moveTo(MARCH);
        var template = save(minMaxTemplate(7, 0));

        // Nothing checks for a fortnight, and then the app comes back.
        clock.moveTo(MARCH.plusDays(21));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).singleElement()
                .satisfies(task -> assertThat(firingDateOf(task)).isEqualTo(MARCH.plusDays(7)));
    }

    /// **An open task suppresses the template**, so a neglected chore does not accumulate: six
    /// checks over six days, one task.
    @Test
    void anOpenTaskStopsTheTemplateFiringAgain() {
        clock.moveTo(MARCH);
        var template = save(minMaxTemplate(1, 0));

        for (var day = 1; day <= 6; day++) {
            clock.moveTo(MARCH.plusDays(day));
            dueTemplateChecker.check();
        }

        assertThat(tasksOf(template)).hasSize(1);
    }

    /// **A cancelled task buys a full interval of quiet** (ADR-0011). Any closure ends the round, so
    /// the clock restarts from the cancelled task's firing date. Read the last *completion* instead
    /// and nothing is open to suppress the template while the last completion stays in the past —
    /// so it fires again tomorrow, and the day after, until something is completed.
    @Test
    void aCancelledTaskBuysAFullInterval() {
        clock.moveTo(MARCH);
        var template = save(minMaxTemplate(10, 0));
        taskRepository.save(TaskMother.firedTask(template.id(), MARCH.plusDays(10), TaskStatus.CANCELLED, null));

        clock.moveTo(MARCH.plusDays(19));
        dueTemplateChecker.check();
        assertThat(openTasksOf(template)).isEmpty();

        clock.moveTo(MARCH.plusDays(20));
        dueTemplateChecker.check();

        assertThat(openTasksOf(template)).singleElement()
                .satisfies(task -> assertThat(firingDateOf(task)).isEqualTo(MARCH.plusDays(20)));
    }

    /// **The calendar task completed at 09:00 that must not come back at 10:00.** ADR-0016 needed a
    /// same-date rule of its own for this; ADR-0017's predicate makes it a consequence of the last
    /// closure instead. Worth proving because the hourly tick is what exposes it — a daily cron hid
    /// it by cadence accident, which is the least trustworthy kind of correctness.
    @Test
    void aCalendarTaskClosedTodayDoesNotFireAgainTheSameDay() {
        clock.moveTo(MARCH);
        var template = save(everyTuesday());

        clock.moveTo(TUESDAY);
        dueTemplateChecker.check();
        assertThat(tasksOf(template)).hasSize(1);

        // 09:00: it is done. What follows is the rest of the day's ticks.
        complete(tasksOf(template).getFirst(), TUESDAY);
        for (var tick = 0; tick < 5; tick++) {
            dueTemplateChecker.check();
        }

        assertThat(tasksOf(template)).hasSize(1);
    }

    /// A calendar template does not drift: completing Tuesday's task on Wednesday buys until the
    /// **next Tuesday**, not seven days from Wednesday. That difference from `MinMax` is the whole
    /// reason both triggers exist.
    @Test
    void aCalendarTemplateFiresAgainOnItsNextDateAndNotBefore() {
        clock.moveTo(MARCH);
        var template = save(everyTuesday());

        clock.moveTo(TUESDAY);
        dueTemplateChecker.check();
        clock.moveTo(TUESDAY.plusDays(1));
        complete(tasksOf(template).getFirst(), TUESDAY.plusDays(1));

        clock.moveTo(TUESDAY.plusDays(6));
        dueTemplateChecker.check();
        assertThat(tasksOf(template)).hasSize(1);

        clock.moveTo(TUESDAY.plusWeeks(1));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).hasSize(2)
                .anySatisfy(task -> assertThat(firingDateOf(task)).isEqualTo(TUESDAY.plusWeeks(1)));
    }

    /// **Three missed Tuesdays come back as one task**, anchored on the most recent of them. Two
    /// weeks away must not produce a fortnight of bin tasks for bins already collected.
    @Test
    void missedCalendarDatesComeBackAsOneTaskForTheLatestOfThem() {
        clock.moveTo(MARCH);
        var template = save(everyTuesday());

        clock.moveTo(TUESDAY.plusWeeks(3).plusDays(1));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).singleElement()
                .satisfies(task -> assertThat(firingDateOf(task)).isEqualTo(TUESDAY.plusWeeks(3)));
    }

    /// A deactivated template stops firing and keeps its history (ADR-0013).
    @Test
    void aDeactivatedTemplateDoesNotFire() {
        clock.moveTo(MARCH);
        var template = save(deactivated(minMaxTemplate(1, 0)));

        clock.moveTo(MARCH.plusDays(30));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).isEmpty();
    }

    /// A manual template is run by hand and never by the tick, however long it sits there.
    @Test
    void aManualTemplateNeverFiresOnATick() {
        clock.moveTo(MARCH);
        var template = save(templateWith(new Trigger.Manual("When is the workshop?")));

        clock.moveTo(MARCH.plusDays(300));
        dueTemplateChecker.check();

        assertThat(tasksOf(template)).isEmpty();
    }

    /// **One template's failure is one template's failure.** A template that renders nothing throws
    /// when it fires; without the per-template catch that exception leaves the sweep, and every
    /// template it had not reached yet silently stops producing tasks — for as long as the bad row
    /// exists, not for one hour. Spring keeps the *schedule* alive through a throwing tick; only
    /// this keeps the *sweep* alive.
    @Test
    void oneFailingTemplateDoesNotStopTheSweep() {
        clock.moveTo(MARCH);
        var broken = save(templateWithNoDefinitions());
        var healthy = save(minMaxTemplate(1, 0));
        var log = captureLogsOf(DueTemplateChecker.class);

        clock.moveTo(MARCH.plusDays(1));
        assertThatCode(() -> dueTemplateChecker.check()).doesNotThrowAnyException();

        assertThat(tasksOf(healthy)).hasSize(1);
        assertThat(log.list)
                .filteredOn(event -> event.getFormattedMessage().contains(broken.id().toString()))
                .singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    /// **An idle tick is silent** (ADR-0016). At 24 ticks a day, a line saying nothing happened
    /// fills ADR-0009's 30-day forensic window with records of nothing having happened — the same
    /// wallpaper argument that killed the notification mail.
    @Test
    void aTickThatFiresNothingLogsNothing() {
        clock.moveTo(MARCH);
        save(templateWith(new Trigger.Manual("When is the workshop?")));
        var log = captureLogsOf(DueTemplateChecker.class);

        dueTemplateChecker.check();

        assertThat(log.list).isEmpty();
    }

    /// The other half of that rule: a tick that fires says what it fired **and which date it fired
    /// for**, which is the only trace a catch-up leaves anywhere.
    @Test
    void aTickThatFiresSaysWhatItFired() {
        clock.moveTo(MARCH);
        var template = save(minMaxTemplate(1, 0));
        var log = captureLogsOf(DueTemplateChecker.class);

        clock.moveTo(MARCH.plusDays(1));
        dueTemplateChecker.check();

        assertThat(log.list)
                .filteredOn(event -> event.getFormattedMessage().contains(template.id().toString()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).contains(MARCH.plusDays(1).toString());
                });
    }

    private TaskTemplate save(TaskTemplate template) {
        return taskTemplateRepository.save(template);
    }

    /// A template whose one definition sets **no offsets**, so the dates on the task it produces
    /// come from the trigger and the firing date rather than from the definition. The mother's
    /// templates carry offsets, which is the right default everywhere except here.
    private static TaskTemplate minMaxTemplate(int interval, int window) {
        return templateWith(Trigger.MinMax.ofIntervalAndWindow(interval, window));
    }

    private static TaskTemplate everyTuesday() {
        return templateWith(new Trigger.Calendar(new CalendarRule.Weeks(1, Set.of(DayOfWeek.TUESDAY))));
    }

    private static TaskTemplate templateWith(Trigger trigger) {
        return TaskTemplate.of(UUID.randomUUID(), "Bin " + UUID.randomUUID(), "house", MARCH, trigger,
                List.of(TaskDefinition.of(UUID.randomUUID(), "Bin out", null, null, Importance.IMPORTANT, null)));
    }

    /// Renders nothing, so firing it throws: a firing with no tasks is not a firing.
    private static TaskTemplate templateWithNoDefinitions() {
        return TaskTemplate.of(UUID.randomUUID(), "Empty " + UUID.randomUUID(), "house", MARCH,
                Trigger.MinMax.ofIntervalAndWindow(1, 0), List.of());
    }

    private static TaskTemplate deactivated(TaskTemplate template) {
        return new TaskTemplate(template.id(), template.name(), template.context(), false,
                template.activeSince(), template.storedTrigger(), template.taskDefinitions(), template.version());
    }

    private List<Task> tasksOf(TaskTemplate template) {
        return StreamSupport.stream(taskRepository.findAll().spliterator(), false)
                .filter(task -> template.id().equals(task.taskTemplateId()))
                .toList();
    }

    /// The tasks the checker created, as opposed to one a test seeded to give the template a past.
    private List<Task> openTasksOf(TaskTemplate template) {
        return tasksOf(template).stream()
                .filter(task -> task.status() == TaskStatus.OPEN)
                .toList();
    }

    private LocalDate firingDateOf(Task task) {
        return LocalDate.ofInstant(task.creationDateTime(), TestClock.ZONE);
    }

    /// Closed the way the application closes a task: a patch through the real write path, so the
    /// server mints the sequence and the fold does the rest.
    private void complete(Task task, LocalDate completedOn) {
        taskPatchService.patch(TaskPatch.builder()
                .taskId(task.id())
                .dateTime(clock.instant())
                .change("status", TaskStatus.COMPLETED)
                .change("completedOn", completedOn)
                .build());
    }

    private ListAppender<ILoggingEvent> captureLogsOf(Class<?> type) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }
}

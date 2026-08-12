package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.task.TaskOccurrences;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/// **The due check**: every template is asked whether it has come round, and the ones that have
/// fire ([ADR-0016](../../../../../../../../docs/adr/0016-the-due-check-ticks-hourly-and-starts-with-the-app.md)).
///
/// This is a plain service and knows nothing about a schedule. `DueCheckSchedule` is the only thing
/// that does, which is what lets the whole engine be driven date by date from a test instead of by
/// waiting an hour — and is why the startup fire is not a second, rarely-exercised code path.
///
/// ### One predicate, and it lives on the template
///
/// The decision is [TaskTemplate#firingDateOn]: active, nothing open, and a date at or after
/// `active_since` and strictly after the last closure. Nothing here re-states any part of it —
/// including *active*, which is deliberately **not** pushed down into the query. A `WHERE active`
/// would be the second half of a predicate whose first half is in Java, and this ticket exists
/// because ADR-0016 and ADR-0017 each had a version of this rule.
///
/// So the sweep reads every template and asks each one. It costs two queries per template, 24 times
/// a day, over the 43 live templates [#35](https://github.com/stainii/task/issues/35) counted.
///
/// ### An idle tick is silent
///
/// A tick that fires logs what it fired; a tick that finds nothing logs nothing. Portal's
/// unconditional *"Checking if recurring tasks are due…"* at 24×/day would fill
/// [ADR-0009](../../../../../../../../docs/adr/0009-the-app-is-its-own-monitor.md)'s 30-day
/// forensic window with records of nothing having happened.
@Service
@RequiredArgsConstructor
@Slf4j
public class DueTemplateChecker {

    /// A scheduled firing has no one to answer a `${…}`, so there is nothing to substitute. Keeping
    /// a placeholder out of a scheduled template is `TaskTemplate#validateForSaving`'s job, not
    /// something to paper over here.
    private static final Map<String, String> NO_VARIABLES = Map.of();

    private final TaskTemplateRepository taskTemplateRepository;
    private final TaskOccurrences taskOccurrences;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public void check() {
        var today = LocalDate.now(clock);
        for (TaskTemplate template : taskTemplateRepository.findAll()) {
            checkOne(template, today);
        }
    }

    /// One template's failure is one template's failure.
    ///
    /// Without this, a single unrenderable name or a corrupt stored trigger throws out of the sweep
    /// and every template after it in the iteration silently stops firing — for as long as the bad
    /// one exists, not for one hour. Spring keeps the *schedule* alive through a throwing tick
    /// (`LOG_AND_SUPPRESS_ERROR_HANDLER`, checked in ADR-0016); it cannot keep the *sweep* alive.
    private void checkOne(TaskTemplate template, LocalDate today) {
        try {
            template.firingDateOn(
                            today,
                            taskOccurrences.hasOpenOccurrence(template.id()),
                            taskOccurrences.lastClosureOf(template.id()).orElse(null))
                    .ifPresent(firingDate -> fire(template, firingDate));
        } catch (RuntimeException e) {
            log.error("Template {} ({}) could not be checked for {}.", template.id(), template.name(), today, e);
        }
    }

    /// The firing itself: rendered here, saved as tasks by `task`'s listener, both inside the
    /// listener's transaction (ADR-0002). There is no state where a template counts as fired and no
    /// task exists — a firing leaves no record of its own, so the tasks *are* the record.
    ///
    /// **The anchor is the firing date.** For a scheduled template the date the rule named is the
    /// date its tasks are measured from; only a manual run has an anchor of its own to type.
    private void fire(TaskTemplate template, LocalDate firingDate) {
        eventPublisher.publishEvent(template.render(NO_VARIABLES, firingDate, firingDate));

        // The firing date is logged even when it is today, because a date in the past is the
        // catch-up path and the only visible trace that the app was down across a date the
        // calendar named. Reading it means comparing it to the timestamp, which is cheaper than
        // having two messages to grep for.
        log.info("Template {} ({}) fired {} task(s) for {}.",
                template.id(), template.name(), template.taskDefinitions().size(), firingDate);
    }
}

package be.stijnhooft.task.backend.template.mother;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.template.CalendarRule;
import be.stijnhooft.task.backend.template.StoredTrigger;
import be.stijnhooft.task.backend.template.TaskDefinition;
import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.Trigger;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/// Templates for tests, built by hand rather than by Instancio.
///
/// Instancio cannot mint a [StoredTrigger] that satisfies its own discriminator — it would fill
/// every column of every shape at once — and `docs/quality-bar.md` §5 forbids test data dated
/// decades ahead, which is what it does to `activeSince`. Both problems disappear if the mother
/// simply says what it means.
public class TaskTemplateMother {

    public static final LocalDate ACTIVE_SINCE = LocalDate.of(2026, 3, 1);

    public static TaskTemplate manualTemplate() {
        return templateWith(new Trigger.Manual("When is the workshop?"));
    }

    public static TaskTemplate minMaxTemplate(int interval, int window) {
        return templateWith(Trigger.MinMax.ofIntervalAndWindow(interval, window));
    }

    public static TaskTemplate calendarTemplate() {
        return templateWith(new Trigger.Calendar(new CalendarRule.Weeks(1, Set.of(DayOfWeek.TUESDAY))));
    }

    public static TaskTemplate templateWith(Trigger trigger) {
        return TaskTemplate.of(
                UUID.randomUUID(),
                "Template " + UUID.randomUUID(),
                "house",
                ACTIVE_SINCE,
                trigger,
                List.of(definition("Beddengoed wassen", 0, 2)));
    }

    /// A template whose definitions fan out around one anchor, in the shape the real
    /// `Opvolgen workshop` template has: preparation before it, follow-ups after.
    public static TaskTemplate templateWithSeveralDefinitions() {
        return TaskTemplate.of(
                UUID.randomUUID(),
                "Opvolgen workshop ${school}",
                "work",
                ACTIVE_SINCE,
                new Trigger.Manual("When is the workshop?"),
                List.of(
                        definition("Voorbereidingsmail ${school}", -14, -7),
                        definition("Feedback nagevraagd bij ${school}?", 1, 7, Importance.NOT_SO_IMPORTANT)));
    }

    public static TaskDefinition definition(String name, Integer startOffset, Integer dueOffset) {
        return definition(name, startOffset, dueOffset, Importance.IMPORTANT);
    }

    public static TaskDefinition definition(String name, Integer startOffset, Integer dueOffset, Importance importance) {
        return TaskDefinition.of(UUID.randomUUID(), name, startOffset, dueOffset, importance, null);
    }
}

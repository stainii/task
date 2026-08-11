package be.stijnhooft.task.backend.task.eventlistener;

import be.stijnhooft.task.backend.task.TaskTemplateFired;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;

/// The inbound port's other half: `task` turning another module's fact into its own aggregate.
///
/// **This is where a `Task` gets built**, and that is the whole point of the payload change. The
/// event used to arrive carrying finished `Task` objects, which meant `template` constructed them —
/// so `Task` had to be visible outside `task`, and `ApplicationModules.verify()` had nothing to
/// verify. Now the event carries descriptions and the aggregate never leaves home.
@Service
@RequiredArgsConstructor
public class TaskEventListeners {

    private final TaskService taskService;
    private final Clock clock;

    /// Delivery is **synchronous and in-transaction** — a plain `@EventListener`, not
    /// `@ApplicationModuleListener` (ADR-0002). Firing and task creation are therefore atomic:
    /// there is no state where a template counts as fired and no task exists, which is the shape of
    /// the `activeTask` bug this model was built to make impossible.
    @EventListener
    public void onTaskTemplateFired(TaskTemplateFired event) {
        taskService.create(event.definitions().stream()
                .map(definition -> toTask(event, definition))
                .toList());
    }

    /// **The firing date becomes the creation date.** An occurrence is derived rather than stored,
    /// so a task's creation date is the only record of when its template came round — and
    /// `TaskOccurrences#lastClosureOf` reads it back as exactly that. A calendar template catching
    /// up on a date it slept through must produce a task dated for the date it was for, or the
    /// firing predicate compares today against today and fires again tomorrow.
    ///
    /// The day becomes an instant in the `Clock` bean's zone, which is the same conversion the
    /// importer makes for a portal execution (ADR-0005) — a migrated firing and a live one are the
    /// same rows.
    private Task toTask(TaskTemplateFired event, TaskTemplateFired.RenderedDefinition definition) {
        return Task.builderForInitialTask(clock)
                .name(definition.name())
                .description(definition.description())
                .importance(definition.importance())
                .context(event.context())
                .creationDateTime(event.firingDate().atStartOfDay(clock.getZone()).toInstant())
                .startDate(definition.startDate())
                .dueDate(definition.dueDate())
                .taskTemplateId(event.templateId())
                .occurrenceId(event.occurrenceId())
                .build();
    }
}

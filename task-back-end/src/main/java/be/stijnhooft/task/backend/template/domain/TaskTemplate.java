package be.stijnhooft.task.backend.template.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// **One template, one trigger.** The single generator of tasks in this application: a name, a
/// context, one or more [TaskDefinition]s, and exactly one [Trigger] saying when it comes round
/// ([ADR-0001](../../../../../../../../docs/adr/0001-one-task-aggregate-with-triggered-templates.md)).
///
/// It absorbed `RecurringTaskTemplate`, and with it three concepts died rather than moved:
///
/// - **`Execution` and `activeTask`.** An occurrence is *derived*, never stored. The firing date is
///   the task's creation date, the close date is the patch that closed it, and *"does this template
///   have an open occurrence?"* is a query over tasks. Safe only because tasks are never deleted.
/// - **`DeviationBase`.** One anchor, two offsets — see [TaskDefinition].
/// - **`variableNames`.** Variables are inferred from the `${…}` in the text
///   ([ADR-0013 §188](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
///   Portal's declared list had drifted: one template asked for a `${lector}` it never used, and
///   discarded the answer, for years.
///
/// ### `context` lives here, `importance` and `description` do not
///
/// A context never varies inside a template — 11 real definitions, 3 templates, zero variation —
/// and [ADR-0006](../../../../../../../../docs/adr/0006-one-overview-grouped-by-a-swappable-axis.md)
/// made it the overview's grouping axis, so it is something a whole template belongs to. Importance
/// genuinely does vary between definitions, so it sits on the definition. A template that would
/// span two contexts is two templates that happen to share a date.
///
/// ### Deactivated, not deleted
///
/// [#35](https://github.com/stainii/task/issues/35) measured what deleting cost portal: tasks
/// reference 115 distinct templates and 43 survive, so **49% of recurring tasks point at nothing**.
/// `taskTemplateId` is load-bearing now, so [#active] retires a template while its history stays
/// reachable. Deletion survives only while a template has no tasks at all — a count, not a
/// judgement. The endpoints for that are [#50](https://github.com/stainii/task/issues/50)'s.
@Table("task_template")
public record TaskTemplate(

        @Id UUID id,

        String name,

        /// Never varies inside a template, and the axis ADR-0006 groups the overview by.
        String context,

        /// A deactivated template stops firing and drops out of the list; its tasks keep pointing
        /// at something real.
        boolean active,

        /// **The date this template began firing under its current rule** — written on creation, on
        /// reactivation, and whenever the *trigger* changes
        /// ([ADR-0017 §64](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)).
        /// That one sentence is the whole definition; the three cases fall out of it.
        ///
        /// It does two jobs, deliberately in one field rather than two that could disagree: it is
        /// the **floor and phase** of a calendar enumeration, and it is the **seed** a brand-new
        /// min/max template fires from, which is where REC-003's explicit start date went.
        ///
        /// Editing a definition's name or description writes nothing here.
        LocalDate activeSince,

        /// The trigger, flattened into columns. Read it through [#trigger()]; this component is the
        /// table's shape, not the domain's.
        @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) StoredTrigger storedTrigger,

        @MappedCollection(idColumn = "task_template_id", keyColumn = "index") List<TaskDefinition> taskDefinitions,

        @Version @JsonIgnore long version) {

    public TaskTemplate {
        taskDefinitions = List.copyOf(taskDefinitions);
    }

    public static TaskTemplate of(UUID id, String name, String context, LocalDate activeSince,
                                  Trigger trigger, List<TaskDefinition> taskDefinitions) {
        return new TaskTemplate(id, name, context, true, activeSince, StoredTrigger.of(trigger), taskDefinitions, 0L);
    }

    /// *When does this come round?* — answered by the trigger itself, never by a scheduler.
    @JsonIgnore
    public Trigger trigger() {
        return storedTrigger.toTrigger();
    }

    /// **The firing predicate — one rule, not several**
    /// ([ADR-0017 §105](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)).
    ///
    /// The template fires for `D`, its trigger's latest occurrence on or before `today`, when all
    /// of these hold:
    ///
    /// 1. the template is **active**;
    /// 2. it has **no open task** — ADR-0001's suppression rule;
    /// 3. `D >= activeSince`, and `D` is strictly after `lastClosure`.
    ///
    /// ADR-0016's *"a calendar trigger fires for a date only once"* is **subsumed here, not
    /// implemented beside this**: a task already carrying today's date is either open, and rule 2
    /// stops it, or closed at `D`, and rule 3 stops it. Two rules over one condition is how two
    /// implementations come to disagree at a date boundary — and a date boundary is where
    /// `docs/quality-bar.md` says the bugs are.
    ///
    /// Rule 3 reads the most recent **closed** task rather than any task, which is what makes dates
    /// that passed *while a task was open* satisfied by closing it: suppression pauses the rhythm
    /// and the dates never move. Otherwise completing a three-week-old bin task instantly hands you
    /// another.
    ///
    /// A missed date therefore comes back as **exactly one** firing, anchored on the most recent
    /// date missed, however long the outage — `latestFiringDateOn` names one date and there is no
    /// walk behind it.
    ///
    /// Rule 3's floor is currently unreachable, and stays written down: every [Trigger] is handed
    /// `activeSince` and applies it — a calendar rule enumerates from it, and `MinMax` starts its
    /// round at it — so no shape can return a date below it today. It is kept because the floor is
    /// the predicate's guarantee rather than a trigger's courtesy, and a fourth shape that forgot
    /// to apply it would otherwise fire below `activeSince` by omission.
    ///
    /// @param today        the application's notion of today, from the `Clock` bean
    /// @param hasOpenTask  whether any task of this template is still open
    ///                     (`TaskOccurrences#hasOpenOccurrence`)
    /// @param lastClosure  the firing date of the most recently closed task
    ///                     (`TaskOccurrences#lastClosureOf`), or null when nothing has closed
    /// @return the date to fire for, or empty when this template must not fire
    public Optional<LocalDate> firingDateOn(LocalDate today, boolean hasOpenTask, @Nullable LocalDate lastClosure) {
        if (!active || hasOpenTask) {
            return Optional.empty();
        }
        return trigger().latestFiringDateOn(today, activeSince, lastClosure)
                .filter(firingDate -> !firingDate.isBefore(activeSince))
                .filter(firingDate -> lastClosure == null || firingDate.isAfter(lastClosure));
    }

    /// A trigger change is one of the three things that rewrites [#activeSince], so the two move
    /// together and cannot be changed apart. A bin template re-ruled from Tuesdays to Thursdays
    /// would otherwise find no task on any Thursday and immediately fire a backdated one.
    public TaskTemplate withTrigger(Trigger trigger, LocalDate today) {
        return new TaskTemplate(id, name, context, active, today, StoredTrigger.of(trigger), taskDefinitions, version);
    }
}

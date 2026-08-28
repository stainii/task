package be.stijnhooft.task.backend.template.domain;

import be.stijnhooft.task.backend.task.LastClosure;
import be.stijnhooft.task.backend.task.TaskTemplateFired;
import be.stijnhooft.task.backend.template.exception.TaskTemplateInvalidException;
import be.stijnhooft.task.backend.template.util.VariableUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static be.stijnhooft.task.backend.template.util.VariableUtils.fillInVariables;

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
    /// 3. `D >= activeSince`, and `D` is strictly after the last closure's **firing date**.
    ///
    /// ADR-0016's *"a calendar trigger fires for a date only once"* is **subsumed here, not
    /// implemented beside this**: a task already carrying today's date is either open, and rule 2
    /// stops it, or closed at `D`, and rule 3 stops it. Two rules over one condition is how two
    /// implementations come to disagree at a date boundary — and a date boundary is where
    /// `docs/quality-bar.md` says the bugs are.
    ///
    /// Rule 3 reads the most recent **closed** task rather than any task, which is what makes a
    /// calendar date that passed *while a task was open* come back exactly once when it is closed:
    /// suppression pauses the rhythm and the dates never move.
    ///
    /// **It is the firing date that rule 3 compares against, and only `Calendar` is protected by
    /// it** (ADR-0022). For `MinMax` the filter is close to vacuous — `closedOn + min` is after the
    /// firing date whenever you closed on time — so the *"completing a three-week-old bin task
    /// instantly hands you another"* hazard this rule was written for was never covered here for
    /// min/max at all. What covers it is `MinMax` counting from the closure date;
    /// [#75](https://github.com/stainii/task/issues/75) is what it cost to believe otherwise. The
    /// filter still earns its place for min/max: a `completedOn` backdated past the task's own
    /// firing date lands `closedOn + min` on or before it, and rule 3 blocks that firing.
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
    /// @param lastClosure  the most recently closed task's two dates
    ///                     (`TaskOccurrences#lastClosureOf`), or null when nothing has closed
    /// @return the date to fire for, or empty when this template must not fire
    public Optional<LocalDate> firingDateOn(LocalDate today, boolean hasOpenTask, @Nullable LastClosure lastClosure) {
        if (!active || hasOpenTask) {
            return Optional.empty();
        }
        return trigger().latestFiringDateOn(today, activeSince, lastClosure)
                .filter(firingDate -> !firingDate.isBefore(activeSince))
                .filter(firingDate -> lastClosure == null || firingDate.isAfter(lastClosure.firedOn()));
    }

    /// A trigger change is one of the three things that rewrites [#activeSince], so the two move
    /// together and cannot be changed apart. A bin template re-ruled from Tuesdays to Thursdays
    /// would otherwise find no task on any Thursday and immediately fire a backdated one.
    public TaskTemplate withTrigger(Trigger trigger, LocalDate today) {
        return new TaskTemplate(id, name, context, active, today, StoredTrigger.of(trigger), taskDefinitions, version);
    }

    /// **Switched off**: it stops firing, and drops out of the authoring list. Its tasks stay where
    /// they are, still pointing at something real — which is the whole reason this exists instead of
    /// a delete ([#35](https://github.com/stainii/task/issues/35) measured 49% of portal's recurring
    /// tasks pointing at a template that had been deleted out from under them).
    ///
    /// It moves [#activeSince] too, though nothing reads it while the template is off. That is not
    /// belt-and-braces: it collapses the field's rule from a list of cases to one sentence —
    /// **anything that changes whether or how this template fires moves `activeSince`** — and a rule
    /// with one clause is a rule two implementations cannot half-agree on. ADR-0017's three cases
    /// fall out of it unchanged.
    public TaskTemplate deactivated(LocalDate today) {
        return new TaskTemplate(id, name, context, false, today, storedTrigger, taskDefinitions, version);
    }

    /// **Switched back on, from today.** The date matters: a calendar template reactivated after
    /// three months away would otherwise catch up on a date it slept through on purpose
    /// ([ADR-0013's amendment](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)),
    /// and a min/max template would fire the instant it came back, its round having started before
    /// the pause.
    public TaskTemplate reactivated(LocalDate today) {
        return new TaskTemplate(id, name, context, true, today, storedTrigger, taskDefinitions, version);
    }

    /// Every variable this template asks for, inferred from the `${…}` in its context and in every
    /// definition's name and description. There is no declared list (ADR-0013).
    @JsonIgnore
    public Set<String> variables() {
        var texts = Stream.concat(
                        Stream.of(context),
                        taskDefinitions.stream()
                                .flatMap(definition -> Stream.of(definition.name(), definition.description())))
                .toArray(String[]::new);
        return VariableUtils.variablesIn(texts);
    }

    /// **Save-time validation, and deliberately not an unrepresentable state**
    /// ([ADR-0013 §188](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
    ///
    /// The weaker guarantee is chosen on purpose, and the reason is where the check *cannot* go: a
    /// compact constructor runs on every read as well as every write, so one bad row would throw out
    /// of `findAll()` and take the whole due check's sweep with it — the failure `DueTemplateChecker`
    /// already catches per template, moved somewhere nothing can catch it. Validation belongs on the
    /// way in.
    ///
    /// Two rules:
    ///
    /// - **A template must be able to render something.** A template with no definitions fires an
    ///   event with no tasks, which `TaskTemplateFired` refuses — so today it throws once an hour,
    ///   for ever, and the only trace is an ERROR line.
    ///   [#49](https://github.com/stainii/task/issues/49) left five such rows in the shared test
    ///   database by `PUT`ting exactly this.
    /// - **`${…}` is manual-only.** Nobody is present to answer a placeholder when a template fires
    ///   at 04:00, so a scheduled template containing one renders a task literally named `${school}`.
    ///
    /// @throws TaskTemplateInvalidException when this template could not usefully fire
    public void validateForSaving() {
        if (taskDefinitions.isEmpty()) {
            throw new TaskTemplateInvalidException(
                    "Template " + id + " has no task definitions, so it could never produce a task.");
        }

        var variables = variables();
        if (!variables.isEmpty() && !(trigger() instanceof Trigger.Manual)) {
            throw new TaskTemplateInvalidException(
                    "Template " + id + " is scheduled, so nothing can answer its variables "
                            + variables + ". Variables are manual-only.");
        }
    }

    /// **Renders a firing into the fact that it happened**: `${…}` substituted, offsets resolved to
    /// real dates, nothing left for the listener to look up.
    ///
    /// It lives on the aggregate, not in a service, because everything it reads is the template's
    /// own — the placeholders, the offsets, and the trigger that supplies the fallback due date.
    /// That also makes it callable without a Spring context, which is what lets `/render-fixtures/`
    /// pin it the way `/fold-fixtures/` pins the fold ([ADR-0011](../../../../../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)):
    /// **no rendering rule without a fixture**, because this rule exists twice — here and in the
    /// front-end's preview.
    ///
    /// What crosses the module boundary is the result, never a `Task`: building one of those is
    /// `task`'s own business (ADR-0002).
    ///
    /// The template's **context** is rendered before any definition is, so a template whose text
    /// resolves to nothing fails loudly and produces **no tasks at all** rather than some of them
    /// (TODO-022, over portal's silent `"No name"` fallback).
    ///
    /// @param variables   the answers to [#variables]. A scheduled firing has none, which is what
    ///                    [#validateForSaving] guarantees is enough.
    /// @param firingDate  the date the template came round — today for a manual run, the rule's date
    ///                    for a scheduled one, which after an outage is in the past
    /// @param anchor      the date this firing's tasks are measured from, or null when a manual
    ///                    template was run without one
    public TaskTemplateFired render(Map<String, String> variables, LocalDate firingDate, @Nullable LocalDate anchor) {
        var renderedContext = fillInVariables(context, variables)
                .filter(rendered -> !rendered.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + id + " renders to an empty context."));

        // The trigger's own arithmetic, asked here rather than passed in: a caller that computed it
        // itself is a caller that can compute it differently, and the manual-run path used to pass a
        // hard null that only agreed with `Manual` by luck.
        var defaultDueDate = trigger().defaultDueDateFor(firingDate).orElse(null);

        var rendered = taskDefinitions.stream()
                .map(definition -> render(definition, variables, firingDate, anchor, defaultDueDate))
                .toList();

        return new TaskTemplateFired(id, UUID.randomUUID(), firingDate, renderedContext, rendered);
    }

    private TaskTemplateFired.RenderedDefinition render(TaskDefinition definition, Map<String, String> variables,
                                                        LocalDate firingDate, @Nullable LocalDate anchor,
                                                        @Nullable LocalDate defaultDueDate) {
        var renderedName = fillInVariables(definition.name(), variables)
                .filter(rendered -> !rendered.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition " + definition.id() + " of template " + id + " renders to an empty name."));

        return new TaskTemplateFired.RenderedDefinition(
                renderedName,
                fillInVariables(definition.description(), variables).orElse(null),
                definition.importance(),
                // No start offset means the task starts the day the template came round. It used to
                // mean "today", which is the same date for a manual run and the wrong one for a
                // calendar template catching up on a date it slept through.
                definition.startDateFrom(anchor).orElse(firingDate),
                definition.dueDateFrom(anchor).orElse(defaultDueDate));
    }
}

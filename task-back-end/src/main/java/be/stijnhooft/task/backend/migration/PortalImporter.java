package be.stijnhooft.task.backend.migration;

import be.stijnhooft.task.backend.migration.diff.StoredVersusFolded;
import be.stijnhooft.task.backend.migration.map.Contexts;
import be.stijnhooft.task.backend.migration.map.FlowId;
import be.stijnhooft.task.backend.migration.map.PatchTranslator;
import be.stijnhooft.task.backend.migration.map.PortalDates;
import be.stijnhooft.task.backend.migration.map.PortalIds;
import be.stijnhooft.task.backend.migration.portal.PortalArchive;
import be.stijnhooft.task.backend.migration.portal.RecurringTasksReader;
import be.stijnhooft.task.backend.migration.portal.TodoReader;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskImport;
import be.stijnhooft.task.backend.template.TaskTemplateImport;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/// **Portal's data is replayed into one history, not copied into one schema**
/// ([ADR-0005](../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// This is the reading-and-mapping half ([#52](https://github.com/stainii/task/issues/52)); the
/// diff report and the rehearsal are [#53](https://github.com/stainii/task/issues/53)'s.
///
/// The order is forced by the data, not chosen: templates first, because a task's provenance is a
/// reference to one; tasks second; executions last, because an execution is only synthesised when
/// no migrated task already accounts for it.
@Slf4j
public class PortalImporter {

    /// ADR-0005: an execution matching a migrated task — same template, completion within ±1 day —
    /// contributes nothing. In portal a single occurrence wrote *both* a todo task and an execution
    /// row, so synthesising unconditionally would put a phantom beside every real task.
    private static final int EXECUTION_MATCH_TOLERANCE_DAYS = 1;

    private final TaskImport taskImport;
    private final TaskTemplateImport taskTemplateImport;
    private final Clock clock;
    private final ZoneId zone;
    private final StoredVersusFolded storedVersusFolded;

    public PortalImporter(TaskImport taskImport, TaskTemplateImport taskTemplateImport, Clock clock) {
        this.taskImport = taskImport;
        this.taskTemplateImport = taskTemplateImport;
        this.clock = clock;
        this.zone = clock.getZone();
        this.storedVersusFolded = new StoredVersusFolded(zone);
    }

    public ImportReport importFrom(TodoReader todo, List<RecurringTasksReader> deployments) {
        var report = new ImportReport();
        var today = LocalDate.now(clock);

        var deploymentNames = todo.deploymentNames();
        var flowIds = new FlowId(deploymentNames);

        var tasks = todo.tasks();
        var patches = todo.patches();
        report.count("portal tasks read", tasks.size());
        report.count("portal patches read", patches.size());

        reconcilePrefixes(deploymentNames, deployments, tasks, flowIds, report);

        taskImport.deleteAllTasks();
        taskTemplateImport.deleteAllTemplates();

        var importanceByDeployment = todo.importanceByDeployment();
        var templates = importTemplates(todo, deployments, importanceByDeployment, report, today);
        var completions = importTasks(tasks, patches, flowIds, templates, report);
        synthesiseExecutions(deployments, templates, completions, importanceByDeployment, report);

        report.count("tasks written", taskImport.taskCount());
        report.count("patches written", taskImport.patchCount());
        report.count("templates written", taskTemplateImport.templateCount());
        report.count("definitions written", taskTemplateImport.definitionCount());
        return report;
    }

    /// **Step 2 of ADR-0005: every prefix has a database and every database has a prefix.** Either
    /// side failing is an abort signal, not a warning — a deployment whose prefix nobody matches
    /// imports its whole history as orphaned tasks, silently, and the `flowId` that could have
    /// linked them is gone the moment the import finishes.
    ///
    /// `Todo` is excluded before the match rather than treated as a failure. It is not a deployment
    /// but portal-todo's own per-task flow id, and ADR-0005 as first written would have **aborted on
    /// a prefix that is entirely correct**.
    private void reconcilePrefixes(List<String> deploymentNames,
                                   List<RecurringTasksReader> deployments,
                                   List<PortalArchive.PortalTask> tasks,
                                   FlowId flowIds,
                                   ImportReport report) {
        var databases = deployments.stream().map(RecurringTasksReader::deployment).collect(Collectors.toSet());

        // The cross-check ADR-0005 asked for: the set derived from the data, against the set read
        // from `subscription.origin`. They agree in the frozen archive; if a later dump disagrees,
        // that is worth knowing before anything is written.
        var derived = new TreeSet<String>();
        var unparseable = new ArrayList<String>();
        for (var task : tasks) {
            var provenance = task.provenance();
            if (provenance == null || FlowId.isTodo(provenance)) {
                continue;
            }
            var parsed = flowIds.parse(provenance);
            if (parsed.isPresent()) {
                derived.add(parsed.get().deployment());
            } else if (task.flowId() != null) {
                // A task whose own id is not a flow id is simply a hand-made task, so only a real
                // `flowId` that matches nothing is a finding.
                unparseable.add(provenance);
            }
        }

        var named = Set.copyOf(deploymentNames);
        var missingDatabase = named.stream().filter(name -> !databases.contains(name)).sorted().toList();
        var missingPrefix = databases.stream().filter(name -> !named.contains(name)).sorted().toList();
        var undeclared = derived.stream().filter(name -> !named.contains(name)).toList();

        if (!missingDatabase.isEmpty() || !missingPrefix.isEmpty() || !undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "The deployment prefixes and the recurring-tasks databases do not reconcile, so a "
                            + "deployment's history would import unlinked and unrecoverably. "
                            + "Prefixes with no database: " + missingDatabase
                            + "; databases with no prefix: " + missingPrefix
                            + "; prefixes in the data that no subscription declares: " + undeclared + ".");
        }

        report.count("deployments reconciled", named.size());
        report.count("flowIds matching no known prefix", unparseable.size());
        unparseable.stream().limit(50).forEach(flowId -> report.note("flowId matching no prefix", flowId));
    }

    /// Portal's 44 `recurring_task` rows become min/max templates and its 3 `taskTemplate` documents
    /// become manual ones. Nothing becomes a calendar template: portal had no calendar recurrence,
    /// so converting one is something the author does afterwards in the UI.
    private Map<String, UUID> importTemplates(TodoReader todo,
                                              List<RecurringTasksReader> deployments,
                                              Map<String, Importance> importanceByDeployment,
                                              ImportReport report,
                                              LocalDate today) {
        var byProvenance = new HashMap<String, UUID>();

        for (var deployment : deployments) {
            var firstExecution = deployment.executions().stream()
                    .collect(Collectors.groupingBy(PortalArchive.PortalExecution::recurringTaskId,
                            Collectors.minBy(Comparator.comparing(PortalArchive.PortalExecution::date))));

            for (var recurring : deployment.recurringTasks()) {
                var id = PortalIds.ofRecurringTask(recurring.deployment(), String.valueOf(recurring.id()));
                var context = Contexts.normalise(recurring.deployment());
                var importance = importanceByDeployment.getOrDefault(recurring.deployment(), Importance.IMPORTANT);

                // `active_since` is the template's first recorded execution, not the import date.
                // ADR-0017 makes it the floor and phase of the rule, and `MinMax` starts its round at
                // the *later* of it and the last closure - so a date in the past preserves the rhythm
                // (next asked for `min` days after the last time it was done), while the import date
                // would silence every migrated chore for a full interval from cutover.
                var activeSince = firstExecution.getOrDefault(recurring.id(), java.util.Optional.empty())
                        .map(PortalArchive.PortalExecution::date)
                        .orElse(today);

                taskTemplateImport.importTemplate(new TaskTemplateImport.ImportedTemplate(
                        id,
                        recurring.name(),
                        context,
                        activeSince,
                        new TaskTemplateImport.ImportedTrigger.MinMax(recurring.min(), recurring.max()),
                        List.of(new TaskTemplateImport.ImportedDefinition(
                                PortalIds.ofDefinition(id, 0),
                                recurring.name(),
                                null,
                                importance,
                                null,
                                null))));

                byProvenance.put(recurring.deployment() + "-" + recurring.id(), id);
                report.increment("min/max templates written");
            }
        }

        for (var template : todo.taskTemplates()) {
            var id = PortalIds.ofTaskTemplate(template.id());
            var definitions = new ArrayList<TaskTemplateImport.ImportedDefinition>();
            for (var index = 0; index < template.definitions().size(); index++) {
                var definition = template.definitions().get(index);
                definitions.add(new TaskTemplateImport.ImportedDefinition(
                        PortalIds.ofDefinition(id, index),
                        definition.name(),
                        definition.description(),
                        parseImportance(definition.importance()),
                        definition.startDateDeviationDays(),
                        definition.dueDateDeviationDays()));
            }

            taskTemplateImport.importTemplate(new TaskTemplateImport.ImportedTemplate(
                    id,
                    template.name(),
                    // A context never varies inside a template - #47 measured zero variation across
                    // all 11 real definitions - so the first definition's is the template's.
                    Contexts.normalise(contextOf(template)),
                    // A manual template never fires on its own, so `active_since` has no rule to
                    // phase. Today is the honest answer: this is when it began being available here.
                    today,
                    new TaskTemplateImport.ImportedTrigger.Manual(null),
                    List.copyOf(definitions)));

            report.increment("manual templates written");
        }

        return byProvenance;
    }

    private static String contextOf(PortalArchive.PortalTaskTemplate template) {
        return template.definitions().stream()
                .map(PortalArchive.PortalTaskDefinition::context)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("Unknown");
    }

    /// Groups patches by their own `taskId`, translates them, and folds each task into existence.
    ///
    /// Grouping by `taskId` rather than by portal's `Task.history` array is deliberate and recovers
    /// **32 patches** the array had lost. Portal's array is the artefact already proven
    /// untrustworthy — it is the same document ADR-0005 discards — while a patch naming a real task
    /// is real history. The two agree everywhere else in the archive.
    ///
    /// @return every migrated task's completion, keyed by template, for the execution match
    private Map<UUID, List<LocalDate>> importTasks(List<PortalArchive.PortalTask> tasks,
                                                   List<PortalArchive.PortalPatch> patches,
                                                   FlowId flowIds,
                                                   Map<String, UUID> templates,
                                                   ImportReport report) {
        var translator = new PatchTranslator(zone);
        var tasksById = tasks.stream().collect(Collectors.toMap(PortalArchive.PortalTask::id, task -> task));
        var patchesByTask = new LinkedHashMap<String, List<PortalArchive.PortalPatch>>();

        for (var patch : patches) {
            if (!tasksById.containsKey(patch.taskId())) {
                // 17 patches naming two task ids that exist nowhere. They are the live API's orphan
                // case - a patch with no task to belong to - and there is nothing to hold them.
                report.increment("patches naming no task (dropped)");
                report.note("patch naming no task", patch.id() + " → task " + patch.taskId());
                continue;
            }
            patchesByTask.computeIfAbsent(patch.taskId(), key -> new ArrayList<>()).add(patch);
        }

        var completions = new HashMap<UUID, List<LocalDate>>();

        for (var task : tasks) {
            var portalPatches = patchesByTask.getOrDefault(task.id(), List.of());
            if (portalPatches.isEmpty()) {
                // Never occurs in the archive: every one of the 11,855 tasks has patches, and every
                // one of those has a creation patch.
                throw new IllegalStateException("Task " + task.id() + " has no patches to fold.");
            }

            var provenance = flowIds.parse(task.provenance());
            var templateId = provenance
                    .map(parsed -> templates.get(parsed.deployment() + "-" + parsed.recurringTaskId()))
                    .orElse(null);
            var context = provenance.map(parsed -> Contexts.normalise(parsed.deployment())).orElse(null);

            if (provenance.isPresent()) {
                report.increment("tasks generated by a deployment");
                if (templateId == null) {
                    // 49% of the recurring corpus. Deleting a template took its `execution` rows with
                    // it and left its tasks standing, and no dump taken at any date could recover it.
                    report.increment("tasks whose template no longer exists (null link)");
                } else {
                    report.increment("tasks linked to their template");
                }
            } else if (FlowId.isTodo(task.provenance())) {
                // Portal-todo's own per-task flow id. All 746 are distinct, so it names no template
                // and links to nothing.
                report.increment("tasks with a Todo flow id (no template)");
            } else {
                report.increment("hand-made tasks");
            }

            // The date a cleared start date falls back to. Taken from the creation patch rather
            // than the stored document, which is the thing ADR-0005 discards - so the fallback is
            // read from the history like everything else the task is made of.
            var creationDate = creationDateOf(task, portalPatches);

            var translated = new ArrayList<TaskImport.ImportedPatch>(portalPatches.size());
            for (var patch : portalPatches) {
                if (patch.changes().containsKey("startDateTime") && patch.changes().get("startDateTime") == null) {
                    report.increment("cleared start dates defaulted to the creation date");
                    report.note("cleared start date", "task " + task.id() + " in patch " + patch.id());
                }
                translated.add(translator.translate(
                        PortalIds.ofPatch(patch.id()),
                        patch.dateTime(),
                        patch.changes(),
                        context,
                        templateId,
                        creationDate));
            }

            withDefaultImportance(translated, report);

            var taskId = PortalIds.ofTask(task.id());
            var folded = taskImport.importTask(taskId, translated);
            report.increment("tasks imported");

            // #53: the diff happens here because this is the only moment both versions of the task
            // exist - portal's document, and what its own history folds to. Reading either back
            // afterwards would answer a question about the database instead.
            var comparison = storedVersusFolded.compare(task, folded, portalPatches, context);
            comparison.differences().forEach(report::difference);
            report.count("date-times that lost a time of day", comparison.dateTimesThatLostATimeOfDay());

            if (templateId != null) {
                completions.computeIfAbsent(templateId, key -> new ArrayList<>())
                        .addAll(completionDates(translated));
            }
        }

        return completions;
    }

    /// ADR-0018 makes `importance` non-nullable and ADR-0005 maps a missing one to
    /// `NOT_SO_IMPORTANT`. Seventeen tasks in the archive never set it in any patch, and the fold's
    /// own default is `IMPORTANT` — so the value has to be written rather than left to fall through,
    /// or those seventeen silently become more important than portal ever showed them.
    private static void withDefaultImportance(List<TaskImport.ImportedPatch> patches, ImportReport report) {
        var setsImportance = patches.stream().anyMatch(patch -> patch.changes().containsKey("importance"));
        if (setsImportance) {
            return;
        }
        for (var index = 0; index < patches.size(); index++) {
            var patch = patches.get(index);
            if (patch.changes().containsKey("creationDateTime")) {
                var changes = new LinkedHashMap<>(patch.changes());
                changes.put("importance", Importance.NOT_SO_IMPORTANT.name());
                patches.set(index, new TaskImport.ImportedPatch(patch.id(), patch.dateTime(), changes));
                report.increment("tasks defaulted to NOT_SO_IMPORTANT");
                return;
            }
        }
    }

    /// The task's creation date, read from the patch that carries `creationDateTime` — the same
    /// discriminator the live API uses. Falls back to the earliest patch's own timestamp, which
    /// cannot happen in the archive: every one of the 11,855 tasks has a creation patch.
    private LocalDate creationDateOf(PortalArchive.PortalTask task, List<PortalArchive.PortalPatch> patches) {
        return patches.stream()
                .filter(patch -> patch.changes().get("creationDateTime") != null)
                .map(patch -> PortalDates.toLocalDate(
                        java.util.Objects.requireNonNull(patch.changes().get("creationDateTime")), zone))
                .findFirst()
                .orElseGet(() -> patches.stream()
                        .map(PortalArchive.PortalPatch::dateTime)
                        .min(java.time.Instant::compareTo)
                        .map(instant -> PortalDates.toLocalDate(instant, zone))
                        .orElseThrow(() -> new IllegalStateException(
                                "Task " + task.id() + " has no patch to date it from.")));
    }

    private static List<LocalDate> completionDates(List<TaskImport.ImportedPatch> patches) {
        return patches.stream()
                .map(patch -> patch.changes().get("completedOn"))
                .filter(java.util.Objects::nonNull)
                .map(LocalDate::parse)
                .toList();
    }

    /// **An execution is a task that fired and closed at once.**
    ///
    /// Only *unmatched* executions are synthesised: in portal one occurrence wrote both a todo task
    /// and an execution row, so a phantom would land beside every real task. An execution matching
    /// more than one migrated task is **reported, never resolved** — ADR-0005 is explicit that the
    /// importer does not guess.
    ///
    /// The fire date is set equal to the completion date. Portal only ever recorded when something
    /// was done, and an obviously degenerate lead time beats a plausible invented one.
    private void synthesiseExecutions(List<RecurringTasksReader> deployments,
                                      Map<String, UUID> templates,
                                      Map<UUID, List<LocalDate>> completions,
                                      Map<String, Importance> importanceByDeployment,
                                      ImportReport report) {
        var translator = new PatchTranslator(zone);

        for (var deployment : deployments) {
            var recurringById = deployment.recurringTasks().stream()
                    .collect(Collectors.toMap(PortalArchive.PortalRecurringTask::id, task -> task));

            for (var execution : deployment.executions()) {
                report.increment("executions read");
                var templateId = templates.get(execution.deployment() + "-" + execution.recurringTaskId());
                if (templateId == null) {
                    // Cannot happen: `execution` has a foreign key to `recurring_task`, which is
                    // exactly why deleting a template took its executions and left its tasks.
                    report.increment("executions whose template is missing (dropped)");
                    continue;
                }

                var matches = completions.getOrDefault(templateId, List.of()).stream()
                        .filter(completedOn -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                                completedOn, execution.date())) <= EXECUTION_MATCH_TOLERANCE_DAYS)
                        .count();

                if (matches == 1) {
                    report.increment("executions matched by a migrated task");
                    continue;
                }
                if (matches > 1) {
                    // Reported, never resolved. It is still not synthesised: something already
                    // accounts for this day, and inventing another task would be resolving the
                    // ambiguity by guess - in the direction that adds data.
                    report.increment("executions matching several tasks (reported, not synthesised)");
                    report.note("ambiguous execution", "%s execution %d on %s matches %d tasks"
                            .formatted(execution.deployment(), execution.id(), execution.date(), matches));
                    continue;
                }

                var recurring = recurringById.get(execution.recurringTaskId());
                if (recurring == null) {
                    report.increment("executions whose recurring task is missing (dropped)");
                    continue;
                }

                synthesise(execution, recurring, templateId, translator,
                        importanceByDeployment.getOrDefault(execution.deployment(), Importance.IMPORTANT), report);
            }
        }
    }

    private void synthesise(PortalArchive.PortalExecution execution,
                            PortalArchive.PortalRecurringTask recurring,
                            UUID templateId,
                            PatchTranslator translator,
                            Importance importance,
                            ImportReport report) {
        var taskId = PortalIds.ofSynthesisedTask(execution.deployment(), execution.id());
        var context = Contexts.normalise(execution.deployment());
        var firedAt = execution.date().atStartOfDay(zone).toInstant();

        // A second apart, and that second is load-bearing. The fold breaks a tie on the patch id as
        // a string, so two patches sharing an instant would order by a minted UUID - and a
        // completion sorting before its own creation folds to a task that is OPEN, because the
        // creation patch's `status` would be the last one applied. The fire date is still equal to
        // the completion date, which is what ADR-0005 asks for: both are the same local day.
        var completedAt = firedAt.plusSeconds(1);

        // Created and completed in the same breath, which is the only thing REC-005 can mean once
        // `Execution` is deleted - and the identical shape a live out-of-band completion produces
        // (ADR-0011). Two patches, because that is what the live path writes.
        var creationChanges = new LinkedHashMap<String, String>();
        creationChanges.put("name", recurring.name());
        creationChanges.put("context", context);
        creationChanges.put("creationDateTime", firedAt.toString());
        creationChanges.put("startDateTime", execution.date().atStartOfDay().toString());
        creationChanges.put("importance", importance.name());
        creationChanges.put("status", "OPEN");

        var creation = translator.translate(
                PortalIds.ofSynthesisedPatch(execution.deployment(), execution.id(), "create"),
                firedAt,
                creationChanges,
                context,
                templateId,
                execution.date());

        var completionChanges = new LinkedHashMap<String, String>();
        completionChanges.put("status", "COMPLETED");

        var completion = translator.translate(
                PortalIds.ofSynthesisedPatch(execution.deployment(), execution.id(), "complete"),
                completedAt,
                completionChanges,
                context,
                null,
                execution.date());

        // Nothing to diff: a synthesised task has no portal document to disagree with. It *is* the
        // interpretation of an execution row, so comparing it with itself would prove nothing.
        taskImport.importTask(taskId, List.of(creation, completion));
        report.increment("executions synthesised into tasks");
    }

    private static Importance parseImportance(@Nullable String value) {
        if (value == null) {
            return Importance.IMPORTANT;
        }
        try {
            return Importance.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return Importance.IMPORTANT;
        }
    }
}

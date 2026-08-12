package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.task.Importance;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/// What `template` will accept from the one-shot portal importer
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)) — the
/// mirror of [be.stijnhooft.task.backend.task.TaskImport], and a port for the same reason:
/// `TaskTemplate`, `TaskDefinition` and the sealed `Trigger` all live in `template.domain`, which
/// [ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)
/// makes internal.
///
/// [ImportedTrigger] is deliberately **two** shapes rather than three. Portal had no calendar
/// recurrence at all — it is new functionality [#13](https://github.com/stainii/task/issues/13)
/// added — so nothing in the corpus can produce one, and a `Calendar` case here would be a branch
/// no data reaches. Converting a migrated min/max template to calendar is a thing the *author* does
/// afterwards in the UI, where [ADR-0017](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)
/// resets `activeSince` for them.
public interface TaskTemplateImport {

    /// Truncates templates and their definitions. ADR-0005: truncate and rebuild, never append.
    void deleteAllTemplates();

    void importTemplate(ImportedTemplate template);

    long templateCount();

    long definitionCount();

    record ImportedTemplate(
            UUID id,
            String name,
            String context,
            LocalDate activeSince,
            ImportedTrigger trigger,
            List<ImportedDefinition> definitions) {
    }

    /// `expectedDurationInHours` and both `*DeviationBase` selectors are already gone by the time a
    /// definition reaches here — dropped by [#12](https://github.com/stainii/task/issues/12) and by
    /// [ADR-0013](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)'s
    /// one anchor respectively. A null offset means the produced task simply has no such date.
    record ImportedDefinition(
            UUID id,
            String name,
            @Nullable String description,
            Importance importance,
            @Nullable Integer startDateOffsetDays,
            @Nullable Integer dueDateOffsetDays) {
    }

    sealed interface ImportedTrigger {

        /// Portal's `taskTemplate` documents: run by hand, with `${…}` placeholders someone answers.
        record Manual(@Nullable String anchorLabel) implements ImportedTrigger {
        }

        /// Portal's `recurring_task` rows: `min`/`max` days between executions, carried over as they
        /// stand.
        record MinMax(int min, int max) implements ImportedTrigger {
        }
    }
}

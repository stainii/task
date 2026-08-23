package be.stijnhooft.task.backend.task;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// What `task` will accept from the one-shot portal importer, and nothing else
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// A purpose-built inbound port in the module's exposed API, for the same reason
/// [TaskOccurrences] is one: `Task` and `TaskPatch` live in `task.domain`, which
/// [ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)
/// makes internal, and ADR-0003 refuses `@NamedInterface`. So the importer hands over the only
/// thing it actually has — a task id and the patches that happened to it — and `task` builds its
/// own aggregate.
///
/// **This is what "writes through the app's own fold and model, not raw SQL" means in code.** The
/// implementation folds through `Task.foldOf` and mints sequences from the real sequence, so every
/// migrated task is one the running application could have produced itself. An importer with its
/// own private write path would leave the claim that a task *is* its folded history untested
/// exactly where it matters most.
public interface TaskImport {

    /// Truncates tasks and their patches **and advances the sync epoch with them**, in one
    /// transaction.
    ///
    /// The truncate is what ADR-0005 asks for: the importer is re-runnable and idempotent —
    /// **truncate and rebuild, never append** — so a dry run is free. The epoch is what
    /// [#72](https://github.com/stainii/task/issues/72) added, and it is not a second concern
    /// bolted on: restarting `task_patch_sequence` at 1 *is* starting a new lineage of history, the
    /// same condition `restore.sh` bumps the epoch for (ADR-0008, step four). A device that synced
    /// before the import holds a cursor ahead of the server, and without the bump it concludes it
    /// is up to date permanently while the server reissues its numbers to different patches.
    ///
    /// **The bump is the first act rather than the last, and in the truncate's own transaction.**
    /// The load that follows is thousands of transactions and can fail halfway; bumping afterwards
    /// would leave every partial import — and every crash — sitting in a new lineage under the old
    /// epoch, which is the silent failure itself. Bumping first can only ever cost a resync nobody
    /// needed.
    ///
    /// The name says *lineage* rather than *delete* on purpose. This is the step
    /// [#72](https://github.com/stainii/task/issues/72) found missing precisely because it was
    /// invisible, and a caller reading `deleteAllTasks()` has no reason to think about cursors.
    ///
    /// @return the epoch the server is now on, so the import report can print it
    long startNewLineage();

    /// Folds one portal task into existence from its translated patches.
    ///
    /// The patches are handed over unordered; the fold sorts them itself, so the importer cannot
    /// impose an order the running application would not have used. Sequences are assigned in fold
    /// order, which is ADR-0005's *migrated patches occupy sequences 1..N in `dateTime` order*.
    ///
    /// Returns what the task folded to, because [#53](https://github.com/stainii/task/issues/53)
    /// diffs it against the document portal stored and this is the only moment both exist. Reading
    /// it back afterwards would be the wrong shape twice over: the migration module cannot see
    /// `task.domain` ([ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)),
    /// and a second query would prove the *database* right rather than the fold.
    ///
    /// @throws IllegalArgumentException when the patches cannot produce a task — a missing creation
    ///                                  patch, or a field the fold can never fill. ADR-0005 requires
    ///                                  this to fail loudly rather than fall back to portal's stored
    ///                                  row.
    FoldedTask importTask(UUID taskId, List<ImportedPatch> patches);

    long taskCount();

    long patchCount();

    /// How many migrated tasks are still open — the only number
    /// [#39](https://github.com/stainii/task/issues/39) needs before it opens the dogfooding
    /// instance, because 28 against 12,483 looks like an empty app and reads as a bug.
    long openTaskCount();

    /// One portal patch, already translated into the new vocabulary by the `migration` module.
    ///
    /// There is no `voids`: portal had no void patch. Its undo was a compensating patch, which is
    /// the defect ADR-0004 replaced, and nothing in the corpus needs the field.
    ///
    /// A null value in [#changes] is meaningful — it is how a field is cleared — so the map is the
    /// same shape `TaskPatch` itself holds.
    record ImportedPatch(UUID id, Instant dateTime, Map<String, String> changes) {
    }

    /// What one migrated task folded to, in the eight fields portal's stored document also has —
    /// the comparable surface, and nothing else.
    ///
    /// `status` is a `String` rather than the enum, and `taskTemplateId`, `occurrenceId` and
    /// `completedOn` are absent: `TaskStatus` lives in `task.domain` and is internal, and the three
    /// missing fields have no counterpart in portal to be compared against. A port carrying more
    /// than the caller can use would be leaking the aggregate through the back door ADR-0003 shut.
    record FoldedTask(
            String name,
            Instant creationDateTime,
            LocalDate startDate,
            @Nullable LocalDate dueDate,
            String context,
            Importance importance,
            @Nullable String description,
            String status) {
    }
}

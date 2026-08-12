package be.stijnhooft.task.backend.task;

import java.time.Instant;
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

    /// Truncates tasks and their patches, because ADR-0005 requires the importer to be re-runnable
    /// and idempotent — **truncate and rebuild, never append**, so a dry run is free.
    void deleteAllTasks();

    /// Folds one portal task into existence from its translated patches.
    ///
    /// The patches are handed over unordered; the fold sorts them itself, so the importer cannot
    /// impose an order the running application would not have used. Sequences are assigned in fold
    /// order, which is ADR-0005's *migrated patches occupy sequences 1..N in `dateTime` order*.
    ///
    /// @throws IllegalArgumentException when the patches cannot produce a task — a missing creation
    ///                                  patch, or a field the fold can never fill. ADR-0005 requires
    ///                                  this to fail loudly rather than fall back to portal's stored
    ///                                  row.
    void importTask(UUID taskId, List<ImportedPatch> patches);

    long taskCount();

    long patchCount();

    /// One portal patch, already translated into the new vocabulary by the `migration` module.
    ///
    /// There is no `voids`: portal had no void patch. Its undo was a compensating patch, which is
    /// the defect ADR-0004 replaced, and nothing in the corpus needs the field.
    ///
    /// A null value in [#changes] is meaningful — it is how a field is cleared — so the map is the
    /// same shape `TaskPatch` itself holds.
    record ImportedPatch(UUID id, Instant dateTime, Map<String, String> changes) {
    }
}

package be.stijnhooft.task.backend.migration.map;

import be.stijnhooft.task.backend.task.TaskImport;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// **Blobs are translated, not copied**
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)): dead
/// keys dropped, values narrowed, count and order and timestamps untouched.
///
/// ### The eleven keys portal wrote
///
/// | portal key | becomes | why |
/// |---|---|---|
/// | `name`, `context`, `description`, `importance`, `status` | itself | same vocabulary |
/// | `creationDateTime` | itself | already an `Instant`; all 11,872 carry a `Z` |
/// | `startDateTime` | `startDate` | narrowed by [PortalDates] |
/// | `dueDateTime` | `dueDate` | narrowed by [PortalDates] |
/// | `flowId` | *dropped* | dead in the model ([#12](https://github.com/stainii/task/issues/12)); its provenance is read once, before it goes |
/// | `expectedDurationInHours` | *dropped* | dead in the model (#12) |
/// | `id` | *dropped* | the task's identity, which arrives as the patch's `taskId` |
///
/// ### A patch left with nothing to say stays an empty patch
///
/// 687 patches carry `expectedDurationInHours`, and some carry nothing else. ADR-0005 keeps them:
/// the timestamp is still a true fact, deleting it is the collapsing
/// [#4](https://github.com/stainii/task/issues/4) forbade, and *patches in equals patches out* is a
/// checkable invariant. This is also why the importer cannot write through `TaskPatchService`,
/// which rejects a patch that changes nothing.
///
/// ### Two keys are added rather than translated
///
/// - **`taskTemplateId`**, on the creation patch, when `flowId` named a template that still exists.
///   Provenance restored retroactively — for 51% of the recurring corpus; the rest imports null and
///   is counted, never synthesised.
/// - **`completedOn`**, on any patch that sets `status` to `COMPLETED`. ADR-0011 added the field and
///   ADR-0005's amendment puts it here: *when did I do it*, taken from the completing patch's own
///   date in the reader's zone. Without it every migrated completion would read as done on the day
///   the fold ran.
///
/// ### A cleared start date becomes the creation date
///
/// Four patches in the archive set `startDateTime` to null, and the new model's `startDate` is
/// **not nullable** — `Task.foldOf` throws `IncompleteTaskHistoryException` rather than produce a
/// task without one, because ADR-0004 requires the creation patch to carry every field and
/// ADR-0006 bands the overview on this one. So four real tasks cannot be represented as they stand,
/// which ADR-0005 did not anticipate.
///
/// They are **translated, not dropped and not failed on**: a cleared start date meant *no
/// constraint on when this starts* in portal, and the new model expresses that as **starting the
/// day it was created** — which is exactly what `Task.builderForInitialTask` defaults to. Keeping
/// the last non-null value instead would hide `Dirty Diana - Michael Jackson` until 28 July 2020,
/// three weeks after its owner asked to see it immediately.
///
/// All four are completed tasks from 2020, so nothing turns on the choice today. It is counted and
/// reported because it is a value the importer invented, and the importer is re-runnable if the
/// call is wrong.
public final class PatchTranslator {

    private static final Set<String> DROPPED = Set.of("flowId", "expectedDurationInHours", "id");

    private static final Map<String, String> RENAMED = Map.of(
            "startDateTime", "startDate",
            "dueDateTime", "dueDate");

    private static final Set<String> NARROWED_TO_DATE = Set.of("startDate", "dueDate");

    private final ZoneId zone;

    public PatchTranslator(ZoneId zone) {
        this.zone = zone;
    }

    /// @param patchId        the new patch id, already minted by [PortalIds]
    /// @param dateTime       portal's own timestamp, carried over untouched — it orders the fold
    /// @param changes        portal's `changes` map
    /// @param context        the context this task must end up in, or null to keep what portal
    ///                       wrote. Non-null for a recurring task, whose `Personal` is overwritten
    ///                       with its deployment name (REC-011, see [Contexts])
    /// @param taskTemplateId the template this task came from, or null when there is none to name
    /// @param creationDate   the task's own creation date, which a cleared start date falls back to
    ///                       — see the note on this class
    public TaskImport.ImportedPatch translate(UUID patchId,
                                              java.time.Instant dateTime,
                                              Map<String, String> changes,
                                              @Nullable String context,
                                              @Nullable UUID taskTemplateId,
                                              LocalDate creationDate) {
        var translated = new LinkedHashMap<String, String>();

        changes.forEach((key, value) -> {
            if (DROPPED.contains(key)) {
                return;
            }
            var name = RENAMED.getOrDefault(key, key);

            // A change *to null* is how a field is cleared, and 48 of them are real: four
            // startDateTime and forty-four dueDateTime. Narrowing must not turn one into a parse.
            if (value == null) {
                translated.put(name, "startDate".equals(name) ? creationDate.toString() : null);
                return;
            }
            translated.put(name, NARROWED_TO_DATE.contains(name)
                    ? PortalDates.toLocalDate(value, zone).toString()
                    : value);
        });

        if (context != null && translated.containsKey("context")) {
            translated.put("context", context);
        }

        // The creation patch is the one carrying creationDateTime - the same discriminator the live
        // API uses - so provenance lands exactly once per task rather than on every edit.
        if (taskTemplateId != null && translated.containsKey("creationDateTime")) {
            translated.put("taskTemplateId", taskTemplateId.toString());
        }

        if ("COMPLETED".equals(translated.get("status"))) {
            translated.put("completedOn", PortalDates.toLocalDate(dateTime, zone).toString());
        }

        return new TaskImport.ImportedPatch(patchId, dateTime, translated);
    }
}

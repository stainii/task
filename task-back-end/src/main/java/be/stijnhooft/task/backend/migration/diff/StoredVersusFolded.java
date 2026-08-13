package be.stijnhooft.task.backend.migration.diff;

import be.stijnhooft.task.backend.migration.map.Contexts;
import be.stijnhooft.task.backend.migration.map.PortalIds;
import be.stijnhooft.task.backend.migration.portal.PortalArchive;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskImport;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// **Every task portal stored, against what the real fold computes from the same patches** — the
/// half of [ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)'s
/// diff report that [#53](https://github.com/stainii/task/issues/53) owns.
///
/// ### It compares eight fields and explains the differences
///
/// Portal's document and ours share exactly eight fields. Every difference is binned against
/// [Cause]'s closed list, and anything fitting none of them is [Cause#UNEXPLAINED] — the only thing
/// a person has to adjudicate.
///
/// ### Where the explanations come from
///
/// Causes 3 to 6 are read off the values: a context overwritten by a deployment name, a
/// near-duplicate spelling folded, a missing importance defaulted, a cleared start date. The two
/// that matter most are read off the **order** instead, from portal's `history` array — insertion
/// order, so arrival order, and the only record of it that survives anywhere in the corpus.
///
/// The test for D2 is exact rather than heuristic: take the patches that touch this field, and ask
/// whether the last of them **in the array** is the last of them **by the fold's own comparator**.
/// If it is not, portal and the fold were reading different patches as the winner, which is the
/// defect, working as documented.
///
/// ### What it deliberately cannot explain
///
/// A patch that is missing from the array altogether — the 32
/// [#52](https://github.com/stainii/task/issues/52) recovered by grouping on `taskId` instead —
/// is **not** a cause. Portal never applied those patches and the fold does, which is a real
/// difference in what the task *is*, and 32 of them is a readable number. Letting them surface
/// costs a minute of reading; adding a cause for them after the fact would be the move ADR-0005
/// forbids.
public class StoredVersusFolded {

    /// The new model's field, and the key portal's patches used for it.
    private record Field(String name, String portalKey) {
    }

    private static final Field NAME = new Field("name", "name");
    private static final Field CONTEXT = new Field("context", "context");
    private static final Field STATUS = new Field("status", "status");
    private static final Field IMPORTANCE = new Field("importance", "importance");
    private static final Field DESCRIPTION = new Field("description", "description");
    private static final Field START_DATE = new Field("startDate", "startDateTime");
    private static final Field DUE_DATE = new Field("dueDate", "dueDateTime");
    private static final Field CREATION_DATE_TIME = new Field("creationDateTime", "creationDateTime");

    /// Portal's stored `creationDateTime` is written once by the creation patch and copied, never
    /// computed, so no ordering of anything can move it. A difference there means the translation is
    /// broken, and it is unexplained by construction.
    private static final Set<String> CANARIES = Set.of(CREATION_DATE_TIME.name());

    private final ZoneId zone;

    public StoredVersusFolded(ZoneId zone) {
        this.zone = zone;
    }

    /// @param differences                 every field where the two disagree, attributed
    /// @param dateTimesThatLostATimeOfDay start and due date-times whose day survived and whose
    ///                                    time of day did not. Not a difference — the comparison is
    ///                                    at day granularity by construction — but a fact that
    ///                                    stops being recoverable at cutover, so it is counted once
    public record Comparison(List<Difference> differences, long dateTimesThatLostATimeOfDay) {

        public Comparison {
            differences = List.copyOf(differences);
        }
    }

    /// @param deploymentContext the context REC-011 forces onto this task, or null when portal's own
    ///                          value is kept
    public Comparison compare(PortalArchive.PortalTask stored,
                              TaskImport.FoldedTask folded,
                              List<PortalArchive.PortalPatch> patches,
                              @Nullable String deploymentContext) {

        var differences = new ArrayList<Difference>();
        var open = "OPEN".equals(folded.status());
        var context = new Context(stored, folded, patches, deploymentContext, open);

        context.compare(differences, NAME, stored.name(), folded.name());
        context.compare(differences, CONTEXT, stored.context(), folded.context());
        context.compare(differences, STATUS, stored.status(), folded.status());
        context.compare(differences, IMPORTANCE, stored.importance(), folded.importance().name());
        context.compare(differences, DESCRIPTION, stored.description(), folded.description());
        context.compare(differences, CREATION_DATE_TIME,
                text(millis(stored.creationDateTime())), text(millis(folded.creationDateTime())));
        context.compare(differences, START_DATE, text(dateOf(stored.startDateTime())), text(folded.startDate()));
        context.compare(differences, DUE_DATE, text(dateOf(stored.dueDateTime())), text(folded.dueDate()));

        return new Comparison(differences, lostTimeOfDay(stored.startDateTime()) + lostTimeOfDay(stored.dueDateTime()));
    }

    /// One task's worth of the things every field's attribution needs.
    private final class Context {

        private final PortalArchive.PortalTask stored;
        private final TaskImport.FoldedTask folded;
        private final List<PortalArchive.PortalPatch> patches;
        private final @Nullable String deploymentContext;
        private final boolean open;
        private final Set<String> duplicated;

        private Context(PortalArchive.PortalTask stored,
                        TaskImport.FoldedTask folded,
                        List<PortalArchive.PortalPatch> patches,
                        @Nullable String deploymentContext,
                        boolean open) {
            this.stored = stored;
            this.folded = folded;
            this.patches = patches;
            this.deploymentContext = deploymentContext;
            this.open = open;
            this.duplicated = duplicatedIn(stored.historyOrder());
        }

        private void compare(List<Difference> differences, Field field,
                             @Nullable String storedValue, @Nullable String foldedValue) {
            if (Objects.equals(storedValue, foldedValue)) {
                return;
            }
            differences.add(new Difference(
                    stored.id(), field.name(), storedValue, foldedValue, causeOf(field, storedValue, foldedValue), open));
        }

        private Cause causeOf(Field field, @Nullable String storedValue, @Nullable String foldedValue) {
            if (CANARIES.contains(field.name())) {
                return Cause.UNEXPLAINED;
            }

            // The value-shaped explanations first: each is a mapping decision that would produce
            // this exact difference on purpose, whatever the patches did.
            if (field == CONTEXT && storedValue != null) {
                if (deploymentContext != null && deploymentContext.equals(foldedValue)) {
                    return Cause.CONTEXT_OVERWRITTEN_BY_DEPLOYMENT;
                }
                if (Contexts.normalise(storedValue).equals(foldedValue)) {
                    return Cause.CONTEXT_NORMALISED;
                }
            }
            if (field == IMPORTANCE && storedValue == null
                    && Importance.NOT_SO_IMPORTANT.name().equals(foldedValue)) {
                return Cause.IMPORTANCE_DEFAULTED;
            }
            if (field == START_DATE && storedValue == null && clearsStartDate()) {
                return Cause.CLEARED_START_DATE_DEFAULTED;
            }

            return orderingCause(field);
        }

        /// Which patch portal applied last for this field, against which one the fold takes — the
        /// exact test for D2, rather than a guess from how many patches there are.
        private Cause orderingCause(Field field) {
            var touching = patches.stream()
                    .filter(patch -> patch.changes().containsKey(field.portalKey()))
                    .toList();
            if (touching.size() < 2) {
                return Cause.UNEXPLAINED;
            }

            if (touching.stream().anyMatch(patch -> duplicated.contains(patch.id()))) {
                return Cause.DUPLICATED_HISTORY_ENTRY;
            }

            // A patch the array never held is not a patch portal put in the wrong order - it is one
            // portal never applied at all, and the fold does. Attributing that to misordering would
            // launder the 32 patches #52 recovered into an expected difference, when they are a real
            // change in what the task *is*.
            var order = stored.historyOrder();
            if (!touching.stream().allMatch(patch -> order.contains(patch.id()))) {
                return Cause.UNEXPLAINED;
            }

            var lastToArrive = touching.stream()
                    .max(Comparator.comparingInt(patch -> order.indexOf(patch.id())));
            var newestByTheClock = touching.stream().max(FOLD_ORDER);

            if (lastToArrive.isEmpty() || newestByTheClock.isEmpty()) {
                return Cause.UNEXPLAINED;
            }
            return lastToArrive.get().id().equals(newestByTheClock.get().id())
                    ? Cause.UNEXPLAINED
                    : Cause.OUT_OF_ORDER_ARRIVAL;
        }

        private boolean clearsStartDate() {
            return patches.stream().anyMatch(patch ->
                    patch.changes().containsKey("startDateTime") && patch.changes().get("startDateTime") == null);
        }
    }

    /// The fold's own order, applied to portal's patches: the client's clock, ties broken by the
    /// patch id **as the fold will see it** — the minted UUID's string form, not portal's id, or
    /// this would answer a question about a different comparator.
    private static final Comparator<PortalArchive.PortalPatch> FOLD_ORDER =
            Comparator.comparing(PortalArchive.PortalPatch::dateTime)
                    .thenComparing(patch -> PortalIds.ofPatch(patch.id()).toString());

    private static Set<String> duplicatedIn(List<String> order) {
        var seen = new LinkedHashSet<String>();
        var twice = new LinkedHashSet<String>();
        order.forEach(id -> {
            if (!seen.add(id)) {
                twice.add(id);
            }
        });
        return Set.copyOf(twice);
    }

    /// **A BSON date holds milliseconds; portal's patch strings hold microseconds.** So the stored
    /// document is a *truncated copy* of the value the fold reads — `…310Z` against `…310607Z`, for
    /// 8,895 of the 11,855 tasks — and comparing the two at full precision reports the truncation as
    /// a difference in 75% of the corpus.
    ///
    /// Milliseconds is therefore not a tolerance, it is **the precision at which the stored document
    /// can express anything at all**. Anything finer compares a value against a lossier rendering of
    /// itself. The fold keeps the microseconds, which is strictly better than what portal could hold.
    private static @Nullable Instant millis(@Nullable Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private @Nullable LocalDate dateOf(@Nullable Instant instant) {
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    private long lostTimeOfDay(@Nullable Instant instant) {
        return instant != null && !instant.atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT) ? 1 : 0;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

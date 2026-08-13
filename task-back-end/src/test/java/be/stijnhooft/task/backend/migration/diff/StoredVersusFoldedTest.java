package be.stijnhooft.task.backend.migration.diff;

import be.stijnhooft.task.backend.migration.portal.PortalArchive;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.TaskImport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The attribution rules, at their boundaries — because these rules decide what a person is asked
/// to look at in the last hour before their data becomes irreplaceable.
///
/// The one that matters is [Cause#UNEXPLAINED]: a rule that explains too much is worse than no rule,
/// since a difference wrongly binned as *expected* is a difference nobody ever reads.
class StoredVersusFoldedTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final Instant CREATED = Instant.parse("2021-06-21T04:00:00Z");

    private final StoredVersusFolded comparer = new StoredVersusFolded(BRUSSELS);

    @Test
    void aTaskThatFoldsToWhatPortalStoredHasNothingToReport() {
        var comparison = comparer.compare(stored(task -> { }), folded(task -> { }), List.of(), null);

        assertThat(comparison.differences()).isEmpty();
    }

    /// D2 itself: two patches touch `status`, and the one portal applied last — last in the
    /// `history` array, which is insertion order — is not the newest by the clock that minted it.
    @Test
    void aFieldWhoseLastArrivalIsNotItsNewestPatchIsOutOfOrderArrival() {
        var early = patch("a", "2021-07-01T10:00:00Z", Map.of("status", "COMPLETED"));
        var late = patch("b", "2021-07-02T10:00:00Z", Map.of("status", "OPEN"));

        // Arrived b then a: portal's last write is a's COMPLETED, while the fold takes b's OPEN.
        var comparison = comparer.compare(
                stored(task -> task.status("COMPLETED").historyOrder(List.of("b", "a"))),
                folded(task -> task.status("OPEN")),
                List.of(early, late),
                null);

        assertThat(comparison.differences()).singleElement().satisfies(difference -> {
            assertThat(difference.field()).isEqualTo("status");
            assertThat(difference.cause()).isEqualTo(Cause.OUT_OF_ORDER_ARRIVAL);
            assertThat(difference.isEscalated()).isTrue();
        });
    }

    /// The same two patches, arrived in the order they were minted. Portal and the fold agree about
    /// which one wins, so nothing here explains a difference — and one appearing anyway is a defect,
    /// which is the whole point of not attributing it.
    @Test
    void theSameTwoPatchesInClockOrderExplainNothing() {
        var early = patch("a", "2021-07-01T10:00:00Z", Map.of("status", "COMPLETED"));
        var late = patch("b", "2021-07-02T10:00:00Z", Map.of("status", "OPEN"));

        var comparison = comparer.compare(
                stored(task -> task.status("COMPLETED").historyOrder(List.of("a", "b"))),
                folded(task -> task.status("OPEN")),
                List.of(early, late),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    /// A single patch cannot have been applied in the wrong order relative to anything.
    @Test
    void oneTouchingPatchIsNeverAnOrderingProblem() {
        var comparison = comparer.compare(
                stored(task -> task.name("Fietsband plakken").historyOrder(List.of("a"))),
                folded(task -> task.name("Fietsband oppompen")),
                List.of(patch("a", "2021-07-01T10:00:00Z", Map.of("name", "Fietsband oppompen"))),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    /// Portal's repair recursion re-`add`s a patch it has already applied, so the same id appears
    /// twice and can re-apply a stale value on the second pass.
    @Test
    void aPatchAppearingTwiceInTheArrayIsADuplicatedHistoryEntry() {
        var comparison = comparer.compare(
                stored(task -> task.status("COMPLETED").historyOrder(List.of("a", "b", "a"))),
                folded(task -> task.status("OPEN")),
                List.of(patch("a", "2021-07-01T10:00:00Z", Map.of("status", "COMPLETED")),
                        patch("b", "2021-07-02T10:00:00Z", Map.of("status", "OPEN"))),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.DUPLICATED_HISTORY_ENTRY);
    }

    /// The 32 patches #52 recovered by grouping on `taskId` are **not** explained away. Portal never
    /// applied them and the fold does, which is a real difference in what the task is.
    @Test
    void aPatchMissingFromPortalsArrayIsNotACause() {
        var comparison = comparer.compare(
                stored(task -> task.status("COMPLETED").historyOrder(List.of("a"))),
                folded(task -> task.status("OPEN")),
                List.of(patch("a", "2021-07-01T10:00:00Z", Map.of("status", "COMPLETED")),
                        patch("lost", "2021-07-02T10:00:00Z", Map.of("status", "OPEN"))),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    @Test
    void aRecurringTasksHardcodedPersonalIsOverwrittenByItsDeployment() {
        var comparison = comparer.compare(
                stored(task -> task.context("Personal")),
                folded(task -> task.context("Housagotchi")),
                List.of(),
                "Housagotchi");

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.CONTEXT_OVERWRITTEN_BY_DEPLOYMENT);
    }

    @Test
    void aNearDuplicateSpellingIsANormalisedContext() {
        var comparison = comparer.compare(
                stored(task -> task.context("Scholencoordinatie")),
                folded(task -> task.context("Scholencoördinatie")),
                List.of(),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.CONTEXT_NORMALISED);
    }

    /// A hand-made task landing in some *third* context is not normalisation, whatever else is true
    /// of it — the rule must not launder a context the mapping got wrong.
    @Test
    void aContextThatIsNeitherTheDeploymentNorANormalisationIsUnexplained() {
        var comparison = comparer.compare(
                stored(task -> task.context("Baby")),
                folded(task -> task.context("VDAB")),
                List.of(),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    @Test
    void aMissingImportanceBecomesNotSoImportant() {
        var comparison = comparer.compare(
                stored(task -> task.importance(null)),
                folded(task -> task.importance(Importance.NOT_SO_IMPORTANT)),
                List.of(),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.IMPORTANCE_DEFAULTED);
    }

    /// ADR-0018 maps a *missing* importance to `NOT_SO_IMPORTANT`. A stored importance that changed
    /// into a different one is a different event entirely.
    @Test
    void anImportanceThatChangedRatherThanDefaultedIsUnexplained() {
        var comparison = comparer.compare(
                stored(task -> task.importance("IMPORTANT")),
                folded(task -> task.importance(Importance.NOT_SO_IMPORTANT)),
                List.of(),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    @Test
    void aClearedStartDateFallsBackToTheCreationDate() {
        var cleared = new HashMap<String, String>();
        cleared.put("startDateTime", null);

        var comparison = comparer.compare(
                stored(task -> task.startDateTime(null)),
                folded(task -> task.startDate(LocalDate.of(2021, 6, 21))),
                List.of(patch("a", "2021-07-01T10:00:00Z", cleared)),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.CLEARED_START_DATE_DEFAULTED);
    }

    /// `creationDateTime` is written once and copied, never computed, so no ordering of anything can
    /// move it. It is the one canary, and it stays unexplained even when the task's history is a
    /// mess in exactly the way that explains every other field.
    @Test
    void aCreationDateTimeDifferenceIsNeverExplainedAway() {
        var comparison = comparer.compare(
                stored(task -> task.creationDateTime(CREATED).historyOrder(List.of("b", "a"))),
                folded(task -> task.creationDateTime(CREATED.plusSeconds(3600))),
                List.of(patch("a", "2021-07-01T10:00:00Z", Map.of("creationDateTime", "x")),
                        patch("b", "2021-07-02T10:00:00Z", Map.of("creationDateTime", "y"))),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::cause).isEqualTo(Cause.UNEXPLAINED);
    }

    /// Portal stored its `LocalDateTime` through `ZoneId.systemDefault()`, which was Brussels:
    /// `2021-06-27T22:00:00Z` **is** the 28th at midnight, and reading it at the same zone the
    /// importer uses is what makes the two comparable at all. Read at UTC it would be the 27th, and
    /// every summer due date in the corpus would report a difference that is not there.
    @Test
    void aStoredDateTimeIsReadBackAtTheImportersOwnZone() {
        var comparison = comparer.compare(
                stored(task -> task.dueDateTime(Instant.parse("2021-06-27T22:00:00Z"))),
                folded(task -> task.dueDate(LocalDate.of(2021, 6, 28))),
                List.of(),
                null);

        assertThat(comparison.differences()).isEmpty();
    }

    /// A time of day is not a difference — the comparison is at day granularity by construction —
    /// but it stops being recoverable at cutover, so it is counted once.
    @Test
    void aTimeOfDayIsCountedRatherThanReported() {
        var comparison = comparer.compare(
                stored(task -> task.dueDateTime(Instant.parse("2021-06-28T15:30:00Z"))),
                folded(task -> task.dueDate(LocalDate.of(2021, 6, 28))),
                List.of(),
                null);

        assertThat(comparison.differences()).isEmpty();
        assertThat(comparison.dateTimesThatLostATimeOfDay()).isEqualTo(1);
    }

    /// Only an *open* task's status is escalated: a completed task whose status differs is history,
    /// and a report that shouts about everything says nothing.
    @Test
    void onlyAnOpenTasksStatusIsEscalated() {
        var comparison = comparer.compare(
                stored(task -> task.status("OPEN")),
                folded(task -> task.status("COMPLETED")),
                List.of(),
                null);

        assertThat(comparison.differences()).singleElement()
                .extracting(Difference::isEscalated).isEqualTo(false);
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static PortalArchive.PortalPatch patch(String id, String dateTime, Map<String, String> changes) {
        return new PortalArchive.PortalPatch(id, "task-1", Instant.parse(dateTime), changes);
    }

    /// Mutable builders rather than eight-argument constructor calls: every test above varies one
    /// field and holds the other seven identical, and a wall of repeated arguments hides which one.
    private static PortalArchive.PortalTask stored(java.util.function.Consumer<StoredBuilder> customise) {
        var builder = new StoredBuilder();
        customise.accept(builder);
        return builder.build();
    }

    private static TaskImport.FoldedTask folded(java.util.function.Consumer<FoldedBuilder> customise) {
        var builder = new FoldedBuilder();
        customise.accept(builder);
        return builder.build();
    }

    private static final class StoredBuilder {
        private String name = "Fietsband oppompen";
        private @Nullable String context = "Personal";
        private @Nullable String status = "OPEN";
        private @Nullable String importance = "IMPORTANT";
        private @Nullable Instant creationDateTime = CREATED;
        private @Nullable Instant startDateTime = Instant.parse("2021-06-20T22:00:00Z");
        private @Nullable Instant dueDateTime = Instant.parse("2021-06-27T22:00:00Z");
        private @Nullable String description = null;
        private List<String> historyOrder = List.of();

        StoredBuilder name(String value) { this.name = value; return this; }
        StoredBuilder context(String value) { this.context = value; return this; }
        StoredBuilder status(String value) { this.status = value; return this; }
        StoredBuilder importance(@Nullable String value) { this.importance = value; return this; }
        StoredBuilder creationDateTime(Instant value) { this.creationDateTime = value; return this; }
        StoredBuilder startDateTime(@Nullable Instant value) { this.startDateTime = value; return this; }
        StoredBuilder dueDateTime(@Nullable Instant value) { this.dueDateTime = value; return this; }
        StoredBuilder historyOrder(List<String> value) { this.historyOrder = value; return this; }

        PortalArchive.PortalTask build() {
            return new PortalArchive.PortalTask("task-1", null, name, context, status, importance,
                    creationDateTime, startDateTime, dueDateTime, description, historyOrder);
        }
    }

    private static final class FoldedBuilder {
        private String name = "Fietsband oppompen";
        private Instant creationDateTime = CREATED;
        private LocalDate startDate = LocalDate.of(2021, 6, 21);
        private @Nullable LocalDate dueDate = LocalDate.of(2021, 6, 28);
        private String context = "Personal";
        private Importance importance = Importance.IMPORTANT;
        private @Nullable String description = null;
        private String status = "OPEN";

        FoldedBuilder name(String value) { this.name = value; return this; }
        FoldedBuilder context(String value) { this.context = value; return this; }
        FoldedBuilder status(String value) { this.status = value; return this; }
        FoldedBuilder importance(Importance value) { this.importance = value; return this; }
        FoldedBuilder creationDateTime(Instant value) { this.creationDateTime = value; return this; }
        FoldedBuilder startDate(LocalDate value) { this.startDate = value; return this; }
        FoldedBuilder dueDate(@Nullable LocalDate value) { this.dueDate = value; return this; }

        TaskImport.FoldedTask build() {
            return new TaskImport.FoldedTask(name, creationDateTime, startDate, dueDate, context,
                    importance, description, status);
        }
    }
}

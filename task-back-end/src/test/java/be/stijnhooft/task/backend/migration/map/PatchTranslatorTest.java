package be.stijnhooft.task.backend.migration.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// **Blobs are translated, not copied** — the eleven keys portal wrote, and the two the importer
/// adds.
class PatchTranslatorTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final Instant WHEN = Instant.parse("2022-09-14T04:00:00.626Z");
    private static final LocalDate CREATED = LocalDate.of(2022, 9, 14);

    private final PatchTranslator translator = new PatchTranslator(BRUSSELS);

    private static Map<String, String> changes(String... keysAndValues) {
        var changes = new LinkedHashMap<String, String>();
        for (var index = 0; index < keysAndValues.length; index += 2) {
            changes.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return changes;
    }

    @Test
    void deadKeysAreDropped() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("name", "Gitaarles",
                        "flowId", "Setlist-402",
                        "expectedDurationInHours", "1",
                        "id", "73877f72-f160-4433-a822-a282878fb646"),
                null, null, CREATED);

        assertThat(translated.changes()).containsOnlyKeys("name");
    }

    /// TODO-001 narrowed the model to `LocalDate`, so the two keys are renamed as well as narrowed.
    @Test
    void dateTimeKeysAreRenamedAndNarrowed() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("startDateTime", "2022-09-14T06:00:00.626132",
                        "dueDateTime", "2022-09-15T00:00"),
                null, null, CREATED);

        assertThat(translated.changes())
                .containsEntry("startDate", "2022-09-14")
                .containsEntry("dueDate", "2022-09-15")
                .doesNotContainKeys("startDateTime", "dueDateTime");
    }

    /// ADR-0005: *a patch left with nothing to say stays as an empty patch*. The timestamp is still
    /// a true fact, and *patches in equals patches out* is the invariant the dry run checks.
    @Test
    void aPatchWhoseOnlyContentIsDeadStaysAnEmptyPatch() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("expectedDurationInHours", "1"), null, null, CREATED);

        assertThat(translated.changes()).isEmpty();
        assertThat(translated.dateTime()).isEqualTo(WHEN);
    }

    /// A change *to* null is how portal cleared a field, and 48 of them are real. Narrowing must not
    /// turn one into a parse failure.
    @Test
    void aChangeToNullSurvivesAsANullChange() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("dueDateTime", null), null, null, CREATED);

        assertThat(translated.changes()).containsKey("dueDate");
        assertThat(translated.changes().get("dueDate")).isNull();
    }

    /// **A cleared start date cannot be represented**, because `startDate` is not nullable — so the
    /// four archive patches that clear one fall back to the task's creation date, which is what
    /// "no constraint on when this starts" means in the new model.
    @Test
    void aClearedStartDateBecomesTheCreationDate() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("startDateTime", null), null, null, CREATED);

        assertThat(translated.changes()).containsEntry("startDate", CREATED.toString());
    }

    /// A cleared *due* date is a different matter: `dueDate` is nullable, so it clears.
    @Test
    void aClearedDueDateStillClears() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("dueDateTime", null, "startDateTime", null), null, null, CREATED);

        assertThat(translated.changes().get("dueDate")).isNull();
        assertThat(translated.changes()).containsEntry("startDate", CREATED.toString());
    }

    /// REC-011: the deployment name overwrites portal's hardcoded `Personal`.
    @Test
    void aRecurringTasksContextIsOverwrittenWithItsDeployment() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("context", "Personal"), "Housagotchi", null, CREATED);

        assertThat(translated.changes()).containsEntry("context", "Housagotchi");
    }

    /// **A hand-made task's own context is normalised on the way through.** #53's rehearsal found
    /// this missing: `Contexts` was written, tested and recorded in ADR-0005, and then only ever
    /// called for deployment names that were already canonical — so 1,302 tasks imported as
    /// `Scholencoordinatie`, `Medisch huis` and `Personal ` beside their real spellings, and
    /// ADR-0006 gives every stray one its own card in the overview.
    ///
    /// The stored-versus-folded report is structurally blind to it, because portal's document and
    /// the fold agree: they are wrong in the same way. Which is why it needs a test here.
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "Scholencoordinatie, Scholencoördinatie",
            "'Personal ', Personal",
            "'Medisch huis', Medisch Huis",
            "Baby, Baby"})
    void aHandMadeTasksOwnContextIsNormalised(String portal, String expected) {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("context", portal), null, null, CREATED);

        assertThat(translated.changes()).containsEntry("context", expected);
    }

    /// The override applies to a context the patch already carries, and does not invent one — a
    /// later edit that never mentioned context must not start doing so.
    @Test
    void aPatchThatNeverMentionedContextDoesNotGainOne() {
        var translated = translator.translate(UUID.randomUUID(), WHEN,
                changes("status", "OPEN"), "Housagotchi", null, CREATED);

        assertThat(translated.changes()).doesNotContainKey("context");
    }

    /// Provenance lands exactly once per task, on the patch carrying `creationDateTime` — the same
    /// discriminator the live API uses to tell a create from an edit.
    @Test
    void provenanceIsWrittenOnlyOnTheCreationPatch() {
        var templateId = UUID.randomUUID();

        var creation = translator.translate(UUID.randomUUID(), WHEN,
                changes("creationDateTime", "2022-09-14T04:00:00.626110Z", "name", "Gitaarles"),
                null, templateId, CREATED);
        var edit = translator.translate(UUID.randomUUID(), WHEN, changes("name", "Gitaarles"), null, templateId, CREATED);

        assertThat(creation.changes()).containsEntry("taskTemplateId", templateId.toString());
        assertThat(edit.changes()).doesNotContainKey("taskTemplateId");
    }

    /// ADR-0011's `completedOn`, from the completing patch's own date in the reader's zone. Without
    /// it every migrated completion reads as done on the day the import ran.
    @Test
    void aCompletionCarriesTheDayItHappened() {
        var translated = translator.translate(UUID.randomUUID(), Instant.parse("2024-03-15T23:30:00Z"),
                changes("status", "COMPLETED"), null, null, CREATED);

        assertThat(translated.changes()).containsEntry("completedOn", "2024-03-16");
    }

    @Test
    void anOpenPatchCarriesNoCompletionDate() {
        var translated = translator.translate(UUID.randomUUID(), WHEN, changes("status", "OPEN"), null, null, CREATED);

        assertThat(translated.changes()).doesNotContainKey("completedOn");
    }

    /// Portal's own timestamp is carried over untouched: it orders the fold, and re-stamping it
    /// would reorder years of history.
    @Test
    void theTimestampIsNeverRewritten() {
        var translated = translator.translate(UUID.randomUUID(), WHEN, changes("name", "x"), null, null, CREATED);

        assertThat(translated.dateTime()).isEqualTo(WHEN);
    }
}

package be.stijnhooft.task.backend.task.domain;

import be.stijnhooft.task.backend.task.Importance;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs `/fold-fixtures/` against the Java fold.
///
/// The fold exists twice - here and in TypeScript (#55) - and the two drifting apart would be
/// silent and would corrupt real data, so the fixtures are the specification and both suites
/// enumerate the same directory. **No fold rule without a fixture.**
class FoldFixtureTest {

    private static final Path FIXTURES = Path.of("..", "fold-fixtures");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void thereAreFixturesToRun() throws IOException {
        // A path that silently matches nothing is how #32's pitest run spent four months
        // measuring its own exclusion. A green suite that ran no fixtures is the same failure.
        assertThat(fixtureFiles()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void foldsAsSpecified(String name, Fixture fixture) {
        var patches = fixture.patches().stream()
                .map(FoldFixtureTest::toPatch)
                .toList();

        var folded = Task.foldOf(fixture.taskId(), patches, 0L);
        var expected = fixture.expected();

        assertThat(folded.id()).as("id").isEqualTo(expected.id());
        assertThat(folded.name()).as("name").isEqualTo(expected.name());
        assertThat(folded.creationDateTime()).as("creationDateTime").isEqualTo(expected.creationDateTime());
        assertThat(folded.startDate()).as("startDate").isEqualTo(expected.startDate());
        assertThat(folded.dueDate()).as("dueDate").isEqualTo(expected.dueDate());
        assertThat(folded.context()).as("context").isEqualTo(expected.context());
        assertThat(folded.importance()).as("importance").isEqualTo(expected.importance());
        assertThat(folded.description()).as("description").isEqualTo(expected.description());
        assertThat(folded.status()).as("status").isEqualTo(expected.status());
        assertThat(folded.completedOn()).as("completedOn").isEqualTo(expected.completedOn());
        assertThat(folded.taskTemplateId()).as("taskTemplateId").isEqualTo(expected.taskTemplateId());
        assertThat(folded.occurrenceId()).as("occurrenceId").isEqualTo(expected.occurrenceId());

        // The folded history holds one entry per distinct patch id. Without this, a duplicate
        // being applied twice is indistinguishable from it being dropped, because applying the
        // same change twice lands on the same value.
        var distinctIds = fixture.patches().stream().map(FixturePatch::id).distinct().count();
        assertThat(folded.history()).as("history").hasSize((int) distinctIds);
    }

    /// The same fixture folded from a shuffled history must give the same task. Arrival order is
    /// the one thing the fold is not allowed to depend on, and a fixture file can only ever list
    /// its patches in *some* order.
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void foldsTheSameWhateverOrderThePatchesArriveIn(String name, Fixture fixture) {
        var patches = fixture.patches().stream()
                .map(FoldFixtureTest::toPatch)
                .toList();

        var asListed = Task.foldOf(fixture.taskId(), patches, 0L);

        var reversed = new java.util.ArrayList<>(patches);
        Collections.reverse(reversed);
        assertThat(Task.foldOf(fixture.taskId(), reversed, 0L)).isEqualTo(asListed);

        var rotated = new java.util.ArrayList<>(patches);
        Collections.rotate(rotated, 1);
        assertThat(Task.foldOf(fixture.taskId(), rotated, 0L)).isEqualTo(asListed);
    }

    private static TaskPatch toPatch(FixturePatch patch) {
        return new TaskPatch(patch.id(), patch.taskId(), patch.dateTime(), patch.sequence(), patch.voids(),
                patch.changes());
    }

    static Stream<Object[]> fixtures() throws IOException {
        return fixtureFiles().stream()
                .map(file -> new Object[]{file.getFileName().toString(), read(file)});
    }

    private static List<Path> fixtureFiles() throws IOException {
        try (var files = Files.list(FIXTURES)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static Fixture read(Path file) {
        try {
            return MAPPER.readValue(file.toFile(), Fixture.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fold fixture " + file, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(String rule, UUID taskId, List<FixturePatch> patches, ExpectedTask expected) {

        @Override
        public String toString() {
            return rule;
        }
    }

    record FixturePatch(UUID id, UUID taskId, Instant dateTime, Long sequence, UUID voids,
                        Map<String, String> changes) {
    }

    record ExpectedTask(UUID id, String name, Instant creationDateTime, LocalDate startDate, LocalDate dueDate,
                        String context, Importance importance, String description, TaskStatus status,
                        LocalDate completedOn, UUID taskTemplateId, UUID occurrenceId) {
    }
}

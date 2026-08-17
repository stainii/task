package be.stijnhooft.task.backend.template.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs `/firing-fixtures/` against the Java triggers.
///
/// *When does this fire next?* now exists twice - here and in the front-end, whose authoring screen
/// lists the next dates under the rule it reads back as a sentence
/// ([#68](https://github.com/stainii/task/issues/68)). Two answers to that question drifting apart
/// would be silent and would show a date no task will ever carry, so the fixtures are the
/// specification and both suites enumerate the same directory. **No firing rule without a fixture.**
///
/// The third directory of its kind, on `/render-fixtures/`'s contract exactly - `docs/quality-bar.md`
/// §5: *a third implementation of anything gets the same treatment*.
///
/// A plain unit test with no Spring context, which is what asking the trigger itself buys: nothing
/// here needs a database to say what a date should be.
class FiringFixtureTest {

    private static final Path FIXTURES = Path.of("..", "firing-fixtures");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void thereAreFixturesToRun() throws IOException {
        // A path that silently matches nothing is how #32's pitest run spent four months measuring
        // its own exclusion. A green suite that ran no fixtures is the same failure.
        assertThat(fixtureFiles()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void listsTheDatesTheFixtureNames(String name, Fixture fixture) {
        var trigger = fixture.trigger().toTrigger();

        var dates = trigger.nextFiringDates(fixture.from(), fixture.activeSince(), fixture.lastClosure(), fixture.count());

        assertThat(dates).containsExactlyElementsOf(fixture.expected());
    }

    /// Asking for none is asking for none, whatever the trigger — and **a negative count is not an
    /// exception**, it is the same nothing.
    ///
    /// Asserted for every fixture rather than as one case, because it is the one answer all three
    /// shapes have to agree on, and because the two implementations first disagreed here: one threw
    /// where the other returned an empty list. No fixture states a count below zero, so this is how
    /// that edge crosses the two suites.
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void listsNothingWhenNoDatesAreAskedFor(String name, Fixture fixture) {
        var trigger = fixture.trigger().toTrigger();

        assertThat(trigger.nextFiringDates(fixture.from(), fixture.activeSince(), fixture.lastClosure(), 0))
                .as("asked for none")
                .isEmpty();
        assertThat(trigger.nextFiringDates(fixture.from(), fixture.activeSince(), fixture.lastClosure(), -1))
                .as("asked for fewer than none")
                .isEmpty();
    }

    /// **The preview and the scheduler have to name the same days**, and this is where that is
    /// checked rather than assumed.
    ///
    /// Every date the forward enumeration lists is asserted to be the answer
    /// [Trigger#latestFiringDateOn] gives *on* that date — which is the question the hourly due
    /// check actually asks. So the fixtures do not merely pin two implementations of a preview
    /// against each other: they pin the preview against the code that fires real tasks, and a
    /// forward rule that drifted off its backward mirror by a day would fail here.
    ///
    /// Only [Trigger.Calendar] is checked. [Trigger.MinMax]'s one date is *its* round start plus
    /// `min`, which the backward form only reports once that day has arrived — the same fact asked
    /// from a different day, not a second opinion — and [Trigger.Manual] lists nothing.
    @ParameterizedTest(name = "{0}")
    @MethodSource("calendarFixtures")
    void everyDateListedIsADateTheSchedulerWouldFireOn(String name, Fixture fixture) {
        var trigger = fixture.trigger().toTrigger();

        for (var date : trigger.nextFiringDates(fixture.from(), fixture.activeSince(), fixture.lastClosure(), fixture.count())) {
            assertThat(trigger.latestFiringDateOn(date, fixture.activeSince(), null))
                    .as("the scheduler's answer on %s, a date the preview lists", date)
                    .contains(date);
        }
    }

    static Stream<Object[]> calendarFixtures() throws IOException {
        return fixtures().filter(fixture -> ((Fixture) fixture[1]).trigger().type() == StoredTrigger.Type.CALENDAR);
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
            throw new IllegalStateException("Could not read firing fixture " + file, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(String rule, StoredTrigger trigger, LocalDate activeSince, @Nullable LocalDate lastClosure,
                   LocalDate from, int count, List<LocalDate> expected) {

        @Override
        public String toString() {
            return rule;
        }
    }
}

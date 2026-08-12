package be.stijnhooft.task.backend.template.domain;

import be.stijnhooft.task.backend.task.Importance;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Runs `/render-fixtures/` against the Java renderer.
///
/// Template rendering exists twice - here and in the front-end, which previews what running a
/// template will create and mints a task for *"I already did this"* offline (ADR-0011). The two
/// drifting apart would be silent and would put a wrong date on a real task, so the fixtures are the
/// specification and both suites enumerate the same directory. **No rendering rule without a
/// fixture.**
///
/// A plain unit test with no Spring context, which is why `render` sits on the aggregate: everything
/// it needs is the template's own.
class RenderFixtureTest {

    private static final Path FIXTURES = Path.of("..", "render-fixtures");

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
    void rendersAsSpecified(String name, Fixture fixture) {
        var template = toTemplate(fixture.template());
        var firing = fixture.firing();

        if (fixture.expectedError() != null) {
            assertThatThrownBy(() -> template.render(firing.variables(), firing.firingDate(), firing.anchor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(fixture.expectedError());
            return;
        }

        var fired = template.render(firing.variables(), firing.firingDate(), firing.anchor());
        var expected = fixture.expected();

        assertThat(fired.templateId()).as("templateId").isEqualTo(template.id());
        assertThat(fired.firingDate()).as("firingDate").isEqualTo(firing.firingDate());
        assertThat(fired.context()).as("context").isEqualTo(expected.context());

        assertThat(fired.definitions()).as("definitions").hasSameSizeAs(expected.definitions());
        for (int i = 0; i < expected.definitions().size(); i++) {
            var actual = fired.definitions().get(i);
            var wanted = expected.definitions().get(i);
            assertThat(actual.name()).as("name of definition %d", i).isEqualTo(wanted.name());
            assertThat(actual.description()).as("description of definition %d", i).isEqualTo(wanted.description());
            assertThat(actual.importance()).as("importance of definition %d", i).isEqualTo(wanted.importance());
            assertThat(actual.startDate()).as("startDate of definition %d", i).isEqualTo(wanted.startDate());
            assertThat(actual.dueDate()).as("dueDate of definition %d", i).isEqualTo(wanted.dueDate());
        }
    }

    /// A refusal produces **no** tasks, not the ones that happened to render before it. That is
    /// TODO-022, and it is the one thing an `expectedError` fixture is really about - so it is
    /// asserted here rather than left implied by the exception.
    @ParameterizedTest(name = "{0}")
    @MethodSource("failingFixtures")
    void aRefusalRendersNothingAtAll(String name, Fixture fixture) {
        var template = toTemplate(fixture.template());
        var firing = fixture.firing();

        assertThat(template.taskDefinitions()).as("a refusal fixture needs a definition that would have rendered")
                .hasSizeGreaterThan(0);
        assertThatThrownBy(() -> template.render(firing.variables(), firing.firingDate(), firing.anchor()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TaskTemplate toTemplate(FixtureTemplate fixture) {
        return TaskTemplate.of(
                UUID.randomUUID(),
                fixture.name(),
                fixture.context(),
                fixture.activeSince(),
                fixture.trigger().toTrigger(),
                fixture.taskDefinitions().stream()
                        .map(definition -> TaskDefinition.of(
                                UUID.randomUUID(),
                                definition.name(),
                                definition.startDateOffsetDays(),
                                definition.dueDateOffsetDays(),
                                definition.importance(),
                                definition.description()))
                        .toList());
    }

    static Stream<Object[]> fixtures() throws IOException {
        return fixtureFiles().stream()
                .map(file -> new Object[]{file.getFileName().toString(), read(file)});
    }

    static Stream<Object[]> failingFixtures() throws IOException {
        return fixtures().filter(fixture -> ((Fixture) fixture[1]).expectedError() != null);
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
            throw new IllegalStateException("Could not read render fixture " + file, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(String rule, FixtureTemplate template, FixtureFiring firing,
                   @Nullable ExpectedFiring expected, @Nullable String expectedError) {

        @Override
        public String toString() {
            return rule;
        }
    }

    record FixtureTemplate(String name, String context, LocalDate activeSince, StoredTrigger trigger,
                           List<FixtureDefinition> taskDefinitions) {
    }

    record FixtureDefinition(String name, @Nullable String description, @Nullable Integer startDateOffsetDays,
                             @Nullable Integer dueDateOffsetDays, @Nullable Importance importance) {
    }

    record FixtureFiring(LocalDate firingDate, @Nullable LocalDate anchor, Map<String, String> variables) {
    }

    record ExpectedFiring(String context, List<ExpectedDefinition> definitions) {
    }

    record ExpectedDefinition(String name, @Nullable String description, Importance importance,
                              LocalDate startDate, @Nullable LocalDate dueDate) {
    }
}

package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #24's production stack is described by two files that nothing else reads: {@code deploy/compose.yaml}
 * and {@code deploy/production.example.env}. Both fail at 02:00, unattended, on the machine holding
 * the only copy of the data — so the two ways they can be wrong in silence are asserted here.
 *
 * <p>This is the map's most repeated defect shape, for the seventh time: a guarantee that lives in
 * code, broken by something that lives in YAML. See {@link ToolchainPinsTest}, which does the same
 * for the JDK, and {@code ComposeFile}, which did it for the dev fixture's image tags.
 */
class ProductionComposeTest {

    private static final Path PRODUCTION_COMPOSE = Path.of("..", "deploy", "compose.yaml");
    private static final Path EXAMPLE_ENV = Path.of("..", "deploy", "production.example.env");

    /**
     * ADR-0007 refused a staging environment, and its argument was explicitly that #20's pins keep
     * the dev stack and the test suite on the same images as production. Two compose files naming
     * Postgres and Keycloak is what would quietly make that untrue — Renovate bumps one, the suite
     * keeps passing against the other, and the thing tested is no longer the thing running.
     */
    @Test
    void productionRunsTheSameDatabaseAndAuthServerTheTestSuiteDoes() {
        for (String service : List.of("postgres", "keycloak")) {
            assertThat(ComposeFile.imageOf(PRODUCTION_COMPOSE, service))
                    .withFailMessage(
                            """
                            deploy/compose.yaml runs %s '%s' but task-back-end/compose.yaml — which the
                            test suite reads — runs '%s'.

                            ADR-0007's refusal of a staging environment rests on these being the same
                            image. Move both, in one commit.""",
                            service,
                            ComposeFile.imageOf(PRODUCTION_COMPOSE, service),
                            ComposeFile.imageOf(service))
                    .isEqualTo(ComposeFile.imageOf(service));
        }
    }

    /**
     * Compose substitutes an unset variable with the empty string and carries on. A variable added
     * to the compose file and forgotten in the example env therefore produces a stack that starts
     * with a blank password, a blank issuer URI or a blank tunnel token — at night, on the box, with
     * no one watching. The example file is the only checklist there is for what production.env must
     * contain, so it has to be exhaustive.
     */
    @Test
    void everyVariableTheProductionStackReadsIsDocumentedInTheExampleEnv() throws IOException {
        Set<String> used = variablesIn(PRODUCTION_COMPOSE);
        Set<String> documented = keysIn(EXAMPLE_ENV);

        Set<String> undocumented = new TreeSet<>(used);
        undocumented.removeAll(documented);

        assertThat(undocumented)
                .withFailMessage(
                        """
                        deploy/compose.yaml reads %s, which deploy/production.example.env never mentions.

                        Compose does not fail on an unset variable; it substitutes an empty string. The
                        symptom is a stack that comes up wrong rather than one that refuses to come up.""",
                        undocumented)
                .isEmpty();
    }

    /**
     * And the other direction, which fails more gently but rots faster: a variable documented in the
     * example that nothing reads any more sends whoever fills the file in on an errand that has no
     * effect. {@code TASK_VERSION} is the deliberate exception — deploy.sh reads it from the file to
     * decide whether the stack is pinned, and compose only ever sees the value deploy.sh passes.
     */
    @Test
    void theExampleEnvDocumentsNothingThatHasStoppedBeingRead() throws IOException {
        Set<String> stale = new TreeSet<>(keysIn(EXAMPLE_ENV));
        stale.removeAll(variablesIn(PRODUCTION_COMPOSE));
        stale.remove("TASK_VERSION");

        assertThat(stale)
                .withFailMessage(
                        "deploy/production.example.env documents %s, which deploy/compose.yaml no longer reads.",
                        stale)
                .isEmpty();
    }

    private static Set<String> variablesIn(Path file) throws IOException {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\$\\{([A-Z0-9_]+)}").matcher(read(file));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(found)
                .withFailMessage("No ${VARIABLES} in %s at all — production configuration is supposed "
                        + "to arrive from the environment (#31), so this test would be measuring nothing.",
                        file.toAbsolutePath())
                .isNotEmpty();
        return found;
    }

    private static Set<String> keysIn(Path file) throws IOException {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("(?m)^#?([A-Z0-9_]+)=").matcher(read(file));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String read(Path file) throws IOException {
        assertThat(file)
                .withFailMessage("Expected to find %s. Tests must run with task-back-end as the working directory.",
                        file.toAbsolutePath())
                .isRegularFile();
        return Files.readString(file);
    }
}

package be.stijnhooft.task.backend;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads container image tags out of {@code compose.yaml}, which is their only copy.
 *
 * <p>#20 pinned Postgres and Keycloak in two places — {@code compose.yaml} and the test
 * configuration — and warned that moving one without the other is exactly how the suite and the dev
 * stack drifted onto different Keycloak versions before. #25 put Renovate on this repo, and its
 * docker-compose manager can see {@code compose.yaml} while nothing can see a Java string literal.
 * That would have made the drift automatic and silent: Renovate bumps the image, the suite keeps
 * passing against the old one, CI goes green, and the PR automerges.
 *
 * <p>So the literals are gone and the tests read the tag. Drift is not detected here; it is made
 * impossible. ADR-0007's *no staging* decision rests on these agreeing — it justified itself on
 * "#20's pins keep dev-compose and the test suite on the same images".
 */
public final class ComposeFile {

    /** Tests run with {@code task-back-end} as the working directory. */
    private static final Path COMPOSE_FILE = Path.of("compose.yaml");

    private ComposeFile() {
    }

    /**
     * The {@code image:} of one service, e.g. {@code postgres:18.4}.
     *
     * @throws IllegalStateException if the file, the service or its image is missing — a silent
     *                               fallback to a hard-coded default would reintroduce exactly the
     *                               duplication this class exists to remove.
     */
    public static String imageOf(String service) {
        Map<String, Object> services = services();
        Object definition = services.get(service);
        if (!(definition instanceof Map<?, ?> fields)) {
            throw new IllegalStateException(
                    "No service '" + service + "' in " + COMPOSE_FILE.toAbsolutePath()
                            + " (found: " + services.keySet() + ")");
        }
        Object image = fields.get("image");
        if (image == null) {
            throw new IllegalStateException(
                    "Service '" + service + "' in " + COMPOSE_FILE.toAbsolutePath() + " has no image:");
        }
        return image.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        if (!Files.isRegularFile(COMPOSE_FILE)) {
            throw new IllegalStateException(
                    "Cannot find " + COMPOSE_FILE.toAbsolutePath()
                            + ". Tests must run with task-back-end as the working directory.");
        }
        try (InputStream in = Files.newInputStream(COMPOSE_FILE)) {
            Map<String, Object> root = new Yaml().load(in);
            Object services = root == null ? null : root.get("services");
            if (!(services instanceof Map)) {
                throw new IllegalStateException("No services: block in " + COMPOSE_FILE.toAbsolutePath());
            }
            return (Map<String, Object>) services;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + COMPOSE_FILE.toAbsolutePath(), e);
        }
    }
}

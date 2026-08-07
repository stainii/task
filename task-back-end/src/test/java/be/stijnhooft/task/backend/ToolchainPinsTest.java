package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDK major is written twice — {@code .sdkmanrc} at the repo root and {@code <java.version>} in
 * this POM — and #25 pointed Renovate at both so a new JDK arrives as one grouped pull request.
 *
 * <p>This test exists because a half-applied bump is otherwise silent and green. If {@code .sdkmanrc}
 * moved to 27 and the POM stayed at 26, everything still compiles: you would simply be targeting the
 * previous release's bytecode on a newer JDK, forever, with nothing failing and nothing to notice.
 * With automerge on, that pull request would merge itself.
 *
 * <p>CI is deliberately not a third copy — {@code .github/workflows/ci.yml} reads the major from
 * {@code .sdkmanrc} at runtime. #24's production image is required to derive it the same way.
 */
class ToolchainPinsTest {

    private static final Path SDKMANRC = Path.of("..", ".sdkmanrc");
    private static final Path POM = Path.of("pom.xml");

    @Test
    void theJdkMajorInSdkmanrcAndThePomAgree() throws IOException {
        String sdkmanrcMajor = matchOne(SDKMANRC, "(?m)^java=(\\d+)");
        String pomMajor = matchOne(POM, "<java\\.version>(\\d+)</java\\.version>");

        assertThat(pomMajor)
                .withFailMessage(
                        """
                        The JDK is pinned in two places and they disagree: .sdkmanrc says %s, pom.xml says %s.

                        Nothing else will tell you. A mismatch compiles cleanly and silently targets the
                        older release, so this assertion is the only signal. Move both, in one commit.""",
                        sdkmanrcMajor, pomMajor)
                .isEqualTo(sdkmanrcMajor);
    }

    private static String matchOne(Path file, String regex) throws IOException {
        assertThat(file)
                .withFailMessage("Expected to find %s. Tests must run with task-back-end as the working directory.",
                        file.toAbsolutePath())
                .isRegularFile();

        Matcher matcher = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(matcher.find())
                .withFailMessage(
                        """
                        %s no longer matches /%s/, so the JDK pin cannot be read.

                        Renovate finds it with the same shape of pattern (see renovate.json), so if this
                        broke, Renovate has stopped proposing JDK updates too — silently, because a
                        dependency it cannot see simply never appears on the dashboard.""",
                        file.toAbsolutePath(), regex)
                .isTrue();
        return matcher.group(1);
    }
}

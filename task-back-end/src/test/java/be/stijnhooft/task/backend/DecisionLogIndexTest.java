package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/adr/README.md} is the decision log's index, and #29 made it the entry point future-you
 * reads first: the manual sends you there to find out <em>why</em>, rather than to the twenty files.
 *
 * <p>An index is only worth reading if it is complete, and nothing else notices when it stops being
 * complete. A new ADR that never reaches the index is invisible to the one reader who most needs it —
 * someone returning after six months, who has no way to know a twenty-first decision was ever made.
 * That is the habit #29 asked for: not a promise to keep the index current, but a build that fails
 * when it is not.
 */
class DecisionLogIndexTest {

    private static final Path ADR_FOLDER = Path.of("..", "docs", "adr");
    private static final Path INDEX = ADR_FOLDER.resolve("README.md");
    private static final Pattern LINK_TO_AN_ADR = Pattern.compile("\\]\\((\\d{4}-[^)#]+\\.md)\\)");

    @Test
    void everyDecisionIsListedInTheIndex() throws IOException {
        String index = readIndex();

        assertThat(decisionFiles())
                .allSatisfy(adr -> assertThat(index)
                        .withFailMessage(
                                """
                                %s is not linked from the decision log index (%s).

                                Add a line for it. The index is what future-you reads instead of the
                                twenty files, so a decision missing from it is a decision nobody knows
                                was taken.""",
                                adr, INDEX.toAbsolutePath())
                        .contains("(" + adr + ")"));
    }

    @Test
    void theIndexPointsAtNothingThatHasGone() throws IOException {
        assertThat(linkedFiles())
                .allSatisfy(linked -> assertThat(ADR_FOLDER.resolve(linked))
                        .withFailMessage(
                                """
                                The decision log index links %s, and there is no such file.

                                A renamed or deleted ADR leaves the index pointing at nothing, which
                                reads as a working link right up to the moment you click it.""",
                                linked)
                        .isRegularFile());
    }

    /** Every ADR on disk, by file name. The index itself is not one of them. */
    private static List<String> decisionFiles() throws IOException {
        try (Stream<Path> files = Files.list(ADR_FOLDER)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .filter(name -> !name.equals(INDEX.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    /** Every ADR file name the index links to. */
    private static List<String> linkedFiles() throws IOException {
        return LINK_TO_AN_ADR
                .matcher(readIndex())
                .results()
                .map(match -> match.group(1))
                .distinct()
                .toList();
    }

    private static String readIndex() throws IOException {
        assertThat(INDEX)
                .withFailMessage(
                        "Expected to find %s. Tests must run with task-back-end as the working directory.",
                        INDEX.toAbsolutePath())
                .isRegularFile();
        return Files.readString(INDEX);
    }
}

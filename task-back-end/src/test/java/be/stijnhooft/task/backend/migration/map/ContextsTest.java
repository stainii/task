package be.stijnhooft.task.backend.migration.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Every value here becomes a card label in ADR-0006's overview, so these are the near-duplicates
/// that would otherwise become two cards each.
class ContextsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "'Personal ', Personal",
            "Personal, Personal",
            "Scholencoordinatie, Scholencoördinatie",
            "'Scholencoordinatie ', Scholencoördinatie",
            "Scholencoördinatie, Scholencoördinatie",
            "'Medisch huis', Medisch Huis",
            "'Medisch Huis', Medisch Huis"})
    void knownNearDuplicatesFoldOntoOneSpelling(String portal, String expected) {
        assertThat(Contexts.normalise(portal)).isEqualTo(expected);
    }

    /// The other 21 values are the author's own and are left exactly as written. Normalising is a
    /// named list, not a general rule that quietly rewrites labels.
    @ParameterizedTest
    @CsvSource({"Baby", "Realdolmen", "Ideeën", "VDAB", "Montenegro", "Unknown"})
    void anythingNotInTheTableIsKeptVerbatim(String portal) {
        assertThat(Contexts.normalise(portal)).isEqualTo(portal);
    }

    /// Never occurs in the archive — no task has a blank context — but a blank would otherwise reach
    /// a NOT NULL column as an empty card label.
    @Test
    void aBlankContextBecomesUnknown() {
        assertThat(Contexts.normalise("   ")).isEqualTo("Unknown");
    }

    /// Deployment names pass through unchanged: they are already the spelling REC-011 chose.
    @ParameterizedTest
    @CsvSource({"Housagotchi", "Health", "Setlist", "social-recurring-tasks"})
    void aDeploymentNameIsAlreadyItsContext(String deployment) {
        assertThat(Contexts.normalise(deployment)).isEqualTo(deployment);
    }
}
